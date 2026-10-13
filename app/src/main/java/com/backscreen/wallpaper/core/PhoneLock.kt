package com.backscreen.wallpaper.core

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * When the phone counts as locked, for what the back screen shows and keeps. A secure phone
 * (PIN, pattern, password or biometrics) with its main screen off counts as locked at once, before
 * Android's lock delay ends: the back screen faces away from you, so nothing on it waits for that.
 */
object PhoneLock {

    /**
     * Locked with a secure lock screen, the kind that hides notifications' sensitive content, or
     * about to be. A lock screen you just swipe away hides nothing. For notifications.
     */
    fun hidesContent(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return rule(keyguard.isDeviceLocked, keyguard.isDeviceSecure, mainScreenOn(context))
    }

    /** The lock screen is up, any kind, or about to be. For Bring back when the phone locks. */
    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return rule(keyguard.isKeyguardLocked, keyguard.isDeviceSecure, mainScreenOn(context))
    }

    /** [locked] now, or [secure] with the main screen off. Plain Kotlin, so it's unit tested. */
    fun rule(locked: Boolean, secure: Boolean, mainScreenOn: Boolean) = locked || (secure && !mainScreenOn)

    /** On and lit; dimmed for the always-on display counts as off. */
    private fun mainScreenOn(context: Context) =
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
}
