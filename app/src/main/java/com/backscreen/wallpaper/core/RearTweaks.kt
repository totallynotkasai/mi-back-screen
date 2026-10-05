package com.backscreen.wallpaper.core

import android.annotation.SuppressLint
import android.content.Context

/**
 * One of Xiaomi's settings that a lend has changed: what it held before ([original], null if it
 * wasn't set at all), and what this app set it to ([ours]).
 */
data class Tweak(val original: String?, val ours: String)

/** What a setting holds now; [value] is null if it isn't set. [raw] is what the command printed. */
data class Current(val value: String?, val raw: String)

/** What a new base value for a setting (Stays lit for) means for it now. */
sealed interface BaseChange {
    /** Set it to [value] now (null unsets it). */
    data class Write(val value: String?) : BaseChange

    /** A lend has it changed: leave it, and the lend puts back [tweak]'s original instead. */
    data class PutBackLater(val tweak: Tweak) : BaseChange

    /** It holds that already, or will when the lend ends. */
    data object None : BaseChange
}

/**
 * The rules for changing Xiaomi's settings while an app is lent the back screen, and putting
 * them back, and reading what the shell prints for them. Plain Kotlin, so it's unit tested.
 */
object TweakRules {
    private val OVERRIDE_DENSITY = Regex("""Override density:\s*(\d+)""")
    private val PHYSICAL_DENSITY = Regex("""Physical density:\s*(\d+)""")
    private val LOCKED = Regex("""^lock (\d)$""")

    /**
     * What to save before setting [wanted] where [current] is now. Across several changes in one
     * lend, the first original is kept, unless you changed the setting yourself meanwhile: then
     * yours is what gets put back.
     */
    fun beforeChange(saved: Tweak?, current: String?, wanted: String) =
        Tweak(if (saved != null && current == saved.ours) saved.original else current, wanted)

    /** What would be put back if the lend ended now: the saved original while it's still ours. */
    fun toPutBack(saved: Tweak?, current: String?): String? =
        if (saved != null && current == saved.ours) saved.original else current

    /** Whether to put [saved]'s original back: only if it still holds what this app set. */
    fun shouldPutBack(saved: Tweak, current: String?) = current == saved.ours

    /**
     * The value the setting rests at changed to [base] (Stays lit for, on the Wallpaper tab). While
     * a lend has the setting changed ([saved], still holding its value), the lend keeps it, and
     * puts back [base] when it ends instead of what it saved. Otherwise it's set now.
     */
    fun baseChanged(saved: Tweak?, current: String?, base: String?): BaseChange = when {
        saved != null && current == saved.ours ->
            if (saved.original == base) BaseChange.None else BaseChange.PutBackLater(saved.copy(original = base))
        current == base -> BaseChange.None
        else -> BaseChange.Write(base)
    }

    /**
     * Xiaomi's own value, before this app changed it: the one saved when Stays lit for first
     * changed it ([own]), or else what a lend would put back now, or else what it holds.
     */
    fun xiaomiOwn(own: Saved?, saved: Tweak?, current: String?): String? =
        if (own != null) own.value else toPutBack(saved, current)

    /** `settings get` prints the value, or "null" when it isn't set. */
    fun timeout(output: String): String? = output.trim().takeIf { it.isNotEmpty() && it != "null" }

    /** The override `wm density` prints, or null if the display has none and uses its physical density. */
    fun density(output: String): String? = OVERRIDE_DENSITY.find(output)?.groupValues?.get(1)

    fun physicalDensity(output: String): Int? = PHYSICAL_DENSITY.find(output)?.groupValues?.get(1)?.toIntOrNull()

    /** The rotation setting as one value: `wm user-rotation` ("free" or "lock 1"), a bar, then `wm fixed-to-user-rotation`. */
    fun rotation(userRotation: String, fixed: String) = "${userRotation.trim()}|${fixed.trim()}"

    /** A rotation value's two halves: the rotation it's locked to (null if free), and the fixed mode. */
    fun splitRotation(value: String?): Pair<Int?, String> {
        val parts = value.orEmpty().split('|')
        val locked = LOCKED.find(parts.getOrElse(0) { "" }.trim())?.groupValues?.get(1)?.toInt()
        return locked to parts.getOrElse(1) { "" }.trim().ifEmpty { "default" }
    }
}

/** The Xiaomi settings a lend may change, how to read each, and how to set it. Shell thread. */
enum class TweakKind(val key: String, val label: String) {
    /** How long the back screen stays lit after the last touch (`subscreen_display_time`, ms). */
    TIMEOUT("timeout", "Xiaomi's back screen timeout") {
        override fun read(rear: Int) = RearCommands.rearTimeout()?.let { Current(TweakRules.timeout(it), it) }
        override fun write(rear: Int, value: String?) =
            value?.toIntOrNull()?.let { RearCommands.setRearTimeout(it) } ?: RearCommands.clearRearTimeout()
    },

    /** The back screen's density, which sets how big apps draw. Xiaomi overrides the panel's 450 to 260. */
    DENSITY("density", "Back screen density") {
        override fun read(rear: Int) = RearCommands.rearDensity(rear)?.let { Current(TweakRules.density(it), it) }
        // Never reset when there was an override: that goes to the panel's 450, not Xiaomi's 260.
        override fun write(rear: Int, value: String?) =
            value?.toIntOrNull()?.let { RearCommands.setRearDensity(rear, it) } ?: RearCommands.resetRearDensity(rear)
    },

