package com.backscreen.wallpaper.wallpaper

/**
 * Where the gallery is: the image showing ([current], as written in the listing), when it last
 * changed ([changedAt], ms), and the shuffled order's [seed].
 */
data class GalleryPlace(val current: String? = null, val changedAt: Long = 0L, val seed: Long = 0L)

/** What [GalleryRotation] decided: the image to show, or null for none, and where the gallery is now. */
data class GalleryStep(val image: String?, val place: GalleryPlace)

/**
 * Which image the gallery shows, and when it moves on. Plain Kotlin, so it's unit tested;
 * [Gallery] reads the listing and the saved place, and saves what this decides.
 *
 * In order, it goes down the listing and round again. Shuffled, it goes through an order of its
 * own: every image once, then a new order, which never starts with the image just shown. That
 * order is each image's place in a hash of it and the [GalleryPlace.seed], so an image added to
 * the folder simply joins it, and one deleted leaves the rest as they were. If the image showing
 * is gone, it carries on from where that one was; if it's in the listing but can't be shown
 * ([bad]), it's passed over.
 */
object GalleryRotation {

    /**
     * The image to show from [images], as listed, at [now]. With [advance], it moves on if the one
     * showing has had its [intervalMs]. An empty listing forgets the place; a listing with only
     * [bad] images shows none, but keeps it. [newSeed] makes a seed for a new shuffled order.
     */
    fun current(
        images: List<String>, bad: Set<String>, place: GalleryPlace, shuffle: Boolean,
        now: Long, intervalMs: Long, advance: Boolean, newSeed: () -> Long,
    ): GalleryStep {
        if (images.isEmpty()) return GalleryStep(null, place.copy(current = null, changedAt = 0L))
        val usable = images.filter { it !in bad }
        if (usable.isEmpty()) return GalleryStep(null, place)
        val seeded = seeded(place, shuffle, newSeed)
        val current = seeded.current
        return when {
            // None yet, or it's gone or can't be shown: the one after it.
            current == null || current !in usable -> moveOn(images, usable, seeded, shuffle, now, newSeed)
            // After the clock was set back, count from now.
            now < seeded.changedAt -> GalleryStep(current, seeded.copy(changedAt = now))
            advance && usable.size > 1 && now - seeded.changedAt >= intervalMs -> moveOn(images, usable, seeded, shuffle, now, newSeed)
            else -> GalleryStep(current, seeded)
        }
    }

    /** Next, at once: the image after the one showing. */
    fun skip(images: List<String>, bad: Set<String>, place: GalleryPlace, shuffle: Boolean, now: Long, newSeed: () -> Long): GalleryStep {
        val usable = images.filter { it !in bad }
        if (usable.isEmpty()) return current(images, bad, place, shuffle, now, 0L, advance = false, newSeed)
        return moveOn(images, usable, seeded(place, shuffle, newSeed), shuffle, now, newSeed)
    }

    /** When the image showing is due to change, or null if there's nothing to change to. */
    fun nextChangeAt(usableCount: Int, place: GalleryPlace, intervalMs: Long): Long? =
        if (usableCount < 2) null else place.changedAt + intervalMs

    /** Where [image] comes in the order [seed] gives: a well-mixed hash of the two. */
    fun key(seed: Long, image: String): Long {
        // FNV-1a over the characters, then SplitMix64's mixing, so close names land far apart.
        var h = seed xor FNV_OFFSET
        for (c in image) h = (h xor c.code.toLong()) * FNV_PRIME
        h = (h xor (h ushr 30)) * MIX_1
        h = (h xor (h ushr 27)) * MIX_2
        return h xor (h ushr 31)
    }

    /** A shuffled gallery needs a seed: one made now, if it hasn't had one. */
    private fun seeded(place: GalleryPlace, shuffle: Boolean, newSeed: () -> Long) =
        if (shuffle && place.seed == 0L) place.copy(seed = newSeed()) else place

    private fun moveOn(
        images: List<String>, usable: List<String>, place: GalleryPlace, shuffle: Boolean, now: Long, newSeed: () -> Long,
    ): GalleryStep {
        val (next, seed) = if (shuffle) shuffledAfter(usable, place.current, place.seed, newSeed) else listedAfter(images, usable, place.current) to place.seed
        return GalleryStep(next, GalleryPlace(next, now, seed))
    }

    /**
     * In order: the first image that can be shown after [current] in the listing, round again at
     * the end. The first if [current] isn't in it (its place there isn't known).
     */
    private fun listedAfter(images: List<String>, usable: List<String>, current: String?): String {
        val at = images.indexOf(current)
        if (at < 0) return usable.first()
        for (i in 1..images.size) {
            val image = images[(at + i) % images.size]
            if (image in usable) return image
        }
        return usable.first()
    }

    /**
     * Shuffled: the next image in [seed]'s order after [current] (which may be gone). At the end,
     * a new order from a new seed, not starting with [current]. With the seed it's in.
     */
    private fun shuffledAfter(usable: List<String>, current: String?, seed: Long, newSeed: () -> Long): Pair<String, Long> {
        val order = Order(seed)
        if (current == null) return usable.minWith(order) to seed
        usable.filter { order.compare(it, current) > 0 }.minWithOrNull(order)?.let { return it to seed }
        // The end of this order. One image just stays.
        if (usable.size == 1) return usable.first() to seed
        val fresh = newSeed()
        val first = usable.sortedWith(Order(fresh)).first { it != current }
        return first to fresh
    }

    /** Images by [key], ties by name. */
    private class Order(private val seed: Long) : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val byKey = key(seed, a).compareTo(key(seed, b))
            return if (byKey != 0) byKey else a.compareTo(b)
        }
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val MIX_1 = -0x40a7b892e31b1a47L
    private const val MIX_2 = -0x6b2fb644ecceee15L
}
