package com.backscreen.wallpaper

/** Reads the task list that `am stack list` prints. Plain Kotlin, so it's unit tested. */
object TaskList {
    private val ROOT_TASK = Regex("""^RootTask id=\d+ .*\bdisplayId=(\d+)\b""")
    private val TASK = Regex("""^\s+taskId=\d+: ([^/\s]+)/""")

    /** The packages with a task on [displayId], the one in front first. */
    fun packagesOn(output: String, displayId: Int): List<String> {
        val found = LinkedHashSet<String>()
        var display: Int? = null
        for (line in output.lineSequence()) {
            val root = ROOT_TASK.find(line)
            if (root != null) {
                display = root.groupValues[1].toInt()
                continue
            }
            if (display != displayId) continue
            TASK.find(line)?.let { found += it.groupValues[1] }
        }
        return found.toList()
    }
}
