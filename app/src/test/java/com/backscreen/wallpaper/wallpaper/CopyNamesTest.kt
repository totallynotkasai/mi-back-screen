package com.backscreen.wallpaper.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyNamesTest {

    private val now = 1_760_000_000_000L

    @Test
    fun newNamesSortInTheOrderPicked() {
        val names = CopyNames.next(emptyList(), 12, now)
        assertEquals("img_1760000000000_000", names.first())
        assertEquals(names, names.sorted())
        assertEquals(12, names.toSet().size)
    }

    @Test
    fun aNewPickNeverReusesAName() {
        val first = CopyNames.next(emptyList(), 3, now)
        // Picked again within the same millisecond, and after the clock went back.
        val again = CopyNames.next(first, 3, now)
        val earlier = CopyNames.next(first + again, 3, now - 60_000)
        val all = first + again + earlier
        assertEquals(9, all.toSet().size)
        assertEquals(all, all.sorted())
    }

    @Test
    fun oldCopiesSortFirst() {
        val names = CopyNames.next(listOf("img_000", "img_001"), 2, now)
        val all = listOf("img_000", "img_001") + names
        assertEquals(all, all.sorted())
        assertTrue(names.none { it.startsWith("img_0") })
    }
}
