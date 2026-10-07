package com.shilapi.xcertplay.carlife

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.shilapi.xcertplay.host.R

/**
 * Home-screen companion widget (DiPlay 0.2.9 parity): the next turn and the
 * current song, pushed live from the CarLife session. Tapping it opens the
 * CarLife home.
 */
class CarLifeNaviWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        /** Latest session data; the engine pushes, the widget renders. */
        @Volatile
        private var roadName: String = ""

        @Volatile
        private var distance: String = ""

        @Volatile
        private var songLine: String = ""

        fun updateNavi(context: Context, road: String, dist: String) {
            roadName = road
            distance = dist
            push(context)
        }

        fun updateMedia(context: Context, song: String, artist: String) {
            songLine = if (song.isBlank()) "" else "♪ $song — $artist".trimEnd('—', ' ')
            push(context)
        }

        fun clear(context: Context) {
            roadName = ""
            distance = ""
            songLine = ""
            push(context)
        }

        private fun push(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CarLifeNaviWidget::class.java))
            render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_carlife_navi)
            val navi = when {
                roadName.isNotBlank() && distance.isNotBlank() -> "↰ $roadName · $distance"
                roadName.isNotBlank() -> "↰ $roadName"
                else -> context.getString(R.string.widget_wait_phone)
            }
            views.setTextViewText(R.id.widget_navi_text, navi)
            if (songLine.isNotBlank()) {
                views.setTextViewText(R.id.widget_song_text, songLine)
            } else {
                views.setTextViewText(R.id.widget_song_text, context.getString(R.string.widget_no_media))
            }
            val intent = Intent(context, CarLifeHomeActivity::class.java)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(ids, views)
        }
    }
}
