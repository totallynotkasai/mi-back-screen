package com.backscreen.wallpaper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.wallpaper.WallpaperSettings

/** Home screen widget: tap to turn the back screen wallpaper on or off. */
class ToggleWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, buildViews(context))
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ToggleWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val layout = if (WallpaperSettings.isEnabled(context)) R.layout.widget_on else R.layout.widget_off
            // Tapping a widget is allowed to start a foreground service from the background.
            val toggle = PendingIntent.getForegroundService(
                context, 0,
                Intent(context, KeeperService::class.java).setAction(KeeperService.ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return RemoteViews(context.packageName, layout).apply {
                setOnClickPendingIntent(android.R.id.background, toggle)
            }
        }
    }
}
