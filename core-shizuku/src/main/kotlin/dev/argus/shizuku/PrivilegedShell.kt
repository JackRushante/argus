package dev.argus.shizuku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

data class ShellResult(
    val exitCode: Int,
    val stdout: ByteArray = byteArrayOf(),
    val stderr: ByteArray = byteArrayOf(),
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val errorCode: String? = null,
    /** Correlazione locale: non viene inviata al processo remoto né inclusa nei log. */
    val executionId: String? = null,
) {
    val stdoutText: String get() = stdout.toString(StandardCharsets.UTF_8)
    val stderrText: String get() = stderr.toString(StandardCharsets.UTF_8)
    val successful: Boolean get() = exitCode == 0 && !timedOut && errorCode == null
}

interface PrivilegedShell {
    suspend fun run(
        command: List<String>,
        priority: Int = 0,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        maxOutputBytes: Int = DEFAULT_TEXT_OUTPUT_BYTES,
        executionId: String? = null,
    ): ShellResult

    /** Scrive stdout su un FD aperto dall'app: evita il limite di transazione Binder sui PNG. */
    suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int = 0,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        maxOutputBytes: Int = DEFAULT_FILE_OUTPUT_BYTES,
        executionId: String? = null,
    ): ShellResult

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_TEXT_OUTPUT_BYTES = 256 * 1024
        const val DEFAULT_FILE_OUTPUT_BYTES = 16 * 1024 * 1024
        const val MAX_TIMEOUT_MILLIS = 120_000L
    }
}

internal data class ShellRequest(
    val command: List<String>,
    val timeoutMillis: Long,
    val maxOutputBytes: Int,
    val destination: File?,
    val executionId: String?,
) {
    init {
        require(command.isNotEmpty()) { "Comando vuoto" }
        require(command.size <= 128) { "Troppi argomenti" }
        require(command.sumOf { it.length } <= 64 * 1024) { "Comando troppo lungo" }
        require(timeoutMillis in 1..PrivilegedShell.MAX_TIMEOUT_MILLIS) { "Timeout non valido" }
        val cap = if (destination == null) {
            PrivilegedShell.DEFAULT_TEXT_OUTPUT_BYTES
        } else {
            PrivilegedShell.DEFAULT_FILE_OUTPUT_BYTES
        }
        require(maxOutputBytes in 1..cap) { "Output cap non valido" }
        if (executionId != null) {
            require(executionId.isNotBlank()) { "Execution ID vuoto" }
            require(executionId.length <= 128) { "Execution ID troppo lungo" }
        }
    }
}

internal fun interface ShellTransport {
    suspend fun execute(request: ShellRequest): ShellResult
}

/**
 * Actor single-writer. Non interrompe il comando già partito; tra quelli in attesa sceglie
 * priorità decrescente e, a parità, FIFO.
 */
internal class PrioritizedPrivilegedShell(
    private val transport: ShellTransport,
    scope: CoroutineScope,
) : PrivilegedShell, AutoCloseable {
    private data class Pending(
        val priority: Int,
        val sequence: Long,
        val request: ShellRequest,
        val result: CompletableDeferred<ShellResult>,
    )

    private val lock = Any()
    private val queue = PriorityQueue(
        compareByDescending<Pending> { it.priority }.thenBy { it.sequence },
    )
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val sequences = AtomicLong()
    @Volatile private var closed = false
    private val worker: Job = scope.launch { workLoop() }

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = enqueue(
        ShellRequest(
            command.toList(),
            timeoutMillis,
            maxOutputBytes,
            destination = null,
            executionId = executionId,
        ),
        priority,
    )

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = enqueue(
        ShellRequest(command.toList(), timeoutMillis, maxOutputBytes, destination, executionId),
        priority,
    )

    private suspend fun enqueue(request: ShellRequest, priority: Int): ShellResult {
        val deferred = CompletableDeferred<ShellResult>()
        synchronized(lock) {
            check(!closed) { "PrivilegedShell chiusa" }
            queue += Pending(priority, sequences.getAndIncrement(), request, deferred)
        }
        wake.trySend(Unit)
        try {
            return deferred.await()
        } catch (error: CancellationException) {
            deferred.cancel(error)
            throw error
        }
    }

    private suspend fun workLoop() {
        try {
            for (ignored in wake) {
                while (true) {
                    val pending = synchronized(lock) {
                        generateSequence { queue.poll() }.firstOrNull { it.result.isActive }
                    } ?: break
                    try {
                        val result = transport.execute(pending.request).copy(
                            executionId = pending.request.executionId,
                        )
                        pending.result.complete(result)
                    } catch (error: CancellationException) {
                        pending.result.cancel(error)
                        throw error
                    } catch (_: Exception) {
                        pending.result.completeExceptionally(
                            IllegalStateException("Trasporto Shizuku non disponibile"),
                        )
                    }
                }
            }
        } finally {
            synchronized(lock) {
                while (queue.isNotEmpty()) {
                    queue.remove().result.cancel(CancellationException("PrivilegedShell chiusa"))
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            while (queue.isNotEmpty()) {
                queue.remove().result.cancel(CancellationException("PrivilegedShell chiusa"))
            }
        }
        wake.close()
        worker.cancel()
    }
}
