package com.backscreen.wallpaper.core

import android.content.Context
import android.content.SharedPreferences

/**
 * One section's settings, in its own preferences file, starting with its main switch. There's
 * no master switch: each section has its own, and every new one starts off, so an update
 * changes nothing until you opt in.
 */
abstract class FeatureSettings(private val file: String) {

    protected fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(file, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    open fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
