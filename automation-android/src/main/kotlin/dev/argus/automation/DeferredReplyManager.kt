package dev.argus.automation

import dev.argus.data.DeferredReplyStore
import kotlinx.coroutines.CancellationException

/** Testo effimero pronto alla consegna manuale: mai una data class, mai in log o audit. */
class DeliverableReply internal constructor(
    val text: String,
    val packageName: String,
    internal val executionId: String,
    internal val actionIndex: Int,
)

sealed interface DeferredReplyResolution {
    class Ready internal constructor(val reply: DeliverableReply) : DeferredReplyResolution

    /** Mai esistita, scaduta o già consumata: la CTA non ha più nulla da consegnare. */
    data object Unavailable : DeferredReplyResolution

    data object Failed : DeferredReplyResolution
}

/**
 * Risolve la CTA "Invia ora" (E13): il ciphertext viene decifrato soltanto dopo il tap e il
 * consumo è un CAS one-shot registrato quando il testo è stato davvero reso azionabile.
 */
class DeferredReplyManager(
    private val store: DeferredReplyStore,
    private val cipher: DeferredReplyCipher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun resolve(executionId: String): DeferredReplyResolution = try {
        val row = store.firstActionable(executionId, nowMillis())
        if (row == null) {
            DeferredReplyResolution.Unavailable
        } else {
            DeferredReplyResolution.Ready(
                DeliverableReply(
                    text = cipher.decrypt(row.payload),
                    packageName = row.packageName,
                    executionId = row.executionId,
                    actionIndex = row.actionIndex,
                ),
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        DeferredReplyResolution.Failed
    }

    /** Da chiamare dopo che il testo è stato reso azionabile; true solo al primo consumo. */
    suspend fun markDelivered(reply: DeliverableReply): Boolean = try {
        store.markConsumed(reply.executionId, reply.actionIndex, nowMillis())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}
