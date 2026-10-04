package com.backscreen.wallpaper.mirror

import android.Manifest
import android.app.StatusBarManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.MainActivity
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.LendReason
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.ui.MainSwitchBar
import com.backscreen.wallpaper.ui.Refreshable
import com.google.android.material.chip.Chip
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
    private lateinit var sizeChips: ChipGroup
    private lateinit var orientationChips: ChipGroup

    private val ctx: Context get() = requireContext()

    // Asked once, when Quick Switch is first switched on: the notification while an app is on
    // the back screen needs it. Refusing leaves the tile and this tab to bring it back.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        BackScreen.log(if (granted) "Notifications allowed" else "Notifications refused")
        if (!granted && view != null) snackbar(R.string.mirror_notifications_denied)
    }

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
        sizeChips = view.findViewById(R.id.sizeChips)
        orientationChips = view.findViewById(R.id.orientationChips)

        mainSwitch.onCheckedChange = ::switched
        addTileButton.setOnClickListener { addTile() }
        view.findViewById<Button>(R.id.bringBackButton).setOnClickListener {
            KeeperService.start(ctx, KeeperService.ACTION_BRING_BACK)
        }

        addChips(keepLitChips, KeepLit.entries, KeepLit::label, MirrorSettings.keepLit(ctx)) {
            MirrorSettings.setKeepLit(ctx, it)
            BackScreen.log("Back screen stays lit: ${it.name.lowercase()}")
            optionChanged()
        }
        addChips(sizeChips, AppSize.entries, AppSize::label, MirrorSettings.size(ctx)) {
            MirrorSettings.setSize(ctx, it)
            BackScreen.log("App size on the back screen: ${it.name.lowercase()}")
            optionChanged()
        }
        addChips(orientationChips, RearOrientation.entries, RearOrientation::label, MirrorSettings.orientation(ctx)) {
            MirrorSettings.setOrientation(ctx, it)
            BackScreen.log("Back screen orientation: ${it.name.lowercase()}")
            optionChanged()
        }
        coverRow.setOnClickListener { coverSwitch.toggle() }
        coverSwitch.isChecked = MirrorSettings.coverReturn(ctx)
        coverSwitch.setOnCheckedChangeListener { _, checked ->
            MirrorSettings.setCoverReturn(ctx, checked)
            optionChanged()
        }
    }

    /** A row of chips, one per option, with [selected] checked; [onChosen] when you pick another. */
    private fun <T> addChips(group: ChipGroup, options: List<T>, label: (T) -> Int, selected: T, onChosen: (T) -> Unit) {
        for (option in options) {
            val chip = layoutInflater.inflate(R.layout.chip_style, group, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = option
            chip.setText(label(option))
            chip.isChecked = option == selected
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            @Suppress("UNCHECKED_CAST")
            val chosen = ids.firstOrNull()?.let { g.findViewById<Chip>(it).tag as T } ?: return@setOnCheckedStateChangeListener
            onChosen(chosen)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (view == null) return
        val on = MirrorSettings.isEnabled(ctx)
        val lend = (RearState.owner(ctx) as? RearOwner.Lent)?.lend?.takeIf { it.reason == LendReason.QUICK_SWITCH }
        val name = lend?.let { AppLabels.get(ctx, it.packageName) }
        mainSwitch.isChecked = on
        mainSwitch.setSummary(
            when {
                !on -> getString(R.string.state_off)
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
        for (group in listOf(keepLitChips, sizeChips, orientationChips)) {
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = on
        }
        coverRow.isEnabled = on
        coverSwitch.isEnabled = on
    }

    private fun switched(on: Boolean) {
        MirrorSettings.setEnabled(ctx, on)
        BackScreen.log(if (on) "Quick Switch on" else "Quick Switch off")
        // The keeper runs while it's on, for the tile to ask; switching off brings an app back.
        KeeperService.start(ctx, KeeperService.ACTION_UPDATE)
        QuickSwitchTile.requestUpdate(ctx)
        if (on) askForNotificationsOnce()
        refresh()
    }

    private fun askForNotificationsOnce() {
        if (MirrorSettings.askedNotifications(ctx)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        MirrorSettings.setAskedNotifications(ctx)
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** An option changed: an app on the back screen takes it at once. */
    private fun optionChanged() {
        if (MirrorSettings.isEnabled(ctx)) KeeperService.start(ctx, KeeperService.ACTION_MIRROR_CHANGED)
    }

    /** Android's own one-tap prompt to add the tile. */
    private fun addTile() {
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
                    MirrorSettings.setTileAdded(ctx, true)
                    if (view != null) snackbar(R.string.tile_added_now)
                }
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {}
                else -> if (view != null) snackbar(R.string.tile_add_failed)
            }
            refresh()
        }
    }

    private fun snackbar(text: Int) = (requireActivity() as MainActivity).snackbar(text, Snackbar.LENGTH_LONG)

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}
