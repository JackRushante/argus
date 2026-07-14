package dev.argus.automation

import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.data.DeferredReplyStore
import dev.argus.engine.notification.ObservedConversationStore
import kotlinx.coroutines.CancellationException

sealed interface PrivacyRevocationResult {
    /** Consenso chiuso e dati locali osservati/differiti eliminati. */
    data object Revoked : PrivacyRevocationResult

    /** Consenso chiuso ma una purge è fallita: richiamare finché non resta residuo. */
    data object RevokedWithResidualData : PrivacyRevocationResult

    /** La preference non è stata scritta: nulla è cambiato. */
    data object Failed : PrivacyRevocationResult
}

/**
 * Revoca del consenso centralizzata e idempotente. L'ordine è parte del contratto: prima si
 * chiude il gate sincrono (ingress e readiness leggono lo StateFlow), poi si eliminano handle
 * RemoteInput, conversazioni osservate e reply differite cifrate. La whitelist contatti viene
 * conservata per scelta: è configurazione esplicita dell'utente, non un dato osservato, e le
 * regole generative armate finiscono comunque in NEEDS_REVIEW via reconcile.
 */
class PrivacyRevocationCoordinator(
    private val preferences: AppPreferencesStore,
    private val replyRegistry: ActiveNotificationReplyRegistry,
    private val observedConversations: ObservedConversationStore,
    private val deferredReplies: DeferredReplyStore,
) {
    suspend fun revoke(): PrivacyRevocationResult {
        val gateClosed = try {
            preferences.setPrivacyAccepted(false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!gateClosed) return PrivacyRevocationResult.Failed

        replyRegistry.clear()

        var residual = false
        try {
            observedConversations.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            residual = true
        }
        try {
            deferredReplies.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            residual = true
        }
        return if (residual) {
            PrivacyRevocationResult.RevokedWithResidualData
        } else {
            PrivacyRevocationResult.Revoked
        }
    }
}
