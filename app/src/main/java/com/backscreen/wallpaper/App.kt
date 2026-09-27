package com.backscreen.wallpaper

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: take the app's colours from the phone's wallpaper.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
