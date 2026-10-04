package com.backscreen.wallpaper.notifications

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.rear.Swipes
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Notifications on the back screen, over the wallpaper and clock:
 *
 * - a banner that slides in at the top when one arrives and away a few seconds later, with
 *   "+2 more" if others came in while it was up;
 * - the list of the ones you haven't cleared, which comes down with your finger when you pull
 *   down from the top, and goes back up when you push it up, tap below it, or leave it 15 s.
 *   The ones that fit show, newest first, then how many more there are.
 *
 * It moves only while the back screen is lit ([isLit]): a dimmed one shows none of the motion,
 * so a banner there just appears and goes, and the list closes. Every change a dimmed back
 * screen should get is passed on ([onShownChanged]), to be pushed to the panel.
 *
 * Sized as a share of its height, like the clock, so the app's preview looks just like the back
 * screen. Padding keeps it clear of the camera; the list's veil covers everything.
 */
class NotificationLayer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Whether the back screen is lit, so motion shows. The app's preview always is. */
    var isLit: () -> Boolean = { true }

    /** Something changed that a dimmed back screen should be sent. */
    var onShownChanged: (() -> Unit)? = null

    /** How far the list is down, 0 to 1, as that changes. The clock fades out by as much. */
    var onShadeShown: ((Float) -> Unit)? = null

    /** The list has gone back up. */
    var onShadeClosed: (() -> Unit)? = null

    private var textColor = Color.WHITE
    private var cardColor = Color.BLACK

    private val stack = BannerStack()
    private val banner = Card(BANNER_TEXT_LINES)
    private var bannerShown: ShownNotification? = null
    private var bannerLeaving = false
    private val hideBannerLater = Runnable { hideBanner() }

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = GONE
    }
    private val cards = mutableListOf<Card>()
    private val footerShape = GradientDrawable()
    private val footer = TextView(context).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.create(MEDIUM, Typeface.NORMAL)
        background = footerShape
        // Measured before it's in the list, and a measured TextView needs these to change its text.
        layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }
    private var items = emptyList<ShownNotification>()
    private var progress = 0f
    private var pulling = false
    private var shadeAnimator: ValueAnimator? = null
    private val closeWhenIdle = Runnable { closeShade(animate = true) }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var downProgress = 0f
    private var dragging = false

    init {
        setWillNotDraw(false)
        banner.view.visibility = GONE
        addView(banner.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    /** Whether the list is down, or on its way. */
    val isShadeOpen get() = list.visibility == VISIBLE

    /** The notification the banner shows, if it's up. */
    val bannerKey: String? get() = bannerShown?.key

    /** The text and card colours: the clock's, so it matches the wallpaper. */
    fun setColors(text: Int, card: Int) {
        if (text == textColor && card == cardColor) return
        textColor = text
        cardColor = card
        banner.applyColors()
        cards.forEach { it.applyColors() }
        applyFooterColors()
        invalidate()
    }

    /**
     * Shows [n] in the banner for [durationMs], or until it's hidden if that's 0, replacing what
     * it shows. Not while the list is down, which shows it instead.
     */
    fun showBanner(n: ShownNotification, durationMs: Long) {
        if (isShadeOpen) return
        stack.push(n.key)
        bannerShown = n
        banner.bind(n, context.getString(R.string.ago_now), stack.more)
        removeCallbacks(hideBannerLater)
        if (durationMs > 0) postDelayed(hideBannerLater, durationMs)
        val v = banner.view
        if (v.visibility == VISIBLE && !bannerLeaving) {
            // Already up: it just says something new.
            onShownChanged?.invoke()
            return
        }
        bannerLeaving = false
        v.animate().cancel()
        if (v.visibility != VISIBLE) {
            v.visibility = VISIBLE
            v.alpha = 0f
        }
        if (!isLit()) {
            showBannerStill()
            return
        }
        // Once it's laid out, so its height is known: from just above the screen.
        v.post {
            if (bannerShown == null || bannerLeaving) return@post
            if (!isLit()) return@post showBannerStill()
            if (v.alpha == 0f) v.translationY = -(v.top + v.height).toFloat()
            v.animate().translationY(0f).alpha(1f).setDuration(IN_MS).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** The banner goes: sliding away while the back screen is lit and [animate], at once otherwise. */
    fun hideBanner(animate: Boolean = true) {
        removeCallbacks(hideBannerLater)
        stack.clear()
        bannerShown = null
        val v = banner.view
        if (v.visibility != VISIBLE || (bannerLeaving && animate)) return
        v.animate().cancel()
        if (!animate || !isLit()) {
            bannerLeaving = false
            v.visibility = GONE
            onShownChanged?.invoke()
            return
        }
        bannerLeaving = true
        v.animate().translationY(-(v.top + v.height).toFloat()).alpha(0f).setDuration(OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (!bannerLeaving) return@withEndAction
                bannerLeaving = false
                v.visibility = GONE
            }
            .start()
    }

    /** [key] was cleared on the phone: the banner goes if it shows it, or counts one fewer. */
    fun removeFromBanner(key: String) {
        if (stack.remove(key)) return hideBanner()
        val shown = bannerShown ?: return
        banner.bind(shown, context.getString(R.string.ago_now), stack.more)
        onShownChanged?.invoke()
    }

    /** What the list shows: [items], newest first. */
    fun setShadeItems(items: List<ShownNotification>) {
        this.items = items
        if (isShadeOpen) {
            fill()
            onShownChanged?.invoke()
        }
    }

    /** Your finger is pulling the list down, [dy] px from where it started. */
    fun pull(dy: Float) {
        if (!pulling) startShade()
        pulling = true
        setProgress((dy / (PULL_DISTANCE * unit())).coerceIn(0f, 1f))
    }

    /** Let go: the list comes the rest of the way down if [open], or goes back up. */
    fun endPull(open: Boolean) {
        pulling = false
        animateShade(if (open) 1f else 0f)
    }

    /** All the way down, as after a quick swipe. */
    fun openShade() {
        startShade()
        animateShade(1f)
    }

    /** Back up: sliding while the back screen is lit and [animate], at once otherwise. */
    fun closeShade(animate: Boolean) {
        pulling = false
        removeCallbacks(closeWhenIdle)
        if (!isShadeOpen) return
        if (animate) animateShade(0f) else settle(0f)
    }

    /**
     * Every touch on the back screen while the list is down: push it up or tap below it to close
     * it. Any touch keeps it open another 15 s.
     */
    fun onShadeTouch(event: MotionEvent): Boolean {
        removeCallbacks(closeWhenIdle)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopShadeAnimation()
                downX = event.x
                downY = event.y
                downProgress = progress
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && hypot(dx, dy) > slop) dragging = true
                if (dragging) setProgress((downProgress + min(0f, dy) / (PULL_DISTANCE * unit())).coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                when {
                    dragging && (Swipes.pushCloses(dx, dy, height) || progress < HALF) -> animateShade(0f)
                    dragging -> animateShade(1f)
                    event.y > list.top + list.translationY + list.height -> animateShade(0f)
                    else -> animateShade(1f)
                }
            }
            MotionEvent.ACTION_CANCEL -> animateShade(1f)
        }
        return true
    }

    /**
     * The back screen dimmed: motion stops where it would end. A banner coming in is simply
     * there, one going is gone, and the list closes.
     */
    fun dimmed() {
        val v = banner.view
        v.animate().cancel()
        if (bannerLeaving) {
            bannerLeaving = false
            v.visibility = GONE
        } else if (v.visibility == VISIBLE) {
            v.alpha = 1f
            v.translationY = 0f
        }
        closeShade(animate = false)
        onShownChanged?.invoke()
    }

    private fun showBannerStill() {
        banner.view.alpha = 1f
        banner.view.translationY = 0f
        onShownChanged?.invoke()
    }

    /** The list starts coming down: the banner goes, and the list fills. */
    private fun startShade() {
        removeCallbacks(closeWhenIdle)
        stopShadeAnimation()
        removeCallbacks(hideBannerLater)
        stack.clear()
        bannerShown = null
        bannerLeaving = false
        banner.view.animate().cancel()
        banner.view.visibility = GONE
        list.visibility = VISIBLE
        fill()
    }

    private fun animateShade(to: Float) {
        stopShadeAnimation()
        if (!isLit() || progress == to) return settle(to)
        shadeAnimator = ValueAnimator.ofFloat(progress, to).apply {
            duration = (SHADE_MS * abs(to - progress)).toLong().coerceAtLeast(MIN_SHADE_MS)
            interpolator = DecelerateInterpolator()
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (shadeAnimator !== animation) return
                    shadeAnimator = null
                    settle(to)
                }
            })
            start()
        }
    }

    private fun stopShadeAnimation() {
        val running = shadeAnimator ?: return
        shadeAnimator = null
        running.cancel()
    }

    /** All the way down, which starts the 15 s, or back up, which closes it. */
    private fun settle(at: Float) {
        stopShadeAnimation()
        setProgress(at)
        if (at >= 1f) {
            postDelayed(closeWhenIdle, IDLE_MS)
        } else if (isShadeOpen) {
            pulling = false
            list.visibility = GONE
            onShadeClosed?.invoke()
        }
        onShownChanged?.invoke()
    }

    private fun setProgress(p: Float) {
        progress = p
        // Hidden just above the screen, and down by as much as it's pulled.
        list.translationY = -(paddingTop + margin() + list.measuredHeight) * (1 - p)
        invalidate()
        onShadeShown?.invoke(p)
    }

    /** Fills the list with the newest that fit, then how many more there are, or that there are none. */
    private fun fill() {
        val u = unit()
        val margin = margin()
        val listWidth = width - paddingLeft - paddingRight - 2 * margin
        if (u <= 0f || listWidth <= 0) return
        val widthSpec = MeasureSpec.makeMeasureSpec(listWidth, MeasureSpec.EXACTLY)
        val anyHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val gap = (GAP * u).roundToInt()
        list.removeAllViews()
        footer.text = resources.getQuantityString(R.plurals.shade_more, items.size, items.size)
        footer.measure(widthSpec, anyHeight)
        val footerSpace = footer.measuredHeight + gap
        val room = height - paddingTop - paddingBottom - 2 * margin
        val now = System.currentTimeMillis()
        var used = 0
        var shown = 0
        for ((i, n) in items.withIndex()) {
            val card = cards.getOrElse(i) { Card(LIST_TEXT_LINES).also { it.applySize(u); cards += it } }
            card.bind(n, ago(now, n.postedAt), 0)
            card.view.measure(widthSpec, anyHeight)
            val space = card.view.measuredHeight + if (shown > 0) gap else 0
            val reserve = if (i < items.size - 1) footerSpace else 0
            // Always at least one.
            if (shown > 0 && used + space + reserve > room) break
            list.addView(card.view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (shown > 0) gap else 0
            })
            used += space
            shown++
        }
        val rest = items.size - shown
        if (items.isEmpty() || rest > 0) {
            footer.text = if (items.isEmpty()) context.getString(R.string.shade_empty) else resources.getQuantityString(R.plurals.shade_more, rest, rest)
            list.addView(footer, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = if (shown > 0) gap else 0
            })
        }
        list.measure(widthSpec, anyHeight)
        setProgress(progress)
    }

    private fun ago(now: Long, then: Long) = when (val ago = NotificationFormatter.ago(now, then)) {
        Ago.Now -> context.getString(R.string.ago_now)
        is Ago.Minutes -> context.getString(R.string.ago_minutes, ago.n)
        is Ago.Hours -> context.getString(R.string.ago_hours, ago.n)
        is Ago.Days -> context.getString(R.string.ago_days, ago.n)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val u = unit()
        if (u <= 0f) return
        val margin = margin()
        for (v in listOf(banner.view, list)) {
            (v.layoutParams as LayoutParams).setMargins(margin, margin, margin, 0)
        }
        banner.applySize(u)
        cards.forEach { it.applySize(u) }
        val padH = (FOOTER_PAD_H * u).roundToInt()
        val padV = (FOOTER_PAD_V * u).roundToInt()
        footer.setPadding(padH, padV, padH, padV)
        footer.setTextSize(TypedValue.COMPLEX_UNIT_PX, SMALL_SIZE * u)
        footerShape.cornerRadius = u
        applyFooterColors()
        post {
            requestLayout()
            if (isShadeOpen) fill()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // The list's veil, over the whole screen, camera area included.
        if (progress > 0f) canvas.drawColor(withAlpha(cardColor, VEIL_OPACITY * progress))
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideBannerLater)
        removeCallbacks(closeWhenIdle)
        stopShadeAnimation()
        banner.view.animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun applyFooterColors() {
        footerShape.setColor(withAlpha(cardTone(), CARD_OPACITY))
        footer.setTextColor(withAlpha(textColor, SECONDARY_OPACITY))
    }

    /** A card's colour: the clock's background colour, lifted a little towards its text. */
    private fun cardTone() = ColorUtils.blendARGB(cardColor, textColor, CARD_LIFT)

    private fun unit() = (height - paddingTop - paddingBottom).toFloat()

    private fun margin() = (MARGIN * unit()).roundToInt()

    private fun withAlpha(color: Int, opacity: Float) =
        ColorUtils.setAlphaComponent(color, (Color.alpha(color) * opacity.coerceIn(0f, 1f)).roundToInt())

    /** One notification on a card: the app's icon, its name and when, then the title and message. */
    private inner class Card(private val textLines: Int) {
        val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val shape = GradientDrawable()
        private val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val app = textView(MEDIUM, lines = 1)
        private val more = textView(MEDIUM, lines = 1)
        private val title = textView(MEDIUM, lines = 1)
        private val text = textView(REGULAR, lines = textLines)
        private var fallbackIcon = false

        init {
            view.background = shape
            header.addView(icon)
            header.addView(app, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            header.addView(more)
            view.addView(header)
            view.addView(title)
            view.addView(text)
            applyColors()
        }

        fun bind(n: ShownNotification, whenText: String, moreCount: Int) {
            val appIcon = if (n.packageName == SampleNotification.PACKAGE) AppCompatResources.getDrawable(context, R.drawable.ic_sample_chat)
            else AppIcons.get(context, n.packageName)
            fallbackIcon = appIcon == null
            icon.setImageDrawable(appIcon ?: AppCompatResources.getDrawable(context, R.drawable.ic_notifications))
            app.text = context.getString(R.string.notification_header, n.appName, whenText)
            more.text = resources.getQuantityString(R.plurals.banner_more, moreCount, moreCount)
            more.visibility = if (moreCount > 0) VISIBLE else GONE
            title.text = n.title
            title.visibility = if (n.title != null) VISIBLE else GONE
            text.text = n.text
            text.visibility = if (n.text != null) VISIBLE else GONE
            applyColors()
        }

        fun applySize(u: Float) {
            val padH = (CARD_PAD_H * u).roundToInt()
            view.setPadding(padH, (CARD_PAD_TOP * u).roundToInt(), padH, (CARD_PAD_BOTTOM * u).roundToInt())
            shape.cornerRadius = CARD_CORNER * u
            val iconSize = (ICON_SIZE * u).roundToInt()
            icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (ICON_GAP * u).roundToInt() }
            (more.layoutParams as LinearLayout.LayoutParams).marginStart = (ICON_GAP * u).roundToInt()
            app.setTextSize(TypedValue.COMPLEX_UNIT_PX, SMALL_SIZE * u)
            more.setTextSize(TypedValue.COMPLEX_UNIT_PX, SMALL_SIZE * u)
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, TITLE_SIZE * u)
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, TEXT_SIZE * u)
            text.setLineSpacing(LINE_EXTRA * u, 1f)
            (title.layoutParams as LinearLayout.LayoutParams).topMargin = (TITLE_GAP * u).roundToInt()
            (text.layoutParams as LinearLayout.LayoutParams).topMargin = (TEXT_GAP * u).roundToInt()
        }

        fun applyColors() {
            shape.setColor(withAlpha(cardTone(), CARD_OPACITY))
            app.setTextColor(withAlpha(textColor, SECONDARY_OPACITY))
            more.setTextColor(withAlpha(textColor, SECONDARY_OPACITY))
            title.setTextColor(textColor)
            text.setTextColor(withAlpha(textColor, TEXT_OPACITY))
            // App icons keep their own colours; the stand-in bell takes the text's.
            icon.imageTintList = if (fallbackIcon) ColorStateList.valueOf(textColor) else null
        }

        private fun textView(font: String, lines: Int) = TextView(context).apply {
            typeface = Typeface.create(font, Typeface.NORMAL)
            maxLines = lines
            isSingleLine = lines == 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
    }

    private companion object {
        const val MEDIUM = "sans-serif-medium"
        const val REGULAR = "sans-serif"
        const val BANNER_TEXT_LINES = 3
        // The list fits more in with the message cut to 2 lines.
        const val LIST_TEXT_LINES = 2

        const val IN_MS = 320L
        const val OUT_MS = 240L
        const val SHADE_MS = 300L
        const val MIN_SHADE_MS = 120L
        const val IDLE_MS = 15_000L
        const val HALF = 0.5f

        // How far your finger moves, as a share of the height, to bring the list all the way down.
        const val PULL_DISTANCE = 0.45f

        // Sizes and places, as shares of the height inside the padding.
        const val MARGIN = 0.04f
        const val GAP = 0.018f
        const val CARD_PAD_H = 0.045f
        const val CARD_PAD_TOP = 0.034f
        const val CARD_PAD_BOTTOM = 0.04f
        const val CARD_CORNER = 0.06f
        const val ICON_SIZE = 0.066f
        const val ICON_GAP = 0.022f
        const val SMALL_SIZE = 0.044f
        const val TITLE_SIZE = 0.056f
        const val TEXT_SIZE = 0.05f
        const val LINE_EXTRA = 0.008f
        const val TITLE_GAP = 0.018f
        const val TEXT_GAP = 0.006f
        const val FOOTER_PAD_H = 0.045f
        const val FOOTER_PAD_V = 0.022f

        const val CARD_OPACITY = 0.9f

        // How far a card is lifted from its colour towards the text's, so it stands out from a
        // backdrop of the same colour, such as black with no wallpaper.
        const val CARD_LIFT = 0.14f
        const val VEIL_OPACITY = 0.55f
        const val SECONDARY_OPACITY = 0.72f
        const val TEXT_OPACITY = 0.88f
    }
}
