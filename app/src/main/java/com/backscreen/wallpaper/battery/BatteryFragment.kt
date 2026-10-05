package com.backscreen.wallpaper.battery

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.MainActivity
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.OverlayRoute
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.rear.RearHostActivity
import com.backscreen.wallpaper.ui.MainSwitchBar
import com.backscreen.wallpaper.ui.Refreshable
import com.backscreen.wallpaper.ui.SwitchRow
import com.backscreen.wallpaper.wallpaper.ClockLayer
import com.backscreen.wallpaper.wallpaper.ColorPicker
import com.backscreen.wallpaper.wallpaper.PreviewBackdrop
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

/**
 * The Battery tab: the charging animation's switch, a preview shaped like the back screen with
 * Play under it, its style and colour, and the options. The style, colour and options grey out
 * while it's off.
 *
 * The preview shows what you'd see when you plug in: the chosen style over the wallpaper and its
 * clock, or over black while the wallpaper is off (a pop-over is black). At rest it holds the
 * animation's middle, still, with the battery's level now; Play runs it from the start here and
 * on the back screen.
 */
class BatteryFragment : Fragment(R.layout.fragment_battery), Refreshable {

    private lateinit var mainSwitch: MainSwitchBar
    private lateinit var backdrop: PreviewBackdrop
    private lateinit var clock: ClockLayer
    private lateinit var charging: ChargingLayer
    private lateinit var playButton: Button
    private lateinit var styleCard: View
    private lateinit var styleChips: ChipGroup
    private lateinit var styleDetail: TextView
    private lateinit var colorRow: View
    private lateinit var colorValue: TextView
    private lateinit var colorSwatch: View
    private lateinit var optionsCard: View
    private lateinit var lightUpRow: View
    private lateinit var lightUpSwitch: MaterialSwitch
    private lateinit var overXiaomiRow: View
    private lateinit var overXiaomiSwitch: MaterialSwitch

    private var status = ChargingStatus(level = 100)

    // After Play, the still comes back a moment after the animation has faded away.
    private var stillDue = false
    private val showStillAgain = Runnable {
        stillDue = false
        if (view != null) charging.showStill(status, fadeIn = true)
    }

    private val ctx: Context get() = requireContext()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainSwitch = view.findViewById(R.id.mainSwitch)
        clock = view.findViewById(R.id.clock)
        charging = view.findViewById(R.id.charging)
        playButton = view.findViewById(R.id.playButton)
        styleCard = view.findViewById(R.id.styleCard)
        styleChips = view.findViewById(R.id.styleChips)
        styleDetail = view.findViewById(R.id.styleDetail)
        colorRow = view.findViewById(R.id.colorRow)
        colorValue = view.findViewById(R.id.colorValue)
        colorSwatch = view.findViewById(R.id.colorSwatch)
        optionsCard = view.findViewById(R.id.optionsCard)
        lightUpRow = view.findViewById(R.id.lightUpRow)
        lightUpSwitch = view.findViewById(R.id.lightUpSwitch)
        overXiaomiRow = view.findViewById(R.id.overXiaomiRow)
        overXiaomiSwitch = view.findViewById(R.id.overXiaomiSwitch)
        // The animation takes the clock's colours, and the image's for Auto, as on the back screen.
        backdrop = PreviewBackdrop(view.findViewById(R.id.previewFrame), view.findViewById(R.id.preview), clock, ::matchClockColors)

        mainSwitch.onCheckedChange = { on ->
            BatterySettings.setEnabled(ctx, on)
            BackScreen.log(if (on) "Charging animation on" else "Charging animation off")
            // The service hears plug-ins, so it runs while this is on.
            KeeperService.start(ctx, KeeperService.ACTION_UPDATE)
            RearHostActivity.chargingChanged()
            refresh()
        }

        setUpStyle()
        SwitchRow.bind(lightUpRow, lightUpSwitch)
        lightUpSwitch.isChecked = BatterySettings.lightUp(ctx)
        lightUpSwitch.setOnCheckedChangeListener { _, checked -> BatterySettings.setLightUp(ctx, checked) }
        SwitchRow.bind(overXiaomiRow, overXiaomiSwitch)
        overXiaomiSwitch.isChecked = BatterySettings.overXiaomi(ctx)
        overXiaomiSwitch.setOnCheckedChangeListener { _, checked ->
            BatterySettings.setOverXiaomi(ctx, checked)
            refresh()
        }

        playButton.setOnClickListener { play() }
        // As in a pop-over or the preview, the glow fades away after it plays; Minimal goes below the clock.
        charging.clock = clock
        charging.style = BatterySettings.style(ctx)
        charging.accent = BatterySettings.color(ctx)
        charging.onDone = ::onPreviewPlayed
        charging.onShown = { shown -> clock.alpha = 1f - shown }

