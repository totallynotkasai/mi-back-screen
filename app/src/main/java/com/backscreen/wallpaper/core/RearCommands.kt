package com.backscreen.wallpaper.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/** How a command ended: its exit code, or null if it ran out of time, and what it printed. */
data class ExecResult(val code: Int?, val out: String, val err: String)

/**
 * The only privileged commands this app runs. Shizuku executes them with shell (ADB-level)
 * access. Arguments go straight to the process (no shell), so nothing can be injected.
 *
 * Each has a time limit ([exec]): the keeper runs them one at a time, so one that hung would
 * hold up every command after it.
 */
object RearCommands {
    private const val AM = "/system/bin/am"
    private const val INPUT = "/system/bin/input"
    private const val CMD = "/system/bin/cmd"

    /** A launch token's extra on the host's intent (see KeeperService.hostToken). */
    const val EXTRA_TOKEN = "token"

    // Far longer than any of them takes (most are under 100 ms, a locked `am start` under a second).
    private const val TIMEOUT_MS = 5000L
    private const val TASK_LIST_TIMEOUT_MS = 3000L

    // After a command ends, how long its output may take to arrive.
    private const val STREAM_WAIT_MS = 500L
    const val XIAOMI_REAR_PACKAGE = "com.xiaomi.subscreencenter"
    const val CAMERA_PACKAGE = "com.android.camera"
    private const val REAR_TIMEOUT = "subscreen_display_time"

    // How Xiaomi's own back screen opens its camera there (seen on the phone): NEW_TASK,
    // SINGLE_TOP, EXCLUDE_FROM_RECENTS, RESET_TASK_IF_NEEDED and CLEAR_TASK, with no extras.
    private const val CAMERA_FLAGS = "0x30a08000"
    private val FIXED_ROTATION_MODES = setOf("default", "enabled", "disabled", "enabled_if_no_auto_rotation")

    // Xiaomi's back screen launcher has had different names across HyperOS builds; the first
    // one that starts wins.
    private val XIAOMI_REAR_LAUNCHERS = listOf(
        "$XIAOMI_REAR_PACKAGE/.SubScreenLauncher",
        "$XIAOMI_REAR_PACKAGE/.subscreenlauncher.SubScreenLauncherActivity",
        "com.xiaomi.mirror/.SubscreenLauncher",
    )

    fun isReady() =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /**
     * Our one window on the back screen (RearHostActivity), from [packageName], on [displayId].
     * It stays only if it carries [token], the one the keeper gave this launch.
     */
    fun launchHost(packageName: String, displayId: Int, token: String) = run(
        AM, "start", "--display", displayId.toString(), "-n", "$packageName/.rear.RearHostActivity",
        "--es", EXTRA_TOKEN, token,
    )

    fun moveTaskToDisplay(taskId: Int, displayId: Int) =
        run(AM, "display", "move-stack", taskId.toString(), displayId.toString())

    /** Lights up just that display, as if it had been tapped. */
    fun wakeDisplay(displayId: Int) =
        run(INPUT, "-d", displayId.toString(), "keyevent", "KEYCODE_WAKEUP")

    /**
     * Gives [component], our notification listener, Notification access, as switching it on in
     * Settings does. Works where Settings is blocked for sideloaded apps ("restricted settings").
     */
    fun allowNotificationListener(component: String) =
        run(CMD, "notification", "allow_listener", component)

    /** Takes Notification access away from [component] again. */
    fun disallowNotificationListener(component: String) =
        run(CMD, "notification", "disallow_listener", component)

    /** What `am stack list` prints (read it with [TaskList]), or null if it couldn't run. */
    fun taskList(): String? = read(AM, "stack", "list", timeoutMs = TASK_LIST_TIMEOUT_MS)

    /** Closes the notification shade, once the Quick Switch tile has been tapped. */
    fun collapseShade() = run(CMD, "statusbar", "collapse")

    /**
     * Xiaomi Camera in its back-screen mode, on the main cameras, exactly as Xiaomi's back screen
     * opens it (route 1). It's on HyperOS's list of apps allowed there while locked.
     */
    fun openXiaomiCamera(displayId: Int) =
        run(AM, "start", "--display", displayId.toString(), "-n", "$CAMERA_PACKAGE/.Camera", "-f", CAMERA_FLAGS)

    /** The standard "take a photo" intent, kept to Xiaomi Camera, which lands in the same mode (route 2). */
    fun openCameraByIntent(displayId: Int) =
        run(AM, "start", "--display", displayId.toString(), "-a", "android.media.action.STILL_IMAGE_CAMERA", "-p", CAMERA_PACKAGE)

    /** BACK on that display, as Xiaomi's back strip sends: Xiaomi Camera closes itself and finishes saving. */
    fun pressBack(displayId: Int) = run(INPUT, "-d", displayId.toString(), "keyevent", "KEYCODE_BACK")

    /** Only if Xiaomi Camera hasn't closed after BACK. */
    fun stopXiaomiCamera() = run(AM, "force-stop", CAMERA_PACKAGE)

    // Xiaomi's settings that a lend changes, and always puts back (see RearTweaks). The setting
    // names and the display are fixed; only the values vary, and those are numbers or one of
    // a few fixed words.

