package com.backscreen.wallpaper

import android.app.Application
import com.backscreen.wallpaper.core.Schedules
import com.backscreen.wallpaper.wallpaper.Gallery
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: take the app's colours from the phone's wallpaper.
        DynamicColors.applyToActivitiesIfAvailable(this)
        Gallery.migrate(this)
        // Force stopping the app cancels its alarms; this sets the next schedule change again.
        Schedules.update(this)
    }
}
