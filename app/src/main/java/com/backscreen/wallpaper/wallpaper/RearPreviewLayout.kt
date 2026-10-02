package com.backscreen.wallpaper.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import kotlin.math.max

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

    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF22252C.toInt()
    }
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
        findViewById<WallpaperView>(R.id.preview)?.ratio = scale
        findViewById<View>(R.id.preview)?.setPadding(
            (inset.left * scale).toInt(), (inset.top * scale).toInt(),
            (inset.right * scale).toInt(), (inset.bottom * scale).toInt()
        )
        // The clock always keeps clear of the camera, as on the rear display.
        findViewById<View>(R.id.clock)?.setPadding(
            (cameraInsets.left * scale).toInt(), (cameraInsets.top * scale).toInt(),
            (cameraInsets.right * scale).toInt(), (cameraInsets.bottom * scale).toInt()
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
            if (rect.isEmpty) continue
            scratch.set(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
            drawLenses(canvas, scratch)
        }
    }

    /**
     * The system only reports the camera as a strip, but on the 17 Pro Max it's two round
     * lenses stacked in it, with the display showing around them. (With the camera avoided,
     * the preview's padding already leaves the strip black.)
     */
    private fun drawLenses(canvas: Canvas, strip: RectF) {
        val gap = strip.height() * 0.06f
        val diameter = minOf(strip.width() * 0.86f, (strip.height() - gap) / 2 * 0.94f)
        val r = diameter / 2
        val cx = strip.centerX()
        for (cy in floatArrayOf(strip.centerY() - (r + gap / 2), strip.centerY() + (r + gap / 2))) {
            canvas.save()
            canvas.translate(cx, cy)
            // Glass: dark rings like a real lens, and a small blue glint up and to the left.
            lensPaint.shader = RadialGradient(0f, 0f, r, LENS_COLORS, LENS_STOPS, Shader.TileMode.CLAMP)
            canvas.drawCircle(0f, 0f, r, lensPaint)
            lensPaint.shader = RadialGradient(-0.3f * r, -0.32f * r, 0.22f * r, 0xCC4A5B80.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(-0.3f * r, -0.32f * r, 0.22f * r, lensPaint)
            rimPaint.strokeWidth = max(1f, r * 0.04f)
            canvas.drawCircle(0f, 0f, r, rimPaint)
            canvas.restore()
        }
    }

    private companion object {
        val LENS_COLORS = intArrayOf(
            0xFF0A0D15.toInt(), 0xFF151923.toInt(), 0xFF2A2E38.toInt(),
            0xFF0C0D11.toInt(), 0xFF3A3E48.toInt(), 0xFF111318.toInt(),
        )
        val LENS_STOPS = floatArrayOf(0f, 0.55f, 0.66f, 0.76f, 0.88f, 1f)
    }
}
