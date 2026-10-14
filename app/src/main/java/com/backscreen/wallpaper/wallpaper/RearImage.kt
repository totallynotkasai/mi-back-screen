package com.backscreen.wallpaper.wallpaper

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.Display
import com.backscreen.wallpaper.core.BackScreen

/**
 * The gallery's image as the back screen shows it: decoded for the back screen's shape and the
 * scaling in use, never bigger than [DecodeSize] allows, with its colour for the charging
 * animation's Auto ([WallpaperColour]). The back screen ([WallpaperLayer]), the Wallpaper tab's
 * preview and the other tabs' backdrop ([PreviewBackdrop]) all load it this way, each on a thread
 * of its own; what they do with it differs.
 */
class RearImage(
    /** What shows now: the image, how many there are, and the next change. */
    val shown: Gallery.Shown,
    /** Its image, decoded for [scaling]; null with no image, or when it was already showing. */
    val drawable: Drawable?,
    /** Its colour for the charging animation's Auto, or null for none. */
    val colour: Int?,
    val scaling: Scaling,
) {
    companion object {
        /**
         * The gallery's image now, moving on first if [advance] and it's time, decoded for
         * [display]. One that can't be decoded is passed over ([Gallery.decodeCurrent]); with
         * [alreadyShown], one that's showing already isn't decoded again. Null if loading failed
         * outright, such as running out of memory, which is logged after [failed]: uncaught on a
         * loader's thread, it would take the app down. Not on the main thread.
         */
        fun load(
            context: Context,
            display: Display,
            failed: String,
            advance: Boolean = false,
            alreadyShown: (Uri) -> Boolean = { false },
        ): RearImage? {
            val scaling = WallpaperSettings.scalingInUse(context)
            return try {
                val decoded = Gallery.decodeCurrent(context, advance, alreadyShown) { uri ->
                    val drawable = BackScreen.loadImage(context, uri, display, scaling)
                    drawable to WallpaperColour.of(drawable)
                }
                RearImage(decoded.shown, decoded.result?.first, decoded.result?.second, scaling)
            } catch (e: Throwable) {
                BackScreen.log("$failed: $e")
                null
            }
        }
    }
}