    /**
     * Whether the back screen is turned. It turns only with both a locked rotation and the display
     * fixed to it; HyperOS ignores the lock on its own (checked in Phase 6).
     */
    ROTATION("rotation", "Back screen rotation") {
        override fun read(rear: Int): Current? {
            val user = RearCommands.rearUserRotation(rear) ?: return null
            val fixed = RearCommands.rearFixedRotation(rear) ?: return null
            val value = TweakRules.rotation(user, fixed)
            return Current(value, value)
        }

        override fun write(rear: Int, value: String?): String {
            val (locked, fixed) = TweakRules.splitRotation(value)
            // Locking to 0 before freeing it puts the stored rotation back to the natural one too.
            val rotation = if (locked != null) {
                RearCommands.lockRearRotation(rear, locked)
            } else {
                RearCommands.lockRearRotation(rear, 0)
                RearCommands.freeRearRotation(rear)
            }
            val mode = RearCommands.setRearFixedRotation(rear, fixed)
            return if (rotation == "ok") mode else rotation
        }
    };

    /** What it holds now, or null if it couldn't be read. */
    abstract fun read(rear: Int): Current?

    /** Sets it to [value], or unsets it for null. "ok" if it worked. */
    abstract fun write(rear: Int, value: String?): String
}

/**
 * Everything a lend changes in Xiaomi's settings: how long the back screen stays lit, and the
 * back screen's density and rotation if those are chosen. Each is saved to disk with its original
 * before it's changed, and put back when the lend ends; also when the keeper starts with no app
 * lent, so a crash or a restart can't leave them changed for good. A value is put back only if
 * it still holds what this app set, so a change you make in Xiaomi's settings meanwhile is kept.
 *
 * Runs the shell, so never on the main thread.
 */
object RearTweaks {
    private const val PREFS = "rear_tweaks"
    private const val OURS = ".ours"
    private const val ORIGINAL = ".original"

    /**
     * Sets [kind] on [rear] to what [wanted] says, given the value that would be put back (null if
     * unset) and what the shell printed. Saves the original first. Null from [wanted] leaves it.
     */
    fun set(context: Context, kind: TweakKind, rear: Int, wanted: (original: String?, raw: String) -> String?) {
        val current = kind.read(rear) ?: return BackScreen.log("${kind.label}: couldn't read it, so left as it is")
        val saved = load(context, kind)
        val value = wanted(TweakRules.toPutBack(saved, current.value), current.raw) ?: return
        if (value == current.value && saved == null) return
        if (value == current.value && saved?.ours == value) return
        val tweak = TweakRules.beforeChange(saved, current.value, value)
        save(context, kind, tweak)
        BackScreen.log("${kind.label}: $value (was ${tweak.original ?: "unset"}): ${kind.write(rear, value)}")
    }

    /** Puts [kind] back, if this app changed it. */
    fun putBack(context: Context, kind: TweakKind, rear: Int) {
        val saved = load(context, kind) ?: return
        val current = kind.read(rear) ?: return BackScreen.log("${kind.label}: couldn't read it to put it back; trying later")
        if (TweakRules.shouldPutBack(saved, current.value)) {
            BackScreen.log("${kind.label}: back to ${saved.original ?: "unset"}: ${kind.write(rear, saved.original)}")
        } else {
            BackScreen.log("${kind.label}: changed meanwhile (${current.value}), so left as it is")
        }
        clear(context, kind)
    }

    /** Puts back everything a lend changed. */
    fun putBackAll(context: Context, rear: Int) {
        for (kind in TweakKind.entries) putBack(context, kind, rear)
    }

    /** Whether anything is changed and waiting to be put back. Any thread. */
    fun pending(context: Context) = TweakKind.entries.any { load(context, it) != null }

    /** What a lend changed [kind] from and to, if it's waiting to be put back. Any thread. */
    fun saved(context: Context, kind: TweakKind): Tweak? = load(context, kind)

    /** A lend's [kind] is to be put back to [tweak]'s original instead (see [TweakRules.baseChanged]). */
    fun replace(context: Context, kind: TweakKind, tweak: Tweak) = save(context, kind, tweak)

    /** What's changed, for Diagnostics: "Xiaomi's back screen timeout 120000 (was 10000)". */
    fun summary(context: Context): String? = TweakKind.entries.mapNotNull { kind ->
        load(context, kind)?.let { "${kind.label} ${it.ours} (was ${it.original ?: "unset"})" }
    }.takeIf { it.isNotEmpty() }?.joinToString("; ")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context, kind: TweakKind): Tweak? {
        val p = prefs(context)
        val ours = p.getString(kind.key + OURS, null) ?: return null
        return Tweak(p.getString(kind.key + ORIGINAL, null), ours)
    }

    // commit, not apply: on disk before the setting is changed, so a crash right after still puts it back.
    @SuppressLint("ApplySharedPref")
    private fun save(context: Context, kind: TweakKind, tweak: Tweak) {
        prefs(context).edit()
            .putString(kind.key + OURS, tweak.ours)
            .apply { if (tweak.original == null) remove(kind.key + ORIGINAL) else putString(kind.key + ORIGINAL, tweak.original) }
            .commit()
    }

    @SuppressLint("ApplySharedPref")
    private fun clear(context: Context, kind: TweakKind) {
        prefs(context).edit().remove(kind.key + OURS).remove(kind.key + ORIGINAL).commit()
    }
}
