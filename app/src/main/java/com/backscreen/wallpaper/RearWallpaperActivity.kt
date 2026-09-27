package com.backscreen.wallpaper

import android.app.Activity
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Full-screen wallpaper shown on the rear display. KeeperService launches it straight onto
 * the rear; if HyperOS won't allow that, it's launched on the main display (transparent, so
 * it doesn't flash) and its task moved to the rear, where it's recreated and draws the image.
 */
class RearWallpaperActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setShowWhenLocked(true)

        val display = display ?: return
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            val keeper = KeeperService.instance
            if (keeper == null) finish() else keeper.onWallpaperCreatedOnMain(taskId)
            return
        }

        // Opaque on the rear, so the system doesn't need to draw Xiaomi's launcher behind us.
        setTranslucent(false)
        // Cover the whole panel, camera area included, so the app's preview matches exactly.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val file = BackScreen.wallpaperFile(this)
        try {
            val view = BackScreen.makeView(this, BackScreen.loadWallpaper(file, display))
            if (BackScreen.avoidCamera(this)) {
                val camera = BackScreen.cameraInsets(display)
                view.setPadding(camera.left, camera.top, camera.right, camera.bottom)
            }
            setContentView(view)
            BackScreen.log("Wallpaper showing on back screen")
        } catch (e: Exception) {
            BackScreen.log("Couldn't load image: ${e.message}")
        }
        KeeperService.instance?.onWallpaperCreatedOnRear(display.displayId)
    }

    override fun onStart() {
        super.onStart()
        if (display?.displayId != Display.DEFAULT_DISPLAY) visibleOnRear = true
    }

    override fun onStop() {
        visibleOnRear = false
        if (!isFinishing && display?.displayId != Display.DEFAULT_DISPLAY) {
            KeeperService.instance?.onWallpaperHidden()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<RearWallpaperActivity>? = null

        var visibleOnRear = false
            private set

        fun isOnRear(): Boolean {
            val activity = current?.get() ?: return false
            return !activity.isFinishing && activity.display?.displayId != Display.DEFAULT_DISPLAY
        }

        fun finishCurrent() {
            current?.get()?.finish()
            current = null
            visibleOnRear = false
        }
    }
}
