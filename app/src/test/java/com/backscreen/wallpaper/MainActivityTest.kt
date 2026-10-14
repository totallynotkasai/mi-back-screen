package com.backscreen.wallpaper

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.backscreen.wallpaper.core.ShizukuState
import com.backscreen.wallpaper.mirror.MirrorSettings
import com.backscreen.wallpaper.ui.Feature
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager

/**
 * Robolectric: the app on a phone where Shizuku isn't running, so there's no Shizuku binder at all.
 * The phone has its back screen, as the real one does (Robolectric's activities have no display of
 * their own to fall back on).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun addBackScreen() {
        // Its size in pixels, as the app finds it by (976 x 596).
        ShadowDisplayManager.addDisplay("w976dp-h596dp-land-mdpi")
    }

    @Test
    fun startsWithoutShizuku() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Once the banner's grace period is over, it says Shizuku isn't there, and how to get it.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            scenario.onActivity { activity ->
                assertNotNull(activity.supportFragmentManager.findFragmentByTag(Feature.WALLPAPER.name))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.shizukuBanner).visibility)
                assertBanner(activity, R.string.shizuku_banner_not_installed, R.string.get_shizuku)
            }
        }
    }

    @Test
    fun shizukuInstalledButNotRunningOffersToOpenIt() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = ShizukuState.PACKAGE })

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            scenario.onActivity { assertBanner(it, R.string.shizuku_banner_not_running, R.string.open_shizuku) }
        }
    }

    @Test
    fun aTabThatNeedsShizukuSaysItsWaiting() {
        MirrorSettings.setEnabled(context, true)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<View>(Feature.MIRROR.navId).performClick() }
            // Not straight away: Shizuku's connection usually comes just after the app starts.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            scenario.onActivity { assertEquals(it.getString(R.string.mirror_on_ready), mirrorSummary(it)) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            scenario.onActivity { assertEquals(it.getString(R.string.state_waiting_shizuku), mirrorSummary(it)) }
        }
    }

    private fun assertBanner(activity: MainActivity, title: Int, button: Int) {
        assertEquals(activity.getString(title), activity.findViewById<TextView>(R.id.shizukuBannerTitle).text.toString())
        assertEquals(activity.getString(button), activity.findViewById<TextView>(R.id.shizukuBannerButton).text.toString())
    }

    private fun mirrorSummary(activity: MainActivity): String {
        val tab = activity.supportFragmentManager.findFragmentByTag(Feature.MIRROR.name)!!.requireView()
        return tab.findViewById<TextView>(R.id.mainSwitchSummary).text.toString()
    }
}
