package dev.argus.automation.vm

import dev.argus.automation.BlockCause
import dev.argus.automation.ScheduledAlarmMode
import dev.argus.automation.geofence.PersistedGeofenceRegistration
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ScheduledTimeAlarmEntity
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditKind
import dev.argus.ui.model.DiagnosisFix
import dev.argus.ui.model.DiagnosisLevel
import dev.argus.ui.model.DiagnosisRow
import dev.argus.ui.model.RuleDiagnosisUi
import dev.argus.ui.presentation.RenderLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tutto ciò che serve per rispondere a «Perché non è scattata?» (0.3.6, F2). Gli input si leggono
 * una volta sola quando l'utente apre la sezione: la funzione che li trasforma in righe è pura, così
 * il testo si ricostruisce al cambio di lingua senza rileggere il dispositivo.
 */
internal data class DiagnosisInputs(
    val automation: Automation,
    /** Causa del blocco per ogni capability richiesta oggi non disponibile (vuota = tutto pronto). */
    val blockCauses: Map<String, BlockCause>,
    /** Sveglia persistita della regola (`scheduledTimeAlarmDao().get(id)`). */
    val alarm: ScheduledTimeAlarmEntity?,
    /** Registrazione della geofence (`GeofenceStateStore.get(id)`). */
    val geofence: PersistedGeofenceRegistration?,
    /** Eventi della regola già caricati dal Dettaglio, dal più recente. */
    val log: List<AuditLogRecord>,
)

/** Eventi che raccontano un tentativo fallito o soppresso: candidati alla riga «Ultimo tentativo». */
private val PROBLEM_KINDS = setOf(
    AuditKind.BLOCKED_POLICY,
    AuditKind.ERROR,
    AuditKind.SCHEDULING_FAILED,
    AuditKind.ARM_FAILED,
    AuditKind.ENABLE_FAILED,
    AuditKind.SUPPRESSED_BUDGET,
    AuditKind.SUPPRESSED_COOLDOWN,
    AuditKind.SUPPRESSED_NOT_ELIGIBLE,
    AuditKind.CONDITIONS_NOT_MET,
)

/** Motivi normali di un mancato scatto: informazione, non avviso. */
private val EXPECTED_KINDS = setOf(AuditKind.CONDITIONS_NOT_MET, AuditKind.SUPPRESSED_COOLDOWN)

internal fun buildRuleDiagnosis(
    inputs: DiagnosisInputs,
    nowMillis: Long,
    language: RenderLanguage,
): RuleDiagnosisUi {
    val automation = inputs.automation
    // Sveglia e area esistono solo per una regola attiva: per una spenta la loro assenza è normale.
    val active = automation.status == AutomationStatus.ARMED && automation.enabled
    val rows = buildList {
        add(statusRow(automation, language))
        addAll(requirementRows(inputs.blockCauses, language))
        when (val trigger = automation.trigger) {
            is Trigger.Time -> if (active) {
                addAll(alarmRows(automation, trigger, inputs.alarm, nowMillis, language))
            }
            is Trigger.Geofence -> if (active) add(areaRow(automation, inputs.geofence, language))
            else -> Unit
        }
        add(lastEventRow(inputs.log, nowMillis, language))
    }
    return RuleDiagnosisUi(
        rows = rows,
        checkedAtLabel = language.pick(
            "Checked at ${clock(nowMillis)}",
            "Controllato alle ${clock(nowMillis)}",
        ),
    )
}

private fun statusRow(automation: Automation, language: RenderLanguage): DiagnosisRow = when {
    automation.status == AutomationStatus.ARMED && automation.enabled -> DiagnosisRow(
        DiagnosisLevel.OK,
        language.pick("The rule is active", "La regola è attiva"),
    )
    automation.status == AutomationStatus.ARMED || automation.status == AutomationStatus.DISABLED ->
        DiagnosisRow(
            DiagnosisLevel.WARN,
            language.pick(
                "The rule is off: turn it back on to let it run",
                "La regola è spenta: riattivala per farla scattare",
            ),
        )
    else -> DiagnosisRow(
        DiagnosisLevel.WARN,
        language.pick(
            "The rule needs review: open it and approve it again",
            "La regola è da rivedere: aprila e approvala di nuovo",
        ),
    )
}

