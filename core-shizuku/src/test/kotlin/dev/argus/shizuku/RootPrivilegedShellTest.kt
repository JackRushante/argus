package dev.argus.shizuku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class MemoryRootPreference(var on: Boolean) : RootPreference {
    override fun isEnabled(): Boolean = on
    override fun setEnabled(enabled: Boolean) {
        on = enabled
    }
}

/** Una vera `sh` locale al posto di `su`: prova quoting, marcatori e timeout senza root. */
private class SystemProcess(private val process: Process) : RootProcess {
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream
    override fun waitFor(timeoutMillis: Long): Boolean = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    override fun exitValue(): Int = process.exitValue()
    override fun isAlive(): Boolean = process.isAlive
    override fun destroy() {
        process.destroyForcibly()
    }
}

/** Un processo che risponde con un testo fisso (per `su -c id`) oppure non risponde mai. */
private class ScriptedProcess(output: String, private val exit: Int, private val hangs: Boolean = false) : RootProcess {
    private val gate = Object()
    private var destroyed = false
    override val stdin: OutputStream = OutputStream.nullOutputStream()
    override val stdout: InputStream = if (hangs) blocking() else ByteArrayInputStream(output.toByteArray())
    override val stderr: InputStream = if (hangs) blocking() else ByteArrayInputStream(ByteArray(0))
    var destroyCount = 0

    private fun blocking() = object : InputStream() {
        override fun read(): Int {
            synchronized(gate) { while (!destroyed) gate.wait() }
            return -1
        }
    }

    override fun waitFor(timeoutMillis: Long): Boolean {
        if (!hangs) return true
        Thread.sleep(timeoutMillis)
        return false
    }

    override fun exitValue(): Int = exit
    override fun isAlive(): Boolean = hangs && !destroyed
    override fun destroy() {
        synchronized(gate) {
            destroyed = true
            destroyCount++
            gate.notifyAll()
        }
    }
}

private class TestLauncher(
    private val idReply: () -> RootProcess = { ScriptedProcess("uid=0(root) gid=0(root) groups=0(root)\n", 0) },
    private val session: () -> RootProcess = { SystemProcess(ProcessBuilder("sh").start()) },
) : RootLauncher {
    val starts = CopyOnWriteArrayList<List<String>>()
    val sessions = CopyOnWriteArrayList<RootProcess>()

    override fun start(argv: List<String>): RootProcess {
        starts += argv
        return when (argv) {
            listOf("su", "-c", "id") -> idReply()
            listOf("su") -> session().also { sessions += it }
            else -> throw IOException("unexpected argv")
        }
    }
}

class RootPrivilegedShellTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shells = mutableListOf<RootPrivilegedShell>()

    @AfterTest
    fun tearDown() {
        shells.forEach(RootPrivilegedShell::close)
        scope.cancel()
    }

    private fun shellAvailable() {
        assumeTrue(File("/bin/sh").exists(), "needs a POSIX sh")
        assumeTrue(
            System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "timeout").exists() },
            "needs timeout in PATH",
        )
    }

    private fun shell(
        preference: RootPreference,
        launcher: RootLauncher,
        verifyTimeoutMillis: Long = 2_000,
        graceMillis: Long = 5_000,
    ) = RootPrivilegedShell(
        preference = preference,
        scope = scope,
        launcher = launcher,
        io = Dispatchers.IO,
        now = { System.nanoTime() / 1_000_000 },
        verifyTimeoutMillis = verifyTimeoutMillis,
        graceMillis = graceMillis,
    ).also(shells::add)

    private suspend fun active(preference: RootPreference, launcher: RootLauncher, graceMillis: Long = 5_000): RootPrivilegedShell {
        val shell = shell(preference, launcher, graceMillis = graceMillis)
        assertEquals(RootState.ACTIVE, withTimeout(10_000) { shell.observeState().first { it != RootState.CHECKING } })
        return shell
    }

    // --- interruttore spento: nessuna chiamata a su ---

    @Test
    fun `switch off never starts su`() = runBlocking {
        val launcher = TestLauncher()
        val preference = MemoryRootPreference(false)
        val shell = shell(preference, launcher)

        assertEquals(RootState.OFF, shell.observeState().value)
        assertEquals(RootState.OFF, shell.verify())
        val failure = assertFailsWith<ShizukuShellException> { shell.run(listOf("id")) }
        assertEquals(ShizukuShellException.UNAVAILABLE, failure.code)
        assertEquals(RootState.OFF, shell.setEnabled(false))
        assertTrue(launcher.starts.isEmpty(), "su must not be called while the switch is off")
    }

    @Test
    fun `switching on verifies with su -c id and switching off closes the session`() = runBlocking {
        shellAvailable()
        val launcher = TestLauncher()
        val preference = MemoryRootPreference(false)
        val shell = shell(preference, launcher)

        assertEquals(RootState.ACTIVE, shell.setEnabled(true))
        assertTrue(preference.on)
        assertEquals(listOf(listOf("su", "-c", "id")), launcher.starts.toList())
        assertEquals("hi\n", shell.run(listOf("echo", "hi")).stdoutText)
        assertEquals(RootState.OFF, shell.setEnabled(false))
        assertFalse(preference.on)
        assertFailsWith<ShizukuShellException> { shell.run(listOf("echo", "hi")) }
        Unit
    }

    @Test
    fun `an already enabled switch verifies once at construction`() = runBlocking {
        val launcher = TestLauncher()
        val shell = shell(MemoryRootPreference(true), launcher)
        assertEquals(RootState.ACTIVE, withTimeout(10_000) { shell.observeState().first { it != RootState.CHECKING } })
        assertEquals(listOf(listOf("su", "-c", "id")), launcher.starts.toList())
    }

    // --- verifica ---

    @Test
    fun `uid other than 0 is denied`() = runBlocking {
        val shell = shell(
            MemoryRootPreference(false),
            TestLauncher(idReply = { ScriptedProcess("uid=2000(shell) gid=2000(shell)\n", 0) }),
        )
        assertEquals(RootState.DENIED, shell.setEnabled(true))
        assertFalse(shell.isActive())
    }

    @Test
    fun `non zero exit is denied even with uid 0 text`() = runBlocking {
        val shell = shell(
            MemoryRootPreference(false),
            TestLauncher(idReply = { ScriptedProcess("uid=0(root)\n", 1) }),
        )
        assertEquals(RootState.DENIED, shell.setEnabled(true))
    }

    @Test
    fun `a uid that merely starts with 0 is not root`() = runBlocking {
        val shell = shell(
            MemoryRootPreference(false),
            TestLauncher(idReply = { ScriptedProcess("uid=0123(x)\n", 0) }),
        )
        assertEquals(RootState.DENIED, shell.setEnabled(true))
    }

    @Test
    fun `missing su binary is reported`() = runBlocking {
        val launcher = object : RootLauncher {
            override fun start(argv: List<String>): RootProcess = throw IOException("no such file")
        }
        val shell = shell(MemoryRootPreference(false), launcher)
        assertEquals(RootState.NO_SU, shell.setEnabled(true))
    }

    @Test
    fun `a root manager that never answers is denied after the verify timeout`() = runBlocking {
        val hanging = ScriptedProcess("", 0, hangs = true)
        val shell = shell(
            MemoryRootPreference(false),
            TestLauncher(idReply = { hanging }),
            verifyTimeoutMillis = 200,
        )
        assertEquals(RootState.DENIED, shell.setEnabled(true))
        assertTrue(hanging.destroyCount >= 1)
    }

    // --- esecuzione: stessi limiti, stesso quoting ---

    @Test
    fun `stdout stderr and exit code are separated`() = runBlocking {
        shellAvailable()
        val shell = active(MemoryRootPreference(true), TestLauncher())
        val result = shell.run(listOf("sh", "-c", "echo out; echo err >&2; exit 3"))
        assertEquals("out\n", result.stdoutText)
        assertEquals("err\n", result.stderrText)
        assertEquals(3, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.successful)
    }

    @Test
    fun `every argument stays a single quoted token`() = runBlocking {
        shellAvailable()
        val shell = active(MemoryRootPreference(true), TestLauncher())
        val arguments = listOf(
            "plain",
            "with space",
            "it's",
            "\$HOME `id` \$(id)",
            "semi;colon && pipe | redirect > file",
            "multi\nline",
            "*",
            "\"double\"",
            "",
            "back\\slash",
        )
        val result = shell.run(listOf("printf", "[%s]", *arguments.toTypedArray()))
        assertEquals(arguments.joinToString("") { "[$it]" }, result.stdoutText)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `output without trailing newline is exact`() = runBlocking {
        shellAvailable()
        val shell = active(MemoryRootPreference(true), TestLauncher())
        assertEquals("abc", shell.run(listOf("printf", "abc")).stdoutText)
        assertEquals("", shell.run(listOf("true")).stdoutText)
    }

    @Test
    fun `a command reading stdin does not eat the next commands`() = runBlocking {
        shellAvailable()
        val shell = active(MemoryRootPreference(true), TestLauncher())
        val first = shell.run(listOf("cat"), timeoutMillis = 5_000)
        assertEquals("", first.stdoutText)
        assertEquals(0, first.exitCode)
        assertEquals("after\n", shell.run(listOf("echo", "after")).stdoutText)
    }

    @Test
    fun `output over the cap is truncated and flagged`() = runBlocking {
        shellAvailable()
        val shell = active(MemoryRootPreference(true), TestLauncher())
        val result = shell.run(listOf("printf", "%s", "x".repeat(100)), maxOutputBytes = 10)
        assertEquals("x".repeat(10), result.stdoutText)
        assertTrue(result.truncated)
        assertEquals("after\n", shell.run(listOf("echo", "after")).stdoutText)
    }

    @Test
    fun `runToFile writes stdout to the destination`() = runBlocking {
        shellAvailable()
        val shell = active(MemoryRootPreference(true), TestLauncher())
        val destination = File.createTempFile("argus-root", ".bin")
        try {
            val result = shell.runToFile(listOf("printf", "png-bytes"), destination)
            assertEquals(0, result.exitCode)
            assertEquals("png-bytes", destination.readText())
            assertTrue(result.stdout.isEmpty())
        } finally {
            destination.delete()
        }
    }

    @Test
    fun `a command past its timeout is killed and reported and the next one works`() = runBlocking {
        shellAvailable()
        val launcher = TestLauncher()
        val shell = active(MemoryRootPreference(true), launcher)
        val result = shell.run(listOf("sleep", "20"), timeoutMillis = 300)
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertFalse(result.successful)
        // La sessione dopo un timeout si rifà: nessun residuo del comando scaduto nell'output successivo.
        assertEquals("next\n", shell.run(listOf("echo", "next")).stdoutText)
        assertEquals(2, launcher.sessions.size)
    }

    @Test
    fun `a su that stops answering times out and the session is thrown away`() = runBlocking {
        val silent = ScriptedProcess("", 0, hangs = true)
        val launcher = TestLauncher(session = { silent })
        val shell = active(MemoryRootPreference(true), launcher, graceMillis = 200)
        val result = shell.run(listOf("echo", "never"), timeoutMillis = 200)
        assertTrue(result.timedOut)
        assertTrue(silent.destroyCount >= 1)
    }

    @Test
    fun `a dead session is restarted on the next command`() = runBlocking {
        shellAvailable()
        val launcher = TestLauncher()
        val shell = active(MemoryRootPreference(true), launcher)
        assertEquals("one\n", shell.run(listOf("echo", "one")).stdoutText)
        launcher.sessions.last().destroy()
        withTimeout(5_000) { while (launcher.sessions.last().isAlive()) kotlinx.coroutines.delay(20) }
        assertEquals("two\n", shell.run(listOf("echo", "two")).stdoutText)
        assertEquals(2, launcher.sessions.size)
    }

    @Test
    fun `concurrent commands run one at a time with intact output`() = runBlocking {
        shellAvailable()
        val shell = active(MemoryRootPreference(true), TestLauncher())
        val results = (1..4).map { index ->
            async(Dispatchers.Default) {
                shell.run(listOf("sh", "-c", "echo start-$index; sleep 0.1; echo end-$index"))
            }
        }.awaitAll()
        results.forEachIndexed { position, result ->
            val index = position + 1
            assertEquals("start-$index\nend-$index\n", result.stdoutText)
        }
    }

    @Test
    fun `quote helper escapes single quotes`() {
        assertEquals("'it'\\''s'", posixQuote("it's"))
        assertEquals("''", posixQuote(""))
    }
}
