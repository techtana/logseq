package com.logseq.app

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import java.io.File

/** The animated icon shown beside the voice note widget's mic. */
enum class VoiceNoteMotion(
  val key: String,
  @StringRes val label: Int,
  @LayoutRes val layout: Int?,
) {
  NONE("none", R.string.voice_note_widget_motion_none, null),
  WAVES("waves", R.string.voice_note_widget_motion_waves, R.layout.voice_note_motion_waves),
  EQUALIZER("equalizer", R.string.voice_note_widget_motion_equalizer, R.layout.voice_note_motion_equalizer),
  HEARTBEAT("heartbeat", R.string.voice_note_widget_motion_heartbeat, R.layout.voice_note_motion_heartbeat),
  ARROWS("arrows", R.string.voice_note_widget_motion_arrows, R.layout.voice_note_motion_arrows),
  SPARKLES("sparkles", R.string.voice_note_widget_motion_sparkles, R.layout.voice_note_motion_sparkles),
  NOTES("notes", R.string.voice_note_widget_motion_notes, R.layout.voice_note_motion_notes),
  GIF("gif", R.string.voice_note_widget_motion_gif, null),
}

enum class VoiceNoteMotionSides(val key: String, @StringRes val label: Int) {
  LEFT("left", R.string.voice_note_widget_side_left),
  RIGHT("right", R.string.voice_note_widget_side_right),
  BOTH("both", R.string.voice_note_widget_side_both),
}

/**
 * Which way the icons point. Icons are drawn pointing right, away from a mic
 * on their left, so the right icon is drawn as is when it points outward and
 * the left icon is mirrored.
 */
enum class VoiceNoteMotionDirection(val key: String) {
  OUTWARD("outward"),
  INWARD("inward"),
  RIGHT("right"),
  LEFT("left"),
}

data class VoiceNoteWidgetStyle(
  val motion: VoiceNoteMotion,
  val sides: VoiceNoteMotionSides,
  val direction: VoiceNoteMotionDirection,
) {
  val flipLeft: Boolean
    get() = direction == VoiceNoteMotionDirection.OUTWARD || direction == VoiceNoteMotionDirection.LEFT

  val flipRight: Boolean
    get() = direction == VoiceNoteMotionDirection.INWARD || direction == VoiceNoteMotionDirection.LEFT

  companion object {
    /** Used by widgets that were never configured. */
    val DEFAULT = VoiceNoteWidgetStyle(
      VoiceNoteMotion.WAVES,
      VoiceNoteMotionSides.BOTH,
      VoiceNoteMotionDirection.OUTWARD,
    )
  }
}

/** A GIF turned into frames that a ViewFlipper shows one after another. */
data class VoiceNoteGif(val frames: List<Bitmap>, val frameMs: Int)

/**
 * Each widget's style, in shared preferences, and its GIF frames, in app
 * files. Called on the main thread only, like the widget provider, so a
 * widget update never reads frames while they're being replaced.
 */
object VoiceNoteWidgetStore {
  private const val PREFS = "voice_note_widget"

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun gifDir(context: Context, widgetId: Int) =
    File(File(context.filesDir, "voice_note_widget"), widgetId.toString())

  fun style(context: Context, widgetId: Int): VoiceNoteWidgetStyle {
    val prefs = prefs(context)
    val motion = prefs.getString("motion_$widgetId", null) ?: return VoiceNoteWidgetStyle.DEFAULT
    val sides = prefs.getString("sides_$widgetId", null)
    val direction = prefs.getString("direction_$widgetId", null)
    return VoiceNoteWidgetStyle(
      VoiceNoteMotion.entries.first { it.key == motion },
      VoiceNoteMotionSides.entries.first { it.key == sides },
      VoiceNoteMotionDirection.entries.first { it.key == direction },
    )
  }

  /** The widget's GIF frames, or null when its style doesn't use a GIF. */
  fun gif(context: Context, widgetId: Int): VoiceNoteGif? {
    if (style(context, widgetId).motion != VoiceNoteMotion.GIF) return null
    val prefs = prefs(context)
    val count = prefs.getInt("gif_frames_$widgetId", 0)
    val dir = gifDir(context, widgetId)
    val frames = (0 until count).map { index ->
      val file = File(dir, "$index.png")
      checkNotNull(BitmapFactory.decodeFile(file.path)) { "Missing voice note widget GIF frame $file" }
    }
    check(frames.isNotEmpty()) { "Voice note widget $widgetId uses a GIF but has no frames" }
    return VoiceNoteGif(frames, prefs.getInt("gif_frame_ms_$widgetId", 0))
  }

  /** Saves the style; [gif] is required when the style uses a GIF and ignored otherwise. */
  fun save(context: Context, widgetId: Int, style: VoiceNoteWidgetStyle, gif: VoiceNoteGif?) {
    val dir = gifDir(context, widgetId)
    dir.deleteRecursively()
    val editor = prefs(context).edit()
      .putString("motion_$widgetId", style.motion.key)
      .putString("sides_$widgetId", style.sides.key)
      .putString("direction_$widgetId", style.direction.key)
      .remove("gif_frames_$widgetId")
      .remove("gif_frame_ms_$widgetId")
    if (style.motion == VoiceNoteMotion.GIF) {
      val frames = requireNotNull(gif) { "A GIF style needs GIF frames" }
      check(dir.mkdirs()) { "Couldn't create $dir" }
      frames.frames.forEachIndexed { index, frame ->
        val file = File(dir, "$index.png")
        file.outputStream().use {
          check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Couldn't write $file" }
        }
      }
      editor
        .putInt("gif_frames_$widgetId", frames.frames.size)
        .putInt("gif_frame_ms_$widgetId", frames.frameMs)
    }
    editor.apply()
  }

