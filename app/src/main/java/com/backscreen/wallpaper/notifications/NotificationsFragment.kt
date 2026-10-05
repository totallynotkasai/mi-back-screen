package com.backscreen.wallpaper.notifications

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.MainActivity
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.rear.RearHostActivity
import com.backscreen.wallpaper.ui.MainSwitchBar
import com.backscreen.wallpaper.ui.Refreshable
import com.backscreen.wallpaper.ui.SwitchRow
import com.backscreen.wallpaper.wallpaper.ClockLayer
import com.backscreen.wallpaper.wallpaper.PreviewBackdrop
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/**
 * The Notifications tab: the switch, Notification access, a preview shaped like the back screen
 * with a sample notification in the chosen style and Try on back screen under it, the style,
 * and the options. The style and options grey out while it's off; access can be allowed first.
 */
class NotificationsFragment : Fragment(R.layout.fragment_notifications), Refreshable {

    private lateinit var mainSwitch: MainSwitchBar
    private lateinit var accessIcon: ImageView
    private lateinit var accessText: TextView
    private lateinit var accessButton: Button
    private lateinit var backdrop: PreviewBackdrop
    private lateinit var clock: ClockLayer
    private lateinit var notifications: NotificationLayer
    private lateinit var tryButton: Button
    private lateinit var styleCard: View
    private lateinit var styleChips: ChipGroup
    private lateinit var styleDetail: TextView
    private lateinit var optionsCard: View
    private lateinit var swipeDownRow: View
    private lateinit var swipeDownSwitch: MaterialSwitch
    private lateinit var lightUpRow: View
    private lateinit var lightUpSwitch: MaterialSwitch

    // Shizuku is allowing or removing access, so the button waits.
    private var changingAccess = false

    private val ctx: Context get() = requireContext()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainSwitch = view.findViewById(R.id.mainSwitch)
        accessIcon = view.findViewById(R.id.accessIcon)
        accessText = view.findViewById(R.id.accessText)
        accessButton = view.findViewById(R.id.accessButton)
        clock = view.findViewById(R.id.clock)
        notifications = view.findViewById(R.id.notifications)
        tryButton = view.findViewById(R.id.tryButton)
        styleCard = view.findViewById(R.id.styleCard)
        styleChips = view.findViewById(R.id.styleChips)
        styleDetail = view.findViewById(R.id.styleDetail)
        optionsCard = view.findViewById(R.id.optionsCard)
        swipeDownRow = view.findViewById(R.id.swipeDownRow)
        swipeDownSwitch = view.findViewById(R.id.swipeDownSwitch)
        lightUpRow = view.findViewById(R.id.lightUpRow)
        lightUpSwitch = view.findViewById(R.id.lightUpSwitch)
        // The banner takes the clock's colours, as on the back screen.
        backdrop = PreviewBackdrop(view.findViewById(R.id.previewFrame), view.findViewById(R.id.preview), clock, ::matchClockColors)

        mainSwitch.onCheckedChange = ::switched
        accessButton.setOnClickListener { onAccessButton() }
        tryButton.setOnClickListener { tryIt() }

        val style = NotificationSettings.style(ctx)
        for (option in NotificationStyle.entries) {
            val chip = layoutInflater.inflate(R.layout.chip_style, styleChips, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = option
            chip.setText(option.label)
            chip.isChecked = option == style
            styleChips.addView(chip)
        }
        styleChips.setOnCheckedStateChangeListener { group, ids ->
            val chosen = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as NotificationStyle }
                ?: return@setOnCheckedStateChangeListener
            NotificationSettings.setStyle(ctx, chosen)
            BackScreen.log("Notification style: ${chosen.name.lowercase()}")
            RearHostActivity.notificationsChanged()
            showStyle()
        }

        SwitchRow.bind(swipeDownRow, swipeDownSwitch)
        swipeDownSwitch.isChecked = NotificationSettings.swipeDown(ctx)
        swipeDownSwitch.setOnCheckedChangeListener { _, checked ->
            NotificationSettings.setSwipeDown(ctx, checked)
            RearHostActivity.notificationsChanged()
        }
        SwitchRow.bind(lightUpRow, lightUpSwitch)
        lightUpSwitch.isChecked = NotificationSettings.lightUp(ctx)
        lightUpSwitch.setOnCheckedChangeListener { _, checked -> NotificationSettings.setLightUp(ctx, checked) }

