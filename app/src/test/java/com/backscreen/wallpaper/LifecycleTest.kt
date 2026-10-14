package com.backscreen.wallpaper

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.backscreen.wallpaper.core.BackScreen
import com.backscreen.wallpaper.core.KeeperService
import com.backscreen.wallpaper.core.Schedule
import com.backscreen.wallpaper.core.ScheduleDialog
import com.backscreen.wallpaper.core.ScheduleEditor
import com.backscreen.wallpaper.core.Schedules
import com.backscreen.wallpaper.ui.DiagnosticsDialog
import com.backscreen.wallpaper.ui.Feature
import com.backscreen.wallpaper.ui.RefreshingDialog
import com.backscreen.wallpaper.ui.SetupHelpDialog
import com.backscreen.wallpaper.wallpaper.WallpaperSettings
import com.google.android.material.R as MaterialR
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager

/**
 * Robolectric: what turning the phone (the activity made again), and Recents, must leave as it
 * was, and the schedule editor's Undo. The phone has its back screen, as in [MainActivityTest].
 */
@RunWith(AndroidJUnit4::class)
class LifecycleTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun addBackScreen() {
        // Its size in pixels, as the app finds it by (976 x 596).
        ShadowDisplayManager.addDisplay("w976dp-h596dp-land-mdpi")
    }

    @Test
    fun schedulesKeepTheirSwitchesThroughARotation() {
        // Every row's switch has the same id; the last row's state used to be restored into all of them.
        val on = Schedule(1, LocalTime.of(7, 0), LocalTime.of(9, 0), Schedule.EVERY_DAY, enabled = true)
        val off = Schedule(2, LocalTime.of(18, 0), LocalTime.of(20, 0), Schedule.EVERY_DAY, enabled = false)
        Schedules.save(context, listOf(on, off))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(true, false), Schedules.load(context).sortedBy { it.id }.map { it.enabled })
            scenario.onActivity { activity ->
                val rows = activity.findViewById<LinearLayout>(R.id.scheduleList)
                val shown = (0 until rows.childCount).map {
                    rows.getChildAt(it).findViewById<MaterialSwitch>(R.id.scheduleSwitch).isChecked
                }
                assertEquals(listOf(true, false), shown)
            }
        }
    }

    // These two drive the activity directly: ActivityScenario finds its activity by the intent's
    // action, which the app changes to MAIN once it's handled the tile's.

    @Test
    fun aTileOpeningTheAppTogglesOnce() {
        WallpaperSettings.setEnabled(context, true)
        val intent = LaunchCommand.intent(context, KeeperService.ACTION_TOGGLE)

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertFalse(WallpaperSettings.isEnabled(context))
        assertEquals(Intent.ACTION_MAIN, controller.get().intent.action)
        controller.pause().stop().destroy()
    }

    @Test
    fun recentsReopeningItDoesNotToggleAgain() {
        WallpaperSettings.setEnabled(context, true)
        // What Recents sends when the task's first intent was the tile's.
        val intent = LaunchCommand.intent(context, KeeperService.ACTION_TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertTrue(WallpaperSettings.isEnabled(context))
        controller.pause().stop().destroy()
    }

    @Test
    fun anotherAppAskingMainActivityToToggleIsIgnored() {
        WallpaperSettings.setEnabled(context, true)
        // MainActivity is exported, so any app can send it this; only the private alias counts.
        val intent = Intent(context, MainActivity::class.java).setAction(KeeperService.ACTION_TOGGLE)

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertTrue(WallpaperSettings.isEnabled(context))
        controller.pause().stop().destroy()
    }

    @Test
    fun diagnosticsKeepsOneRefreshThroughARotation() {
        checkOneRefresh(DiagnosticsDialog.TAG, DiagnosticsDialog::show)
    }

    @Test
    fun setupHelpKeepsOneRefreshThroughARotation() {
        checkOneRefresh(SetupHelpDialog.TAG, SetupHelpDialog::show)
    }

    @Test
    fun theScheduleEditorKeepsItsEditsThroughARotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ScheduleDialog.show(wallpaperTab(it).childFragmentManager, null) }
            idle()
            scenario.onActivity {
                // A day off, and the picker for when it turns on left open.
                val dialog = scheduleDialog(it).requireDialog()
                (dialog.findViewById<ChipGroup>(R.id.dayChips)!!.getChildAt(0) as Chip).isChecked = false
                dialog.findViewById<Button>(R.id.startButton)!!.performClick()
            }
            idle()

            scenario.recreate()
            idle()

            scenario.onActivity {
                // The picker that came back still sets the time.
                val picker = scheduleDialog(it).childFragmentManager.findFragmentByTag("start") as MaterialTimePicker
                picker.hour = 6
                picker.minute = 30
                picker.requireDialog().findViewById<View>(MaterialR.id.material_timepicker_ok_button).performClick()
            }
            idle()
            scenario.onActivity {
                val dialog = scheduleDialog(it).requireDialog() as AlertDialog
                assertEquals(
                    ScheduleEditor.formatTime(it, LocalTime.of(6, 30)),
                    dialog.findViewById<Button>(R.id.startButton)!!.text.toString(),
                )
                assertFalse((dialog.findViewById<ChipGroup>(R.id.dayChips)!!.getChildAt(0) as Chip).isChecked)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            idle()
        }
        val saved = Schedules.load(context).single()
        assertEquals(LocalTime.of(6, 30), saved.start)
        assertEquals(LocalTime.of(23, 0), saved.end)
        val firstDay = 1 shl (ScheduleEditor.orderedDays().first().value - 1)
        assertEquals(Schedule.EVERY_DAY and firstDay.inv(), saved.days)
    }

    @Test
    fun aDeletedScheduleCanBeUndone() {
        val schedule = Schedule(1, LocalTime.of(7, 0), LocalTime.of(9, 0), Schedule.WEEKDAYS, enabled = false)
        Schedules.save(context, listOf(schedule))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<LinearLayout>(R.id.scheduleList).getChildAt(0).performClick() }
            idle()
            scenario.onActivity {
                (scheduleDialog(it).requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
            idle()
            assertEquals(emptyList<Schedule>(), Schedules.load(context))
            scenario.onActivity {
                assertEquals(0, it.findViewById<LinearLayout>(R.id.scheduleList).childCount)
                it.findViewById<View>(MaterialR.id.snackbar_action).performClick()
            }
            idle()
            scenario.onActivity { assertEquals(1, it.findViewById<LinearLayout>(R.id.scheduleList).childCount) }
        }
        assertEquals(listOf(schedule), Schedules.load(context))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun wallpaperTab(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag(Feature.WALLPAPER.name)!!

    private fun scheduleDialog(activity: MainActivity) =
        wallpaperTab(activity).childFragmentManager.fragments.filterIsInstance<ScheduleDialog>().single()

    /** The dialog [show] opens under [tag] is there again after a rotation, and only it refreshes. */
    private fun checkOneRefresh(tag: String, show: (androidx.fragment.app.FragmentManager) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                show(it.supportFragmentManager)
                // Asked twice, it's still shown once.
                show(it.supportFragmentManager)
            }
            shadowOf(Looper.getMainLooper()).idle()
            lateinit var first: RefreshingDialog
            scenario.onActivity {
                first = it.supportFragmentManager.findFragmentByTag(tag) as RefreshingDialog
                assertEquals(1, it.supportFragmentManager.fragments.count { f -> f is RefreshingDialog })
            }
            assertTrue(BackScreen.mainHandler.hasCallbacks(first.refresher))

            scenario.recreate()
            shadowOf(Looper.getMainLooper()).idle()

            scenario.onActivity {
                val second = it.supportFragmentManager.findFragmentByTag(tag) as RefreshingDialog
                assertNotSame(first, second)
                assertTrue(second.dialog!!.isShowing)
                assertFalse(BackScreen.mainHandler.hasCallbacks(first.refresher))
                assertTrue(BackScreen.mainHandler.hasCallbacks(second.refresher))
            }
        }
    }
}
