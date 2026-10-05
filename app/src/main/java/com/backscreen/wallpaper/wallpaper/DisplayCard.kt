package com.backscreen.wallpaper.wallpaper

import android.view.LayoutInflater
import android.view.View
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.DisplaySettings
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.LitTime
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.XiaomiLitTime
import com.backscreen.wallpaper.ui.SwitchRow
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

/**
 * The Wallpaper tab's Back screen display card ([DisplaySettings]): Stays lit for, whose As set
 * chip shows Xiaomi's own time; Stay fully lit while unlocked; and Light up when you pick it up.
 * Stays lit for is Xiaomi's setting, so it works with the wallpaper off but needs Shizuku; the two
 * switches work while the wallpaper is up, and the card says so while it isn't.
 */
class DisplayCard(card: View, inflater: LayoutInflater) {
    private val context = card.context
    private val chips: ChipGroup = card.findViewById(R.id.litChips)
    private val shizukuNote: View = card.findViewById(R.id.litShizukuNote)
    private val wallpaperNote: View = card.findViewById(R.id.displayWallpaperNote)
    private val asSet: Chip

    // Xiaomi's own time has been read for As set's chip, through Shizuku, since the tab showed.
    private var read = false
    private var reading = false

    init {
        val chosen = DisplaySettings.litTime(context)
        var asSetChip: Chip? = null
        for (option in LitTime.entries) {
            val chip = inflater.inflate(R.layout.chip_style, chips, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = option
            chip.setText(option.label)
            chip.isChecked = option == chosen
            chips.addView(chip)
            if (option == LitTime.AS_SET) asSetChip = chip
        }
        asSet = asSetChip!!
        chips.setOnCheckedStateChangeListener { group, ids ->
            val option = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as LitTime } ?: return@setOnCheckedStateChangeListener
            DisplaySettings.setLitTime(context, option)
            BackScreen.log("Stays lit for: ${option.name.lowercase()}")
            XiaomiLitTime.changed(context)
        }

        val stayLit = card.findViewById<MaterialSwitch>(R.id.stayLitSwitch)
        SwitchRow.bind(card.findViewById(R.id.stayLitRow), stayLit)
        stayLit.isChecked = DisplaySettings.stayLitUnlocked(context)
        stayLit.setOnCheckedChangeListener { _, checked ->
            DisplaySettings.setStayLitUnlocked(context, checked)
            BackScreen.log(if (checked) "Stay fully lit while unlocked on" else "Stay fully lit while unlocked off")
            KeeperService.instance?.displaySettingsChanged()
        }
        val pickUp = card.findViewById<MaterialSwitch>(R.id.pickUpSwitch)
        SwitchRow.bind(card.findViewById(R.id.pickUpRow), pickUp)
        pickUp.isChecked = DisplaySettings.wakeOnPickUp(context)
        pickUp.setOnCheckedChangeListener { _, checked ->
            DisplaySettings.setWakeOnPickUp(context, checked)
            BackScreen.log(if (checked) "Light up when picked up on" else "Light up when picked up off")
            KeeperService.instance?.displaySettingsChanged()
        }
    }

    /** The tab is showing again: Xiaomi's time may have changed meanwhile. */
    fun reload() {
        read = false
        refresh()
    }

    /** Whether Shizuku is ready for Stays lit for, and the wallpaper for the switches. The tab calls this every second. */
    fun refresh() {
        val ready = RearCommands.isReady()
        for (i in 0 until chips.childCount) chips.getChildAt(i).isEnabled = ready
        shizukuNote.visibility = if (ready) View.GONE else View.VISIBLE
        wallpaperNote.visibility = if (WallpaperSettings.isEnabled(context)) View.GONE else View.VISIBLE
        if (ready && !read && !reading) readXiaomi()
    }

    private fun readXiaomi() {
        reading = true
        val app = context.applicationContext
        thread {
            val ms = XiaomiLitTime.xiaomiMs(app)
            BackScreen.mainHandler.post {
                reading = false
                read = true
                asSet.text = ms?.let { context.getString(R.string.lit_as_set_value, formatMs(it)) }
                    ?: context.getString(R.string.lit_as_set)
            }
        }
    }

    /** 10 s, 2 min, or 1 min 30 s. */
    private fun formatMs(ms: Int): String {
        val seconds = (ms + 500) / 1000
        return when {
            seconds < 60 -> context.getString(R.string.duration_seconds, seconds)
            seconds % 60 == 0 -> context.getString(R.string.duration_whole_minutes, seconds / 60)
            else -> context.getString(R.string.duration_minutes, seconds / 60, seconds % 60)
        }
    }
}
