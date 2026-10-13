package com.backscreen.wallpaper

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Before
    fun addBackScreen() {
        ShadowDisplayManager.addDisplay("w601dp-h367dp-land")
    }

    @Test
    fun startsWithoutShizuku() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Once the banner's grace period is over, it says Shizuku isn't running.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            scenario.onActivity { activity ->
                assertNotNull(activity.supportFragmentManager.findFragmentByTag(Feature.WALLPAPER.name))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.shizukuBanner).visibility)
                assertEquals(
                    activity.getString(R.string.shizuku_banner_not_running),
                    activity.findViewById<TextView>(R.id.shizukuBannerTitle).text.toString(),
                )
            }
        }
    }
}
