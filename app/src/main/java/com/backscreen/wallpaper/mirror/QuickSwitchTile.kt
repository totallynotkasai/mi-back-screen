package com.backscreen.wallpaper.mirror

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.backscreen.wallpaper.MainActivity
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.LendReason
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import java.lang.ref.WeakReference

/**
 * The Quick Switch tile: tap it while using an app to move that app to the back screen, and
 * again to bring it back. Unavailable while Mirror is off. While the phone is locked, it asks
 * you to unlock first, both ways: an app sent from the lock screen would show on the back
 * screen without it.
 */
class QuickSwitchTile : TileService() {

    override fun onTileAdded() {
        MirrorSettings.setTileAdded(this, true)
    }

    override fun onTileRemoved() {
        MirrorSettings.setTileAdded(this, false)
    }

    override fun onStartListening() {
        showing = WeakReference(this)
        refresh()
    }

    override fun onStopListening() {
        if (showing.get() === this) showing = WeakReference(null)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val owner = RearState.owner(this)
        val lend = (owner as? RearOwner.Lent)?.lend
        val notice = KeeperService.instance?.quickSwitch?.notice
        tile.label = getString(R.string.mirror_switch)
        when {
            !MirrorSettings.isEnabled(this) -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.qs_tile_off)
            }
            lend?.reason == LendReason.CAMERA -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.qs_tile_camera)
            }
            notice != null -> {
                tile.state = if (lend != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.subtitle = getString(notice)
            }
            lend != null -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = AppLabels.get(this, lend.packageName)
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.qs_tile_ready)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        if (!MirrorSettings.isEnabled(this)) return
        if (isLocked) unlockAndRun { quickSwitch() } else quickSwitch()
    }

    private fun quickSwitch() {
        // The keeper runs while Mirror is on, so it's usually there to ask directly.
        KeeperService.instance?.let { return it.quickSwitch.toggle() }
        try {
            KeeperService.start(this, KeeperService.ACTION_QUICK_SWITCH)
        } catch (e: IllegalStateException) {
            // Not allowed to start the service from here; the app is, so go through it.
            openApp()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(KeeperService.ACTION_QUICK_SWITCH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, QuickSwitchTile::class.java)

        /** The tile while Quick Settings shows it. */
        private var showing = WeakReference<QuickSwitchTile>(null)

        /**
         * Mirror was switched, or an app came or went: a tile on show is redrawn at once, since
         * asking Android to listen again does nothing while it's already listening; otherwise
         * it's redrawn next time it's seen.
         */
        fun requestUpdate(context: Context) {
            BackScreen.mainHandler.post { showing.get()?.refresh() }
            requestListeningState(context, component(context))
        }
    }
}
