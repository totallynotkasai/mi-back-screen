package com.backscreen.wallpaper.wallpaper

/**
 * Whether the back screen's next load decodes its image again even if it's the one showing: a
 * reload was asked for (the scaling changed, say), and no load has ended since. A load overtaken
 * by a newer one leaves it to that one; the newest ending settles it, whether or not it worked,
 * so a file that won't load isn't decoded again at every ordinary show. Plain Kotlin, so it's
 * unit tested. Main thread.
 */
class ReloadRule {
    private var latest = 0
    private var pending = false

    /** One load: its [number], and whether it decodes again. */
    data class Load(val number: Int, val reload: Boolean)

    /** A reload is wanted, but no load can start yet; the next one does it. */
    fun ask() {
        pending = true
    }

    /** A load starts, decoding again if [force] or a reload is pending. */
    fun start(force: Boolean): Load {
        if (force) pending = true
        return Load(++latest, pending)
    }

    /** [load] ended, whether or not it worked. True if it's the newest, whose result counts. */
    fun ended(load: Load): Boolean {
        if (load.number != latest) return false
        pending = false
        return true
    }
}
