package com.backscreen.wallpaper.core

import android.annotation.SuppressLint
import android.content.Context
import com.backscreen.wallpaper.R

/** How long the back screen stays bright after a touch or a wake: Xiaomi's own setting, or one of these. */
enum class LitTime(val ms: Int?, val label: Int) {
    /** Xiaomi's own value, left alone (10 s on the 17 Pro Max). */
    AS_SET(null, R.string.lit_as_set),
    SECONDS_5(5_000, R.string.lit_5s),
    SECONDS_30(30_000, R.string.keep_lit_30s),
    MINUTE_1(60_000, R.string.lit_1min),
    MINUTES_2(120_000, R.string.keep_lit_2min),
    MINUTES_5(300_000, R.string.lit_5min),
}

/** Xiaomi's own value of a setting, as it was before this app changed it: [value] is null if it wasn't set. */
data class Saved(val value: String?)

/**
 * The Wallpaper tab's Back screen display card. Not a section of its own (the bottom bar holds
 * five), so there's no main switch. Stays lit for is Xiaomi's own setting and counts with the
 * wallpaper off too ([XiaomiLitTime]); the other two need the wallpaper ([DisplayKeeper]).
 */
object DisplaySettings {
    private const val PREFS = "display"
    private const val KEY_LIT_TIME = "lit_time"
    private const val KEY_XIAOMI_LIT_TIME = "xiaomi_lit_time"
    private const val KEY_STAY_LIT_UNLOCKED = "stay_lit_unlocked"
    private const val KEY_WAKE_ON_PICKUP = "wake_on_pickup"

    // Saved for a setting that wasn't set at all, so it's unset again afterwards.
    private const val UNSET = "unset"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stays lit for: As set by default, which leaves Xiaomi's value alone. */
    fun litTime(context: Context): LitTime =
        LitTime.entries.firstOrNull { it.ms == prefs(context).getInt(KEY_LIT_TIME, 0) } ?: LitTime.AS_SET

    fun setLitTime(context: Context, value: LitTime) {
        prefs(context).edit().apply { if (value.ms == null) remove(KEY_LIT_TIME) else putInt(KEY_LIT_TIME, value.ms) }.apply()
    }

    /** Xiaomi's own timeout, saved the first time Stays lit for changed it; null until then. */
    fun xiaomiLitTime(context: Context): Saved? =
        prefs(context).getString(KEY_XIAOMI_LIT_TIME, null)?.let { Saved(it.takeIf { v -> v != UNSET }) }

    /** On disk before Xiaomi's setting is changed, so it can always be put back; null forgets it. */
    @SuppressLint("ApplySharedPref")
    fun saveXiaomiLitTime(context: Context, saved: Saved?) {
        val edit = prefs(context).edit()
        if (saved == null) edit.remove(KEY_XIAOMI_LIT_TIME) else edit.putString(KEY_XIAOMI_LIT_TIME, saved.value ?: UNSET)
        edit.commit()
    }

    /** Stay fully lit while unlocked. Off by default: it uses more battery. */
    fun stayLitUnlocked(context: Context) = prefs(context).getBoolean(KEY_STAY_LIT_UNLOCKED, false)

    fun setStayLitUnlocked(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_STAY_LIT_UNLOCKED, on).apply()
    }

    /**
     * Light up when you pick it up. On by default, unlike other new options, since it puts right
     * what Xiaomi gets wrong rather than adding something (decided before Phase 9).
     */
    fun wakeOnPickUp(context: Context) = prefs(context).getBoolean(KEY_WAKE_ON_PICKUP, true)

    fun setWakeOnPickUp(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_ON_PICKUP, on).apply()
    }
}
