package com.backscreen.wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView

/**
 * One wallpaper image, fitted the way [scaling] says. The app's preview is smaller than the
 * back screen, so it sets [ratio] (preview size / back screen size) for [Scaling.NONE] to
 * show the image at the size it will really be.
 *
 * A plain ImageView on purpose: the back screen's window uses a platform theme, where an
 * AppCompat view logs an error each time it's created, and wallpapers never need tinting.
 */
@SuppressLint("AppCompatCustomView")
class WallpaperView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    var scaling = Scaling.FILL
        set(value) {
            field = value
            scaleType = value.scaleType
            updateMatrix()
        }

    var ratio = 1f
        set(value) {
            if (field == value) return
            field = value
            updateMatrix()
        }

    init {
        scaleType = Scaling.FILL.scaleType
        // Padding keeps the image clear of the camera; don't draw into it.
        cropToPadding = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        updateMatrix()
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        updateMatrix()
        return changed
    }

    /** For [Scaling.NONE]: the image at its own size, times [ratio], centred. */
    private fun updateMatrix() {
        // Called from ImageView's constructor, before this class has set its fields.
        @Suppress("SENSELESS_COMPARISON")
        if (scaling == null || scaling != Scaling.NONE) return
        val d = drawable ?: return
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        imageMatrix = Matrix().apply {
            setScale(ratio, ratio)
            postTranslate((w - d.intrinsicWidth * ratio) / 2f, (h - d.intrinsicHeight * ratio) / 2f)
        }
    }
}
