package dev.argus.automation.phone

import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import kotlinx.coroutines.CancellationException

/** Motivo nel Log di una regola col filtro SIM che non è scattata perché lo slot dell'evento è ignoto. */
const val SIM_UNKNOWN_REASON = "sim_unknown"

/**
 * Fail-closed del filtro SIM, con una traccia. Il matcher rifiuta già ogni evento il cui slot è
 * ignoto (`sim == null`): qui si scrive soltanto, per le regole armate col filtro che altrimenti
 * sarebbero scattate (stesso evento, stesso numero e testo), un `BLOCKED_POLICY` / `sim_unknown`,
 * così «perché non è scattata?» ha una risposta. Poi l'evento prosegue invariato verso il motore.
 */
class SimAuditingPhoneEventDispatcher(
    private val delegate: PhoneEventDispatcher,
    private val store: AutomationStore,
    private val audit: AuditSink,
    private val matcher: TriggerMatcher = TriggerMatcher(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PhoneEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        val event = envelope.event
        if (event is TriggerEvent.PhoneStateChanged && event.sim == null) {
            recordUnknownSim(envelope, event)
        }
        delegate.dispatch(envelope)
    }

    private suspend fun recordUnknownSim(envelope: TriggerEnvelope, event: TriggerEvent.PhoneStateChanged) {
        try {
            store.armed().forEach { automation ->
                val trigger = automation.trigger as? Trigger.PhoneState ?: return@forEach
                if (trigger.sim == null) return@forEach
                // Scatterebbe se lo slot fosse noto: solo allora il filtro SIM è la causa.
                if (!matcher.matches(trigger.copy(sim = null), event)) return@forEach
                audit.record(
                    AuditEvent(
                        automationId = automation.id,
                        kind = AuditKind.BLOCKED_POLICY,
                        atMillis = nowMillis(),
                        detail = SIM_UNKNOWN_REASON,
                        eventId = envelope.id,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il Log non deve fermare la consegna dell'evento.
        }
    }
}
