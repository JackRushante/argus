package dev.argus.shizuku

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@Keep
class PrivilegedShellUserService() : IPrivilegedShellService.Stub() {
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun execute(
        command: Array<out String>?,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): Bundle {
        val stdout = ByteArrayOutputStream()
        return runCommand(command, timeoutMillis, maxOutputBytes, stdout, includeStdout = true)
    }

    override fun executeToFile(
        command: Array<out String>?,
        stdoutDestination: ParcelFileDescriptor?,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): Bundle {
        if (stdoutDestination == null) return errorBundle("destination_missing")
        return ParcelFileDescriptor.AutoCloseOutputStream(stdoutDestination).use { output ->
            runCommand(command, timeoutMillis, maxOutputBytes, output, includeStdout = false)
        }
    }

    @Synchronized
    private fun runCommand(
        rawCommand: Array<out String>?,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        stdoutDestination: OutputStream,
        includeStdout: Boolean,
    ): Bundle {
        val command = rawCommand?.toList().orEmpty()
        if (command.isEmpty() || command.size > MAX_ARGUMENTS ||
            command.sumOf { it.length } > MAX_COMMAND_CHARS
        ) return errorBundle("command_invalid")
        if (timeoutMillis !in 1..PrivilegedShell.MAX_TIMEOUT_MILLIS ||
            maxOutputBytes !in 1..PrivilegedShell.DEFAULT_FILE_OUTPUT_BYTES
        ) return errorBundle("limits_invalid")

        val stderr = ByteArrayOutputStream()
        val stdoutTruncated = AtomicBoolean(false)
        val stderrTruncated = AtomicBoolean(false)
        val process = try {
            ProcessBuilder(command)
                .directory(java.io.File("/"))
                .start()
        } catch (_: Exception) {
            return errorBundle("start_failed")
        }
        runCatching { process.outputStream.close() }

        val stdoutThread = drain(
            process.inputStream,
            stdoutDestination,
            maxOutputBytes,
            stdoutTruncated,
            "argus-shell-stdout",
        )
        val stderrThread = drain(
            process.errorStream,
            stderr,
            minOf(maxOutputBytes, MAX_STDERR_BYTES),
            stderrTruncated,
            "argus-shell-stderr",
        )

        val finished = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
        stdoutThread.join(JOIN_MILLIS)
        stderrThread.join(JOIN_MILLIS)
        if (stdoutThread.isAlive) runCatching { process.inputStream.close() }
        if (stderrThread.isAlive) runCatching { process.errorStream.close() }
        if (stdoutThread.isAlive) stdoutThread.join(JOIN_MILLIS)
        if (stderrThread.isAlive) stderrThread.join(JOIN_MILLIS)

        return Bundle().apply {
            putInt(KEY_EXIT_CODE, if (finished) process.exitValue() else EXIT_TIMEOUT)
            if (includeStdout && stdoutDestination is ByteArrayOutputStream) {
                putByteArray(KEY_STDOUT, stdoutDestination.toByteArray())
            }
            putByteArray(KEY_STDERR, stderr.toByteArray())
            putBoolean(KEY_TIMED_OUT, !finished)
            putBoolean(KEY_TRUNCATED, stdoutTruncated.get() || stderrTruncated.get())
        }
    }

    private fun drain(
        input: java.io.InputStream,
        output: OutputStream,
        limit: Int,
        truncated: AtomicBoolean,
        name: String,
    ) = thread(start = true, isDaemon = true, name = name) {
        val buffer = ByteArray(8 * 1024)
        var written = 0
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val accepted = minOf(count, limit - written)
                if (accepted > 0) {
                    output.write(buffer, 0, accepted)
                    written += accepted
                }
                if (accepted < count) truncated.set(true)
            }
            output.flush()
        } catch (_: Exception) {
            // Chiusura stream durante timeout/kill: lo stato timedOut è già nel risultato.
        }
    }

    private fun errorBundle(code: String) = Bundle().apply {
        putInt(KEY_EXIT_CODE, EXIT_INTERNAL_ERROR)
        putString(KEY_ERROR_CODE, code)
    }

    override fun uid(): Int = Process.myUid()

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }

    internal companion object {
        const val KEY_EXIT_CODE = "exit_code"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        const val KEY_TIMED_OUT = "timed_out"
        const val KEY_TRUNCATED = "truncated"
        const val KEY_ERROR_CODE = "error_code"
        private const val MAX_ARGUMENTS = 128
        private const val MAX_COMMAND_CHARS = 64 * 1024
        private const val MAX_STDERR_BYTES = 64 * 1024
        private const val EXIT_TIMEOUT = -1
        private const val EXIT_INTERNAL_ERROR = -127
        private const val JOIN_MILLIS = 2_000L
    }
}