/** Una riga per causa distinta, nell'ordine dell'enum: più capability con la stessa causa ne fanno una. */
private fun requirementRows(
    causes: Map<String, BlockCause>,
    language: RenderLanguage,
): List<DiagnosisRow> {
    if (causes.isEmpty()) {
        return listOf(
            DiagnosisRow(
                DiagnosisLevel.OK,
                language.pick(
                    "Required permissions and services: all ready",
                    "Permessi e servizi necessari: tutto pronto",
                ),
            ),
        )
    }
    return causes.values.toSortedSet().map { cause ->
        DiagnosisRow(DiagnosisLevel.WARN, causeText(cause, language), DiagnosisFix.OPEN_SYSTEM)
    }
}

private fun causeText(cause: BlockCause, l: RenderLanguage): String = when (cause) {
    BlockCause.SHIZUKU_NOT_INSTALLED -> l.pick(
        "Shizuku is required and it is not installed",
        "Serve Shizuku, che non è installato",
    )
    BlockCause.SHIZUKU_NOT_RUNNING -> l.pick(
        "Shizuku is not running: start it",
        "Shizuku non è in esecuzione: avvialo",
    )
    BlockCause.SHIZUKU_NOT_AUTHORIZED -> l.pick(
        "Shizuku has not authorized Argus yet",
        "Shizuku non ha ancora autorizzato Argus",
    )
    BlockCause.SHIZUKU_TOO_OLD -> l.pick(
        "Shizuku is too old: update it",
        "Shizuku troppo vecchio: aggiornalo",
    )
    BlockCause.LOCATION_OFF -> l.pick(
        "The phone's location is off",
        "La posizione del telefono è spenta",
    )
    BlockCause.LOCATION_PERMISSION -> l.pick(
        "The location permission is missing",
        "Manca il permesso di posizione",
    )
    BlockCause.NOTIFICATION_ACCESS -> l.pick(
        "Notification access is missing",
        "Manca l'accesso alle notifiche",
    )
    BlockCause.NOTIFICATIONS_PERMISSION -> l.pick(
        "Argus is not allowed to show notifications",
        "Argus non ha il permesso di mostrare notifiche",
    )
    BlockCause.SMS_PERMISSION -> l.pick(
        "The SMS permission is missing",
        "Manca il permesso per gli SMS",
    )
    BlockCause.CALL_PERMISSION -> l.pick(
        "The call permissions are missing",
        "Mancano i permessi per le chiamate",
    )
    BlockCause.BLUETOOTH_PERMISSION -> l.pick(
        "The Bluetooth permission (nearby devices) is missing",
        "Manca il permesso Bluetooth (dispositivi nelle vicinanze)",
    )
    BlockCause.DND_ACCESS -> l.pick(
        "Do Not Disturb access is missing",
        "Manca l'accesso a Non disturbare",
    )
    BlockCause.AI_NOT_READY -> l.pick(
        "AI is not set up: it needs the provider key and the privacy consent",
        "L'AI non è configurata: serve la chiave del provider e il consenso privacy",
    )
    BlockCause.SENSOR_UNAVAILABLE -> l.pick(
        "The required sensor is not available or the physical activity permission is missing",
        "Il sensore richiesto non è disponibile o manca il permesso attività fisica",
    )
    BlockCause.OTHER -> l.pick(
        "A required feature is not available",
        "Una funzione richiesta non è disponibile",
    )
}

/** Sveglia della regola a orario: c'è, è della versione giusta, quando scatta e con che precisione. */
private fun alarmRows(
    automation: Automation,
    trigger: Trigger.Time,
    alarm: ScheduledTimeAlarmEntity?,
    nowMillis: Long,
    language: RenderLanguage,
): List<DiagnosisRow> {
    if (alarm == null) {
        return listOf(
            DiagnosisRow(
                DiagnosisLevel.WARN,
                language.pick(
                    "No alarm is registered for this rule",
                    "Nessuna sveglia registrata per questa regola",
                ),
            ),
        )
    }
    if (alarm.approvalFingerprint != automation.approvalFingerprint?.value) {
        return listOf(
            DiagnosisRow(
                DiagnosisLevel.INFO,
                language.pick(
                    "The alarm is being updated to the new version of the rule",
                    "La sveglia si sta aggiornando alla nuova versione della regola",
                ),
            ),
        )
    }
    val due = dayTimeLabel(alarm.eventAtMillis, nowMillis, language)
    // Una sveglia scattata viene subito riprogrammata: se l'orario registrato è passato da più di un
    // minuto, Android non l'ha ancora consegnata (sveglia rinviata o persa).
    val next = if (alarm.eventAtMillis < nowMillis - LATE_ALARM_GRACE_MILLIS) {
        DiagnosisRow(
            DiagnosisLevel.WARN,
            language.pick(
                "The alarm was due $due and has not arrived yet",
                "La sveglia era prevista $due e non è ancora arrivata",
            ),
        )
    } else {
        DiagnosisRow(
            DiagnosisLevel.OK,
            language.pick("Next alarm: $due", "Prossima sveglia: $due"),
        )
    }
    if (alarm.scheduledMode != ScheduledAlarmMode.INEXACT.name) return listOf(next)
    // In Sistema oggi non c'è una riga per le sveglie esatte: niente «Apri Sistema» su questo avviso.
    val inexact = if (trigger.precision == TimePrecision.EXACT) {
        DiagnosisRow(
            DiagnosisLevel.WARN,
            language.pick(
                "Android does not grant Argus exact alarms: it may delay it",
                "Android non concede ad Argus le sveglie esatte: può ritardarla",
            ),
        )
    } else {
        DiagnosisRow(
            DiagnosisLevel.INFO,
            language.pick(
                "The alarm is not exact: Android may delay it by a few minutes",
                "Sveglia non esatta: Android può ritardarla di qualche minuto",
            ),
        )
    }
    return listOf(next, inexact)
}

