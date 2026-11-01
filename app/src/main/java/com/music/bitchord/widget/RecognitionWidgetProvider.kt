package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.widget.RecognitionDeepLink

/**
 * Home-screen widget that starts song recognition on tap. Two sizes share one
 * provider: a 1x1 circle (just the mic) and a 2x2 pill (mic + label).
 */
open class RecognitionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, layoutFor(manager, id))
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(RecognitionDeepLink.EXTRA_START_RECOGNITION, true)
            }
            val pi = PendingIntent.getActivity(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            views.setOnClickPendingIntent(R.id.widget_mic, pi)
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    private fun layoutFor(manager: AppWidgetManager, id: Int): Int {
        return R.layout.widget_recognition_wide
    }


}