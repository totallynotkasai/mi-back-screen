package com.backscreen.wallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Full-screen wallpaper shown on the rear display. KeeperService launches it straight onto
 * the rear; if HyperOS won't allow that, it's launched on the main display (transparent, so
 * it doesn't flash) and its task moved to the rear, where it's recreated and draws the image.
 *
 * Two layers: the gallery's images, which crossfade from one to the next, and the clock.
 */
class RearWallpaperActivity : Activity() {

    private lateinit var images: FrameLayout
    private lateinit var clock: ClockLayer
    @Volatile private var shown: Uri? = null
    private var loads = 0

    // Decoding happens here so a big image or a slow folder doesn't hold up the screen.
    private val loader = Executors.newSingleThreadExecutor()
    private val nextImage = Runnable { showImage(advance = true) }

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
        val camera = BackScreen.cameraInsets(display)
        images = FrameLayout(this)
        if (BackScreen.avoidCamera(this)) images.setPadding(camera.left, camera.top, camera.right, camera.bottom)
        // The clock always keeps clear of the camera.
        clock = ClockLayer(this).apply { setPadding(camera.left, camera.top, camera.right, camera.bottom) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(images)
            addView(clock)
        })
        clock.backdrop = images
        applyClock()

        // The first image straight away, so there's no blank moment.
        try {
            val uri = Gallery.current(this, advance = true)
            if (uri == null) {
                BackScreen.log("No images to show")
            } else {
                setImage(uri, BackScreen.loadImage(this, uri, display, Gallery.scaling(this)), fade = false)
                BackScreen.log("Wallpaper showing on back screen")
            }
        } catch (e: Exception) {
            BackScreen.log("Couldn't load image: ${e.message}")
        }
        scheduleNextImage()
        KeeperService.instance?.onWallpaperCreatedOnRear(display.displayId)
    }

    override fun onStart() {
        super.onStart()
        if (display?.displayId != Display.DEFAULT_DISPLAY) {
            visibleOnRear = true
            if (::images.isInitialized) showImage(advance = true)
        }
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
        BackScreen.mainHandler.removeCallbacks(nextImage)
        loader.shutdownNow()
        if (::images.isInitialized) {
            for (i in 0 until images.childCount) BackScreen.stop((images.getChildAt(i) as ImageView).drawable)
        }
        super.onDestroy()
    }

    private fun applyClock() {
        clock.visibility = if (BackScreen.showClock(this)) View.VISIBLE else View.GONE
        val settings = BackScreen.clockSettings(this)
        if (clock.settings != settings) clock.settings = settings
    }

    /**
     * Shows the gallery's current image, moving on to the next first if [advance] and it's time.
     * With [force], reloads it even if it's the one already showing.
     */
    private fun showImage(advance: Boolean, force: Boolean = false) {
        val display = display ?: return
        val load = ++loads
        loader.execute {
            try {
                val uri = Gallery.current(this, advance)
                val drawable = if (uri == null || (uri == shown && !force)) null
                else BackScreen.loadImage(this, uri, display, Gallery.scaling(this))
                runOnUiThread {
                    if (isDestroyed || load != loads) return@runOnUiThread
                    if (uri != null && drawable != null) setImage(uri, drawable, fade = true)
                    scheduleNextImage()
                }
            } catch (e: Exception) {
                BackScreen.log("Couldn't load image: ${e.message}")
                runOnUiThread { if (!isDestroyed) scheduleNextImage() }
            }
        }
    }

    /** Puts [drawable] on top, fading it in over the old image, which is then removed. */
    private fun setImage(uri: Uri, drawable: Drawable, fade: Boolean) {
        shown = uri
        val view = WallpaperView(this).apply {
            scaling = Gallery.scaling(this@RearWallpaperActivity)
            setImageDrawable(drawable)
        }
        BackScreen.start(drawable)
        val old = (0 until images.childCount).map { images.getChildAt(it) as ImageView }
        images.addView(view)
        val removeOld = {
            for (o in old) {
                BackScreen.stop(o.drawable)
                images.removeView(o)
            }
            // A new image under the clock: its automatic colours may need to change.
            clock.backdropChanged()
        }
        if (fade && old.isNotEmpty()) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_MS).withEndAction(removeOld)
        } else {
            removeOld()
        }
        BackScreen.log("Showing image ${uri.lastPathSegment?.substringAfterLast('/')}")
    }

    private fun scheduleNextImage() {
        BackScreen.mainHandler.removeCallbacks(nextImage)
        val at = Gallery.nextChangeAt(this) ?: return
        // While the phone sleeps this runs late; onStart and the display turning on catch up.
        BackScreen.mainHandler.postDelayed(nextImage, max(MIN_DELAY_MS, at - System.currentTimeMillis()))
    }

    companion object {
        private const val FADE_MS = 800L
        private const val MIN_DELAY_MS = 1000L

        private var current: WeakReference<RearWallpaperActivity>? = null

        var visibleOnRear = false
            private set

        private fun showing() = current?.get()?.takeIf {
            !it.isFinishing && it.display?.displayId != Display.DEFAULT_DISPLAY && it::images.isInitialized
        }

        fun isOnRear() = showing() != null

        /** Moves the gallery on if it's time; the rear display just turned on. */
        fun refreshImage() {
            showing()?.showImage(advance = true)
        }

        /** Loads the gallery's current image again. False if the wallpaper isn't up. */
        fun reload(): Boolean {
            val activity = showing() ?: return false
            activity.showImage(advance = false, force = true)
            KeeperService.instance?.showChanges()
            return true
        }

        /** The clock or gallery timing changed. */
        fun settingsChanged() {
            val activity = showing() ?: return
            activity.applyClock()
            activity.scheduleNextImage()
            KeeperService.instance?.showChanges()
        }

        fun finishCurrent() {
            current?.get()?.finish()
            current = null
            visibleOnRear = false
        }
    }
}
