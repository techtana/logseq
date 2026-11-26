package com.logseq.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Home screen widget: one tap opens Logseq's voice note recorder, which keeps
 * dictating into today's journal until it's stopped. Each widget can show
 * motion icons or a GIF beside its mic, picked in VoiceNoteWidgetConfigActivity.
 */
class VoiceNoteWidgetProvider : AppWidgetProvider() {
  companion object {
    const val VOICE_NOTE_URL = "logseq://mobile/go/voice-note"

    fun update(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
      val views = VoiceNoteWidgetViews.build(
        context,
        VoiceNoteWidgetStore.style(context, widgetId),
        VoiceNoteWidgetStore.gif(context, widgetId),
        startVoiceNote(context),
      )
      appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun startVoiceNote(context: Context): PendingIntent =
      PendingIntent.getActivity(
        context,
        0,
        Intent(Intent.ACTION_VIEW, Uri.parse(VOICE_NOTE_URL), context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
  }

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray
  ) {
    appWidgetIds.forEach { update(context, appWidgetManager, it) }
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) {
    VoiceNoteWidgetStore.delete(context, appWidgetIds)
  }

  override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
    VoiceNoteWidgetStore.restore(context, oldWidgetIds, newWidgetIds)
  }
}
