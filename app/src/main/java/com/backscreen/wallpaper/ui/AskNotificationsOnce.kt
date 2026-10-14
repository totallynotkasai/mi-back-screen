package com.backscreen.wallpaper.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.camera.CameraSettings
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.FeatureSettings
import com.backscreen.wallpaper.mirror.MirrorSettings
import com.google.android.material.snackbar.Snackbar

/**
 * Asks for permission to post notifications when Mirror or Camera is first switched on: their
 * notification, while an app or Xiaomi Camera is on the back screen, needs it. Asked once for the
 * two, so a refusal isn't met with the question again. Each section notes that it asked in its
 * own settings, and either one's note counts: Mirror's has held it for both since 2.0.
 *
 * Made with the fragment, as Android needs. [denied] is what the tab says if you refuse.
 */
class AskNotificationsOnce(private val fragment: Fragment, private val settings: FeatureSettings, denied: Int) {

    private val ask = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        BackScreen.log(if (granted) "Notifications allowed" else "Notifications refused")
        if (!granted && fragment.view != null) fragment.snackbar(denied, Snackbar.LENGTH_LONG)
    }

    /** Asks, unless Mirror or Camera has asked already, or it's allowed. */
    fun ask() {
        val context = fragment.requireContext()
        if (ASKERS.any { it.askedNotifications(context) }) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        settings.setAskedNotifications(context)
        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        val ASKERS = listOf(MirrorSettings, CameraSettings)
    }
}
