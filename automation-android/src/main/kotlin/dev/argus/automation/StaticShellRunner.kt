package dev.argus.automation

import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.ProgramActionResult
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult

fun interface StaticShellRunner {
    suspend fun run(command: String, context: FireContext): ActionResult

    /** Default chiuso per fake/implementazioni legacy: catturare richiede un contratto esplicito. */
    suspend fun runCaptured(command: String, context: FireContext): ProgramActionResult =
        ProgramActionResult(ActionResult.Failure("shell_capture_unavailable"))
}

/** Esegue soltanto il comando letterale già incluso nello snapshot approvato e fingerprintato. */
class ShizukuStaticShellRunner(
    private val shell: PrivilegedShell,
) : StaticShellRunner {
    override suspend fun run(command: String, context: FireContext): ActionResult {
        require(command.isNotBlank() && command.length <= MAX_COMMAND_CHARS && '\u0000' !in command)
        val result = shell.run(
            command = listOf(SYSTEM_SHELL, "-c", command),
            priority = context.priority,
            timeoutMillis = SHELL_TIMEOUT_MILLIS,
            maxOutputBytes = MAX_IGNORED_OUTPUT_BYTES,
            executionId = context.executionId.value,
        )
        return when {
            result.timedOut -> ActionResult.Failure("shell_timeout")
            !result.successful -> ActionResult.Failure(result.failureCode())
            else -> ActionResult.Success
        }
    }

    override suspend fun runCaptured(command: String, context: FireContext): ProgramActionResult {
        require(command.isNotBlank() && command.length <= MAX_COMMAND_CHARS && '\u0000' !in command)
        val result = shell.run(
            command = listOf(SYSTEM_SHELL, "-c", command),
            priority = context.priority,
            timeoutMillis = SHELL_TIMEOUT_MILLIS,
            maxOutputBytes = MAX_CAPTURED_OUTPUT_BYTES,
            executionId = context.executionId.value,
        )
        return when {
            result.timedOut -> ProgramActionResult(ActionResult.Failure("shell_timeout"))
            result.truncated -> ProgramActionResult(ActionResult.Failure("shell_output_too_large"))
            !result.successful -> ProgramActionResult(ActionResult.Failure(result.failureCode()))
            else -> ProgramActionResult(ActionResult.Success, capturedText = result.stdoutText)
        }
    }

    /**
     * Fallimento shell diagnosticabile: un errore di trasporto (Binder/gateway) resta "shell_failed",
     * ma un exit code non-zero diventa "shell_failed_exit_<n>" così un comando allucinato è
     * riconoscibile (127 = command not found, 126 = not executable, ...).
     */
    private fun ShellResult.failureCode(): String =
        if (errorCode != null) "shell_failed" else "shell_failed_exit_$exitCode"

    private companion object {
        const val SYSTEM_SHELL = "/system/bin/sh"
        const val MAX_COMMAND_CHARS = 8_192
        const val SHELL_TIMEOUT_MILLIS = 30_000L
        const val MAX_IGNORED_OUTPUT_BYTES = 16 * 1_024
        const val MAX_CAPTURED_OUTPUT_BYTES = 64 * 1_024
    }
}
