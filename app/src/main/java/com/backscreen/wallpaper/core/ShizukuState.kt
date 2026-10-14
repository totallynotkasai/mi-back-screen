package com.backscreen.wallpaper.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** How far Shizuku is from letting the app use the back screen, and so what the app offers to fix it. */
enum class ShizukuState {
    /** Running, and the app is allowed. */
    READY,

    /** Running, and it can ask you to allow the app. */
    NOT_ALLOWED,

    /** Running, but it won't ask again: you chose "Deny and don't ask again". Shizuku's own app can still allow it. */
    BLOCKED,

    /** Installed, but not running. */
    NOT_RUNNING,

    /** Not on the phone. */
    NOT_INSTALLED;

    companion object {
        /** Shizuku's own app. */
        const val PACKAGE = "moe.shizuku.privileged.api"

        /** Where to get it: Shizuku's own page, with Google Play and GitHub. */
        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

        /**
         * [ping]: Shizuku answers. [granted]: it allows the app. [rationale]: it won't ask again
         * (Shizuku's shouldShowRequestPermissionRationale). [installed]: its app is on the phone.
         */
        fun of(ping: Boolean, granted: Boolean, rationale: Boolean, installed: Boolean) = when {
            ping && granted -> READY
            ping && rationale -> BLOCKED
            ping -> NOT_ALLOWED
            installed -> NOT_RUNNING
            else -> NOT_INSTALLED
        }

        /** As it is now. Cheap once ready: Shizuku keeps the answer until it reconnects. */
        fun read(context: Context): ShizukuState {
            val ping = Shizuku.pingBinder()
            val (granted, rationale) = try {
                val granted = ping && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                granted to (ping && !granted && Shizuku.shouldShowRequestPermissionRationale())
            } catch (e: RuntimeException) {
                // It stopped between the calls.
                return of(ping = false, granted = false, rationale = false, isInstalled(context))
            }
            return of(ping, granted, rationale, installed = ping || isInstalled(context))
        }

        private fun isInstalled(context: Context) = try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        /** Opens Shizuku's app, where it's started and where Authorized apps are; null if it can't be opened. */
        fun openIntent(context: Context): Intent? =
            context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