        status = ChargingStatus.read(ctx) ?: status
        charging.showStill(status)
        showStyle()
    }

    /** The style chips and the colour row. */
    private fun setUpStyle() {
        val chosen = BatterySettings.style(ctx)
        for (option in ChargingStyle.entries) {
            val chip = layoutInflater.inflate(R.layout.chip_style, styleChips, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = option
            chip.setText(option.label)
            chip.isChecked = option == chosen
            styleChips.addView(chip)
        }
        styleChips.setOnCheckedStateChangeListener { group, ids ->
            val style = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as ChargingStyle } ?: return@setOnCheckedStateChangeListener
            BatterySettings.setStyle(ctx, style)
            BackScreen.log("Charging style: ${style.name.lowercase()}")
            charging.style = style
            rest()
            showStyle()
            RearHostActivity.chargingChanged()
        }
        colorRow.setOnClickListener {
            ColorPicker.show(
                ctx, R.string.charging_colour, R.string.colour_auto_wallpaper, BatterySettings.color(ctx), autoColor(),
            ) { color ->
                BatterySettings.setColor(ctx, color)
                charging.accent = color
                showStyle()
                RearHostActivity.chargingChanged()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) reloadPreview()
    }

    override fun onPause() {
        super.onPause()
        rest()
    }

    /** Another tab is showing, or this one again. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) rest() else reloadPreview()
    }

    override fun onDestroyView() {
        BackScreen.mainHandler.removeCallbacks(showStillAgain)
        backdrop.release()
        super.onDestroyView()
    }

    override fun refresh() {
        if (view == null) return
        val on = BatterySettings.isEnabled(ctx)
        val wallpaperOn = WallpaperSettings.isEnabled(ctx)
        mainSwitch.isChecked = on
        mainSwitch.setSummary(
            when {
                !on -> R.string.state_off
                wallpaperOn -> R.string.battery_on_wallpaper
                BatterySettings.overXiaomi(ctx) -> R.string.battery_on_xiaomi
                else -> R.string.battery_on_nowhere
            }
        )
        // The style and options are for while it's on.
        for (card in listOf(styleCard, optionsCard)) card.alpha = if (on) 1f else DISABLED_ALPHA
        for (i in 0 until styleChips.childCount) styleChips.getChildAt(i).isEnabled = on
        for (v in listOf(colorRow, lightUpRow, lightUpSwitch, overXiaomiRow, overXiaomiSwitch)) v.isEnabled = on

        backdrop.refresh()
        // A pop-over has no clock: Minimal is centred there.
        charging.centred = !wallpaperOn

        // The level now, while it's at rest.
        val now = ChargingStatus.read(ctx)
        if (now != null && now != status && !charging.isPlaying && !stillDue) {
            status = now
            charging.showStill(status)
        }
    }

    /** Shown again: the Wallpaper tab may have changed what's under it. */
    private fun reloadPreview() {
        backdrop.reload()
        refresh()
    }

    /** Runs it from the start in the preview, and on the back screen where a plug-in would play it now. */
    private fun play() {
        BackScreen.mainHandler.removeCallbacks(showStillAgain)
        stillDue = false
        status = ChargingStatus.read(ctx) ?: status
        matchClockColors()
        charging.play(status)
        playButton.isEnabled = false
        val message = when {
            !BatterySettings.isEnabled(ctx) -> R.string.play_preview_only
            RearState.owner(ctx) is RearOwner.Lent -> R.string.play_lent
            RearState.snapshot(ctx).routeOverlay(BatterySettings.overXiaomi(ctx)) == OverlayRoute.DROP ->
                R.string.play_wallpaper_off
            else -> {
                KeeperService.start(ctx, KeeperService.ACTION_PLAY_CHARGING)
                if (RearCommands.isReady()) null else R.string.need_shizuku
            }
        }
        message?.let { (requireActivity() as MainActivity).snackbar(it, Snackbar.LENGTH_LONG) }
    }

    private fun onPreviewPlayed() {
        playButton.isEnabled = true
        stillDue = true
        BackScreen.mainHandler.postDelayed(showStillAgain, STILL_AGAIN_MS)
    }

    /** Out of sight, or a new style: stop a Play, and show the still. */
    private fun rest() {
        if (view == null) return
        BackScreen.mainHandler.removeCallbacks(showStillAgain)
        stillDue = false
        playButton.isEnabled = true
        charging.showStill(status)
    }

    /**
     * The clock's colours, so it matches the wallpaper; light on a dark veil without one. Auto
     * takes the image's colour.
     */
    private fun matchClockColors() {
        if (backdrop.clockShown) charging.setColors(clock.textColor, clock.autoBgColor)
        else charging.setColors(Color.WHITE, Color.BLACK)
        charging.setAutoAccent(backdrop.colour)
        if (view != null) showStyle()
    }

    /** What Auto gives now: the image's colour, or the clock's text colour without one. */
    private fun autoColor() = backdrop.colour ?: if (backdrop.clockShown) clock.textColor else Color.WHITE

    /** What the chosen style does, and the colour row. */
    private fun showStyle() {
        styleDetail.setText(BatterySettings.style(ctx).summary)
        val chosen = BatterySettings.color(ctx)
        colorValue.text = chosen?.let { "#%06X".format(Locale.US, it and 0xFFFFFF) } ?: getString(R.string.colour_auto)
        colorRow.contentDescription = getString(if (chosen == null) R.string.charging_colour_auto else R.string.charging_colour_custom)
        colorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(charging.accentColor)
            setStroke(
                resources.displayMetrics.density.toInt().coerceAtLeast(1),
                MaterialColors.getColor(colorSwatch, com.google.android.material.R.attr.colorOutline),
            )
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.38f
        const val STILL_AGAIN_MS = 700L
    }
}
