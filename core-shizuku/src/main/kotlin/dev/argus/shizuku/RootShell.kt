package dev.argus.shizuku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Stato del backend root (`su`).
 * - [OFF]: interruttore spento (o mai acceso): Argus non chiama `su`.
 * - [CHECKING]: interruttore acceso, `su -c id` in corso (il gestore root può chiedere il consenso).
 * - [ACTIVE]: `su` ha risposto uid 0: i comandi privilegiati possono passare di qui.
 * - [DENIED]: `su` esiste ma non ha dato uid 0 (consenso negato, scaduto o altro utente).
 * - [NO_SU]: nessun `su` eseguibile.
 */
enum class RootState { OFF, CHECKING, ACTIVE, DENIED, NO_SU }

/** Interruttore «Usa root (su)» persistito. Spento di default. */
interface RootPreference {
    fun isEnabled(): Boolean

    fun setEnabled(enabled: Boolean)
}

/** Superficie del backend root che usano i ViewModel e il wiring. */
interface RootAccess {
    fun observeState(): StateFlow<RootState>

    fun isEnabled(): Boolean

    /** Vero solo con interruttore acceso E uid 0 verificato. */
    fun isActive(): Boolean = observeState().value == RootState.ACTIVE

    /** Salva l'interruttore; acceso verifica con `su -c id`, spento chiude la sessione `su`. */
    suspend fun setEnabled(enabled: Boolean): RootState

    /** Ripete la verifica. Con l'interruttore spento non chiama `su` e restituisce [RootState.OFF]. */
    suspend fun verify(): RootState
}

internal interface RootProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream

    fun waitFor(timeoutMillis: Long): Boolean

    fun exitValue(): Int

    fun isAlive(): Boolean

    fun destroy()
}

internal fun interface RootLauncher {
    @Throws(IOException::class)
    fun start(argv: List<String>): RootProcess
}

internal object SuLauncher : RootLauncher {
    override fun start(argv: List<String>): RootProcess {
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        return object : RootProcess {
            override val stdin: OutputStream get() = process.outputStream
            override val stdout: InputStream get() = process.inputStream
            override val stderr: InputStream get() = process.errorStream
            override fun waitFor(timeoutMillis: Long): Boolean =
                process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)

            override fun exitValue(): Int = process.exitValue()
            override fun isAlive(): Boolean = process.isAlive
            override fun destroy() {
                runCatching { process.destroyForcibly() }
            }
        }
    }
}

/** Quoting POSIX di un argomento: un unico token, qualunque cosa contenga (`$`, `;`, spazi, a capo). */
internal fun posixQuote(argument: String): String =
    "'" + argument.replace("'", "'\\''") + "'"

/**
 * Riga di script per la shell `su` persistente. Il comando gira con stdin su `/dev/null` (non può
 * mangiare le righe successive), sotto `timeout -s KILL` se esiste, e finisce con un marcatore
 * casuale su stdout (con l'exit code) e su stderr: l'output è tutto ciò che precede il marcatore.
 */
internal fun rootScript(command: List<String>, timeoutMillis: Long, token: String): String {
    val quoted = command.joinToString(" ") { posixQuote(it) }
    val seconds = String.format(Locale.ROOT, "%d.%03d", timeoutMillis / 1000, timeoutMillis % 1000)
    return "{ if command -v timeout >/dev/null 2>&1; then timeout -s KILL $seconds $quoted; " +
        "else $quoted; fi; } </dev/null; __argus_rc=\$?; " +
        "printf '%s:%d\\n' '$token' \"\$__argus_rc\"; printf '%s\\n' '$token' >&2\n"
}

private class SessionDeadException : IOException("su session ended")

private class LimitedSink(private val out: OutputStream, private val limit: Int) {
    private var written = 0
    var truncated = false
        private set

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        val accepted = minOf(length, limit - written)
        if (accepted > 0) {
            out.write(buffer, offset, accepted)
            written += accepted
        }
        if (accepted < length) truncated = true
    }
}

/** Separa l'output dal marcatore finale anche se il marcatore arriva spezzato tra due letture. */
private class MarkerScanner(token: String, private val sink: LimitedSink) {
    private val marker = token.toByteArray(Charsets.US_ASCII)
    private var pending = ByteArray(0)
    private val trailer = ByteArrayOutputStream()
    var done = false
        private set

