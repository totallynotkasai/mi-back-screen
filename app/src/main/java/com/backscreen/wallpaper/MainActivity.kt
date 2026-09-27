package com.backscreen.wallpaper

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var previewFrame: RearPreviewLayout
    private lateinit var preview: ImageView
    private lateinit var emptyState: View
    private lateinit var cameraCard: View
    private lateinit var cameraSwitch: MaterialSwitch
    private lateinit var toggleSwitch: MaterialSwitch
    private lateinit var toggleState: TextView
    private lateinit var shizukuDot: View
    private lateinit var shizukuStatus: TextView
    private lateinit var allowButton: Button
    private lateinit var detailsButton: Button
    private lateinit var log: TextView

    // System photo picker: only the chosen file is shared, no storage permission needed.
    private val pickImage = registerForActivityResult(PickVisualMedia()) { uri -> uri?.let(::onImagePicked) }

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
        if (checked) turnOn() else KeeperService.start(this, KeeperService.ACTION_RESTORE)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewFrame = findViewById(R.id.previewFrame)
        preview = findViewById(R.id.preview)
        emptyState = findViewById(R.id.emptyState)
        cameraCard = findViewById(R.id.cameraCard)
        cameraSwitch = findViewById(R.id.cameraSwitch)
        toggleSwitch = findViewById(R.id.toggleSwitch)
        toggleState = findViewById(R.id.toggleState)
        shizukuDot = findViewById(R.id.shizukuDot)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        allowButton = findViewById(R.id.allowButton)
        detailsButton = findViewById(R.id.detailsButton)
        log = findViewById(R.id.log)

        val choose = View.OnClickListener {
            pickImage.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.chooseButton).setOnClickListener(choose)
        findViewById<View>(R.id.previewCard).setOnClickListener(choose)
        findViewById<View>(R.id.toggleCard).setOnClickListener { toggleSwitch.toggle() }
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
        if (BackScreen.isEnabled(this)) KeeperService.start(this, KeeperService.ACTION_RESTORE) else turnOn()
    }

    override fun onResume() {
        super.onResume()
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
        toggleState.setText(if (enabled) R.string.state_on else R.string.state_off_xiaomi)

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
        if (log.visibility == View.VISIBLE) log.text = BackScreen.logText()
    }

    private fun turnOn() {
        val message = when {
            !BackScreen.wallpaperFile(this).exists() -> R.string.need_image
            !RearCommands.isReady() -> R.string.need_shizuku
            else -> null
        }
        if (message != null) {
            Snackbar.make(toggleSwitch, message, Snackbar.LENGTH_SHORT).show()
            BackScreen.mainHandler.post { refresh() } // flips the switch back off
            return
        }
        KeeperService.start(this, KeeperService.ACTION_APPLY)
    }

    private fun onImagePicked(uri: Uri) {
        try {
            // Copy into private app storage: the picker's access to the original is temporary.
            contentResolver.openInputStream(uri)!!.use { input ->
                BackScreen.wallpaperFile(this).outputStream().use { input.copyTo(it) }
            }
            BackScreen.log("Chose ${contentResolver.getType(uri)} (${BackScreen.wallpaperFile(this).length() / 1024} KB)")
            loadPreview()
            if (BackScreen.isEnabled(this)) KeeperService.start(this, KeeperService.ACTION_APPLY)
        } catch (e: Exception) {
            BackScreen.log("Couldn't copy image: ${e.message}")
        }
    }

    /** Matches the preview to the rear display's size and camera cutout. */
    private fun showRearShape() {
        val rear = BackScreen.findRearDisplay(this) ?: return
        previewFrame.setRearDisplay(rear, BackScreen.avoidCamera(this))
        cameraCard.visibility = if (previewFrame.hasCamera) View.VISIBLE else View.GONE
    }

    private fun loadPreview() {
        val file = BackScreen.wallpaperFile(this)
        val display = BackScreen.findRearDisplay(this) ?: display ?: return
        if (!file.exists()) return
        try {
            preview.setImageDrawable(BackScreen.loadWallpaper(file, display))
            // Black like the rear display, wherever the image doesn't reach.
            preview.setBackgroundColor(Color.BLACK)
            emptyState.visibility = View.GONE
        } catch (e: Exception) {
            BackScreen.log("Preview failed: ${e.message}")
        }
    }

    private companion object {
        const val REQUEST_SHIZUKU = 2
    }
}
