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
 * - sleeping: the sheet's white pill carrying its five-bar waveform at rest,
 *   the state between takes and the face the picker previews;
 * - listening: the pill's bars breathe and rise with the room, frame bitmaps
 *   rendered by [RecognitionActivity] and handed here through [showWave] at
 *   the same cadence the in-app waveform runs at. RemoteViews has no frame of
 *   its own to animate in — the animation is a stream of measured pictures,
 *   which is what makes it react to the sound instead of merely pulsing.
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
         * finish — so a widget never stands at a frozen frame.
         */
        fun showSleep(context: Context) {
            updateAll(context) {
                buildViews(it).apply {
                    setContentDescription(
                        R.id.widget_wave,
                        it.getString(R.string.widget_recognition_label),
                    )
                }
            }
        }

        /**
         * One listening frame, shared by every pill on the screen. [bitmap]
         * is a picture of the pill's own bars; the RemoteViews around it are
         * the standard build.
         */
        fun showWave(context: Context, bitmap: Bitmap) {
            updateAll(context) {
                buildViews(it).apply {
                    setImageViewBitmap(R.id.widget_wave, bitmap)
                    setContentDescription(
                        R.id.widget_wave,
                        it.getString(R.string.widget_recognition_listening),
                    )
                }
            }
        }

        private fun updateAll(context: Context, viewsOf: (Context) -> RemoteViews) {
            val appContext = context.applicationContext
            runCatching {
                AppWidgetManager.getInstance(appContext).updateAppWidget(
                    ComponentName(appContext, RecognitionWidgetWide::class.java),
                    viewsOf(appContext),
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