    fun feed(chunk: ByteArray) {
        if (done) {
            trailer.write(chunk)
            return
        }
        val buffer = pending + chunk
        val index = indexOf(buffer)
        if (index >= 0) {
            sink.write(buffer, 0, index)
            trailer.write(buffer, index + marker.size, buffer.size - index - marker.size)
            pending = ByteArray(0)
            done = true
            return
        }
        val keep = minOf(buffer.size, marker.size - 1)
        sink.write(buffer, 0, buffer.size - keep)
        pending = buffer.copyOfRange(buffer.size - keep, buffer.size)
    }

    /** `:<exit code>\n` dopo il marcatore su stdout; `null` finché la riga non è completa. */
    fun exitCode(): Int? {
        val text = trailer.toString(Charsets.US_ASCII.name())
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        return text.substring(0, newline).removePrefix(":").trim().toIntOrNull() ?: PROTOCOL_ERROR_EXIT
    }

    private fun indexOf(buffer: ByteArray): Int {
        outer@ for (start in 0..buffer.size - marker.size) {
            for (offset in marker.indices) {
                if (buffer[start + offset] != marker[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    companion object {
        const val PROTOCOL_ERROR_EXIT = -127
    }
}

private class Chunk(val isErr: Boolean, val data: ByteArray)

/** Un processo `su` persistente e i due thread che ne leggono stdout e stderr. */
private class RootSession(
    private val process: RootProcess,
    private val now: () -> Long,
    private val graceMillis: Long,
) {
    private val chunks = LinkedBlockingQueue<Chunk>()
    private val random = SecureRandom()

    init {
        pump(process.stdout, false)
        pump(process.stderr, true)
    }

    private fun pump(stream: InputStream, isErr: Boolean) {
        thread(start = true, isDaemon = true, name = "argus-su-${if (isErr) "stderr" else "stdout"}") {
            val buffer = ByteArray(8 * 1024)
            try {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 0) chunks.put(Chunk(isErr, buffer.copyOf(count)))
                }
            } catch (_: IOException) {
                // Il processo è chiuso o distrutto: vale come fine del flusso.
            }
            chunks.put(Chunk(isErr, ByteArray(0)))
        }
    }

    @Volatile
    private var poisoned = false

    fun isAlive(): Boolean = !poisoned && process.isAlive()

    fun destroy() {
        runCatching { process.stdin.close() }
        process.destroy()
    }

    /**
     * Esegue un comando. L'output standard va in [stdoutTarget] (limitato a `maxOutputBytes`); lo
     * stderr è restituito nel risultato. [isActive] falso = coroutine cancellata.
     */
    fun execute(
        request: ShellRequest,
        stdoutTarget: OutputStream,
        isActive: () -> Boolean,
    ): ShellResult {
        val token = newToken()
        val started = now()
        val deadline = started + request.timeoutMillis + graceMillis
        val stderrBytes = ByteArrayOutputStream()
        val outSink = LimitedSink(stdoutTarget, request.maxOutputBytes)
        val errSink = LimitedSink(stderrBytes, minOf(request.maxOutputBytes, MAX_STDERR_BYTES))
        val outScan = MarkerScanner(token, outSink)
        val errScan = MarkerScanner(token, errSink)
        try {
            process.stdin.write(
                rootScript(request.command, request.timeoutMillis, token).toByteArray(Charsets.UTF_8),
            )
            process.stdin.flush()
        } catch (error: IOException) {
            throw SessionDeadException()
        }
        while (!(outScan.done && outScan.exitCode() != null && errScan.done)) {
            if (!isActive()) throw CancellationException("su command cancelled")
            val remaining = deadline - now()
            if (remaining <= 0) {
                // Nessun marcatore entro timeout + tolleranza: il `su` non risponde, la sessione è da buttare.
                poisoned = true
                destroy()
                return ShellResult(
                    exitCode = EXIT_TIMEOUT,
                    stderr = stderrBytes.toByteArray(),
                    timedOut = true,
                    truncated = outSink.truncated || errSink.truncated,
                )
            }
            val chunk = chunks.poll(minOf(remaining, POLL_SLICE_MILLIS), TimeUnit.MILLISECONDS) ?: continue
            if (chunk.data.isEmpty()) throw SessionDeadException()
            (if (chunk.isErr) errScan else outScan).feed(chunk.data)
        }
        val code = outScan.exitCode() ?: MarkerScanner.PROTOCOL_ERROR_EXIT
        val timedOut = now() - started >= request.timeoutMillis && code in INNER_TIMEOUT_EXIT_CODES
        return ShellResult(
            exitCode = if (timedOut) EXIT_TIMEOUT else code,
            stderr = stderrBytes.toByteArray(),
            timedOut = timedOut,
            truncated = outSink.truncated || errSink.truncated,
            errorCode = if (code == MarkerScanner.PROTOCOL_ERROR_EXIT) "root_protocol" else null,
        )
    }

    private fun newToken(): String {
        val bytes = ByteArray(8).also(random::nextBytes)
        return "__ARGUS_" + bytes.joinToString("") { "%02x".format(it) } + "__"
    }

    private companion object {
        const val POLL_SLICE_MILLIS = 100L
        const val MAX_STDERR_BYTES = 64 * 1024
        const val EXIT_TIMEOUT = -1
        val INNER_TIMEOUT_EXIT_CODES = setOf(124, 137)
    }
}

/**
 * Backend root: un `su` persistente che esegue i comandi privilegiati quando Shizuku non c'è. È
 * un'altra implementazione di [PrivilegedShell]: stessa coda single-writer e priorità
 * ([PrioritizedPrivilegedShell]), stessi limiti di timeout e di output, argomenti quotati uno a uno.
 *
 * Regole d'oro: nessuna chiamata a `su` con l'interruttore ([RootPreference]) spento, mai all'avvio
 * dell'app se è spento; con l'interruttore acceso `su -c id` verifica uid 0 e solo allora il backend
 * è [RootState.ACTIVE]. I fallimenti sono [ShizukuShellException] con i codici di sempre
 * (`shizuku_unavailable`, `shizuku_timeout`).
 */
class RootPrivilegedShell internal constructor(
    private val preference: RootPreference,
    scope: CoroutineScope,
    private val launcher: RootLauncher,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    private val verifyTimeoutMillis: Long,
    private val graceMillis: Long,
) : PrivilegedShell, RootAccess, AutoCloseable {
    constructor(preference: RootPreference, scope: CoroutineScope) : this(
        preference = preference,
        scope = scope,
        launcher = SuLauncher,
        io = Dispatchers.IO,
        now = { System.nanoTime() / NANOS_PER_MILLI },
        verifyTimeoutMillis = VERIFY_TIMEOUT_MILLIS,
        graceMillis = GRACE_MILLIS,
    )

    private val state = MutableStateFlow(if (preference.isEnabled()) RootState.CHECKING else RootState.OFF)
    private val verifyMutex = Mutex()
    private val sessionLock = Any()
    private var session: RootSession? = null
    private val queue = PrioritizedPrivilegedShell(RootTransport(), scope)

    init {
        // Solo con l'interruttore già acceso: spento, all'avvio non parte nessun `su`.
        if (preference.isEnabled()) scope.launch { verify() }
    }

    override fun observeState(): StateFlow<RootState> = state.asStateFlow()

    override fun isEnabled(): Boolean = preference.isEnabled()

    override suspend fun setEnabled(enabled: Boolean): RootState {
        preference.setEnabled(enabled)
        if (!enabled) {
            dropSession()
            state.value = RootState.OFF
            return RootState.OFF
        }
        return verify()
    }

    override suspend fun verify(): RootState = verifyMutex.withLock {
        if (!preference.isEnabled()) {
            state.value = RootState.OFF
            return@withLock RootState.OFF
        }
        state.value = RootState.CHECKING
        val result = try {
            withContext(io) { probeSu() }
        } catch (error: CancellationException) {
            state.value = if (preference.isEnabled()) RootState.DENIED else RootState.OFF
            throw error
        }
        // L'interruttore può essere stato spento mentre il gestore root chiedeva il consenso.
        val final = if (preference.isEnabled()) result else RootState.OFF
        if (final != RootState.ACTIVE) dropSession()
        state.value = final
        final
    }

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = queue.run(command, priority, timeoutMillis, maxOutputBytes, executionId)

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = queue.runToFile(
        command,
        destination,
        priority,
        timeoutMillis,
        maxOutputBytes,
        executionId,
    )

    override fun close() {
        queue.close()
        dropSession()
    }

    /** `su -c id`: ACTIVE solo se risponde uid 0. */
    private fun probeSu(): RootState {
        val process = try {
            launcher.start(listOf(SU, "-c", "id"))
        } catch (_: Exception) {
            return RootState.NO_SU
        }
        return try {
            runCatching { process.stdin.close() }
            val output = ByteArrayOutputStream()
            val readers = listOf(
                thread(start = true, isDaemon = true, name = "argus-su-verify-out") {
                    readBounded(process.stdout, output)
                },
                thread(start = true, isDaemon = true, name = "argus-su-verify-err") {
                    readBounded(process.stderr, null)
                },
            )
            if (!process.waitFor(verifyTimeoutMillis)) return RootState.DENIED
            readers.forEach { it.join(READER_JOIN_MILLIS) }
            val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
            if (process.exitValue() == 0 && UID_ZERO.containsMatchIn(text)) {
                RootState.ACTIVE
            } else {
                RootState.DENIED
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            RootState.DENIED
        } finally {
            process.destroy()
        }
    }

    private fun readBounded(stream: InputStream, sink: ByteArrayOutputStream?) {
        val buffer = ByteArray(1024)
        var total = 0
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (sink != null && total < VERIFY_OUTPUT_LIMIT) {
                    synchronized(sink) { sink.write(buffer, 0, minOf(count, VERIFY_OUTPUT_LIMIT - total)) }
                    total += count
                }
            }
        } catch (_: IOException) {
            // Processo chiuso: basta quanto letto.
        }
    }

    private fun obtainSession(): RootSession = synchronized(sessionLock) {
        session?.takeIf { it.isAlive() }?.let { return it }
        session?.destroy()
        session = null
        val process = try {
            launcher.start(listOf(SU))
        } catch (error: Exception) {
            state.value = RootState.NO_SU
            throw ShizukuShellException(ShizukuShellException.UNAVAILABLE, error)
        }
        RootSession(process, now, graceMillis).also { session = it }
    }

    private fun dropSession(expected: RootSession? = null) = synchronized(sessionLock) {
        val current = session ?: return@synchronized
        if (expected != null && current !== expected) return@synchronized
        session = null
        current.destroy()
    }

    private inner class RootTransport : ShellTransport {
        override suspend fun execute(request: ShellRequest): ShellResult = withContext(io) {
            if (!preference.isEnabled() || state.value != RootState.ACTIVE) {
                throw ShizukuShellException(ShizukuShellException.UNAVAILABLE)
            }
            val job = currentCoroutineContext()[Job]
            val isActive = { job?.isActive != false }
            val active = obtainSession()
            val result = try {
                runOnSession(active, request, isActive)
            } catch (error: CancellationException) {
                dropSession(active)
                throw error
            } catch (error: IOException) {
                // Anche SessionDeadException: il `su` è morto o non risponde più.
                dropSession(active)
                throw ShizukuShellException(ShizukuShellException.UNAVAILABLE, error)
            }
            // Dopo un timeout lo stato del `su` persistente non è più affidabile (figli orfani che
            // scrivono ancora): lo si rifà al comando successivo.
            if (result.timedOut || !active.isAlive()) dropSession(active)
            result
        }

        private fun runOnSession(
            active: RootSession,
            request: ShellRequest,
            isActive: () -> Boolean,
        ): ShellResult {
            val destination = request.destination
            if (destination == null) {
                val buffer = ByteArrayOutputStream()
                return active.execute(request, buffer, isActive).copy(stdout = buffer.toByteArray())
            }
            destination.parentFile?.mkdirs()
            return FileOutputStream(destination).use { file -> active.execute(request, file, isActive) }
        }
    }

    internal companion object {
        const val SU = "su"
        const val NANOS_PER_MILLI = 1_000_000L
        const val VERIFY_TIMEOUT_MILLIS = 30_000L
        const val GRACE_MILLIS = 5_000L
        private const val VERIFY_OUTPUT_LIMIT = 4 * 1024
        private const val READER_JOIN_MILLIS = 1_000L
        private val UID_ZERO = Regex("""\buid=0\b""")
    }
}
