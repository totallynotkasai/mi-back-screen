package com.backscreen.wallpaper

import org.junit.Assert.assertEquals
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

    @Test
    fun nothingOnAnEmptyOrUnreadableList() {
        assertEquals(emptyList<String>(), TaskList.packagesOn("", 1))
        assertEquals(emptyList<String>(), TaskList.packagesOn("Error: unknown command", 1))
    }
}
