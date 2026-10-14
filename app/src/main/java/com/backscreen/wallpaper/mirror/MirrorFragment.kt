package com.backscreen.wallpaper.mirror

import android.app.StatusBarManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.LendReason
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.ui.AskNotificationsOnce
import com.backscreen.wallpaper.ui.DISABLED_ALPHA
import com.backscreen.wallpaper.ui.MainSwitchBar
import com.backscreen.wallpaper.ui.Refreshable
import com.backscreen.wallpaper.ui.SwitchRow
import com.backscreen.wallpaper.ui.addChoiceChips
import com.backscreen.wallpaper.ui.setChipsEnabled
import com.backscreen.wallpaper.ui.shizukuMissing
import com.backscreen.wallpaper.ui.snackbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/**
 * The Mirror tab: Quick Switch's switch, what it does with a button that adds its tile to Quick
 * Settings, the app that's on the back screen with Bring back, and the options, which grey out
 * while it's off.
 */
class MirrorFragment : Fragment(R.layout.fragment_mirror), Refreshable {

    private lateinit var mainSwitch: MainSwitchBar
    private lateinit var addTileButton: Button
    private lateinit var tileAddedRow: View
    private lateinit var lentCard: View
    private lateinit var lentText: TextView
    private lateinit var optionsCard: View
    private lateinit var keepLitChips: ChipGroup
    private lateinit var coverRow: View
    private lateinit var coverSwitch: MaterialSwitch
    private lateinit var lockReturnRow: View
    private lateinit var lockReturnSwitch: MaterialSwitch
    private lateinit var sizeChips: ChipGroup
    private lateinit var orientationChips: ChipGroup

    private val ctx: Context get() = requireContext()

    // Refusing leaves the tile and this tab to bring an app back.
    private val askNotifications = AskNotificationsOnce(this, MirrorSettings, R.string.mirror_notifications_denied)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainSwitch = view.findViewById(R.id.mainSwitch)
        addTileButton = view.findViewById(R.id.addTileButton)
        tileAddedRow = view.findViewById(R.id.tileAddedRow)
        lentCard = view.findViewById(R.id.lentCard)
        lentText = view.findViewById(R.id.lentText)
        optionsCard = view.findViewById(R.id.optionsCard)
        keepLitChips = view.findViewById(R.id.keepLitChips)
        coverRow = view.findViewById(R.id.coverRow)
        coverSwitch = view.findViewById(R.id.coverSwitch)
        lockReturnRow = view.findViewById(R.id.lockReturnRow)
        lockReturnSwitch = view.findViewById(R.id.lockReturnSwitch)
        sizeChips = view.findViewById(R.id.sizeChips)
        orientationChips = view.findViewById(R.id.orientationChips)

        mainSwitch.onCheckedChange = ::switched
        addTileButton.setOnClickListener { addTile() }
        view.findViewById<Button>(R.id.bringBackButton).setOnClickListener {
            KeeperService.start(ctx, KeeperService.ACTION_BRING_BACK)
        }

        keepLitChips.addChoiceChips(KeepLit.entries, MirrorSettings.keepLit(ctx), { getString(it.label) }) {
            MirrorSettings.setKeepLit(ctx, it)
            BackScreen.log("Back screen stays lit: ${it.name.lowercase()}")
            optionChanged()
        }
        sizeChips.addChoiceChips(AppSize.entries, MirrorSettings.size(ctx), { getString(it.label) }) {
            MirrorSettings.setSize(ctx, it)
            BackScreen.log("App size on the back screen: ${it.name.lowercase()}")
            optionChanged()
        }
        orientationChips.addChoiceChips(RearOrientation.entries, MirrorSettings.orientation(ctx), { getString(it.label) }) {
            MirrorSettings.setOrientation(ctx, it)
            BackScreen.log("Back screen orientation: ${it.name.lowercase()}")
            optionChanged()
        }
        SwitchRow.bind(coverRow, coverSwitch)
        coverSwitch.isChecked = MirrorSettings.coverReturn(ctx)
        coverSwitch.setOnCheckedChangeListener { _, checked ->
            MirrorSettings.setCoverReturn(ctx, checked)
            optionChanged()
        }
        SwitchRow.bind(lockReturnRow, lockReturnSwitch)
        lockReturnSwitch.isChecked = MirrorSettings.lockReturn(ctx)
        lockReturnSwitch.setOnCheckedChangeListener { _, checked ->
            MirrorSettings.setLockReturn(ctx, checked)
            BackScreen.log(if (checked) "Bring back when the phone locks: on" else "Bring back when the phone locks: off")
            optionChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (view == null) return
        val on = MirrorSettings.isEnabled(ctx)
        val lend = RearState.lendFor(ctx, LendReason.QUICK_SWITCH)
        val name = lend?.let { AppLabels.shown(ctx, it.packageName, ::refresh) }
        mainSwitch.isChecked = on
        mainSwitch.setSummary(
            when {
                !on -> getString(R.string.state_off)
                shizukuMissing -> getString(R.string.state_waiting_shizuku)
                name != null -> getString(R.string.mirror_on_lent, name)
                else -> getString(R.string.mirror_on_ready)
            }
        )
        val added = MirrorSettings.tileAdded(ctx)
        addTileButton.visibility = if (added) View.GONE else View.VISIBLE
        tileAddedRow.visibility = if (added) View.VISIBLE else View.GONE
        lentCard.visibility = if (name != null) View.VISIBLE else View.GONE
        if (name != null) lentText.text = getString(R.string.lent_now, name)
        // The options are for while it's on.
        optionsCard.alpha = if (on) 1f else DISABLED_ALPHA
        for (group in listOf(keepLitChips, sizeChips, orientationChips)) group.setChipsEnabled(on)
        coverRow.isEnabled = on
        coverSwitch.isEnabled = on
        lockReturnRow.isEnabled = on
        lockReturnSwitch.isEnabled = on
    }

    private fun switched(on: Boolean) {
        MirrorSettings.setEnabled(ctx, on)
        BackScreen.log(if (on) "Quick Switch on" else "Quick Switch off")
        // The keeper runs while it's on, for the tile to ask; switching off brings an app back.
        KeeperService.start(ctx, KeeperService.ACTION_UPDATE)
        QuickSwitchTile.requestUpdate(ctx)
        if (on) askNotifications.ask()
        refresh()
    }

    /** An option changed: an app on the back screen takes it at once. */
    private fun optionChanged() {
        if (MirrorSettings.isEnabled(ctx)) KeeperService.start(ctx, KeeperService.ACTION_MIRROR_CHANGED)
    }

    /** Android's own one-tap prompt to add the tile. */
    private fun addTile() {
        // The answer can come after the phone was turned and this tab made again, so it saves through the app.
        val app = ctx.applicationContext
        val statusBar = ctx.getSystemService(StatusBarManager::class.java)
        statusBar.requestAddTileService(
            QuickSwitchTile.component(ctx),
            getString(R.string.mirror_switch),
            Icon.createWithResource(ctx, R.drawable.ic_swap),
            ContextCompat.getMainExecutor(ctx),
        ) { result ->
            BackScreen.log("Add Quick Switch tile: $result")
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                    MirrorSettings.setTileAdded(app, true)
                    if (view != null) snackbar(R.string.tile_added_now, Snackbar.LENGTH_LONG)
                }
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {}
                else -> if (view != null) snackbar(R.string.tile_add_failed, Snackbar.LENGTH_LONG)
            }
            refresh()
        }
    }
}
