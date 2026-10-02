package com.backscreen.wallpaper.core

/** Reads the task list that `am stack list` prints. Plain Kotlin, so it's unit tested. */
object TaskList {
    private val ROOT_TASK = Regex("""^RootTask id=\d+ .*\bdisplayId=(\d+)\b""")
    private val TASK = Regex("""^\s+taskId=(\d+): ([^/\s]+)/""")

    /** One task: its id, and the package of the app it belongs to. */
    data class Task(val id: Int, val packageName: String)

    /** The tasks on [displayId], the one in front first. */
    fun tasksOn(output: String, displayId: Int): List<Task> {
        val found = mutableListOf<Task>()
        var display: Int? = null
        for (line in output.lineSequence()) {
            val root = ROOT_TASK.find(line)
            if (root != null) {
                display = root.groupValues[1].toInt()
                continue
            }
            if (display != displayId) continue
            TASK.find(line)?.let { found += Task(it.groupValues[1].toInt(), it.groupValues[2]) }
        }
        return found
    }

    /** The packages with a task on [displayId], the one in front first. */
    fun packagesOn(output: String, displayId: Int): List<String> =
        tasksOn(output, displayId).map { it.packageName }.distinct()
}
