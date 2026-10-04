package com.backscreen.wallpaper.wallpaper

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.MainActivity
import com.backscreen.wallpaper.R
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.Schedule
import com.backscreen.wallpaper.core.ScheduleEditor
import com.backscreen.wallpaper.core.Schedules
import com.backscreen.wallpaper.rear.RearHostActivity
import com.backscreen.wallpaper.ui.MainSwitchBar
import com.backscreen.wallpaper.ui.Refreshable
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The Wallpaper tab: the switch, a preview shaped like the back screen, the images and gallery,
 * scaling and panning, the clock, keeping clear of the camera, and schedules.
 */
class WallpaperFragment : Fragment(R.layout.fragment_wallpaper), Refreshable {

    private lateinit var mainSwitch: MainSwitchBar
    private lateinit var previewFrame: RearPreviewLayout
    private lateinit var preview: WallpaperView
    private lateinit var emptyState: View
    private lateinit var emptyCaption: View
    private lateinit var cameraCard: View
    private lateinit var cameraSwitch: MaterialSwitch
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
    private lateinit var fitCard: View
    private lateinit var scalingPanNote: View
    private lateinit var panSwitch: MaterialSwitch
    private lateinit var panOptions: View
    private lateinit var panSpeeds: ChipGroup
    private lateinit var panImageNote: TextView
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

    // Reading a big folder and decoding an image can take a second or more. They're done here
    // rather than on the main thread, which the back screen shares: a stall there froze the
    // app and stopped the back screen's pan.
    private val loader = Executors.newSingleThreadExecutor()
    private var previewLoads = 0
    private var previewLoading = false

    // Set while controls are updated to match the settings, so their listeners ignore it.
    private var syncingClock = false

    private val ctx: Context get() = requireContext()

    // System photo picker: only the chosen files are shared, no storage permission needed.
    private val pickImages = registerForActivityResult(PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onImagesPicked(uris)
    }

