package com.backscreen.wallpaper

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import java.lang.ref.WeakReference

/** Quick Settings tile: tap to turn the back screen wallpaper on or off. */
class ToggleTile : TileService() {

    override fun onStartListening() {
        showing = WeakReference(this)
        refresh()
    }

    override fun onStopListening() {
        if (showing.get() === this) showing = WeakReference(null)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val enabled = WallpaperSettings.isEnabled(this)
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
        /** The tile while Quick Settings shows it. */
        private var showing = WeakReference<ToggleTile>(null)

        /**
         * The switch changed: a tile on show is redrawn at once, since asking Android to listen
         * again does nothing while it's already listening; otherwise it's redrawn next time it's seen.
         */
        fun requestUpdate(context: Context) {
            BackScreen.mainHandler.post { showing.get()?.refresh() }
            requestListeningState(context, ComponentName(context, ToggleTile::class.java))
        }
    }
}
