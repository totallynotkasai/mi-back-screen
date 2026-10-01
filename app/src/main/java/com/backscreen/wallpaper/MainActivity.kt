package com.backscreen.wallpaper

import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var previewFrame: RearPreviewLayout
    private lateinit var preview: WallpaperView
    private lateinit var emptyState: View
    private lateinit var emptyCaption: View
    private lateinit var cameraCard: View
    private lateinit var cameraSwitch: MaterialSwitch
    private lateinit var toggleSwitch: MaterialSwitch
    private lateinit var toggleState: TextView
    private lateinit var shizukuDot: View
    private lateinit var shizukuStatus: TextView
    private lateinit var allowButton: Button
    private lateinit var detailsButton: Button
    private lateinit var log: TextView
    private lateinit var scheduleSummary: TextView
    private lateinit var scheduleList: LinearLayout
    private lateinit var setupCard: View
    private lateinit var alarmWarning: View
    private lateinit var batteryWarning: View

    private lateinit var clock: ClockLayer
    private lateinit var galleryCard: View
    private lateinit var gallerySummary: TextView
    private lateinit var galleryRotation: View
    private lateinit var intervalValue: TextView
    private lateinit var shuffleSwitch: MaterialSwitch
    private lateinit var scalingChips: ChipGroup
    private lateinit var clockSwitch: MaterialSwitch
    private lateinit var clockOptions: View
    private lateinit var clockStyles: ChipGroup
    private lateinit var alignX: MaterialButtonToggleGroup
    private lateinit var alignY: MaterialButtonToggleGroup
    private lateinit var clockColorValue: TextView
    private lateinit var clockColorSwatch: View
    private lateinit var clockBgValue: TextView
    private lateinit var clockBgSwatch: View
    private lateinit var clockOpacity: Slider
    private lateinit var clockOpacityValue: TextView

    private var schedules = emptyList<Schedule>()
    private var previewUri: Uri? = null

    // Set while controls are updated to match the settings, so their listeners ignore it.
    private var syncingClock = false

    // System photo picker: only the chosen files are shared, no storage permission needed.
    private val pickImages = registerForActivityResult(PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onImagesPicked(uris)
    }

    // System folder picker: access to just that folder, which you can take back in Settings.
    private val pickFolder = registerForActivityResult(OpenDocumentTree()) { uri -> uri?.let(::onFolderPicked) }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            BackScreen.mainHandler.postDelayed(this, 1000)
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        BackScreen.log(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku access granted" else "Shizuku access denied")
        refresh()
    }

    private val onToggle = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) turnOn() else turnOff()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewFrame = findViewById(R.id.previewFrame)
        preview = findViewById(R.id.preview)
        emptyState = findViewById(R.id.emptyState)
        emptyCaption = findViewById(R.id.emptyCaption)
        cameraCard = findViewById(R.id.cameraCard)
        cameraSwitch = findViewById(R.id.cameraSwitch)
        toggleSwitch = findViewById(R.id.toggleSwitch)
        toggleState = findViewById(R.id.toggleState)
        shizukuDot = findViewById(R.id.shizukuDot)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        allowButton = findViewById(R.id.allowButton)
        detailsButton = findViewById(R.id.detailsButton)
        log = findViewById(R.id.log)
        scheduleSummary = findViewById(R.id.scheduleSummary)
        scheduleList = findViewById(R.id.scheduleList)
        setupCard = findViewById(R.id.setupCard)
        alarmWarning = findViewById(R.id.alarmWarning)
        batteryWarning = findViewById(R.id.batteryWarning)
        clock = findViewById(R.id.clock)
        galleryCard = findViewById(R.id.galleryCard)
        gallerySummary = findViewById(R.id.gallerySummary)
        galleryRotation = findViewById(R.id.galleryRotation)
        intervalValue = findViewById(R.id.intervalValue)
        shuffleSwitch = findViewById(R.id.shuffleSwitch)
        scalingChips = findViewById(R.id.scalingChips)
        clockSwitch = findViewById(R.id.clockSwitch)
        clockOptions = findViewById(R.id.clockOptions)
        clockStyles = findViewById(R.id.clockStyles)
        alignX = findViewById(R.id.alignX)
        alignY = findViewById(R.id.alignY)
        clockColorValue = findViewById(R.id.clockColorValue)
        clockColorSwatch = findViewById(R.id.clockColorSwatch)
        clockBgValue = findViewById(R.id.clockBgValue)
        clockBgSwatch = findViewById(R.id.clockBgSwatch)
        clockOpacity = findViewById(R.id.clockOpacity)
        clockOpacityValue = findViewById(R.id.clockOpacityValue)

        val choose = View.OnClickListener {
            pickImages.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.chooseButton).setOnClickListener(choose)
        findViewById<View>(R.id.emptyChooseButton).setOnClickListener(choose)
        findViewById<View>(R.id.previewCard).setOnClickListener(choose)
        findViewById<View>(R.id.removeButton).setOnClickListener { confirmRemoveImages() }
        findViewById<TextView>(R.id.about).text = getString(
            R.string.about, packageManager.getPackageInfo(packageName, 0).versionName
        )
        findViewById<View>(R.id.folderButton).setOnClickListener { pickFolder.launch(Gallery.folder(this)) }

        findViewById<View>(R.id.intervalRow).setOnClickListener { chooseInterval() }
        findViewById<View>(R.id.shuffleRow).setOnClickListener { shuffleSwitch.toggle() }
        shuffleSwitch.isChecked = Gallery.shuffle(this)
        shuffleSwitch.setOnCheckedChangeListener { _, checked -> Gallery.setShuffle(this, checked) }
        findViewById<View>(R.id.nextButton).setOnClickListener {
            Gallery.skip(this)
            loadPreview()
            RearWallpaperActivity.reload()
        }

        addChips(scalingChips, Scaling.entries, Gallery.scaling(this), Scaling::label) { scaling ->
            Gallery.setScaling(this, scaling)
            loadPreview()
            RearWallpaperActivity.reload()
        }

        setUpClock()
        findViewById<View>(R.id.toggleCard).setOnClickListener { toggleSwitch.toggle() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { refreshBackScreen() }
        cameraCard.setOnClickListener { cameraSwitch.toggle() }
        cameraSwitch.isChecked = BackScreen.avoidCamera(this)
        cameraSwitch.setOnCheckedChangeListener { _, checked ->
            BackScreen.setAvoidCamera(this, checked)
            showRearShape()
            if (BackScreen.isEnabled(this)) KeeperService.start(this, KeeperService.ACTION_APPLY)
        }
        toggleSwitch.setOnCheckedChangeListener(onToggle)
        allowButton.setOnClickListener { if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_SHIZUKU) }
        detailsButton.setOnClickListener {
            val show = log.visibility != View.VISIBLE
            log.visibility = if (show) View.VISIBLE else View.GONE
            detailsButton.setText(if (show) R.string.hide_details else R.string.show_details)
        }

        findViewById<View>(R.id.addScheduleButton).setOnClickListener { editSchedule(null) }
        findViewById<View>(R.id.alarmButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        findViewById<View>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        schedules = Schedules.load(this)
        showSchedules()

        shizukuDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        showRearShape()
        loadPreview()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The Quick Settings tile opens us to toggle when it isn't allowed to itself. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != KeeperService.ACTION_TOGGLE) return
        if (BackScreen.isEnabled(this)) turnOff() else turnOn()
    }

    override fun onResume() {
        super.onResume()
        // A chosen folder may have changed while we were away.
        showGallery()
        BackScreen.mainHandler.post(refresher)
    }

    override fun onPause() {
        BackScreen.mainHandler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun refresh() {
        val running = Shizuku.pingBinder()
        val ready = RearCommands.isReady()
        val enabled = BackScreen.isEnabled(this)

        // Update the switch without re-triggering its listener.
        toggleSwitch.setOnCheckedChangeListener(null)
        toggleSwitch.isChecked = enabled
        toggleSwitch.setOnCheckedChangeListener(onToggle)
        toggleState.setText(
            when {
                !enabled -> R.string.state_off_xiaomi
                previewUri == null -> R.string.state_on_black
                else -> R.string.state_on
            }
        )

        shizukuStatus.setText(
            when {
                ready -> R.string.shizuku_ready
                running -> R.string.shizuku_not_allowed
                else -> R.string.shizuku_not_running
            }
        )
        (shizukuDot.background as GradientDrawable).setColor(
            MaterialColors.getColor(
                shizukuDot,
                if (ready) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorError
            )
        )
        allowButton.visibility = if (running && !ready) View.VISIBLE else View.GONE
        if (log.visibility == View.VISIBLE) {
            log.text = listOfNotNull(ClockAlarm.summary(), BackScreen.logText()).joinToString("\n")
        }
        // Keep up with the gallery moving on while the app is open.
        if (previewUri != null && Gallery.shown(this) != previewUri) loadPreview()
        refreshSchedule(enabled)
        refreshSetup()
    }

    /** The next scheduled change. */
    private fun refreshSchedule(enabled: Boolean) {
        val active = schedules.any { it.enabled }
        val next = Schedules.nextChange(schedules, System.currentTimeMillis(), enabled)
        scheduleSummary.text = when {
            !active -> getString(R.string.schedule_none)
            next == null -> getString(R.string.schedule_no_change)
            else -> getString(
                if (next.second) R.string.schedule_turns_on else R.string.schedule_turns_off,
                ScheduleEditor.formatWhen(this, next.first)
            )
        }
    }

    /**
     * What has to be allowed for schedules and the clock to run on time: exact alarms, and
     * HyperOS not freezing the app in the background, which holds its alarms back.
     */
    private fun refreshSetup() {
        val needed = schedules.any { it.enabled } || BackScreen.showClock(this)
        val exact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        alarmWarning.visibility = if (needed && !exact) View.VISIBLE else View.GONE
        batteryWarning.visibility = if (needed && !unrestricted) View.VISIBLE else View.GONE
        setupCard.visibility = if (needed && !(exact && unrestricted)) View.VISIBLE else View.GONE
    }

    private fun showSchedules() {
        scheduleList.removeAllViews()
        for (schedule in schedules.sortedBy { it.start }) {
            val row = layoutInflater.inflate(R.layout.item_schedule, scheduleList, false)
            row.findViewById<TextView>(R.id.scheduleTimes).text = getString(
                R.string.schedule_times,
                ScheduleEditor.formatTime(this, schedule.start),
                ScheduleEditor.formatTime(this, schedule.end)
            )
            row.findViewById<TextView>(R.id.scheduleDays).text = ScheduleEditor.formatDays(this, schedule.days)
            val switch = row.findViewById<MaterialSwitch>(R.id.scheduleSwitch)
            switch.isChecked = schedule.enabled
            switch.setOnCheckedChangeListener { _, checked ->
                saveSchedules(schedules.map { if (it.id == schedule.id) it.copy(enabled = checked) else it })
            }
            row.setOnClickListener { editSchedule(schedule) }
            scheduleList.addView(row)
        }
        scheduleList.visibility = if (schedules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun editSchedule(schedule: Schedule?) {
        ScheduleEditor.show(this, schedule) { edited ->
            val others = schedules.filter { it.id != schedule?.id }
            saveSchedules(if (edited == null) others else others + edited)
            showSchedules()
        }
    }

    private fun saveSchedules(list: List<Schedule>) {
        schedules = list
        Schedules.save(this, list)
        refresh()
    }

    /**
     * Turns the wallpaper on, or puts it up again if it's on. False if it can't. With no images
     * the back screen is black, with the clock if that's on.
     */
    private fun turnOn(): Boolean {
        if (!RearCommands.isReady()) {
            Snackbar.make(toggleSwitch, R.string.need_shizuku, Snackbar.LENGTH_SHORT).show()
            BackScreen.mainHandler.post { refresh() } // flips the switch back off
            return false
        }
        // Saved here as well as by the service, so the switch doesn't flick back while it starts.
        BackScreen.setEnabled(this, true)
        KeeperService.start(this, KeeperService.ACTION_APPLY)
        return true
    }

    private fun turnOff() {
        BackScreen.setEnabled(this, false)
        KeeperService.start(this, KeeperService.ACTION_RESTORE)
    }

    /** Reloads everything and puts the wallpaper up afresh, for when something didn't update. */
    private fun refreshBackScreen() {
        showGallery()
        loadPreview()
        showClock()
        if (!BackScreen.isEnabled(this)) {
            Snackbar.make(toggleSwitch, R.string.refresh_off, Snackbar.LENGTH_SHORT).show()
            return
        }
        BackScreen.log("Refreshing")
        if (turnOn()) Snackbar.make(toggleSwitch, R.string.refreshing, Snackbar.LENGTH_SHORT).show()
    }

    private fun onImagesPicked(uris: List<Uri>) {
        val snackbar = Snackbar.make(toggleSwitch, R.string.copying_images, Snackbar.LENGTH_INDEFINITE)
        snackbar.show()
        thread {
            val error = try {
                Gallery.setImages(this, uris)
                BackScreen.log("Chose ${uris.size} image(s)")
                null
            } catch (e: Exception) {
                BackScreen.log("Couldn't copy images: ${e.message}")
                e
            }
            runOnUiThread {
                snackbar.dismiss()
                if (error != null) Snackbar.make(toggleSwitch, R.string.copy_failed, Snackbar.LENGTH_SHORT).show()
                onImagesChanged()
            }
        }
    }

    private fun onFolderPicked(folder: Uri) {
        try {
            Gallery.setFolder(this, folder)
            BackScreen.log("Chose folder ${Gallery.folderName(this)}")
        } catch (e: Exception) {
            BackScreen.log("Couldn't use folder: ${e.message}")
        }
        if (!Gallery.hasImages(this)) Snackbar.make(toggleSwitch, R.string.folder_empty, Snackbar.LENGTH_LONG).show()
        onImagesChanged()
    }

    private fun onImagesChanged() {
        loadPreview()
        showGallery()
        if (BackScreen.isEnabled(this) && !RearWallpaperActivity.reload()) {
            KeeperService.start(this, KeeperService.ACTION_APPLY)
        }
    }

    private fun confirmRemoveImages() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_images_title)
            .setMessage(R.string.remove_images_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_images) { _, _ ->
                Gallery.clear(this)
                BackScreen.log("Removed images")
                Snackbar.make(toggleSwitch, R.string.images_removed, Snackbar.LENGTH_SHORT).show()
                // The back screen fades to black.
                onImagesChanged()
            }
            .show()
    }

    /**
     * The gallery card: there once there are images; its timing only with more than one to go
     * through, or a folder.
     */
    private fun showGallery() {
        val count = Gallery.images(this).size
        val folder = Gallery.folderName(this)
        val hasFolder = Gallery.folder(this) != null
        galleryCard.visibility = if (count > 0 || hasFolder) View.VISIBLE else View.GONE
        galleryRotation.visibility = if (count > 1 || hasFolder) View.VISIBLE else View.GONE
        val images = resources.getQuantityString(R.plurals.gallery_count, count, count)
        gallerySummary.text = if (folder == null) images else getString(R.string.gallery_from_folder, images, folder)
        intervalValue.text = formatInterval(Gallery.interval(this))
    }

    private fun chooseInterval() {
        val options = Gallery.INTERVALS
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_every)
            .setSingleChoiceItems(
                options.map(::formatInterval).toTypedArray(), options.indexOf(Gallery.interval(this))
            ) { dialog, which ->
                Gallery.setInterval(this, options[which])
                intervalValue.text = formatInterval(options[which])
                RearWallpaperActivity.settingsChanged()
                dialog.dismiss()
            }
            .show()
    }

    private fun formatInterval(minutes: Int): String = when {
        minutes >= 1440 -> getString(R.string.every_day_interval)
        minutes >= 60 -> resources.getQuantityString(R.plurals.every_hours, minutes / 60, minutes / 60)
        else -> resources.getQuantityString(R.plurals.every_minutes, minutes, minutes)
    }

    private fun setUpClock() {
        findViewById<View>(R.id.clockRow).setOnClickListener { clockSwitch.toggle() }
        clockSwitch.isChecked = BackScreen.showClock(this)
        clockSwitch.setOnCheckedChangeListener { _, checked ->
            BackScreen.setShowClock(this, checked)
            clockChanged()
        }
        addChips(clockStyles, ClockStyle.entries, BackScreen.clockStyle(this), ClockStyle::label) { style ->
            BackScreen.setClockStyle(this, style)
            clockChanged()
        }

        // Position: buttons for the edges and middle, or drag it in the preview.
        val xs = mapOf(R.id.alignLeft to 0f, R.id.alignCentre to 0.5f, R.id.alignRight to 1f)
        val ys = mapOf(R.id.alignTop to 0f, R.id.alignMiddle to 0.5f, R.id.alignBottom to 1f)
        alignX.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingClock) return@addOnButtonCheckedListener
            BackScreen.setClockPosition(this, xs.getValue(id), clock.settings.y)
            clockChanged()
        }
        alignY.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingClock) return@addOnButtonCheckedListener
            BackScreen.setClockPosition(this, clock.settings.x, ys.getValue(id))
            clockChanged()
        }
        clock.onMoved = { x, y ->
            BackScreen.setClockPosition(this, x, y)
            clockChanged()
        }

        // Colours: Auto reads the image under the clock in the preview, as the back screen does.
        clock.backdrop = preview
        clock.onColorsChanged = { showClockColors() }
        findViewById<View>(R.id.clockColorRow).setOnClickListener {
            ColorPicker.show(
                this, R.string.clock_colour, R.string.colour_auto_text, clock.settings.color, clock.autoTextColor
            ) { color ->
                BackScreen.setClockColor(this, color)
                clockChanged()
            }
        }
        findViewById<View>(R.id.clockBgRow).setOnClickListener {
            ColorPicker.show(
                this, R.string.clock_background, R.string.colour_auto_bg, clock.settings.bgColor, clock.autoBgColor
            ) { color ->
                BackScreen.setClockBgColor(this, color)
                // A background colour can't be seen with none of it showing.
                if (clock.settings.bgOpacity == 0) BackScreen.setClockBgOpacity(this, DEFAULT_BG_OPACITY)
                clockChanged()
            }
        }
        clockOpacity.setLabelFormatter { getString(R.string.percent, it.toInt()) }
        clockOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            BackScreen.setClockBgOpacity(this, value.toInt())
            clockChanged()
        }
        showClock()
    }

    /** A clock setting changed: show it here and on the back screen. */
    private fun clockChanged() {
        showClock()
        RearWallpaperActivity.settingsChanged()
    }

    /** Matches the preview's clock and the clock controls to the settings. */
    private fun showClock() {
        val show = BackScreen.showClock(this)
        val settings = BackScreen.clockSettings(this)
        clock.visibility = if (show) View.VISIBLE else View.GONE
        clockOptions.visibility = if (show) View.VISIBLE else View.GONE
        clock.settings = settings
        showEmptyState()

        syncingClock = true
        // Only a button that matches exactly; after a drag, maybe none.
        check(alignX, when (settings.x) { 0f -> R.id.alignLeft; 0.5f -> R.id.alignCentre; 1f -> R.id.alignRight; else -> null })
        check(alignY, when (settings.y) { 0f -> R.id.alignTop; 0.5f -> R.id.alignMiddle; 1f -> R.id.alignBottom; else -> null })
        clockOpacity.value = settings.bgOpacity.toFloat().coerceIn(clockOpacity.valueFrom, clockOpacity.valueTo)
        syncingClock = false
        showClockColors()
    }

    private fun check(group: MaterialButtonToggleGroup, id: Int?) {
        if (id == null) group.clearChecked() else group.check(id)
    }

    /** The colour rows, with what Auto picked for the image showing. */
    private fun showClockColors() {
        val settings = clock.settings
        clockColorValue.text = settings.color?.let(::formatColor) ?: getString(R.string.colour_auto)
        clockBgValue.text = settings.bgColor?.let(::formatColor) ?: getString(R.string.colour_auto)
        swatch(clockColorSwatch, clock.textColor)
        swatch(clockBgSwatch, clock.bgColor)
        clockOpacityValue.text = getString(R.string.percent, settings.bgOpacity)
    }

    private fun formatColor(color: Int) = "#%06X".format(Locale.US, color and 0xFFFFFF)

    private fun swatch(view: View, color: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                (resources.displayMetrics.density).toInt().coerceAtLeast(1),
                MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutline)
            )
        }
    }

    /** Fills [group] with a choice chip per option, [current] checked; [onChosen] on a change. */
    private fun <T> addChips(group: ChipGroup, options: List<T>, current: T, label: (T) -> Int, onChosen: (T) -> Unit) {
        for (option in options) {
            val chip = layoutInflater.inflate(R.layout.chip_style, group, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = option
            chip.setText(label(option))
            chip.isChecked = option == current
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            @Suppress("UNCHECKED_CAST")
            val option = ids.firstOrNull()?.let { g.findViewById<Chip>(it).tag as T } ?: return@setOnCheckedStateChangeListener
            onChosen(option)
        }
    }

    /** Matches the preview to the rear display's size and camera cutout. */
    private fun showRearShape() {
        val rear = BackScreen.findRearDisplay(this) ?: return
        previewFrame.setRearDisplay(rear, BackScreen.avoidCamera(this))
        cameraCard.visibility = if (previewFrame.hasCamera) View.VISIBLE else View.GONE
    }

    private fun loadPreview() {
        val display = BackScreen.findRearDisplay(this) ?: display ?: return
        val uri = Gallery.current(this)
        previewUri = uri
        BackScreen.stop(preview.drawable)
        // The clock's automatic colours follow the image.
        clock.backdropChanged()
        // Black like the rear display, wherever the image doesn't reach, or with no image.
        preview.setBackgroundColor(Color.BLACK)
        showEmptyState()
        if (uri == null) {
            preview.setImageDrawable(null)
            return
        }
        try {
            val scaling = Gallery.scaling(this)
            val drawable = BackScreen.loadImage(this, uri, display, scaling)
            preview.scaling = scaling
            preview.setImageDrawable(drawable)
            BackScreen.start(drawable)
        } catch (e: Exception) {
            BackScreen.log("Preview failed: ${e.message}")
        }
    }

    /**
     * With no images the preview is black like the back screen, and says so. With the clock
     * on, the preview shows just the clock and the note goes underneath, so they don't overlap.
     */
    private fun showEmptyState() {
        val empty = previewUri == null
        val clockOn = BackScreen.showClock(this)
        emptyState.visibility = if (empty && !clockOn) View.VISIBLE else View.GONE
        emptyCaption.visibility = if (empty && clockOn) View.VISIBLE else View.GONE
    }

    private companion object {
        const val REQUEST_SHIZUKU = 2
        const val DEFAULT_BG_OPACITY = 50
    }
}
