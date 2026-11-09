package com.logseq.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Home screen widget: one tap opens Logseq's voice note recorder, which keeps
 * dictating into today's journal until it's stopped.
 */
class VoiceNoteWidgetProvider : AppWidgetProvider() {
  companion object {
    const val VOICE_NOTE_URL = "logseq://mobile/go/voice-note"
  }

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray
  ) {
    val startVoiceNote = PendingIntent.getActivity(
      context,
      0,
      Intent(Intent.ACTION_VIEW, Uri.parse(VOICE_NOTE_URL), context, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val views = RemoteViews(context.packageName, R.layout.voice_note_widget).apply {
      setOnClickPendingIntent(R.id.voice_note_widget_root, startVoiceNote)
    }
    appWidgetManager.updateAppWidget(appWidgetIds, views)
  }
}
