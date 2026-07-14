package dev.argus.automation.vm

import kotlinx.coroutines.CancellationException

/** `runCatching` inghiotte CancellationException: nelle pipeline Flow usiamo questo confine. */
internal suspend inline fun <T> cancellationSafeOrNull(crossinline block: suspend () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
