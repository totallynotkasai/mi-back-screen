package com.backscreen.wallpaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class RearCommandsExecTest {

    /** A process whose outputs are [out] and [err], and which ends with what [waitFor] returns. */
    private class FakeProcess(
        private val out: InputStream,
        private val err: InputStream,
        private val exit: (FakeProcess) -> Int,
    ) : Process() {
        val stopped = CountDownLatch(1)
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream() = out
        override fun getErrorStream() = err
        override fun waitFor() = exit(this)
        override fun exitValue(): Int = throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
            stopped.countDown()
        }
    }

    private fun text(s: String) = ByteArrayInputStream(s.toByteArray())

    @Test
    fun aHungProcessTimesOutAndIsStopped() {
        // Prints nothing and never ends, until it's stopped.
        val silent = PipedInputStream(PipedOutputStream())
        val process = FakeProcess(silent, text(""), exit = { it.stopped.await(); -1 })

        val started = System.nanoTime()
        val result = RearCommands.exec(timeoutMs = 300) { process }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertNull(result.code)
        assertTrue(process.destroyed)
        assertTrue("took $tookMs ms", tookMs < 2500)
        assertEquals("timed out", RearCommands.summary(result))
    }

    @Test
    fun aLotOnItsErrorOutputFirstDoesNotStallIt() {
        // 200 KB on the error output before anything on the other: read one after the other, as
        // before, its pipe would fill and both sides would wait for ever.
        val outPipe = PipedOutputStream()
        val errPipe = PipedOutputStream()
        val out = PipedInputStream(outPipe)
        val err = PipedInputStream(errPipe)
        val writer = thread {
            errPipe.use { it.write(ByteArray(200_000) { 'x'.code.toByte() }) }
            outPipe.use { it.write("done\n".toByteArray()) }
        }
        val process = FakeProcess(out, err, exit = { writer.join(); 0 })

        val result = RearCommands.exec(timeoutMs = 5000) { process }

        assertEquals(0, result.code)
        assertEquals(200_000, result.err.length)
        assertEquals("done\n", result.out)
        assertEquals("ok", RearCommands.summary(result))
    }

    @Test
    fun theExitCodeAndErrorLineAreReported() {
        val failed = FakeProcess(
            text(""),
            text("Exception occurred while executing 'move-stack':\njava.lang.IllegalArgumentException: no task 99\n"),
            exit = { 255 },
        )
        assertEquals("failed (255): java.lang.IllegalArgumentException: no task 99", RearCommands.summary(RearCommands.exec(5000) { failed }))

        // `am start` ends well, but says it started nothing.
        val notStarted = FakeProcess(
            text("Starting: Intent { cmp=x/.Y }\nError: Activity class {x/x.Y} does not exist.\n"), text(""), exit = { 0 },
        )
        assertEquals("failed: Error: Activity class {x/x.Y} does not exist.", RearCommands.summary(RearCommands.exec(5000) { notStarted }))

        val fine = FakeProcess(text("Starting: Intent { cmp=x/.Y }\n"), text(""), exit = { 0 })
        val result = RearCommands.exec(5000) { fine }
        assertEquals(0, result.code)
        assertEquals("ok", RearCommands.summary(result))
    }

    @Test
    fun aProcessThatCantBeWaitedForIsAnError() {
        // Shizuku stopping mid-command throws from waitFor; the commands report that as an error.
        val broken = FakeProcess(text(""), text(""), exit = { throw IllegalStateException("binder died") })
        val e = assertThrows(IllegalStateException::class.java) { RearCommands.exec(1000) { broken } }
        assertEquals("binder died", e.message)
        assertTrue(broken.destroyed)
    }
}
