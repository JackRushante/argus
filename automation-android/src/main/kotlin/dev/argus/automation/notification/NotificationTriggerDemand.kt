package dev.argus.automation.notification

import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Trigger
import dev.argus.engine.notification.ParsedNotification
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.DraftValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Quali package possono interessare alle regole armate con un trigger notifica. Ogni notifica di
 * qualsiasi app (il navigatore che si aggiorna ogni secondo) faceva partire il servizio in
 * primo piano e un controllo completo delle regole anche senza una sola regola sulle notifiche.
 *
 * Tiene in memoria l'insieme dei package filtrati dai trigger notifica armati (sola lettura del
 * flow delle regole). Un trigger senza package vale per qualsiasi package. Fail-open finché il
 * flow non ha emesso la prima volta e se il flow fallisce: meglio un lavoro in più che una
 * notifica persa. Le risposte generative (WhatsApp) sono regole con trigger notifica sul package
 * di WhatsApp, quindi restano coperte.
 */
class NotificationTriggerDemand(
    automations: Flow<List<Automation>>,
    scope: CoroutineScope,
) {
    @Volatile private var demand: Demand = Demand.Everything

    init {
        scope.launch {
            automations
                .catch { error ->
                    if (error is CancellationException) throw error
                    demand = Demand.Everything
                }
                .collect { rules -> demand = Demand.of(rules) }
        }
    }

    /** Vero se almeno una regola armata con trigger notifica può riguardare [packageName]. */
    fun mayConcern(packageName: String): Boolean = demand.covers(packageName)

    private sealed interface Demand {
        fun covers(packageName: String): Boolean

        data object Everything : Demand {
            override fun covers(packageName: String): Boolean = true
        }

        data class Packages(val packages: Set<String>) : Demand {
            override fun covers(packageName: String): Boolean = packageName in packages
        }

        companion object {
            fun of(rules: List<Automation>): Demand {
                val packages = linkedSetOf<String>()
                for (rule in rules) {
                    if (rule.status != AutomationStatus.ARMED || !rule.enabled) continue
                    val trigger = rule.trigger as? Trigger.Notification ?: continue
                    if (trigger.pkg.isBlank()) return Everything
                    packages += trigger.pkg
                }
                return Packages(packages)
            }
        }
    }
}

/**
 * Cosa fare di una notifica già interpretata: il lavoro (servizio in primo piano più controllo
 * delle regole) parte solo se [demand] dice che una regola armata può riguardarla.
 *
 * Eccezione voluta: una conversazione WhatsApp osservata alimenta il selettore dei contatti
 * (whitelist) anche quando non esiste ancora nessuna regola, quindi in quel caso parte solo il
 * salvataggio della conversazione, senza controllo delle regole.
 */
class NotificationWorkGate(
    private val demand: NotificationTriggerDemand,
    /** Avvia lavoro sotto la lease condivisa (`ReceiverWorkLauncher.launch`). */
    private val startWork: (block: suspend () -> Unit) -> Unit,
    private val persistAndDispatch: suspend (ParsedNotification) -> Unit,
    private val persistObserved: suspend (ParsedNotification) -> Unit,
) {
    fun onParsed(parsed: ParsedNotification) {
        val packageName = (parsed.envelope.event as? TriggerEvent.NotificationPosted)?.pkg
        if (packageName == null || demand.mayConcern(packageName)) {
            startWork { persistAndDispatch(parsed) }
            return
        }
        val observed = parsed.observedConversation
        if (observed != null && observed.packageName in DraftValidator.WHATSAPP_PACKAGES) {
            startWork { persistObserved(parsed) }
        }
    }
}
