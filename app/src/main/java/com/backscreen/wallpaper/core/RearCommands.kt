package com.backscreen.wallpaper.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * The only privileged commands this app runs. Shizuku executes them with shell (ADB-level)
 * access. Arguments go straight to the process (no shell), so nothing can be injected.
 */
object RearCommands {
    private const val AM = "/system/bin/am"
    private const val INPUT = "/system/bin/input"
    private const val PACKAGE = "com.backscreen.wallpaper"
    const val XIAOMI_REAR_PACKAGE = "com.xiaomi.subscreencenter"

    // Xiaomi's back screen launcher has had different names across HyperOS builds; the first
    // one that starts wins.
    private val XIAOMI_REAR_LAUNCHERS = listOf(
        "$XIAOMI_REAR_PACKAGE/.SubScreenLauncher",
        "$XIAOMI_REAR_PACKAGE/.subscreenlauncher.SubScreenLauncherActivity",
        "com.xiaomi.mirror/.SubscreenLauncher",
    )

    fun isReady() =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Our one window on the back screen (RearHostActivity), on [displayId]. */
    fun launchHost(displayId: Int) =
        run(AM, "start", "--display", displayId.toString(), "-n", "$PACKAGE/.rear.RearHostActivity")

    fun moveTaskToDisplay(taskId: Int, displayId: Int) =
        run(AM, "display", "move-stack", taskId.toString(), displayId.toString())

    /** Lights up just that display, as if it had been tapped. */
    fun wakeDisplay(displayId: Int) =
        run(INPUT, "-d", displayId.toString(), "keyevent", "KEYCODE_WAKEUP")

    /** What `am stack list` prints (read it with [TaskList]), or null if it couldn't run. */
    fun taskList(): String? {
        if (!isReady()) return null
        return try {
            val process = newProcess(arrayOf(AM, "stack", "list"))
            val output = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            if (process.waitFor() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }

    /** Xiaomi's back screen app restarts itself straight away, without its launcher. */
    fun restartXiaomiRearApp() =
        run(AM, "force-stop", XIAOMI_REAR_PACKAGE)

    fun restoreXiaomiRearLauncher(displayId: Int): String {
        var result = ""
        for (launcher in XIAOMI_REAR_LAUNCHERS) {
            result = run(AM, "start", "--display", displayId.toString(), "-n", launcher)
            if (result == "ok") return "ok ($launcher)"
        }
        return result
    }

    private fun run(vararg command: String): String {
        if (!isReady()) return "Shizuku not ready"
        return try {
            val process = newProcess(arrayOf(*command))
            val output = process.inputStream.bufferedReader().readText().trim() + "\n" +
                process.errorStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            // `am start` can exit 0 but print "Error: ..." when it didn't start anything.
            val error = output.lines().firstOrNull { it.startsWith("Error") }
            when {
                code != 0 -> "failed ($code): ${output.trim().lines().lastOrNull().orEmpty()}"
                error != null -> "failed: $error"
                else -> "ok"
            }
        } catch (e: Exception) {
            "error: ${e.cause?.message ?: e.message}"
        }
    }

    // Shizuku.newProcess is not public in API 13, but is the simplest way to run a command
    // through the Shizuku server directly (no helper process that must connect back).
    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    private fun newProcess(command: Array<String>) =
        newProcessMethod.invoke(null, command, null, null) as Process
}
