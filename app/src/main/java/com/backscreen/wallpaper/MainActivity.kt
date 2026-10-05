package com.backscreen.wallpaper

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.backscreen.wallpaper.battery.BatteryFragment
import com.backscreen.wallpaper.camera.CameraFragment
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.RearCommands
import com.backscreen.wallpaper.core.RearState
import com.backscreen.wallpaper.mirror.MirrorFragment
import com.backscreen.wallpaper.notifications.NotificationsFragment
import com.backscreen.wallpaper.ui.AppDialogs
import com.backscreen.wallpaper.ui.Feature
import com.backscreen.wallpaper.ui.Refreshable
import com.backscreen.wallpaper.wallpaper.WallpaperFragment
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import rikka.shizuku.Shizuku

/**
 * The app: a top bar with the menu, a banner while Shizuku isn't ready, and a bottom bar with
 * a tab per section (a rail down the side in landscape). A dot on a tab means that section is on.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var nav: NavigationBarView
    private lateinit var banner: View
    private lateinit var bannerTitle: TextView
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: Button

    private var current = Feature.WALLPAPER
    private var createdAt = 0L

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            BackScreen.mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        BackScreen.log(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku access granted" else "Shizuku access denied")
        refresh()
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { BackScreen.mainHandler.post { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createdAt = SystemClock.uptimeMillis()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        nav = findViewById(R.id.nav)
        banner = findViewById(R.id.shizukuBanner)
        bannerTitle = findViewById(R.id.shizukuBannerTitle)
        bannerText = findViewById(R.id.shizukuBannerText)
        bannerButton = findViewById(R.id.shizukuBannerButton)

        // Drawn edge to edge: the top bar keeps clear of the status bar, and the bottom bar of
        // the navigation bar by itself, its colour running on behind the buttons with no scrim.
        // In landscape the rail keeps clear of both by itself, and the section of the navigation bar.
        window.isNavigationBarContrastEnforced = false
        val content = findViewById<View>(R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.setPadding(0, bars.top, 0, 0)
            root.setPadding(bars.left, 0, bars.right, 0)
            content.setPadding(0, 0, 0, if (nav is BottomNavigationView) 0 else bars.bottom)
            insets
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_refresh -> refreshBackScreen()
                R.id.menu_diagnostics -> AppDialogs.diagnostics(this)
                R.id.menu_setup_help -> showSetupHelp()
                R.id.menu_about -> AppDialogs.about(this)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        current = savedInstanceState?.getString(KEY_TAB)?.let(Feature::valueOf) ?: Feature.WALLPAPER
        nav.selectedItemId = current.navId
        showTab(current)
        nav.setOnItemSelectedListener { item ->
            Feature.forNavId(item.itemId)?.let(::showTab)
            true
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        // After the app is force stopped nothing starts the service again, so opening the app
        // carries on where it was: the wallpaper, an app lent the back screen, Xiaomi's settings.
        if (KeeperService.instance == null && RearState.keeperNeeded(this)) {
            KeeperService.start(this, KeeperService.ACTION_RESUME)
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, current.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * The wallpaper's tile opens us to toggle when it isn't allowed to itself, and the Quick
     * Switch tile when the service wasn't running.
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            KeeperService.ACTION_TOGGLE -> toggleWallpaper()
            KeeperService.ACTION_QUICK_SWITCH -> {
                // We're in front now, so there's nothing to send; start the service for the next tap.
                selectTab(Feature.MIRROR)
                KeeperService.start(this, KeeperService.ACTION_UPDATE)
                snackbar(R.string.qs_was_asleep, Snackbar.LENGTH_LONG)
            }
        }
    }

    private fun selectTab(feature: Feature) {
        nav.selectedItemId = feature.navId
    }

    private fun toggleWallpaper() {
        val on = !WallpaperSettings.isEnabled(this)
        if (on && !RearCommands.isReady()) {
            snackbar(R.string.need_shizuku)
            return
        }
        KeeperService.setWallpaper(this, on)
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
        Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }

    /** Shows [feature]'s tab. Each is made the first time it's shown, then kept, so it keeps its place. */
    private fun showTab(feature: Feature) {
        current = feature
        val fm = supportFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        for (other in Feature.entries) {
            if (other != feature) fm.findFragmentByTag(other.name)?.let { tx.hide(it) }
        }
        val fragment = fm.findFragmentByTag(feature.name)
        if (fragment == null) tx.add(R.id.content, newTab(feature), feature.name) else tx.show(fragment)
        tx.commitNow()
        refresh()
    }

    private fun newTab(feature: Feature): Fragment = when (feature) {
        Feature.WALLPAPER -> WallpaperFragment()
        Feature.NOTIFICATIONS -> NotificationsFragment()
        Feature.BATTERY -> BatteryFragment()
        Feature.MIRROR -> MirrorFragment()
        Feature.CAMERA -> CameraFragment()
    }

    private fun refresh() {
        refreshBanner()
        // A dot on each tab whose section is on.
        val dot = MaterialColors.getColor(nav, com.google.android.material.R.attr.colorPrimary)
        for (feature in Feature.entries) {
            if (feature.settings.isEnabled(this)) {
                nav.getOrCreateBadge(feature.navId).apply {
                    isVisible = true
                    backgroundColor = dot
                    setContentDescriptionNumberless(getString(R.string.feature_on))
                }
            } else {
                nav.getBadge(feature.navId)?.isVisible = false
            }
        }
        (supportFragmentManager.findFragmentByTag(current.name) as? Refreshable)?.refresh()
    }

    /**
     * The banner, while Shizuku isn't ready. Its connection arrives just after the app starts,
     * so wait a moment before saying it isn't there.
     */
    private fun refreshBanner() {
        val ready = RearCommands.isReady()
        val running = Shizuku.pingBinder()
        val settled = SystemClock.uptimeMillis() - createdAt > BANNER_GRACE_MS
        banner.visibility = if (!ready && (running || settled)) View.VISIBLE else View.GONE
        if (ready) return
        if (running) {
            bannerTitle.setText(R.string.shizuku_banner_not_allowed)
            bannerText.setText(R.string.shizuku_banner_not_allowed_text)
            bannerButton.setText(R.string.allow_access)
            bannerButton.setOnClickListener { requestShizuku() }
        } else {
            bannerTitle.setText(R.string.shizuku_banner_not_running)
            bannerText.setText(R.string.shizuku_banner_not_running_text)
            bannerButton.setText(R.string.setup_help)
            bannerButton.setOnClickListener { showSetupHelp() }
        }
    }

    private fun requestShizuku() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_SHIZUKU)
    }

    private fun showSetupHelp() = AppDialogs.setupHelp(this, ::requestShizuku)

    /** Reloads everything and puts the wallpaper up afresh, for when something didn't update. */
    private fun refreshBackScreen() {
        (supportFragmentManager.findFragmentByTag(Feature.WALLPAPER.name) as? WallpaperFragment)?.reloadAll()
        when {
            !WallpaperSettings.isEnabled(this) -> snackbar(R.string.refresh_off)
            !RearCommands.isReady() -> snackbar(R.string.need_shizuku)
            else -> {
                BackScreen.log("Refreshing")
                KeeperService.setWallpaper(this, true)
                snackbar(R.string.refreshing)
            }
        }
    }

    /** Shows [text] just above the bottom bar, or at the bottom in landscape. */
    fun snackbar(text: Int, length: Int = Snackbar.LENGTH_SHORT): Snackbar =
        Snackbar.make(findViewById(R.id.content), text, length)
            .apply { if (nav is BottomNavigationView) anchorView = nav }
            .also { it.show() }

    private companion object {
        const val REQUEST_SHIZUKU = 2
        const val REFRESH_MS = 1000L
        const val BANNER_GRACE_MS = 1500L
        const val KEY_TAB = "tab"
    }
}
