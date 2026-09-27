package com.backscreen.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.widget.FrameLayout

/**
 * The preview's frame: the rear display's shape, with the part its camera covers drawn on
 * top, so the preview matches what you'll see. Until [setRearDisplay] is called it assumes
 * the 17 Pro Max's 976 x 596 with no camera.
 */
class RearPreviewLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var rearWidth = 976
    private var rearHeight = 596
    private var cameraRects = emptyList<Rect>()
    private var cameraInsets = Insets.NONE
    private var avoidCamera = false

    private val cameraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val cameraCorner = 12 * resources.displayMetrics.density
    private val scratch = RectF()

    val hasCamera get() = cameraRects.isNotEmpty()

    init {
        setWillNotDraw(false)
    }

    @Suppress("DEPRECATION")
    fun setRearDisplay(display: Display, avoidCamera: Boolean) {
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        rearWidth = m.widthPixels
        rearHeight = m.heightPixels
        cameraRects = display.cutout?.boundingRects.orEmpty()
        cameraInsets = BackScreen.cameraInsets(display)
        this.avoidCamera = avoidCamera
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val scale = width.toFloat() / rearWidth
        // Same padding as the rear wallpaper gets, scaled down, so the crop matches.
        val inset = if (avoidCamera) cameraInsets else Insets.NONE
        findViewById<View>(R.id.preview)?.setPadding(
            (inset.left * scale).toInt(), (inset.top * scale).toInt(),
            (inset.right * scale).toInt(), (inset.bottom * scale).toInt()
        )
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec((rearHeight * scale).toInt(), MeasureSpec.EXACTLY)
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val scale = width.toFloat() / rearWidth
        for (rect in cameraRects) {
            scratch.set(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
            canvas.drawRoundRect(scratch, cameraCorner, cameraCorner, cameraPaint)
        }
    }
}