    // System folder picker: access to just that folder, which you can take back in Settings.
    private val pickFolder = registerForActivityResult(OpenDocumentTree()) { uri -> uri?.let(::onFolderPicked) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainSwitch = view.findViewById(R.id.mainSwitch)
        previewFrame = view.findViewById(R.id.previewFrame)
        preview = view.findViewById(R.id.preview)
        emptyState = view.findViewById(R.id.emptyState)
        emptyCaption = view.findViewById(R.id.emptyCaption)
        cameraCard = view.findViewById(R.id.cameraCard)
        cameraSwitch = view.findViewById(R.id.cameraSwitch)
        scheduleSummary = view.findViewById(R.id.scheduleSummary)
        scheduleList = view.findViewById(R.id.scheduleList)
        setupCard = view.findViewById(R.id.setupCard)
        alarmWarning = view.findViewById(R.id.alarmWarning)
        batteryWarning = view.findViewById(R.id.batteryWarning)
        clock = view.findViewById(R.id.clock)
        galleryCard = view.findViewById(R.id.galleryCard)
        gallerySummary = view.findViewById(R.id.gallerySummary)
        galleryRotation = view.findViewById(R.id.galleryRotation)
        intervalValue = view.findViewById(R.id.intervalValue)
        shuffleSwitch = view.findViewById(R.id.shuffleSwitch)
        scalingChips = view.findViewById(R.id.scalingChips)
        fitCard = view.findViewById(R.id.fitCard)
        scalingPanNote = view.findViewById(R.id.scalingPanNote)
        panSwitch = view.findViewById(R.id.panSwitch)
        panOptions = view.findViewById(R.id.panOptions)
        panSpeeds = view.findViewById(R.id.panSpeeds)
        panImageNote = view.findViewById(R.id.panImageNote)
        clockSwitch = view.findViewById(R.id.clockSwitch)
        clockOptions = view.findViewById(R.id.clockOptions)
        clockStyles = view.findViewById(R.id.clockStyles)
        alignX = view.findViewById(R.id.alignX)
        alignY = view.findViewById(R.id.alignY)
        clockColorValue = view.findViewById(R.id.clockColorValue)
        clockColorSwatch = view.findViewById(R.id.clockColorSwatch)
        clockBgValue = view.findViewById(R.id.clockBgValue)
        clockBgSwatch = view.findViewById(R.id.clockBgSwatch)
        clockOpacity = view.findViewById(R.id.clockOpacity)
        clockOpacityValue = view.findViewById(R.id.clockOpacityValue)
        // What the preview will show, until it's loaded: no flash of "No wallpaper".
        previewUri = Gallery.shown(ctx)

        mainSwitch.onCheckedChange = { checked ->
            if (checked) turnOn() else turnOff()
            refresh()
        }

        val choose = View.OnClickListener {
            pickImages.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
        view.findViewById<View>(R.id.chooseButton).setOnClickListener(choose)
        view.findViewById<View>(R.id.emptyChooseButton).setOnClickListener(choose)
        view.findViewById<View>(R.id.previewCard).setOnClickListener(choose)
        view.findViewById<View>(R.id.removeButton).setOnClickListener { confirmRemoveImages() }
        view.findViewById<View>(R.id.folderButton).setOnClickListener { pickFolder.launch(Gallery.folder(ctx)) }

        view.findViewById<View>(R.id.intervalRow).setOnClickListener { chooseInterval() }
        view.findViewById<View>(R.id.shuffleRow).setOnClickListener { shuffleSwitch.toggle() }
        shuffleSwitch.isChecked = Gallery.shuffle(ctx)
        shuffleSwitch.setOnCheckedChangeListener { _, checked -> Gallery.setShuffle(ctx, checked) }
        view.findViewById<View>(R.id.nextButton).setOnClickListener {
            val app = ctx.applicationContext
            loader.execute {
                Gallery.skip(app)
                BackScreen.mainHandler.post {
                    RearHostActivity.reload()
                    if (view != null) loadPreview()
                }
            }
        }

        addChips(scalingChips, Scaling.entries, Gallery.scaling(ctx), Scaling::label) { scaling ->
            Gallery.setScaling(ctx, scaling)
            loadPreview()
            RearHostActivity.reload()
        }
        setUpPanning(view)

        setUpClock(view)
        cameraCard.setOnClickListener { cameraSwitch.toggle() }
        cameraSwitch.isChecked = WallpaperSettings.avoidCamera(ctx)
        cameraSwitch.setOnCheckedChangeListener { _, checked ->
            WallpaperSettings.setAvoidCamera(ctx, checked)
            showRearShape()
            // Beside the camera the screen is a different shape, so images may pan differently.
            showPanning()
            if (WallpaperSettings.isEnabled(ctx)) KeeperService.start(ctx, KeeperService.ACTION_APPLY)
        }

        view.findViewById<View>(R.id.addScheduleButton).setOnClickListener { editSchedule(null) }
        view.findViewById<View>(R.id.alarmButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }
        view.findViewById<View>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
        schedules = Schedules.load(ctx)
        showSchedules()

        showRearShape()
        loadPreview()
    }

    override fun onResume() {
        super.onResume()
        preview.moving = !isHidden
        // A chosen folder may have changed while we were away.
        showGallery()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        preview.moving = false
    }

    /** Another tab is showing, or this one again: the preview only pans and plays while it can be seen. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        preview.moving = !hidden && isResumed
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun refresh() {
        if (view == null) return
        val enabled = WallpaperSettings.isEnabled(ctx)
        mainSwitch.isChecked = enabled
        mainSwitch.setSummary(
            when {
                !enabled -> R.string.state_off_xiaomi
                KeeperService.instance?.isWaitingForShizuku == true -> R.string.state_waiting_shizuku
                previewUri == null -> R.string.state_on_black
                else -> R.string.state_on
            }
        )
        // Keep up with the gallery moving on while the app is open.
        if (!previewLoading && previewUri != null && Gallery.shown(ctx) != previewUri) loadPreview()
        refreshSchedule(enabled)
        refreshSetup()
    }

    /** Everything again from the settings, for Refresh back screen. */
    fun reloadAll() {
        if (view == null) return
        showGallery()
        loadPreview()
        showClock()
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
                ScheduleEditor.formatWhen(ctx, next.first)
            )
        }
    }

    /**
     * What has to be allowed for schedules and the clock to run on time: exact alarms, and
     * HyperOS not freezing the app in the background, which holds its alarms back.
     */
    private fun refreshSetup() {
        val needed = schedules.any { it.enabled } || WallpaperSettings.showClock(ctx)
        val exact = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
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
                ScheduleEditor.formatTime(ctx, schedule.start),
                ScheduleEditor.formatTime(ctx, schedule.end)
            )
            row.findViewById<TextView>(R.id.scheduleDays).text = ScheduleEditor.formatDays(ctx, schedule.days)
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
        ScheduleEditor.show(requireActivity() as AppCompatActivity, schedule) { edited ->
            val others = schedules.filter { it.id != schedule?.id }
            saveSchedules(if (edited == null) others else others + edited)
            showSchedules()
        }
    }

    private fun saveSchedules(list: List<Schedule>) {
        schedules = list
        Schedules.save(ctx, list)
        refresh()
    }

    /** With no images the back screen is black, with the clock if that's on. */
    private fun turnOn() {
        if (!RearCommands.isReady()) {
            snackbar(R.string.need_shizuku)
            return // the next refresh flips the switch back off
        }
        KeeperService.setWallpaper(ctx, true)
    }

    private fun turnOff() {
        KeeperService.setWallpaper(ctx, false)
    }

    private fun onImagesPicked(uris: List<Uri>) {
        val app = ctx.applicationContext
        val copying = snackbar(R.string.copying_images, Snackbar.LENGTH_INDEFINITE)
        thread {
            val error = try {
                Gallery.setImages(app, uris)
                BackScreen.log("Chose ${uris.size} image(s)")
                null
            } catch (e: Exception) {
                BackScreen.log("Couldn't copy images: ${e.message}")
                e
            }
            BackScreen.mainHandler.post {
                copying.dismiss()
                if (view == null) return@post
                if (error != null) snackbar(R.string.copy_failed)
                onImagesChanged()
            }
        }
    }

    private fun onFolderPicked(folder: Uri) {
        try {
            Gallery.setFolder(ctx, folder)
        } catch (e: Exception) {
            BackScreen.log("Couldn't use folder: ${e.message}")
        }
        val app = ctx.applicationContext
        loader.execute {
            BackScreen.log("Chose folder ${Gallery.folderName(app)}")
            val empty = !Gallery.hasImages(app)
            BackScreen.mainHandler.post { if (view != null && empty) snackbar(R.string.folder_empty, Snackbar.LENGTH_LONG) }
        }
        onImagesChanged()
    }

    private fun onImagesChanged() {
        loadPreview()
        showGallery()
        if (WallpaperSettings.isEnabled(ctx) && !RearHostActivity.reload()) {
            KeeperService.start(ctx, KeeperService.ACTION_APPLY)
        }
    }

    private fun confirmRemoveImages() {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.remove_images_title)
            .setMessage(R.string.remove_images_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_images) { _, _ ->
                Gallery.clear(ctx)
                BackScreen.log("Removed images")
                snackbar(R.string.images_removed)
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
        val app = ctx.applicationContext
        loader.execute {
            val count = Gallery.images(app).size
            val folder = Gallery.folderName(app)
            BackScreen.mainHandler.post { if (view != null) showGallery(count, folder) }
        }
    }

    private fun showGallery(count: Int, folder: String?) {
        val hasFolder = Gallery.folder(ctx) != null
        galleryCard.visibility = if (count > 0 || hasFolder) View.VISIBLE else View.GONE
        fitCard.visibility = if (count > 0) View.VISIBLE else View.GONE
        galleryRotation.visibility = if (count > 1 || hasFolder) View.VISIBLE else View.GONE
        val images = resources.getQuantityString(R.plurals.gallery_count, count, count)
        gallerySummary.text = if (folder == null) images else getString(R.string.gallery_from_folder, images, folder)
        intervalValue.text = formatInterval(Gallery.interval(ctx))
    }

    private fun chooseInterval() {
        val options = Gallery.INTERVALS
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.change_every)
            .setSingleChoiceItems(
                options.map(::formatInterval).toTypedArray(), options.indexOf(Gallery.interval(ctx))
            ) { dialog, which ->
                Gallery.setInterval(ctx, options[which])
                intervalValue.text = formatInterval(options[which])
                RearHostActivity.settingsChanged()
                dialog.dismiss()
            }
            .show()
    }

    private fun formatInterval(minutes: Int): String = when {
        minutes >= 1440 -> getString(R.string.every_day_interval)
        minutes >= 60 -> resources.getQuantityString(R.plurals.every_hours, minutes / 60, minutes / 60)
        else -> resources.getQuantityString(R.plurals.every_minutes, minutes, minutes)
    }

    private fun setUpClock(view: View) {
        view.findViewById<View>(R.id.clockRow).setOnClickListener { clockSwitch.toggle() }
        clockSwitch.isChecked = WallpaperSettings.showClock(ctx)
        clockSwitch.setOnCheckedChangeListener { _, checked ->
            WallpaperSettings.setShowClock(ctx, checked)
            clockChanged()
        }
        addChips(clockStyles, ClockStyle.entries, WallpaperSettings.clockStyle(ctx), ClockStyle::label) { style ->
            WallpaperSettings.setClockStyle(ctx, style)
            clockChanged()
        }

        // Position: buttons for the edges and middle, or drag it in the preview.
        val xs = mapOf(R.id.alignLeft to 0f, R.id.alignCentre to 0.5f, R.id.alignRight to 1f)
        val ys = mapOf(R.id.alignTop to 0f, R.id.alignMiddle to 0.5f, R.id.alignBottom to 1f)
        alignX.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingClock) return@addOnButtonCheckedListener
            WallpaperSettings.setClockPosition(ctx, xs.getValue(id), clock.settings.y)
            clockChanged()
        }
        alignY.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingClock) return@addOnButtonCheckedListener
            WallpaperSettings.setClockPosition(ctx, clock.settings.x, ys.getValue(id))
            clockChanged()
        }
        clock.onMoved = { x, y ->
            WallpaperSettings.setClockPosition(ctx, x, y)
            clockChanged()
        }

        // Colours: Auto reads the image under the clock in the preview, as the back screen does.
        clock.backdrop = preview
        preview.onPanned = clock::backdropMoved
        clock.onColorsChanged = { showClockColors() }
        view.findViewById<View>(R.id.clockColorRow).setOnClickListener {
            ColorPicker.show(
                ctx, R.string.clock_colour, R.string.colour_auto_text, clock.settings.color, clock.autoTextColor
            ) { color ->
                WallpaperSettings.setClockColor(ctx, color)
                clockChanged()
            }
        }
        view.findViewById<View>(R.id.clockBgRow).setOnClickListener {
            ColorPicker.show(
                ctx, R.string.clock_background, R.string.colour_auto_bg, clock.settings.bgColor, clock.autoBgColor
            ) { color ->
                WallpaperSettings.setClockBgColor(ctx, color)
                // A background colour can't be seen with none of it showing.
                if (clock.settings.bgOpacity == 0) WallpaperSettings.setClockBgOpacity(ctx, DEFAULT_BG_OPACITY)
                clockChanged()
            }
        }
        clockOpacity.setLabelFormatter { getString(R.string.percent, it.toInt()) }
        clockOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            WallpaperSettings.setClockBgOpacity(ctx, value.toInt())
            clockChanged()
        }
        showClock()
    }

    /** A clock setting changed: show it here and on the back screen. */
    private fun clockChanged() {
        showClock()
        RearHostActivity.settingsChanged()
    }

    /** Matches the preview's clock and the clock controls to the settings. */
    private fun showClock() {
        val show = WallpaperSettings.showClock(ctx)
        val settings = WallpaperSettings.clockSettings(ctx)
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
        val rear = BackScreen.findRearDisplay(ctx) ?: return
        previewFrame.setRearDisplay(rear, WallpaperSettings.avoidCamera(ctx))
        cameraCard.visibility = if (previewFrame.hasCamera) View.VISIBLE else View.GONE
    }

    /** Loads the gallery's current image into the preview, decoded just as the back screen does. */
    private fun loadPreview() {
        val display = BackScreen.findRearDisplay(ctx) ?: requireActivity().display ?: return
        val app = ctx.applicationContext
        val load = ++previewLoads
        previewLoading = true
        loader.execute {
            val uri = Gallery.current(app)
            val scaling = WallpaperSettings.scalingInUse(app)
            val drawable = try {
                uri?.let { BackScreen.loadImage(app, it, display, scaling) }
            } catch (e: Exception) {
                BackScreen.log("Preview failed: ${e.message}")
                null
            }
            BackScreen.mainHandler.post {
                if (view == null || load != previewLoads) return@post
                previewLoading = false
                showPreview(uri, drawable, scaling)
            }
        }
    }

    private fun showPreview(uri: Uri?, drawable: Drawable?, scaling: Scaling) {
        previewUri = uri
        // Black like the rear display, wherever the image doesn't reach, or with no image.
        preview.setBackgroundColor(Color.BLACK)
        preview.scaling = scaling
        preview.pan = WallpaperSettings.pan(ctx)
        preview.setImageDrawable(drawable)
        // The clock's automatic colours follow the image.
        clock.backdropChanged()
        showEmptyState()
        showPanning()
        // "On" or "On · black, no images".
        refresh()
    }

    private fun setUpPanning(view: View) {
        view.findViewById<View>(R.id.panRow).setOnClickListener { panSwitch.toggle() }
        panSwitch.isChecked = WallpaperSettings.pan(ctx) != null
        panSwitch.setOnCheckedChangeListener { _, checked ->
            WallpaperSettings.setPan(ctx, checked)
            // The options at once; the image may need decoding again, since panning always fills the screen.
            showPanning()
            loadPreview()
            RearHostActivity.reload()
        }
        addChips(panSpeeds, PanSpeed.entries, WallpaperSettings.panSpeed(ctx), PanSpeed::label) { speed ->
            WallpaperSettings.setPanSpeed(ctx, speed)
            // The preview and the back screen carry on from where they are, at the new speed.
            preview.pan = speed
            showPanning()
            RearHostActivity.settingsChanged()
        }
    }

    /** The panning options, the scaling it overrides, and what it does with the image showing. */
    private fun showPanning() {
        val pan = WallpaperSettings.pan(ctx)
        panOptions.visibility = if (pan != null) View.VISIBLE else View.GONE
        scalingPanNote.visibility = if (pan != null) View.VISIBLE else View.GONE
        for (i in 0 until scalingChips.childCount) scalingChips.getChildAt(i).isEnabled = pan == null
        val note = pan?.let(::describePan)
        panImageNote.text = note
        panImageNote.visibility = if (note == null) View.GONE else View.VISIBLE
    }

    /**
     * Which way the image in the preview pans on the back screen, and how long each sweep takes,
     * worked out just as the back screen does: the preview's image is decoded the same way.
     */
    private fun describePan(speed: PanSpeed): String? {
        val image = preview.drawable ?: return null
        val rear = BackScreen.findRearDisplay(ctx) ?: return null
        val screen = BackScreen.naturalSize(rear)
        val inset = if (WallpaperSettings.avoidCamera(ctx)) BackScreen.naturalCameraInsets(rear) else Insets.NONE
        val plan = PanPlanner.plan(
            image.intrinsicWidth, image.intrinsicHeight,
            screen.width - inset.left - inset.right, screen.height - inset.top - inset.bottom,
            screen.height, speed
        )
        val sweep = formatDuration(plan.sweepMs)
        return when (plan.axis) {
            PanAxis.NONE -> getString(R.string.pan_fits)
            PanAxis.HORIZONTAL -> getString(R.string.pan_horizontal, sweep)
            PanAxis.VERTICAL -> getString(R.string.pan_vertical, sweep)
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ((ms + 500) / 1000).toInt()
        return if (seconds < 60) getString(R.string.duration_seconds, seconds)
        else getString(R.string.duration_minutes, seconds / 60, seconds % 60)
    }

    /**
     * With no images the preview is black like the back screen, and says so. With the clock
     * on, the preview shows just the clock and the note goes underneath, so they don't overlap.
     */
    private fun showEmptyState() {
        val empty = previewUri == null
        val clockOn = WallpaperSettings.showClock(ctx)
        emptyState.visibility = if (empty && !clockOn) View.VISIBLE else View.GONE
        emptyCaption.visibility = if (empty && clockOn) View.VISIBLE else View.GONE
    }

    private fun snackbar(text: Int, length: Int = Snackbar.LENGTH_SHORT) =
        (requireActivity() as MainActivity).snackbar(text, length)

    private companion object {
        const val DEFAULT_BG_OPACITY = 50
    }
}
