package com.backscreen.wallpaper.core

/** Reads the task list that `am stack list` prints. Plain Kotlin, so it's unit tested. */
object TaskList {
    private val ROOT_TASK = Regex("""^RootTask id=\d+ .*\bdisplayId=(\d+)\b""")
    private val TASK = Regex("""^\s+taskId=(\d+): ([^/\s]+)/""")
    private val ACTIVITY_TYPE = Regex("""\bmActivityType=(\w+)""")
    private val WINDOWING_MODE = Regex("""\bmWindowingMode=(\w+)""")

    // The screens that aren't an app you're using: Quick Switch has nothing to send then.
    private val NOT_APPS = setOf("home", "recents", "assistant", "dream")
    private const val SYSTEM_UI = "com.android.systemui"

    /** One task: its id, and the package of the app it belongs to. */
    data class Task(val id: Int, val packageName: String)

    /** A task with what its root task's configuration says about it. */
    private data class Listed(val task: Task, val display: Int, val activityType: String?, val windowingMode: String?)

    /** The tasks on [displayId], the one in front first. */
    fun tasksOn(output: String, displayId: Int): List<Task> =
        list(output).filter { it.display == displayId }.map { it.task }

    /** The packages with a task on [displayId], the one in front first. */
    fun packagesOn(output: String, displayId: Int): List<String> =
        tasksOn(output, displayId).map { it.packageName }.distinct()

    /**
     * The app in front on the main screen, for Quick Switch to send to the back screen. Null if
     * there's none to send: the home screen or recents is in front, or this app ([ownPackage]).
     * SystemUI's own tasks and a picture-in-picture window are passed over.
     */
    fun appToSend(output: String, ownPackage: String, mainDisplay: Int = 0): Task? {
        val front = list(output).firstOrNull {
            it.display == mainDisplay && it.task.packageName != SYSTEM_UI && it.windowingMode != "pinned"
        } ?: return null
        if (front.activityType in NOT_APPS || front.task.packageName == ownPackage) return null
        return front.task
    }

    private fun list(output: String): List<Listed> {
        val found = mutableListOf<Listed>()
        var display: Int? = null
        var activityType: String? = null
        var windowingMode: String? = null
        for (line in output.lineSequence()) {
            val root = ROOT_TASK.find(line)
            if (root != null) {
                display = root.groupValues[1].toInt()
                activityType = null
                windowingMode = null
                continue
            }
            val d = display ?: continue
            val task = TASK.find(line)
            if (task == null) {
                // The root task's configuration, on the line after it, says what kind of screen it is.
                ACTIVITY_TYPE.find(line)?.let { activityType = it.groupValues[1] }
                WINDOWING_MODE.find(line)?.let { windowingMode = it.groupValues[1] }
                continue
            }
            found += Listed(Task(task.groupValues[1].toInt(), task.groupValues[2]), d, activityType, windowingMode)
        }
        return found
    }
}