  fun delete(context: Context, widgetIds: IntArray) {
    val editor = prefs(context).edit()
    widgetIds.forEach { id ->
      keys(id).forEach(editor::remove)
      gifDir(context, id).deleteRecursively()
    }
    editor.apply()
  }

  /**
   * Moves settings to the widgets' new ids after they're restored from a
   * backup. An old id can be another widget's new id, so every old entry is
   * read before any new one is written.
   */
  fun restore(context: Context, oldIds: IntArray, newIds: IntArray) {
    val prefs = prefs(context)
    val saved = prefs.all
    val editor = prefs.edit()
    // Removals first: a later put of the same key in this edit wins.
    oldIds.forEach { id -> keys(id).forEach(editor::remove) }
    oldIds.zip(newIds).forEach { (oldId, newId) ->
      keys(oldId).zip(keys(newId)).forEach { (oldKey, newKey) ->
        when (val value = saved[oldKey]) {
          is String -> editor.putString(newKey, value)
          is Int -> editor.putInt(newKey, value)
          null -> Unit
          else -> error("Unexpected voice note widget setting $oldKey: $value")
        }
      }
    }
    editor.apply()

    val staged = oldIds.zip(newIds).mapNotNull { (oldId, newId) ->
      val oldDir = gifDir(context, oldId)
      if (!oldDir.exists()) return@mapNotNull null
      val stagedDir = File(oldDir.parentFile, "restoring-$oldId")
      check(oldDir.renameTo(stagedDir)) { "Couldn't move $oldDir to $stagedDir" }
      stagedDir to gifDir(context, newId)
    }
    staged.forEach { (stagedDir, newDir) ->
      newDir.deleteRecursively()
      check(stagedDir.renameTo(newDir)) { "Couldn't move $stagedDir to $newDir" }
    }
  }

  private fun keys(widgetId: Int) = listOf(
    "motion_$widgetId",
    "sides_$widgetId",
    "direction_$widgetId",
    "gif_frames_$widgetId",
    "gif_frame_ms_$widgetId",
  )
}

/** Builds the widget's RemoteViews, used by the widget itself and by the settings preview. */
object VoiceNoteWidgetViews {
  /**
   * [gif] is required when the style uses a GIF. [onClick] is null in the
   * settings preview, where a tap shouldn't start a voice note.
   */
  fun build(
    context: Context,
    style: VoiceNoteWidgetStyle,
    gif: VoiceNoteGif?,
    onClick: PendingIntent?,
  ): RemoteViews {
    val packageName = context.packageName
    val views = RemoteViews(packageName, R.layout.voice_note_widget)
    if (onClick != null) {
      views.setOnClickPendingIntent(R.id.voice_note_widget_root, onClick)
    }
    views.removeAllViews(R.id.voice_note_motion_left)
    views.removeAllViews(R.id.voice_note_motion_right)
    if (style.motion != VoiceNoteMotion.NONE) {
      if (style.sides != VoiceNoteMotionSides.RIGHT) {
        views.addView(R.id.voice_note_motion_left, motion(packageName, style.motion, gif, style.flipLeft))
      }
      if (style.sides != VoiceNoteMotionSides.LEFT) {
        views.addView(R.id.voice_note_motion_right, motion(packageName, style.motion, gif, style.flipRight))
      }
    }
    return views
  }

  private fun motion(
    packageName: String,
    motion: VoiceNoteMotion,
    gif: VoiceNoteGif?,
    flipped: Boolean,
  ): RemoteViews {
    val content = if (motion == VoiceNoteMotion.GIF) {
      gifViews(packageName, requireNotNull(gif) { "A GIF style needs GIF frames" })
    } else {
      RemoteViews(packageName, requireNotNull(motion.layout))
    }
    val frame = if (flipped) R.layout.voice_note_motion_frame_flipped else R.layout.voice_note_motion_frame
    return RemoteViews(packageName, frame).apply {
      addView(R.id.voice_note_motion_frame, content)
    }
  }

  // Both sides add the same Bitmap objects, which RemoteViews sends only once.
  private fun gifViews(packageName: String, gif: VoiceNoteGif): RemoteViews =
    RemoteViews(packageName, R.layout.voice_note_motion_gif).apply {
      setInt(R.id.voice_note_motion_gif, "setFlipInterval", gif.frameMs)
      gif.frames.forEach { frame ->
        addView(
          R.id.voice_note_motion_gif,
          RemoteViews(packageName, R.layout.voice_note_motion_gif_frame).apply {
            setImageViewBitmap(R.id.voice_note_motion_gif_frame, frame)
          },
        )
      }
    }
}
