package com.backscreen.wallpaper.core

import java.util.concurrent.ConcurrentHashMap

/**
 * What's read from an installed app, such as its icon or name, kept until the app is updated or
 * installed again. Each install puts the app at a new path (ApplicationInfo.sourceDir), so that's
 * the version: a different one means read it again. Any thread. Plain Kotlin, so it's unit tested.
 */
class VersionedCache<K : Any, V : Any> {
    private class Entry<V>(val version: String, val value: V)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    /**
     * [key]'s value for [version]: the one kept, or else [read]'s, which is kept. If [read] gives
     * nothing, the older value is kept and given ([peek]) rather than none.
     */
    fun get(key: K, version: String, read: () -> V?): V? {
        val kept = entries[key]
        if (kept != null && kept.version == version) return kept.value
        val value = read() ?: return kept?.value
        entries[key] = Entry(version, value)
        return value
    }

    /** [key]'s value as last kept, whatever its version, without reading anything. */
    fun peek(key: K): V? = entries[key]?.value
}
