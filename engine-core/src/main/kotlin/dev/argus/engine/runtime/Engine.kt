package dev.argus.engine.runtime
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus

/** @param now fornitore di epoch-millis (iniettato per testabilità; su Android = System::currentTimeMillis). */
class Engine(
    private val store: AutomationStore,
    private val executor: ActionExecutor,
    private val evaluator: ConditionEvaluator,
    private val matcher: TriggerMatcher,
    private val audit: AuditSink = NoopAuditSink,
    private val now: () -> Long,
) {
    companion object {
        /** Tetto anti trigger-storm/ping-pong per le regole generative (spec §5/C2). */
        const val MIN_GENERATIVE_COOLDOWN_MS = 60_000L
    }

    data class FireOutcome(val automation: Automation, val actions: List<Action>, val results: List<ActionResult>)

    /** [stateProvider] è invocato LAZY (al più una volta per batch): gli eventi che non matchano
     *  nessuna regola armata non costano una lettura stato (spec §9, onestà batteria). */
    suspend fun onTrigger(event: TriggerEvent, stateProvider: suspend () -> DeviceState): List<FireOutcome> {
        val candidates = when (event) {
            is TriggerEvent.TimeFired -> listOfNotNull(store.get(event.automationId))
            is TriggerEvent.GeofenceTransitioned -> listOfNotNull(store.get(event.automationId))
            else -> store.armed()
        }.filter { it.status == AutomationStatus.ARMED && it.enabled }
            // priorità CRESCENTE: il più prioritario esegue ULTIMO e vince sui target condivisi (last-writer-wins, spec §5/C1)
            .sortedWith(compareBy({ it.priority }, { it.id.value }))

        var cached: DeviceState? = null
        suspend fun state(): DeviceState = cached ?: stateProvider().also { cached = it }

        val outcomes = mutableListOf<FireOutcome>()
        for (a in candidates) {
            try {
                if (!matcher.matches(a.trigger, event)) continue
                val cooldown = effectiveCooldown(a)
                if (cooldown > 0) {
                    val last = store.lastFiredAt(a.id)
                    if (last != null && now() - last < cooldown) {
                        audit.record(AuditEvent(a.id, AuditKind.SUPPRESSED_COOLDOWN, now())); continue
                    }
                }
                if (a.conditions != null && !evaluator.eval(a.conditions, state())) {
                    audit.record(AuditEvent(a.id, AuditKind.CONDITIONS_NOT_MET, now())); continue
                }
                val ctx = FireContext(event, state(), a.id)
                val results = a.actions.map { executor.execute(it, ctx) }   // Failure non interrompe la catena: esito PARTIAL nel log (spec §6)
                store.recordFired(a.id, now())
                audit.record(AuditEvent(a.id, AuditKind.FIRED, now(),
                    results.joinToString { it::class.simpleName ?: "?" }))
                outcomes += FireOutcome(a, a.actions, results)
            } catch (e: Exception) {
                audit.record(AuditEvent(a.id, AuditKind.ERROR, now(), e.message ?: "error"))
            }
        }
        return outcomes
    }

    private fun effectiveCooldown(a: Automation): Long =
        if (a.actions.any { it.tier == ActionTier.GENERATIVE }) maxOf(a.cooldownMs, MIN_GENERATIVE_COOLDOWN_MS)
        else a.cooldownMs
}
