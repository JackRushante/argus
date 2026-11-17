package dev.argus.automation.health

import dev.argus.automation.BlockCause
import dev.argus.engine.model.Automation
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.listenedTriggers

/**
 * Le cose di cui una regola armata può restare senza da un momento all'altro (0.7.2): ognuna ha il suo
 * avviso, al massimo uno al giorno, e si sistema dalla schermata Sistema.
 */
enum class HealthCapability(val key: String) {
    /** Shizuku, Sui o root `su`: comandi e azioni privilegiate. */
    PRIVILEGED_BACKEND("privileged"),
    /** Accesso alle notifiche: trigger notifica e risposte. */
    NOTIFICATION_LISTENER("notification_listener"),
    /** Servizio di Accessibilità di Argus: macro sullo schermo (`ui_steps`). */
    ACCESSIBILITY("accessibility"),
    /** Sveglie esatte: senza, le regole a orario «puntuali» possono partire in ritardo. */
    EXACT_ALARMS("exact_alarms"),
    /** Permesso di posizione: regole geofence (e identità Wi-Fi). */
    LOCATION("location"),
}

/**
 * Lettura dello stato per gli avvisi: per ogni capability richiesta e NON disponibile ora, la causa
 * (stessa logica della diagnosi del Dettaglio), più le sveglie esatte, che non sono una capability.
 */
data class CapabilityHealthSnapshot(
    val causes: Map<String, BlockCause>,
    val exactAlarmsGranted: Boolean,
)

object CapabilityHealth {
    /** La cosa da sistemare dietro una causa di blocco; null = causa che questi avvisi non coprono. */
    fun of(cause: BlockCause): HealthCapability? = when (cause) {
        BlockCause.SHIZUKU_NOT_INSTALLED,
        BlockCause.SHIZUKU_NOT_RUNNING,
        BlockCause.SHIZUKU_NOT_AUTHORIZED,
        BlockCause.SHIZUKU_TOO_OLD,
        -> HealthCapability.PRIVILEGED_BACKEND
        BlockCause.NOTIFICATION_ACCESS -> HealthCapability.NOTIFICATION_LISTENER
        BlockCause.ACCESSIBILITY_SERVICE -> HealthCapability.ACCESSIBILITY
        BlockCause.LOCATION_PERMISSION -> HealthCapability.LOCATION
        else -> null
    }

    /** Cosa manca ORA a [rule] tra le cose coperte dagli avvisi. */
    fun missing(rule: Automation, snapshot: CapabilityHealthSnapshot): Set<HealthCapability> = buildSet {
        rule.requiredCapabilities.forEach { capability ->
            snapshot.causes[capability]?.let(::of)?.let(::add)
        }
        // Solo le regole a orario che hanno chiesto la puntualità usano la sveglia esatta: le altre
        // sono inesatte di proposito e non perdono nulla.
        // 0.8: anche l'orario di fine di una regola-intervallo.
        val exact = rule.listenedTriggers.any { trigger ->
            trigger is Trigger.Time && trigger.precision == TimePrecision.EXACT
        }
        if (!snapshot.exactAlarmsGranted && exact) {
            add(HealthCapability.EXACT_ALARMS)
        }
    }

    /** Quante delle regole armate e attive restano senza ciascuna cosa; assenti = nessuna regola toccata. */
    fun affectedCounts(rules: List<Automation>, snapshot: CapabilityHealthSnapshot): Map<HealthCapability, Int> =
        rules.filter { it.enabled }
            .flatMap { missing(it, snapshot) }
            .groupingBy { it }
            .eachCount()
}
