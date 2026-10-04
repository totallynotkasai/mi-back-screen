package com.backscreen.wallpaper.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.camera.CameraSettings
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.HostMode
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.LendReason
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.RearOwner
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.core.RearTweaks
import com.backscreen.wallpaper.mirror.AppLabels
import com.backscreen.wallpaper.mirror.MirrorSettings
import com.backscreen.wallpaper.notifications.NotificationAccess
import com.backscreen.wallpaper.notifications.NotificationSettings
import com.backscreen.wallpaper.notifications.RearNotificationListener
import com.backscreen.wallpaper.wallpaper.ClockAlarm
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku

/** The dialogs behind the top bar's menu: Diagnostics, Setup help and About. */
object AppDialogs {
    private const val REFRESH_MS = 1000L

    /** What's happening on the back screen, and the app's recent log, kept up to date while open. */
    fun diagnostics(activity: AppCompatActivity) {
        val dp = activity.resources.displayMetrics.density
        val text = TextView(activity).apply {
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
            typeface = Typeface.MONOSPACE
            setTextColor(MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0))
            setTextIsSelectable(true)
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val update = object : Runnable {
            override fun run() {
                text.text = diagnosticsText(activity)
                BackScreen.mainHandler.postDelayed(this, REFRESH_MS)
            }
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.diagnostics)
            .setView(ScrollView(activity).apply { addView(text) })
            .setNeutralButton(R.string.copy, null)
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener { BackScreen.mainHandler.removeCallbacks(update) }
            .show()
        // Copying leaves it open.
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.diagnostics), text.text))
            Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        update.run()
    }

    private fun diagnosticsText(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val rear = BackScreen.findRearDisplay(context)
        return listOfNotNull(
            context.getString(R.string.diag_version, info.versionName, info.longVersionCode.toInt()),
            context.getString(R.string.diag_shizuku, context.getString(shizukuStatus())),
            context.getString(R.string.diag_owner, ownerName(context)),
            context.getString(R.string.diag_rear_state, BackScreen.stateName(rear?.state)),
            context.getString(R.string.diag_notifications, notificationsStatus(context)),
            context.getString(R.string.diag_mirror, mirrorStatus(context)),
            context.getString(R.string.diag_camera, cameraStatus(context)),
            RearTweaks.summary(context)?.let { context.getString(R.string.diag_tweaks, it) },
            ClockAlarm.summary(),
            "",
            BackScreen.logText(),
        ).joinToString("\n")
    }

    private fun ownerName(context: Context) = when (val owner = RearState.owner(context)) {
        RearOwner.Xiaomi -> context.getString(R.string.owner_xiaomi)
        is RearOwner.Host -> context.getString(if (owner.mode == HostMode.SHADE) R.string.owner_shade else R.string.owner_wallpaper)
        RearOwner.Popover -> context.getString(R.string.owner_popover)
        is RearOwner.Lent -> context.getString(R.string.owner_lent, owner.lend.packageName)
    }

    /** Whether notifications are being read for the back screen, and how many it has. */
    private fun notificationsStatus(context: Context): String = when {
        !NotificationSettings.isEnabled(context) -> context.getString(R.string.diag_notifications_off)
        !NotificationAccess.isGranted(context) -> context.getString(R.string.diag_notifications_no_access)
        !RearNotificationListener.isListening -> context.getString(R.string.diag_notifications_waiting)
        else -> RearNotificationListener.recent().size.let {
            context.resources.getQuantityString(R.plurals.diag_notifications_reading, it, it)
        }
    }

    /** Whether Quick Switch is on, and which app it has on the back screen. */
    private fun mirrorStatus(context: Context): String {
        if (!MirrorSettings.isEnabled(context)) return context.getString(R.string.diag_mirror_off)
        val lend = (RearState.owner(context) as? RearOwner.Lent)?.lend?.takeIf { it.reason == LendReason.QUICK_SWITCH }
            ?: return context.getString(R.string.diag_mirror_on)
        return context.getString(R.string.diag_mirror_lent, AppLabels.get(context, lend.packageName))
    }

    /** Whether swipe left is on, whether Xiaomi Camera is up, and which way it last opened. */
    private fun cameraStatus(context: Context): String {
        val state = context.getString(if (CameraSettings.isEnabled(context)) R.string.diag_mirror_on else R.string.diag_mirror_off)
        val up = (RearState.owner(context) as? RearOwner.Lent)?.lend?.reason == LendReason.CAMERA
        val route = KeeperService.instance?.camera?.route
        return listOfNotNull(
            state,
            context.getString(R.string.diag_camera_up).takeIf { up },
            route?.let { context.getString(R.string.diag_camera_route, it) },
        ).joinToString(", ")
    }

    /** Shizuku's state, as a line for the app. */
    fun shizukuStatus() = when {
        RearCommands.isReady() -> R.string.shizuku_ready
        Shizuku.pingBinder() -> R.string.shizuku_not_allowed
        else -> R.string.shizuku_not_running
    }

    /** What the app needs allowed, each with whether it is and how to allow it. */
    fun setupHelp(activity: AppCompatActivity, requestShizuku: () -> Unit) {
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.setup_help)
            .setView(ScrollView(activity).apply { addView(list) })
            .setPositiveButton(R.string.close, null)
            .create()
        // Rebuilt only when something changes, so a tap on a button is never lost.
        var shown: List<Any>? = null
        val update = object : Runnable {
            override fun run() {
                shown = fillSetupSteps(activity, list, requestShizuku, shown)
                BackScreen.mainHandler.postDelayed(this, REFRESH_MS)
            }
        }
        dialog.setOnDismissListener { BackScreen.mainHandler.removeCallbacks(update) }
        dialog.show()
        update.run()
    }

    /** Fills [list] with the steps, unless they're as [shown]; returns what it shows. */
    private fun fillSetupSteps(
        activity: AppCompatActivity, list: LinearLayout, requestShizuku: () -> Unit, shown: List<Any>?,
    ): List<Any> {
        val shizukuReady = RearCommands.isReady()
        val alarms = activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val battery = activity.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(activity.packageName)
        val appSettings = {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            )
        }
        // Notification access, only while that section is on.
        val notifications = NotificationSettings.isEnabled(activity)
        val notificationAccess = NotificationAccess.isGranted(activity)
        // The app's own notifications, for Bring back or Close camera, only while Quick Switch or
        // the camera's swipe is on.
        val mirror = MirrorSettings.isEnabled(activity) || CameraSettings.isEnabled(activity)
        val posting = activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val state = listOf(shizukuStatus(), alarms, battery, notifications, notificationAccess, mirror, posting)
        if (state == shown) return state
        list.removeAllViews()
        addStep(
            list, R.string.shizuku_title,
            activity.getString(R.string.setup_shizuku_text) + "\n" + activity.getString(shizukuStatus()),
            shizukuReady,
            if (!shizukuReady && Shizuku.pingBinder()) R.string.allow_access else null, requestShizuku,
        )
        addStep(
            list, R.string.setup_alarms_title, activity.getString(R.string.setup_alarms_text), alarms,
            R.string.allow_alarms,
        ) {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}"))
            )
        }
        addStep(
            list, R.string.setup_battery_title, activity.getString(R.string.setup_battery_text), battery,
            R.string.open_app_settings, appSettings,
        )
        if (notifications) {
            addStep(
                list, R.string.access_title, activity.getString(R.string.setup_notifications_text), notificationAccess,
                if (shizukuReady) R.string.allow_access else R.string.open_settings,
            ) {
                if (shizukuReady) NotificationAccess.grant(activity) {} else NotificationAccess.openSettings(activity)
            }
        }
        if (mirror) {
            addStep(
                list, R.string.setup_mirror_notifications_title, activity.getString(R.string.setup_mirror_notifications_text),
                posting, R.string.allow,
            ) {
                activity.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                )
            }
        }
        return state
    }

    private fun addStep(
        list: LinearLayout, title: Int, text: String, done: Boolean, action: Int?, onAction: () -> Unit,
    ) {
        val row = LayoutInflater.from(list.context).inflate(R.layout.item_setup_step, list, false)
        row.findViewById<ImageView>(R.id.stepIcon).apply {
            setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_error)
            imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(this, if (done) MaterialR.attr.colorPrimary else MaterialR.attr.colorError)
            )
            contentDescription = list.context.getString(if (done) R.string.setup_done else R.string.setup_needed_short)
        }
        row.findViewById<TextView>(R.id.stepTitle).setText(title)
        row.findViewById<TextView>(R.id.stepText).text = text
        row.findViewById<Button>(R.id.stepButton).apply {
            if (done || action == null) {
                visibility = View.GONE
            } else {
                setText(action)
                setOnClickListener { onAction() }
            }
        }
        list.addView(row)
    }

    fun about(activity: AppCompatActivity) {
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.about, version))
            .setMessage(R.string.about_message)
            .setNeutralButton(R.string.website) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(activity.getString(R.string.website_url))))
                } catch (e: ActivityNotFoundException) {
                    BackScreen.log("No browser to open the website")
                }
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
