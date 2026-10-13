package com.backscreen.wallpaper.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryRotationTest {

    private val images = (1..10).map { "img$it" }
    private val minute = 60_000L

    /** Seeds for new orders, counting up from [first]. */
    private fun seeds(first: Long = 1000L): () -> Long {
        var next = first
        return { next++ }
    }

    /** [count] presses of Next from [place], shuffled: the images shown, and where it ends. */
    private fun skips(list: List<String>, place: GalleryPlace, count: Int, newSeed: () -> Long = seeds()): Pair<List<String>, GalleryPlace> {
        var at = place
        val shown = (1..count).map {
            val step = GalleryRotation.skip(list, emptySet(), at, shuffle = true, now = it.toLong(), newSeed)
            at = step.place
            step.image!!
        }
        return shown to at
    }

    /** The last image in [seed]'s shuffled order. */
    private fun lastIn(seed: Long, list: List<String> = images) =
        list.maxWith(compareBy<String>({ GalleryRotation.key(seed, it) }, { it }))

    @Test
    fun shuffleShowsEveryImageBeforeAnyRepeat() {
        for (seed in 1L..50L) {
            val (shown, _) = skips(images, GalleryPlace(seed = seed), 10)
            assertEquals("seed $seed", images.toSet(), shown.toSet())
        }
    }

    @Test
    fun aNewOrderNeverStartsWithTheImageJustShown() {
        for (seed in 1L..200L) {
            val last = lastIn(seed)
            val step = GalleryRotation.skip(images, emptySet(), GalleryPlace(last, 0L, seed), shuffle = true, now = 1L, newSeed = { seed + 5000 })
            assertNotEquals("seed $seed", last, step.image)
            assertEquals(seed + 5000, step.place.seed)
        }
    }

    @Test
    fun fromAnUnknownImageAnyCanComeNextTheLastToo() {
        // Before, from an image no longer listed, shuffle could never pick the last one.
        val firsts = (1L..300L).map { seed ->
            GalleryRotation.skip(images, emptySet(), GalleryPlace("gone", 0L, seed), shuffle = true, now = 1L, newSeed = seeds()).image
        }
        assertEquals(images.toSet(), firsts.toSet())
    }

    /** The images still to come in [place]'s shuffled order, until a new one starts. */
    private fun restOfOrder(list: List<String>, place: GalleryPlace): List<String> {
        var at = place
        val rest = mutableListOf<String>()
        while (true) {
            val step = GalleryRotation.skip(list, emptySet(), at, shuffle = true, now = 1L, newSeed = seeds())
            if (step.place.seed != place.seed) return rest
            rest += step.image!!
            at = step.place
        }
    }

    @Test
    fun anAddedImageJoinsWithoutReshufflingTheRest() {
        for (seed in 1L..30L) {
            val (_, place) = skips(images, GalleryPlace(seed = seed), 3)
            val without = restOfOrder(images, place)
            val with = restOfOrder(images + "img11", place)
            // The rest of this order is as it was, the new image in it if it falls after the one showing.
            assertEquals("seed $seed", without, with - "img11")
            // If not, the next order has it.
            if ("img11" !in with) {
                val (next, _) = skips(images + "img11", place, without.size + 11)
                assertEquals("seed $seed", true, "img11" in next)
            }
        }
    }

    @Test
    fun aDeletedImageCarriesOnFromWhereItWas() {
        val seed = 11L
        val (_, place) = skips(images, GalleryPlace(seed = seed), 4)
        val next = GalleryRotation.skip(images, emptySet(), place, shuffle = true, now = 10L, newSeed = seeds()).image
        val after = GalleryRotation.current(images - place.current!!, emptySet(), place, shuffle = true, now = 10L, intervalMs = minute, advance = false, newSeed = seeds())
        assertEquals(next, after.image)
    }

    @Test
    fun oneImageNeverMoves() {
        val place = GalleryPlace("only", changedAt = 0L)
        val later = GalleryRotation.current(listOf("only"), emptySet(), place, shuffle = true, now = 10 * minute, intervalMs = minute, advance = true, newSeed = seeds())
        assertEquals("only", later.image)
        assertEquals(0L, later.place.changedAt)
        assertEquals("only", GalleryRotation.skip(listOf("only"), emptySet(), place, shuffle = false, now = 1L, newSeed = seeds()).image)
    }

    @Test
    fun inOrderItGoesDownTheListAndRoundAgain() {
        val abc = listOf("a", "b", "c")
        val fromB = GalleryRotation.current(abc, emptySet(), GalleryPlace("b", 0L), shuffle = false, now = minute, intervalMs = minute, advance = true, newSeed = seeds())
        assertEquals("c", fromB.image)
        assertEquals(minute, fromB.place.changedAt)
        val fromC = GalleryRotation.current(abc, emptySet(), GalleryPlace("c", 0L), shuffle = false, now = minute, intervalMs = minute, advance = true, newSeed = seeds())
        assertEquals("a", fromC.image)
    }

    @Test
    fun itWaitsForItsTimeAndForAdvance() {
        val place = GalleryPlace("a", 1000L)
        val abc = listOf("a", "b", "c")
        val early = GalleryRotation.current(abc, emptySet(), place, shuffle = false, now = 1000L + minute - 1, intervalMs = minute, advance = true, newSeed = seeds())
        assertEquals("a", early.image)
        assertEquals(place, early.place)
        val notAdvancing = GalleryRotation.current(abc, emptySet(), place, shuffle = false, now = 1000L + 5 * minute, intervalMs = minute, advance = false, newSeed = seeds())
        assertEquals("a", notAdvancing.image)
    }

    @Test
    fun afterTheClockIsSetBackItCountsFromNow() {
        val step = GalleryRotation.current(listOf("a", "b"), emptySet(), GalleryPlace("a", 5000L), shuffle = false, now = 1000L, intervalMs = minute, advance = true, newSeed = seeds())
        assertEquals("a", step.image)
        assertEquals(1000L, step.place.changedAt)
    }

    @Test
    fun anEmptyListingForgetsWhereItWas() {
        val step = GalleryRotation.current(emptyList(), emptySet(), GalleryPlace("a", 5000L, 9L), shuffle = true, now = 1L, intervalMs = minute, advance = true, newSeed = seeds())
        assertNull(step.image)
        assertEquals(GalleryPlace(null, 0L, 9L), step.place)
    }

    @Test
    fun imagesThatCantBeShownArePassedOver() {
        val abc = listOf("a", "b", "c")
        val pastB = GalleryRotation.current(abc, setOf("b"), GalleryPlace("a", 0L), shuffle = false, now = minute, intervalMs = minute, advance = true, newSeed = seeds())
        assertEquals("c", pastB.image)
        // Showing one found bad just now: the next, at once.
        val fromB = GalleryRotation.current(abc, setOf("b"), GalleryPlace("b", minute), shuffle = false, now = minute + 1, intervalMs = minute, advance = false, newSeed = seeds())
        assertEquals("c", fromB.image)
        // All bad: none, and the place is kept for when they can be.
        val none = GalleryRotation.current(abc, abc.toSet(), GalleryPlace("b", 7L), shuffle = false, now = minute, intervalMs = minute, advance = true, newSeed = seeds())
        assertNull(none.image)
        assertEquals(GalleryPlace("b", 7L), none.place)
    }

    @Test
    fun shuffleGetsAnOrderOfItsOwnTheFirstTime() {
        val step = GalleryRotation.current(images, emptySet(), GalleryPlace(), shuffle = true, now = 1L, intervalMs = minute, advance = true, newSeed = { 42L })
        assertEquals(42L, step.place.seed)
        assertEquals(images.minWith(compareBy<String>({ GalleryRotation.key(42L, it) }, { it })), step.image)
    }

    @Test
    fun theNextChangeIsAnIntervalAfterTheLast() {
        assertNull(GalleryRotation.nextChangeAt(1, GalleryPlace("a", 1000L), minute))
        assertNull(GalleryRotation.nextChangeAt(0, GalleryPlace(), minute))
        assertEquals(1000L + minute, GalleryRotation.nextChangeAt(2, GalleryPlace("a", 1000L), minute))
    }
}
