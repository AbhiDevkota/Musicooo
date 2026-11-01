package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.music.bitchord.R

/**
 * Home-screen widget that starts song recognition on tap: the same mic pill
 * as inside the app. One provider serves every instance on the screen —
 * [RecognitionWidgetWide] is only a second manifest entry so the picker lists
 * this under its own, recognizable name — and all of them look the same, so
 * one [RemoteViews] build covers both faces of the widget:
 *
 * - resting: the mic and "Recognize", the state between uses;
 * - listening: the label swaps to "Listening…", which is what a tap becomes
 *   while the microphone runs with no window to show for it — see
 *   [RecognitionActivity]. Swapped in by [showListening], out again before
 *   anything else the flow does.
 *
 * The layouts themselves are static; nothing here animates, because
 * RemoteViews has no frame of its own to animate in.
 */
open class RecognitionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildViews(context, listening = false)
        for (id in ids) {
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    companion object {

        /**
         * Repaints every recognition pill with the listening face — or the
         * resting one. [RecognitionActivity] calls this from the point the
         * mic goes up until it comes down again, including the no-match
         * ending where that reset is the last thing to run.
         */
        fun showListening(context: Context, listening: Boolean) {
            val appContext = context.applicationContext
            val views = buildViews(appContext, listening)
            runCatching {
                AppWidgetManager.getInstance(appContext).updateAppWidget(
                    ComponentName(appContext, RecognitionWidgetWide::class.java),
                    views,
                )
            }
        }

        /**
         * The tap's payload: everything the widget wants from the system is
         * one launch of [RecognitionActivity]. Code 0 and an intent with no
         * extras keeps every pill's PendingIntent one and the same, so both
         * faces install it interchangeably.
         */
        private fun tapIntent(context: Context): Intent =
            Intent(context, RecognitionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

        /**
         * The pill in one of its two faces. One build is applied to every
         * instance — the widgets differ in where they sit, not in content.
         */
        private fun buildViews(context: Context, listening: Boolean): RemoteViews {
            val pending = PendingIntent.getActivity(
                context,
                0,
                tapIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(context.packageName, R.layout.widget_recognition_wide).apply {
                setOnClickPendingIntent(R.id.widget_root, pending)
                setOnClickPendingIntent(R.id.widget_mic, pending)
                setTextViewText(
                    R.id.widget_label,
                    context.getString(
                        if (listening) R.string.widget_recognition_listening
                        else R.string.widget_recognition_label,
                    ),
                )
            }
        }
    }
}
