package com.backscreen.wallpaper.core

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** This colour, [opacity] (0 to 1) as see-through as it was: for fading what's drawn over the wallpaper. */
fun Int.withOpacity(opacity: Float): Int =
    ColorUtils.setAlphaComponent(this, (Color.alpha(this) * opacity.coerceIn(0f, 1f)).roundToInt())
