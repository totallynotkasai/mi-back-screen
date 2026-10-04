package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskListTest {

    // Trimmed from `am stack list` on a Xiaomi 17 Pro Max (HyperOS 3.0.319), with the wallpaper
    // on top of Xiaomi's back screen launcher.
    private val withLauncher = """
        RootTask id=1 bounds=[0,0][1200,2608] displayId=0 userId=0
         configuration={1.25 234mcc30mnc [en_US] ldltr sw400dp w400dp h869dp 480dpi nrml long hdr widecg port display=0}
          taskId=12: com.teslacoilsw.launcher/com.teslacoilsw.launcher.NovaLauncher bounds=[0,0][1200,2608] userId=0 visible=true topActivity=ComponentInfo{com.teslacoilsw.launcher/com.teslacoilsw.launcher.NovaLauncher}

        RootTask id=6 bounds=[0,0][1200,2608] displayId=0 userId=0
          taskId=7: unknown bounds=[0,0][1200,2608] userId=0 visible=false
          taskId=8: unknown bounds=[0,2608][1200,3912] userId=0 visible=false

        RootTask id=3010 bounds=[0,0][976,596] displayId=1 userId=0
         configuration={1.25 234mcc30mnc [en_US] ldltr sw367dp w601dp h367dp 260dpi nrml hdr widecg land display=1}
          taskId=3010: com.backscreen.wallpaper/com.backscreen.wallpaper.RearWallpaperActivity bounds=[0,0][976,596] userId=0 visible=true topActivity=ComponentInfo{com.backscreen.wallpaper/com.backscreen.wallpaper.RearWallpaperActivity}

        RootTask id=3004 bounds=[0,0][976,596] displayId=1 userId=0
          taskId=3005: com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher bounds=[0,0][976,596] userId=0 visible=false topActivity=ComponentInfo{com.xiaomi.subscreencenter/com.xiaomi.subscreencenter.SubScreenLauncher}
    """.trimIndent()

    @Test
    fun findsEachDisplaysPackagesFrontFirst() {
        assertEquals(listOf("com.backscreen.wallpaper", "com.xiaomi.subscreencenter"), TaskList.packagesOn(withLauncher, 1))
        assertEquals(listOf("com.teslacoilsw.launcher"), TaskList.packagesOn(withLauncher, 0))
    }

    @Test
    fun findsEachDisplaysTasksWithTheirIds() {
        assertEquals(
            listOf(TaskList.Task(3010, "com.backscreen.wallpaper"), TaskList.Task(3005, "com.xiaomi.subscreencenter")),
            TaskList.tasksOn(withLauncher, 1)
        )
        // The app in front on the main screen, as Quick Switch takes it.
        assertEquals(TaskList.Task(12, "com.teslacoilsw.launcher"), TaskList.tasksOn(withLauncher, 0).first())
    }

    @Test
    fun launcherInFront() {
        // Xiaomi brought its launcher back over the wallpaper.
        val swapped = withLauncher.lines().let { lines ->
            val wallpaper = lines.indexOfFirst { it.startsWith("RootTask id=3010") }
            val launcher = lines.indexOfFirst { it.startsWith("RootTask id=3004") }
            lines.subList(0, wallpaper) + lines.subList(launcher, lines.size) + lines.subList(wallpaper, launcher)
        }.joinToString("\n")
        assertEquals(listOf("com.xiaomi.subscreencenter", "com.backscreen.wallpaper"), TaskList.packagesOn(swapped, 1))
    }

    @Test
    fun displayIdIsMatchedExactly() {
        val ten = withLauncher.replace("displayId=1 ", "displayId=10 ")
        assertEquals(emptyList<String>(), TaskList.packagesOn(ten, 1))
    }

    // Trimmed from `am stack list` on the phone during Phase 6: YouTube full screen and sideways,
    // over the home screen and recents, with the wallpaper on the back screen.
    private val youtubeInFront = """
        RootTask id=3377 bounds=[0,0][2608,1200] displayId=0 userId=0
         configuration={1.25 234mcc30mnc [en_US] ldltr sw400dp w869dp h400dp 480dpi nrml long hdr widecg land uimode=15 finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 2608, 1200) mWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_270} display=0}
          taskId=3377: app.revanced.android.youtube/app.revanced.android.youtube.revanced_rounded_2 bounds=[0,0][2608,1200] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.youtube/com.google.android.youtube.app.honeycomb.Shell${'$'}HomeActivity}

        RootTask id=1 bounds=[0,0][2608,1200] displayId=0 userId=0
         configuration={1.25 234mcc30mnc [en_US] ldltr sw400dp w869dp h400dp 480dpi nrml long hdr widecg land winConfig={ mBounds=Rect(0, 0 - 2608, 1200) mWindowingMode=fullscreen mActivityType=home mAlwaysOnTop=undefined} display=0}
          taskId=3264: com.teslacoilsw.launcher/com.teslacoilsw.launcher.NovaLauncher bounds=[0,0][2608,1200] userId=0 visible=false topActivity=ComponentInfo{com.teslacoilsw.launcher/com.teslacoilsw.launcher.NovaLauncher}

        RootTask id=3270 bounds=[0,0][1200,2608] displayId=0 userId=0
         configuration={1.25 234mcc30mnc [en_US] ldltr sw400dp w400dp h869dp 480dpi nrml long hdr widecg port winConfig={ mBounds=Rect(0, 0 - 1200, 2608) mWindowingMode=fullscreen mActivityType=recents mAlwaysOnTop=undefined} display=0}
          taskId=3270: com.miui.home/com.miui.home.recents.RecentsActivity bounds=[0,0][1200,2608] userId=0 visible=false topActivity=ComponentInfo{com.miui.home/com.miui.home.recents.RecentsActivity}

        RootTask id=3363 bounds=[0,0][976,596] displayId=1 userId=0
         configuration={1.25 234mcc30mnc [en_US] ldltr sw367dp w601dp h367dp 260dpi nrml hdr widecg land winConfig={ mBounds=Rect(0, 0 - 976, 596) mWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined} display=1}
          taskId=3363: com.backscreen.wallpaper/com.backscreen.wallpaper.rear.RearHostActivity bounds=[0,0][976,596] userId=0 visible=true topActivity=ComponentInfo{com.backscreen.wallpaper/com.backscreen.wallpaper.rear.RearHostActivity}
    """.trimIndent()

    private val own = "com.backscreen.wallpaper"

    @Test
    fun quickSwitchSendsTheAppInFront() {
        assertEquals(TaskList.Task(3377, "app.revanced.android.youtube"), TaskList.appToSend(youtubeInFront, own))
    }

    @Test
    fun quickSwitchHasNothingToSendFromTheHomeScreen() {
        // The home screen in front: the first root task is the launcher.
        val home = youtubeInFront.lines().let { lines ->
            val youtube = lines.indexOfFirst { it.startsWith("RootTask id=3377") }
            val launcher = lines.indexOfFirst { it.startsWith("RootTask id=1 ") }
            lines.subList(launcher, launcher + 4) + lines.subList(youtube, launcher) + lines.subList(launcher + 4, lines.size)
        }.joinToString("\n")
        assertNull(TaskList.appToSend(home, own))
        // Nor from recents, nor this app.
        assertNull(TaskList.appToSend(youtubeInFront.replace("mActivityType=standard mAlwaysOnTop=undefined mRotation", "mActivityType=recents mAlwaysOnTop=undefined mRotation"), own))
        assertNull(TaskList.appToSend(youtubeInFront.replace("app.revanced.android.youtube/", "$own/"), own))
    }

    @Test
    fun quickSwitchPassesOverSystemUiAndPictureInPicture() {
        val pip = """
            RootTask id=50 bounds=[0,0][600,340] displayId=0 userId=0
             configuration={winConfig={ mWindowingMode=pinned mActivityType=standard}}
              taskId=50: com.google.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][600,340] userId=0 visible=true
            RootTask id=51 bounds=[0,0][1200,2608] displayId=0 userId=0
             configuration={winConfig={ mWindowingMode=fullscreen mActivityType=standard}}
              taskId=51: com.android.systemui/com.android.systemui.SomeActivity bounds=[0,0][1200,2608] userId=0 visible=true
        """.trimIndent()
        assertEquals(TaskList.Task(3377, "app.revanced.android.youtube"), TaskList.appToSend(pip + "\n\n" + youtubeInFront, own))
    }

    @Test
    fun quickSwitchFindsNothingInAnEmptyList() {
        assertNull(TaskList.appToSend("", own))
    }

    @Test
    fun nothingOnAnEmptyOrUnreadableList() {
        assertEquals(emptyList<String>(), TaskList.packagesOn("", 1))
        assertEquals(emptyList<String>(), TaskList.packagesOn("Error: unknown command", 1))
    }
}
