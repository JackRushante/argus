package dev.argus.automation.connectivity

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.NoopAuditSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConnectivityReconcileReport(
    val requiredBy: List<AutomationId>,
    val active: Boolean,
    val failed: List<AutomationId> = emptyList(),
    val cleanupSucceeded: Boolean = true,
)

fun interface ConnectivityTriggerRuntime {
    suspend fun reconcile(): ConnectivityReconcileReport
}

object NoopConnectivityTriggerRuntime : ConnectivityTriggerRuntime {
    override suspend fun reconcile() = ConnectivityReconcileReport(emptyList(), active = false)
}

interface ConnectivitySentinelBackend {
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
}

/**
 * Avviso all'utente quando il servizio in primo piano non può partire (Android 12+ lo nega se
 * l'app è in background): le regole Wi-Fi e alimentazione restano ferme fino alla riapertura
 * dell'app. [show] e [clear] sono idempotenti.
 */
interface ForegroundDeniedNotice {
    fun show()
    fun clear()
}

object NoopForegroundDeniedNotice : ForegroundDeniedNotice {
    override fun show() = Unit
    override fun clear() = Unit
}

/** Motivo del Log per le regole connettività ferme perché il servizio è stato negato (B20). */
internal const val FOREGROUND_SERVICE_DENIED_REASON = "foreground_service_denied"

/**
 * Accende un'unica sentinella condivisa solo per i medium non manifest-safe: Wi-Fi e POWER.
 *
 * Se la sentinella non parte (`start()` falso) le regole restano spente: lo si dice in due modi,
 * UNA volta per episodio e non a ogni tentativo. Un audit `SCHEDULING_FAILED` per ogni regola
 * connettività armata e una notifica «apri Argus per riattivarle». L'episodio finisce quando la
 * sentinella riparte (o non serve più): la notifica viene rimossa. Il riavvio dal primo piano c'è
 * già, perché `onForeground` riconcilia.
 */
class ConnectivitySentinelCoordinator(
    private val store: AutomationStore,
    private val backend: ConnectivitySentinelBackend,
    private val audit: AuditSink = NoopAuditSink,
    private val notice: ForegroundDeniedNotice = NoopForegroundDeniedNotice,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ConnectivityTriggerRuntime {
    private val mutex = Mutex()

    /** Regole già tracciate nell'episodio di rifiuto in corso; vuoto fuori da un episodio. */
    private val auditedInEpisode = mutableSetOf<AutomationId>()
    private var noticeShown = false

    override suspend fun reconcile(): ConnectivityReconcileReport = mutex.withLock {
        val required = store.armed()
            .filter { automation ->
                val medium = (automation.trigger as? Trigger.Connectivity)?.medium
                medium == ConnMedium.WIFI || medium == ConnMedium.POWER
            }
            .map { it.id }
            .sortedBy { it.value }

        if (required.isEmpty()) {
            val stopped = backend.stop()
            endEpisode()
            return@withLock ConnectivityReconcileReport(
                requiredBy = emptyList(),
                active = !stopped,
                cleanupSucceeded = stopped,
            )
        }

        val started = backend.start()
        if (started) {
            endEpisode()
        } else {
            reportDenied(required)
        }
        ConnectivityReconcileReport(
            requiredBy = required,
            active = started,
            failed = if (started) emptyList() else required,
        )
    }

    private fun endEpisode() {
        auditedInEpisode.clear()
        noticeShown = false
        // Sempre, anche senza un episodio in memoria: la notifica può venire da un processo
        // precedente e non deve restare appesa dopo che il servizio è ripartito.
        try {
            notice.clear()
        } catch (_: RuntimeException) {
            // La notifica non deve cambiare l'esito del reconcile.
        }
    }

    private suspend fun reportDenied(required: List<AutomationId>) {
        required.filter { it !in auditedInEpisode }.forEach { id ->
            recordDenied(id)
            auditedInEpisode += id
        }
        if (!noticeShown) {
            noticeShown = true
            try {
                notice.show()
            } catch (_: RuntimeException) {
                // Un avviso non mostrabile non cambia l'esito: il Log resta la traccia.
            }
        }
    }

    private suspend fun recordDenied(id: AutomationId) {
        try {
            audit.record(
                AuditEvent(
                    id,
                    AuditKind.SCHEDULING_FAILED,
                    nowMillis(),
                    detail = FOREGROUND_SERVICE_DENIED_REASON,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il Log non deve cambiare l'esito del reconcile.
        }
    }
}
