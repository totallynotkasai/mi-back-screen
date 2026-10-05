package com.backscreen.wallpaper.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.MainActivity
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.LendReason
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.mirror.MirrorSettings
import com.backscreen.wallpaper.rear.RearHostActivity
import com.backscreen.wallpaper.ui.MainSwitchBar
import com.backscreen.wallpaper.ui.Refreshable
import com.backscreen.wallpaper.wallpaper.PreviewBackdrop
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import com.google.android.material.snackbar.Snackbar

/**
 * The Camera tab: the switch for swipe left, a preview of the swipe over your wallpaper, Open
 * camera on back screen (Close camera while it's up), and a note while the wallpaper is off; the
 * ways out are in its help. There are no options. Open camera works while the switch is off,
 * like Battery's Play.
 */
class CameraFragment : Fragment(R.layout.fragment_camera), Refreshable {

    private lateinit var mainSwitch: MainSwitchBar
    private lateinit var backdrop: PreviewBackdrop
    private lateinit var hint: SwipeHint
    private lateinit var openButton: Button
    private lateinit var wallpaperCard: View

    private val ctx: Context get() = requireContext()

    // Asked once, when Mirror or Camera is first switched on: the notification while the camera
    // is up needs it. Refusing leaves this tab and Xiaomi's back strip to close it.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        BackScreen.log(if (granted) "Notifications allowed" else "Notifications refused")
        if (!granted && view != null) snackbar(R.string.camera_notifications_denied)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainSwitch = view.findViewById(R.id.mainSwitch)
        hint = view.findViewById(R.id.swipeHint)
        openButton = view.findViewById(R.id.openButton)
        wallpaperCard = view.findViewById(R.id.wallpaperCard)
        // Nothing is drawn in the clock's colours here, so there's nothing to match.
        backdrop = PreviewBackdrop(
            view.findViewById(R.id.previewFrame), view.findViewById(R.id.preview), view.findViewById(R.id.clock), {},
            view.findViewById(R.id.glow),
        )

        mainSwitch.onCheckedChange = ::switched
        openButton.setOnClickListener { openOrClose() }
        view.findViewById<Button>(R.id.wallpaperButton).setOnClickListener { turnOnWallpaper() }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) reloadPreview()
        hint.playing = !isHidden
    }

    override fun onPause() {
        super.onPause()
        hint.playing = false
    }

    /** Another tab is showing, or this one again. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        hint.playing = !hidden && isResumed
        if (!hidden) reloadPreview()
    }

    override fun onDestroyView() {
        backdrop.release()
        super.onDestroyView()
    }

    override fun refresh() {
        if (view == null) return
        val on = CameraSettings.isEnabled(ctx)
        val wallpaperOn = WallpaperSettings.isEnabled(ctx)
        val open = cameraIsUp()
        mainSwitch.isChecked = on
        mainSwitch.setSummary(
            when {
                !on -> R.string.state_off
                open -> R.string.camera_on_open
                !wallpaperOn -> R.string.camera_on_no_wallpaper
                else -> R.string.camera_on_ready
            }
        )
        openButton.setText(if (open) R.string.close_camera else R.string.open_camera)
        wallpaperCard.visibility = if (wallpaperOn) View.GONE else View.VISIBLE
        backdrop.refresh()
    }

    private fun cameraIsUp() = (RearState.owner(ctx) as? RearOwner.Lent)?.lend?.reason == LendReason.CAMERA

    private fun switched(on: Boolean) {
        CameraSettings.setEnabled(ctx, on)
        BackScreen.log(if (on) "Swipe left for Xiaomi Camera on" else "Swipe left for Xiaomi Camera off")
        // The back screen reads swipes only while a section uses them; nothing new to see there.
        RearHostActivity.swipesChanged()
        if (on) askForNotificationsOnce()
        refresh()
    }

    private fun askForNotificationsOnce() {
        if (MirrorSettings.askedNotifications(ctx)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        MirrorSettings.setAskedNotifications(ctx)
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Opens Xiaomi Camera on the back screen, or closes it if it's up. */
    private fun openOrClose() {
        if (cameraIsUp()) {
            KeeperService.start(ctx, KeeperService.ACTION_CLOSE_CAMERA, why = "the Camera tab")
            return
        }
        when {
            !RearCommands.isReady() -> snackbar(R.string.need_shizuku)
            RearState.owner(ctx) is RearOwner.Lent -> snackbar(R.string.play_lent)
            else -> {
                KeeperService.start(ctx, KeeperService.ACTION_OPEN_CAMERA, why = "the Camera tab")
                // Off, it still opens from here, but a swipe won't.
                if (!CameraSettings.isEnabled(ctx)) snackbar(R.string.camera_opened_switch_off)
            }
        }
    }

    private fun turnOnWallpaper() {
        if (!RearCommands.isReady()) return snackbar(R.string.need_shizuku)
        BackScreen.log("Wallpaper on, from the Camera tab")
        KeeperService.setWallpaper(ctx, true)
        refresh()
    }

    /** Shown again: the Wallpaper tab may have changed what's under it. */
    private fun reloadPreview() {
        backdrop.reload()
        refresh()
    }

    private fun snackbar(text: Int) {
        (requireActivity() as MainActivity).snackbar(text, Snackbar.LENGTH_LONG)
    }
}