        showStyle()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) reloadPreview()
    }

    /** Another tab is showing, or this one again. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) reloadPreview()
    }

    override fun onDestroyView() {
        backdrop.release()
        super.onDestroyView()
    }

    override fun refresh() {
        if (view == null) return
        val on = NotificationSettings.isEnabled(ctx)
        val allowed = NotificationAccess.isGranted(ctx)
        mainSwitch.isChecked = on
        mainSwitch.setSummary(
            when {
                !on -> R.string.state_off
                !allowed -> R.string.notifications_on_no_access
                !WallpaperSettings.isEnabled(ctx) -> R.string.notifications_on_xiaomi
                else -> R.string.notifications_on_wallpaper
            }
        )
        showAccess(allowed)
        // The style and options are for while it's on.
        for (card in listOf(styleCard, optionsCard)) card.alpha = if (on) 1f else DISABLED_ALPHA
        for (i in 0 until styleChips.childCount) styleChips.getChildAt(i).isEnabled = on
        for (v in listOf(swipeDownRow, swipeDownSwitch, lightUpRow, lightUpSwitch)) v.isEnabled = on
        backdrop.refresh()
    }

    /** Whether access is allowed, and the one button that changes that. */
    private fun showAccess(allowed: Boolean) {
        val shizuku = RearCommands.isReady()
        accessIcon.setImageResource(if (allowed) R.drawable.ic_check_circle else R.drawable.ic_error)
        accessIcon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(accessIcon, if (allowed) MaterialR.attr.colorPrimary else MaterialR.attr.colorError)
        )
        accessIcon.contentDescription = getString(if (allowed) R.string.setup_done else R.string.setup_needed_short)
        accessText.setText(
            when {
                allowed -> R.string.access_allowed
                shizuku -> R.string.access_needed
                else -> R.string.access_needed_settings
            }
        )
        accessButton.setText(
            when {
                allowed -> R.string.access_remove
                shizuku -> R.string.allow_access
                else -> R.string.open_settings
            }
        )
        accessButton.isEnabled = !changingAccess
    }

    private fun switched(on: Boolean) {
        NotificationSettings.setEnabled(ctx, on)
        BackScreen.log(if (on) "Notifications on" else "Notifications off")
        RearNotificationListener.switched(ctx, on)
        RearHostActivity.notificationsChanged()
        // One tap: switching it on allows access too, if Shizuku can.
        if (on && !NotificationAccess.isGranted(ctx) && RearCommands.isReady()) allow()
        refresh()
    }

    private fun onAccessButton() {
        val allowed = NotificationAccess.isGranted(ctx)
        when {
            !RearCommands.isReady() -> NotificationAccess.openSettings(ctx)
            allowed -> remove()
            else -> allow()
        }
    }

    private fun allow() = changeAccess(NotificationAccess::grant) { allowed ->
        if (allowed && NotificationSettings.isEnabled(ctx)) RearNotificationListener.switched(ctx, true)
        if (allowed) R.string.access_now_allowed else R.string.access_failed
    }

    private fun remove() = changeAccess(NotificationAccess::remove) { allowed ->
        if (allowed) R.string.access_failed else R.string.access_now_removed
    }

    /** Runs [change] through Shizuku, then says how it went: [result] gives the message. */
    private fun changeAccess(change: (Context, (Boolean) -> Unit) -> Unit, result: (Boolean) -> Int) {
        changingAccess = true
        refresh()
        change(ctx) { allowed ->
            changingAccess = false
            if (view == null) return@change
            (requireActivity() as MainActivity).snackbar(result(allowed))
            refresh()
        }
    }

    /** Shows it in the preview from the start, and on the back screen where it would show now. */
    private fun tryIt() {
        notifications.hideBanner(animate = false)
        showStyle()
        val message = when {
            !NotificationSettings.isEnabled(ctx) -> R.string.try_preview_only
            !WallpaperSettings.isEnabled(ctx) -> R.string.try_wallpaper_off
            RearState.owner(ctx) is RearOwner.Lent -> R.string.play_lent
            else -> {
                KeeperService.start(ctx, KeeperService.ACTION_TRY_NOTIFICATION)
                if (RearCommands.isReady()) null else R.string.need_shizuku
            }
        }
        message?.let { (requireActivity() as MainActivity).snackbar(it, Snackbar.LENGTH_LONG) }
    }

    /** The sample in the preview, in the chosen style, and what that style shows. */
    private fun showStyle() {
        val style = NotificationSettings.style(ctx)
        styleDetail.setText(style.summary)
        NotificationFormatter.format(SampleNotification.make(ctx), style, locked = false, LockScreen())?.let {
            matchClockColors()
            notifications.showBanner(it, durationMs = 0)
        }
    }

    /** Shown again: the Wallpaper tab may have changed what's under it. */
    private fun reloadPreview() {
        backdrop.reload()
        refresh()
    }

    /** The clock's colours, so it matches the wallpaper; light on dark without one. */
    private fun matchClockColors() {
        if (backdrop.clockShown) notifications.setColors(clock.textColor, clock.autoBgColor)
        else notifications.setColors(Color.WHITE, Color.BLACK)
    }

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}