/** Registrazione della geofence nel sistema e ultima posizione nota rispetto all'area. */
private fun areaRow(
    automation: Automation,
    registration: PersistedGeofenceRegistration?,
    language: RenderLanguage,
): DiagnosisRow {
    if (registration == null || !registration.active) {
        return DiagnosisRow(
            DiagnosisLevel.WARN,
            language.pick(
                "The area is not registered with the system",
                "L'area non è registrata nel sistema",
            ),
        )
    }
    if (registration.approvalFingerprint != automation.approvalFingerprint) {
        return DiagnosisRow(
            DiagnosisLevel.INFO,
            language.pick(
                "The area is being updated to the new version of the rule",
                "L'area si sta aggiornando alla nuova versione della regola",
            ),
        )
    }
    return when (registration.lastTransition) {
        null -> DiagnosisRow(
            DiagnosisLevel.INFO,
            language.pick(
                "Position relative to the area: unknown (updates on your next move)",
                "Posizione rispetto all'area: sconosciuta (si aggiorna al prossimo spostamento)",
            ),
        )
        Transition.ENTER, Transition.DWELL -> DiagnosisRow(
            DiagnosisLevel.OK,
            language.pick(
                "Last known position: inside the area",
                "Ultima posizione nota: dentro l'area",
            ),
        )
        Transition.EXIT -> DiagnosisRow(
            DiagnosisLevel.OK,
            language.pick(
                "Last known position: outside the area",
                "Ultima posizione nota: fuori dall'area",
            ),
        )
    }
}

/**
 * L'ultimo scatto contro l'ultimo problema: vince il più recente. Il testo del problema è quello
 * che il Log mostra per lo stesso evento (stesso mapper), così le due schermate non si contraddicono.
 */
private fun lastEventRow(
    log: List<AuditLogRecord>,
    nowMillis: Long,
    language: RenderLanguage,
): DiagnosisRow {
    val recency = compareBy<AuditLogRecord>({ it.atMillis }, { it.id })
    val fired = log.filter { it.kind == AuditKind.FIRED }.maxWithOrNull(recency)
    val problem = log.filter { it.kind in PROBLEM_KINDS }.maxWithOrNull(recency)
    if (problem != null && (fired == null || recency.compare(problem, fired) > 0)) {
        val moment = relativeTimeLabel(problem.atMillis, nowMillis, language)
        val what = problem.toLogRow(emptyList(), language).summary
        return DiagnosisRow(
            if (problem.kind in EXPECTED_KINDS) DiagnosisLevel.INFO else DiagnosisLevel.WARN,
            language.pick("Last attempt $moment: $what", "Ultimo tentativo $moment: $what"),
        )
    }
    if (fired != null) {
        val moment = relativeTimeLabel(fired.atMillis, nowMillis, language)
        return DiagnosisRow(
            DiagnosisLevel.OK,
            language.pick("Last run: $moment", "Ultimo scatto: $moment"),
        )
    }
    return DiagnosisRow(
        DiagnosisLevel.INFO,
        language.pick("It has never run yet", "Non è ancora mai scattata"),
    )
}

/** Margine prima di dire che una sveglia è in ritardo. */
private const val LATE_ALARM_GRACE_MILLIS = 60_000L

private val CHECKED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)

private fun clock(atMillis: Long): String =
    Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).format(CHECKED_AT_FORMAT)