    /** How long Xiaomi keeps the back screen lit after the last touch, in ms; "null" if unset. */
    fun rearTimeout() = read(CMD, "settings", "get", "system", REAR_TIMEOUT)

    fun setRearTimeout(ms: Int) = run(CMD, "settings", "put", "system", REAR_TIMEOUT, ms.toString())

    fun clearRearTimeout() = run(CMD, "settings", "delete", "system", REAR_TIMEOUT)

    /** The back screen's density: "Physical density: 450", then "Override density: 260" if set. */
    fun rearDensity(displayId: Int) = read(CMD, "window", "density", "-d", displayId.toString())

    fun setRearDensity(displayId: Int, dpi: Int) =
        run(CMD, "window", "density", dpi.toString(), "-d", displayId.toString())

    /** Only to put back a display that had no override; Xiaomi's back screen has one (260, not 450). */
    fun resetRearDensity(displayId: Int) = run(CMD, "window", "density", "reset", "-d", displayId.toString())

    /** "free", or "lock" and the rotation it's locked to. */
    fun rearUserRotation(displayId: Int) = read(CMD, "window", "user-rotation", "-d", displayId.toString())

    fun lockRearRotation(displayId: Int, rotation: Int) =
        run(CMD, "window", "user-rotation", "-d", displayId.toString(), "lock", rotation.coerceIn(0, 3).toString())

    fun freeRearRotation(displayId: Int) = run(CMD, "window", "user-rotation", "-d", displayId.toString(), "free")

    /** Whether the display turns to the locked rotation: "default", "enabled", "disabled" and so on. */
    fun rearFixedRotation(displayId: Int) = read(CMD, "window", "fixed-to-user-rotation", "-d", displayId.toString())

    fun setRearFixedRotation(displayId: Int, mode: String): String {
        if (mode !in FIXED_ROTATION_MODES) return "failed: unknown mode $mode"
        return run(CMD, "window", "fixed-to-user-rotation", "-d", displayId.toString(), mode)
    }

    /** What [command] prints, or null if it couldn't run, failed or ran out of time. */
    private fun read(vararg command: String, timeoutMs: Long = TIMEOUT_MS): String? {
        if (!isReady()) return null
        return try {
            val result = exec(timeoutMs) { newProcess(arrayOf(*command)) }
            if (result.code == null) BackScreen.log("${command.drop(1).take(2).joinToString(" ")} timed out")
            if (result.code == 0) result.out else null
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

    /** Runs [command]: "ok", or what went wrong ([summary]). */
    private fun run(vararg command: String): String {
        if (!isReady()) return "Shizuku not ready"
        return try {
            summary(exec(TIMEOUT_MS) { newProcess(arrayOf(*command)) })
        } catch (e: Exception) {
            "error: ${e.cause?.message ?: e.message}"
        }
    }

    /** "ok", or what went wrong: "timed out", "failed (code): last line", or "failed: Error ...". */
    fun summary(result: ExecResult): String {
        val code = result.code ?: return "timed out"
        val output = result.out.trim() + "\n" + result.err.trim()
        // `am start` can exit 0 but print "Error: ..." when it didn't start anything.
        val error = output.lines().firstOrNull { it.startsWith("Error") }
        return when {
            code != 0 -> "failed ($code): ${output.trim().lines().lastOrNull().orEmpty()}"
            error != null -> "failed: $error"
            else -> "ok"
        }
    }

    /**
     * Runs the process [start] makes, giving it up to [timeoutMs]. Both of its outputs are read on
     * threads of their own, so one filling up can't stall the other (or the process). If it runs
     * out of time it's stopped and its outputs closed, and the result has no exit code. The waits
     * are on threads of our own too: Shizuku's process doesn't support Java's waitFor with a time
     * limit. Throws if [start] does, or if the process can't be waited for (Shizuku stopped, say).
     */
    fun exec(timeoutMs: Long, start: () -> Process): ExecResult {
        val process = start()
        val out = Drain(process.inputStream)
        val err = Drain(process.errorStream)
        var code = 0
        var failure: Exception? = null
        val waiter = thread(isDaemon = true, name = "BackScreen-wait") {
            try {
                code = process.waitFor()
            } catch (e: Exception) {
                failure = e
            }
        }
        waiter.join(timeoutMs)
        if (waiter.isAlive || failure != null) {
            try {
                process.destroy()
            } catch (e: Exception) {
                // Gone already.
            }
            out.close()
            err.close()
            failure?.let { throw it }
            return ExecResult(null, out.text(), err.text())
        }
        return ExecResult(code, out.text(), err.text())
    }

    /** Reads [stream] to its end on a thread of its own. */
    private class Drain(private val stream: InputStream) : Closeable {
        private val bytes = ByteArrayOutputStream()
        private val reader = thread(isDaemon = true, name = "BackScreen-read") {
            try {
                stream.copyTo(bytes)
            } catch (e: IOException) {
                // Closed after a timeout.
            }
        }

        /** What it has read: all of it, once the process has ended and its output arrived. */
        fun text(): String {
            reader.join(STREAM_WAIT_MS)
            return bytes.toString(Charsets.UTF_8.name())
        }

        override fun close() {
            try {
                stream.close()
            } catch (e: IOException) {
                // Closed already.
            }
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
