package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.music.bitchord.R

/**
 * Home-screen widget that starts song recognition on tap. One provider serves
 * every instance on the screen — [RecognitionWidgetWide] is only a second
 * manifest entry so the picker lists this under its own, recognizable name —
 * and all of them look the same, so one [RemoteViews] build covers each face:
 *
 * - sleeping: the sheet's five-bar waveform, white on the black capsule, at
 *   rest — the state between takes and the face the picker previews;
 * - listening: the bars breathe and rise with the room, frame bitmaps
 *   rendered by [RecognitionActivity] and handed here through [showWave].
 *   RemoteViews has no frame of its own to animate in — the animation is a
 *   stream of measured pictures, which is what makes the widget react to the
 *   sound instead of merely pulsing.
 */
open class RecognitionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildViews(context)
        for (id in ids) {
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    companion object {

        /**
         * Puts every recognition pill back to sleep. The take's ending calls
         * this last — including the endings where the activity is about to
         * finish — so a widget never stands at a frozen frame. A full apply,
         * because it also restores the tap's PendingIntent and description
         * whatever the frames last replaced.
         */
        fun showSleep(context: Context) {
            val appContext = context.applicationContext
            val views = buildViews(appContext)
            runCatching {
                AppWidgetManager.getInstance(appContext).updateAppWidget(
                    ComponentName(appContext, RecognitionWidgetWide::class.java),
                    views,
                )
            }
        }

        /**
         * One listening frame, shared by every pill on the screen. The frames
         * go out as *partial* updates — only the ImageView of each pill is
         * replaced, leaving the RemoteViews tree the sleep face installed
         * standing — because a full re-apply at the taking cadence is what
         * made the pill stutter: the launcher re-rendered the whole widget
         * eleven times a second instead of re-drawing one bar picture.
         */
        fun showWave(context: Context, bitmap: Bitmap) {
            val appContext = context.applicationContext
            runCatching {
                val manager = AppWidgetManager.getInstance(appContext)
                val ids = manager.getAppWidgetIds(
                    ComponentName(appContext, RecognitionWidgetWide::class.java),
                )
                if (ids.isEmpty()) return
                val views = RemoteViews(appContext.packageName, R.layout.widget_recognition_wide).apply {
                    setImageViewBitmap(R.id.widget_wave, bitmap)
                    setContentDescription(
                        R.id.widget_wave,
                        appContext.getString(R.string.widget_recognition_listening),
                    )
                }
                manager.partiallyUpdateAppWidget(ids, views)
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

        /** The pill at rest: the sleeping face and the tap's PendingIntent. */
        private fun buildViews(context: Context): RemoteViews {
            val pending = PendingIntent.getActivity(
                context,
                0,
                tapIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(context.packageName, R.layout.widget_recognition_wide).apply {
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
        }
    }
}
