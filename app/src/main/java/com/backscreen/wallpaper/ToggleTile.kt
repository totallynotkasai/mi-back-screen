package com.backscreen.wallpaper

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to turn the back screen wallpaper on or off. */
class ToggleTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val enabled = BackScreen.isEnabled(this)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(if (enabled) R.string.state_on else R.string.state_off)
        tile.updateTile()
    }

    override fun onClick() {
        try {
            KeeperService.start(this, KeeperService.ACTION_TOGGLE)
        } catch (e: IllegalStateException) {
            // Not allowed to start a service from here on this build; the app is, so go through it.
            openAppToToggle()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppToToggle() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(KeeperService.ACTION_TOGGLE)
            // Reach an already open app through onNewIntent rather than just bringing it forward.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestUpdate(context: Context) =
            requestListeningState(context, ComponentName(context, ToggleTile::class.java))
    }
}
