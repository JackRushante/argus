package dev.argus.automation.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationPause
import dev.argus.engine.model.Automation
import dev.argus.engine.runtime.CallScreeningDecider
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Le decisioni di `screen_call` prese dal servizio di filtro chiamate, in memoria e per poco: quando poi il
 * motore esegue la regola (le altre azioni girano dopo la decisione), la foglia `screen_call` trova qui la
 * decisione già applicata invece di fare qualcosa. Niente numeri: solo l'id della regola e l'istante.
 */
class ScreenCallDecisionLog(private val now: () -> Long = System::currentTimeMillis) {
    private val decisions = LinkedHashMap<AutomationId, Pair<ScreenCallOp, Long>>()

    @Synchronized
    fun record(automationId: AutomationId, op: ScreenCallOp) {
        decisions[automationId] = op to now()
        val oldest = now() - MAX_AGE_MILLIS
        decisions.entries.removeAll { it.value.second < oldest }
    }

    /** La decisione presa per la regola nell'ultimo paio di minuti, consumata (una sola esecuzione la trova). */
    @Synchronized
    fun consume(automationId: AutomationId): ScreenCallOp? {
        val (op, at) = decisions.remove(automationId) ?: return null
        return op.takeIf { now() - at <= MAX_AGE_MILLIS }
    }

    /** Esito della foglia `screen_call` nell'esecuzione della regola. */
    fun leafResult(automationId: AutomationId): ActionResult =
        if (consume(automationId) != null) ActionResult.Success else ActionResult.Failure(SCREEN_CALL_NOT_APPLIED)

    companion object {
        const val MAX_AGE_MILLIS = 2 * 60 * 1_000L
        const val SCREEN_CALL_NOT_APPLIED = "screen_call_not_applied"
    }
}

/** Se un numero è nei contatti: true, false, null = non si sa (permesso mancante, errore). */
fun interface ContactLookup {
    fun isContact(number: String): Boolean?
}

class AndroidContactLookup(context: Context) : ContactLookup {
    private val appContext = context.applicationContext

    override fun isContact(number: String): Boolean? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            appContext.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.count > 0 }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Cosa risponde il servizio di filtro chiamate (0.8). Legge le regole armate, le capacità e le pause, chiede al
 * [CallScreeningDecider] e registra ogni decisione nel Log SENZA il numero. Tutto entro [budgetMillis]: oltre
 * (o su qualunque errore) la risposta è «lascia squillare» e il Log lo dice per ogni regola di filtro.
 *
 * Una chiamata rifiutata non squilla, quindi Android non manda lo stato RINGING da cui partono le regole
 * «chiamata in arrivo»: dopo aver risposto [afterReject] la consegna al motore (le altre azioni della regola, e
 * le altre regole sulla chiamata in arrivo, girano come sempre). Una chiamata silenziata squilla senza suono
 * e arriva al motore dal solito broadcast.
 */
class CallScreeningCoordinator(
    /** Le regole armate (`AutomationStore.armed`). */
    private val armed: suspend () -> List<Automation>,
    private val snapshots: FirePolicySnapshotProvider,
    private val pause: AutomationPause,
    private val contacts: ContactLookup,
    private val decider: CallScreeningDecider,
    private val decisions: ScreenCallDecisionLog,
    private val audit: AuditSink,
    private val now: () -> Long = System::currentTimeMillis,
    private val budgetMillis: Long = BUDGET_MILLIS,
) {
    /** La risposta da dare alla chiamata: null = lasciala squillare. */
    suspend fun decide(number: String?): ScreenCallOp? {
        val decision = try {
            withTimeoutOrNull(budgetMillis) { evaluate(number) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (decision == null) {
            auditFailOpen()
            return null
        }
        decision.rules.forEach { rule ->
            decisions.record(rule.automationId, decision.op ?: rule.op)
            record(rule.automationId, detailFor(decision.op ?: rule.op))
        }
        return decision.op
    }

    private suspend fun evaluate(number: String?): CallScreeningDecider.Decision {
        val rules = decider.screeningRules(armed())
        if (rules.isEmpty()) return CallScreeningDecider.Decision(null, emptyList())
        val available = snapshots.current().availableCapabilities
        val paused = rules.filter { pause.pauseCodeFor(it.id) != null }.mapTo(mutableSetOf()) { it.id }
        return decider.decide(rules, number, available, { it.id in paused }, contacts::isContact)
    }

    private suspend fun auditFailOpen() {
        val rules = try {
            decider.screeningRules(armed())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        rules.forEach { record(it.id, SCREEN_CALL_FAILED_OPEN) }
    }

    private suspend fun record(id: AutomationId, detail: String) {
        try {
            audit.record(AuditEvent(id, AuditKind.CALL_SCREENED, now(), detail = detail))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il Log non cambia la risposta alla chiamata.
        }
    }

    companion object {
        /** Android aspetta la risposta per circa 5 secondi: si risponde ben prima. */
        const val BUDGET_MILLIS = 2_500L
        const val SCREEN_CALL_REJECTED = "screen_call_rejected"
        const val SCREEN_CALL_SILENCED = "screen_call_silenced"
        const val SCREEN_CALL_FAILED_OPEN = "screen_call_failed_open"

        fun detailFor(op: ScreenCallOp): String = when (op) {
            ScreenCallOp.REJECT -> SCREEN_CALL_REJECTED
            ScreenCallOp.SILENCE -> SCREEN_CALL_SILENCED
        }
    }
}
