package com.backscreen.wallpaper.core

import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The keeper's shell thread: work that runs Shizuku commands ([RearCommands]) goes here, one
 * piece at a time, off the main thread. Shizuku's connection arrives shortly after the process
 * starts, so work asked for before then waits for it, briefly. Every command has a time limit, so
 * one that hangs can't hold up the rest. Main thread, except the work itself.
 */
class ShellRunner {

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "BackScreen-shell") }

    // Work waiting for Shizuku's connection.
    private val waiting = mutableListOf<() -> Unit>()
    private val timeout = Runnable { runWaiting() }

    /** Set once the keeper stops: work posted or queued after that is dropped. */
    var stopped = false
        private set

    /**
     * Runs [block] with Shizuku on the shell thread, waiting briefly for its connection if needed.
     * If Shizuku isn't ready, [onUnavailable] runs instead, on the main thread. Once stopped,
     * neither runs.
     */
    fun run(onUnavailable: () -> Unit, block: () -> Unit) {
        if (stopped) return BackScreen.trace("The keeper has stopped; shell work dropped")
        val task = {
            when {
                stopped -> BackScreen.trace("The keeper has stopped; shell work dropped")
                RearCommands.isReady() -> execute(block)
                else -> {
                    BackScreen.log("Shizuku isn't running or this app isn't allowed")
                    onUnavailable()
                }
            }
        }
        if (Shizuku.pingBinder()) {
            task()
        } else {
            waiting += task
            BackScreen.mainHandler.removeCallbacks(timeout)
            BackScreen.mainHandler.postDelayed(timeout, SHIZUKU_WAIT_MS)
        }
    }

    /** Shizuku connected, or it's waited long enough: whatever was waiting runs now, or finds it isn't ready. Any thread. */
    fun runWaiting() {
        BackScreen.mainHandler.post {
            BackScreen.mainHandler.removeCallbacks(timeout)
            val tasks = waiting.toList()
            waiting.clear()
            tasks.forEach { it() }
        }
    }

    /** For good: nothing more runs, and what's waiting is dropped. */
    fun stop() {
        stopped = true
        waiting.clear()
        BackScreen.mainHandler.removeCallbacks(timeout)
        executor.shutdown()
    }

    /** On the shell thread, unless it was stopped in the meantime and shut. */
    private fun execute(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (e: RejectedExecutionException) {
            BackScreen.trace("The keeper has stopped; shell work dropped")
        }
    }

    private companion object {
        const val SHIZUKU_WAIT_MS = 4000L
    }
}
