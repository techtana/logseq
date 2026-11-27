package com.logseq.app

import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings for one voice note widget: the motion icon or GIF beside the mic,
 * which sides it's on and which way it points. Opens when the widget is placed
 * and, on Android 12+, from the widget's settings on the home screen.
 */
class VoiceNoteWidgetConfigActivity : ComponentActivity() {
  private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    widgetId = intent.getIntExtra(
      AppWidgetManager.EXTRA_APPWIDGET_ID,
      AppWidgetManager.INVALID_APPWIDGET_ID
    )
    setResult(RESULT_CANCELED, resultIntent())
    val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
    if (provider != ComponentName(this, VoiceNoteWidgetProvider::class.java)) {
      finish()
      return
    }

    val style = VoiceNoteWidgetStore.style(this, widgetId)
    val gif = VoiceNoteWidgetStore.gif(this, widgetId)
    enableEdgeToEdge()
    setContent {
      VoiceNoteWidgetConfigTheme {
        VoiceNoteWidgetConfigScreen(
          initialStyle = style,
          initialGif = gif,
          onCancel = ::finish,
          onSave = ::save,
        )
      }
    }
  }

  private fun save(style: VoiceNoteWidgetStyle, gif: VoiceNoteGif?) {
    VoiceNoteWidgetStore.save(this, widgetId, style, gif)
    VoiceNoteWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
    setResult(RESULT_OK, resultIntent())
    finish()
  }

  private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}

/** Turns a GIF into widget frames. */
object VoiceNoteGifDecoder {
  private const val MAX_BYTES = 20 * 1024 * 1024
  private const val MAX_FRAMES = 30
  private const val MIN_FRAME_MS = 60
  private const val FRAME_HEIGHT_DP = 32
  private const val MAX_FRAME_HEIGHT_PX = 128

  /**
   * Samples the GIF every frameMs into at most [MAX_FRAMES] frames, scaled to
   * fit 32dp high and 64dp wide like the widget's other icons. Returns null
   * when the file can't be read or isn't a GIF Android can decode.
   */
  // Movie is deprecated, but it's the platform's only GIF decoder that can
  // seek to a point in the animation; AnimatedImageDrawable only plays it.
  @Suppress("DEPRECATION")
  fun decode(context: Context, uri: Uri): VoiceNoteGif? {
    val bytes = try {
      context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_BYTES + 1) }
    } catch (e: IOException) {
      null
    } ?: return null
    if (bytes.size > MAX_BYTES) return null
    val movie = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
    if (movie.width() <= 0 || movie.height() <= 0) return null

    val targetHeight = min(
      (FRAME_HEIGHT_DP * context.resources.displayMetrics.density).roundToInt(),
      MAX_FRAME_HEIGHT_PX
    )
    val scale = min(targetHeight.toFloat() / movie.height(), 2f * targetHeight / movie.width())
    val width = max(1, (movie.width() * scale).roundToInt())
    val height = max(1, (movie.height() * scale).roundToInt())
    val duration = movie.duration()
    val frameMs = max(MIN_FRAME_MS, (duration + MAX_FRAMES - 1) / MAX_FRAMES)
    val count = if (duration <= 0) 1 else max(1, (duration + frameMs / 2) / frameMs)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    val frames = (0 until count).map { index ->
      movie.setTime(index * frameMs)
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frame ->
        val canvas = Canvas(frame)
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f, paint)
      }
    }
    return VoiceNoteGif(frames, frameMs)
  }

  private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
      val read = read(buffer)
      if (read < 0) break
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }
}

private enum class GifStatus { READY, LOADING, FAILED }

/** Keeps the direction the remaining icon points when switching to one side. */
private fun VoiceNoteWidgetStyle.withSides(sides: VoiceNoteMotionSides): VoiceNoteWidgetStyle {
  val direction = when {
    sides == VoiceNoteMotionSides.BOTH -> direction
    direction == VoiceNoteMotionDirection.RIGHT ->
      if (sides == VoiceNoteMotionSides.LEFT) VoiceNoteMotionDirection.INWARD else VoiceNoteMotionDirection.OUTWARD
    direction == VoiceNoteMotionDirection.LEFT ->
      if (sides == VoiceNoteMotionSides.LEFT) VoiceNoteMotionDirection.OUTWARD else VoiceNoteMotionDirection.INWARD
    else -> direction
  }
  return copy(sides = sides, direction = direction)
}

