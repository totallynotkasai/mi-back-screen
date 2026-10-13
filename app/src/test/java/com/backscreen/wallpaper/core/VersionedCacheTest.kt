package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionedCacheTest {

    @Test
    fun theSameVersionIsReadOnce() {
        val cache = VersionedCache<String, String>()
        var reads = 0
        repeat(3) { assertEquals("Chat", cache.get("chat", "/data/app/~~a/base.apk") { reads++; "Chat" }) }
        assertEquals(1, reads)
    }

    @Test
    fun anUpdateIsReadAgain() {
        val cache = VersionedCache<String, String>()
        cache.get("chat", "/data/app/~~a/base.apk") { "Chat" }
        // Updated: installed at a new path, with a new name.
        assertEquals("Chat Pro", cache.get("chat", "/data/app/~~b/base.apk") { "Chat Pro" })
        assertEquals("Chat Pro", cache.peek("chat"))
    }

    @Test
    fun aFailedReadKeepsTheOldValue() {
        val cache = VersionedCache<String, String>()
        cache.get("chat", "/data/app/~~a/base.apk") { "Chat" }
        assertEquals("Chat", cache.get("chat", "/data/app/~~b/base.apk") { null })
        // And it's tried again next time, since the new version isn't kept.
        assertEquals("Chat Pro", cache.get("chat", "/data/app/~~b/base.apk") { "Chat Pro" })
    }

    @Test
    fun eachKeyIsItsOwn() {
        val cache = VersionedCache<Pair<Int, String>, String>()
        cache.get(10_123 to "chat", "/a") { "personal" }
        cache.get(1_010_123 to "chat", "/a") { "work" }
        assertEquals("personal", cache.peek(10_123 to "chat"))
        assertEquals("work", cache.peek(1_010_123 to "chat"))
        assertNull(cache.peek(10_124 to "chat"))
    }
}