private fun directionChoices(sides: VoiceNoteMotionSides): List<Pair<VoiceNoteMotionDirection, Int>> =
  if (sides == VoiceNoteMotionSides.BOTH) {
    listOf(
      VoiceNoteMotionDirection.OUTWARD to R.string.voice_note_widget_direction_mirrored_outward,
      VoiceNoteMotionDirection.INWARD to R.string.voice_note_widget_direction_mirrored_inward,
      VoiceNoteMotionDirection.RIGHT to R.string.voice_note_widget_direction_same_right,
      VoiceNoteMotionDirection.LEFT to R.string.voice_note_widget_direction_same_left,
    )
  } else {
    listOf(
      VoiceNoteMotionDirection.OUTWARD to R.string.voice_note_widget_direction_outward,
      VoiceNoteMotionDirection.INWARD to R.string.voice_note_widget_direction_inward,
    )
  }

@Composable
private fun VoiceNoteWidgetConfigTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val context = LocalContext.current
  val colors = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
      if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
  }
  MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun VoiceNoteWidgetConfigScreen(
  initialStyle: VoiceNoteWidgetStyle,
  initialGif: VoiceNoteGif?,
  onCancel: () -> Unit,
  onSave: (VoiceNoteWidgetStyle, VoiceNoteGif?) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var style by remember { mutableStateOf(initialStyle) }
  var gif by remember { mutableStateOf(initialGif) }
  var gifStatus by remember { mutableStateOf(GifStatus.READY) }

  val pickGif = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    gifStatus = GifStatus.LOADING
    scope.launch {
      val decoded = withContext(Dispatchers.Default) { VoiceNoteGifDecoder.decode(context, uri) }
      if (decoded == null) {
        gifStatus = GifStatus.FAILED
      } else {
        gif = decoded
        gifStatus = GifStatus.READY
      }
    }
  }
  val needsGif = style.motion == VoiceNoteMotion.GIF && gif == null

  Scaffold(
    bottomBar = {
      Surface(tonalElevation = 3.dp) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
          TextButton(onClick = onCancel) {
            Text(stringResource(R.string.voice_note_widget_cancel))
          }
          Button(
            enabled = !needsGif && gifStatus != GifStatus.LOADING,
            onClick = { onSave(style, gif) },
          ) {
            Text(stringResource(R.string.voice_note_widget_save))
          }
        }
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Text(
        stringResource(R.string.voice_note_widget_settings_title),
        style = MaterialTheme.typography.headlineSmall,
      )

      WidgetPreview(if (needsGif) style.copy(motion = VoiceNoteMotion.NONE) else style, gif)

      Section(R.string.voice_note_widget_motion) {
        ChoiceRow(
          choices = VoiceNoteMotion.entries.map { it to it.label },
          selected = style.motion,
          onSelect = { style = style.copy(motion = it) },
        )
        if (style.motion == VoiceNoteMotion.GIF) {
          OutlinedButton(
            enabled = gifStatus != GifStatus.LOADING,
            onClick = {
              pickGif.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif"))
              )
            },
          ) {
            Text(
              stringResource(
                if (gif == null) R.string.voice_note_widget_choose_gif else R.string.voice_note_widget_change_gif
              )
            )
          }
          when {
            gifStatus == GifStatus.LOADING -> Note(stringResource(R.string.voice_note_widget_gif_loading))
            gifStatus == GifStatus.FAILED ->
              Note(stringResource(R.string.voice_note_widget_gif_error), isError = true)
            gif == null -> Note(stringResource(R.string.voice_note_widget_gif_needed))
          }
        } else if (style.motion != VoiceNoteMotion.NONE && !animatorsEnabled()) {
          Note(stringResource(R.string.voice_note_widget_animations_off))
        }
      }

      if (style.motion != VoiceNoteMotion.NONE) {
        Section(R.string.voice_note_widget_sides) {
          ChoiceRow(
            choices = VoiceNoteMotionSides.entries.map { it to it.label },
            selected = style.sides,
            onSelect = { style = style.withSides(it) },
          )
        }
        Section(R.string.voice_note_widget_direction) {
          ChoiceRow(
            choices = directionChoices(style.sides),
            selected = style.direction,
            onSelect = { style = style.copy(direction = it) },
          )
        }
      }
    }
  }
}

private fun animatorsEnabled() =
  Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

/** The real widget views, so the preview matches the home screen. */
@Composable
private fun WidgetPreview(style: VoiceNoteWidgetStyle, gif: VoiceNoteGif?) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(24.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(vertical = 24.dp),
    contentAlignment = Alignment.Center,
  ) {
    AndroidView(
      modifier = Modifier.size(width = 168.dp, height = 80.dp),
      factory = { FrameLayout(it) },
      update = { container ->
        container.removeAllViews()
        val views = VoiceNoteWidgetViews.build(container.context, style, gif, onClick = null)
        container.addView(views.apply(container.context, container))
      },
    )
  }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    content()
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(choices: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    choices.forEach { (value, label) ->
      FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        label = { Text(stringResource(label)) },
      )
    }
  }
}

@Composable
private fun Note(text: String, isError: Boolean = false) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
