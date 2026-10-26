package dev.argus.automation.vm

import dev.argus.automation.BlockCause
import dev.argus.automation.geofence.PersistedGeofenceRegistration
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ScheduledTimeAlarmEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.ui.model.DiagnosisFix
import dev.argus.ui.model.DiagnosisLevel
import dev.argus.ui.model.DiagnosisRow
import dev.argus.ui.presentation.RenderLanguage
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleDiagnosisTest {
    private val zone = ZoneId.systemDefault()

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Lunedì 26 ottobre 2026, 14:32: il momento del controllo. */
    private val now = at(26, 14, 32)

    private fun sign(value: Automation): Automation =
        value.copy(approvalFingerprint = ApprovalFingerprints.of(value))

    private fun timeRule(
        precision: TimePrecision = TimePrecision.FLEXIBLE,
        status: AutomationStatus = AutomationStatus.ARMED,
        enabled: Boolean = true,
    ): Automation = sign(
        Automation(
            id = AutomationId("t1"),
            name = "sveglia",
            createdBy = CreatedBy.USER,
            status = status,
            trigger = Trigger.Time(cron = "0 9 * * *", tz = "Europe/Rome", precision = precision),
            actions = listOf(Action.ShowNotification("Argus", "ciao")),
            enabled = enabled,
        ),
    )

    private fun geofenceRule(): Automation = sign(
        Automation(
            id = AutomationId("g1"),
            name = "casa",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Geofence(
                lat = 45.0,
                lng = 9.0,
                radiusM = 150.0,
                transition = Transition.EXIT,
            ),
            actions = listOf(Action.ShowNotification("Argus", "ciao")),
        ),
    )

    private fun immediateRule(): Automation = sign(
        Automation(
            id = AutomationId("i1"),
            name = "subito",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Immediate,
            actions = listOf(Action.ShowNotification("Argus", "ciao")),
        ),
    )

    private fun alarmFor(
        rule: Automation,
        eventAt: Long = at(27, 9, 0),
        mode: String = "EXACT",
        precision: String = "EXACT",
        fingerprint: String? = rule.approvalFingerprint?.value,
    ) = ScheduledTimeAlarmEntity(
        automationId = rule.id.value,
        approvalFingerprint = requireNotNull(fingerprint),
        eventAtMillis = eventAt,
        wakeAtMillis = eventAt,
        requestedPrecision = precision,
        scheduledMode = mode,
        updatedAtMillis = at(26, 8, 0),
    )

    private fun registration(
        rule: Automation,
        last: Transition? = null,
        active: Boolean = true,
        fingerprint: ApprovalFingerprint = requireNotNull(rule.approvalFingerprint),
    ) = PersistedGeofenceRegistration(
        automationId = rule.id,
        approvalFingerprint = fingerprint,
        active = active,
        lastTransition = last,
    )

    private var nextId = 1L

    private fun record(
        kind: AuditKind,
        atMillis: Long,
        detail: String = "",
        status: ExecutionStatus? = null,
    ) = AuditLogRecord(
        id = nextId++,
        automationId = "t1",
        automationName = "sveglia",
        kind = kind,
        atMillis = atMillis,
        detail = detail,
        executionId = null,
        executionStatus = status,
        succeededCount = 1,
        failedCount = 0,
        submittedCount = 0,
    )

    private fun diagnose(
        automation: Automation,
        causes: Map<String, BlockCause> = emptyMap(),
        alarm: ScheduledTimeAlarmEntity? = null,
        geofence: PersistedGeofenceRegistration? = null,
        log: List<AuditLogRecord> = emptyList(),
        language: RenderLanguage = RenderLanguage.IT,
    ) = buildRuleDiagnosis(
        DiagnosisInputs(automation, causes, alarm, geofence, log),
        now,
        language,
    )

    private fun ok(text: String) = DiagnosisRow(DiagnosisLevel.OK, text)

    @Test
    fun `an armed time rule with everything ready and an exact alarm shows the right times`() {
        val rule = timeRule(precision = TimePrecision.EXACT)

        val diagnosis = diagnose(rule, alarm = alarmFor(rule))

        assertEquals(
            listOf(
                ok("La regola è attiva"),
                ok("Permessi e servizi necessari: tutto pronto"),
                ok("Prossima sveglia: domani 09:00"),
                DiagnosisRow(DiagnosisLevel.INFO, "Non è ancora mai scattata"),
            ),
            diagnosis.rows,
        )
        assertEquals("Controllato alle 14:32", diagnosis.checkedAtLabel)
    }

    @Test
    fun `the next alarm says today tomorrow or the date`() {
        val rule = timeRule()

        fun next(eventAt: Long) =
            diagnose(rule, alarm = alarmFor(rule, eventAt = eventAt, mode = "INEXACT"))
                .rows.single { it.text.startsWith("Prossima sveglia") }.text

        assertEquals("Prossima sveglia: oggi 18:05", next(at(26, 18, 5)))
        assertEquals("Prossima sveglia: domani 09:00", next(at(27, 9, 0)))
        assertEquals("Prossima sveglia: 29/10 07:30", next(at(29, 7, 30)))
    }

    @Test
    fun `the next alarm uses the same format as the list`() {
        val rule = sign(
            Automation(
                id = AutomationId("rel"),
                name = "tra due ore",
                createdBy = CreatedBy.USER,
                status = AutomationStatus.ARMED,
                trigger = Trigger.Time(afterMs = 7_200_000, tz = "Europe/Rome"),
                actions = listOf(Action.ShowNotification("Argus", "ciao")),
            ),
        )
        val eventAt = at(26, 16, 32)
        val alarm = alarmFor(rule, eventAt = eventAt, mode = "INEXACT", precision = "FLEXIBLE")

        val listLabel = rule.toAutomationRow(null, now, language = RenderLanguage.IT, scheduledAlarm = alarm)
            .nextFireLabel
        val diagnosisText = diagnose(rule, alarm = alarm).rows
            .single { it.text.startsWith("Prossima sveglia") }.text

        assertEquals("oggi 16:32", listLabel)
        assertEquals("Prossima sveglia: $listLabel", diagnosisText)
    }

    @Test
    fun `an inexact alarm for an exact rule warns and a flexible one only informs`() {
        val exact = timeRule(precision = TimePrecision.EXACT)
        val warned = diagnose(exact, alarm = alarmFor(exact, mode = "INEXACT")).rows

        // La riga «Sveglie esatte» in Sistema esiste: l'avviso porta lì.
        assertEquals(
            DiagnosisRow(
                DiagnosisLevel.WARN,
                "Android non concede ad Argus le sveglie esatte: può ritardarla fino a un'ora",
                DiagnosisFix.OPEN_SYSTEM,
            ),
            warned.single { it.level == DiagnosisLevel.WARN },
        )

        val flexible = timeRule()
        val informed = diagnose(
            flexible,
            alarm = alarmFor(flexible, mode = "INEXACT", precision = "FLEXIBLE"),
        ).rows
        assertEquals(
            DiagnosisRow(
                DiagnosisLevel.INFO,
                "Orario flessibile: per risparmiare batteria Android può spostarla fino a un'ora; " +
                    "se serve puntuale, chiedi l'orario esatto in chat",
            ),
            informed.single { it.text.startsWith("Orario flessibile") },
        )
        assertTrue(informed.none { it.level == DiagnosisLevel.WARN })
        // Una regola flessibile non ha bisogno delle sveglie esatte: nessuna scorciatoia.
        assertNull(informed.single { it.text.startsWith("Orario flessibile") }.fix)
    }

    @Test
    fun `a missing alarm warns and an alarm of an older version is being updated`() {
        val rule = timeRule()

        val missing = diagnose(rule, alarm = null).rows
        assertEquals(
            DiagnosisRow(DiagnosisLevel.WARN, "Nessuna sveglia registrata per questa regola"),
            missing[2],
        )

        val stale = diagnose(rule, alarm = alarmFor(rule, fingerprint = "an-older-version")).rows
        assertEquals(
            DiagnosisRow(
                DiagnosisLevel.INFO,
                "La sveglia si sta aggiornando alla nuova versione della regola",
            ),
            stale[2],
        )
        assertTrue(stale.none { it.text.startsWith("Prossima sveglia") })
    }

    @Test
    fun `an alarm whose time has passed is late, not the next one`() {
        val rule = timeRule(precision = TimePrecision.EXACT)

        fun first(eventAt: Long) =
            diagnose(rule, alarm = alarmFor(rule, eventAt = eventAt)).rows[2]

        assertEquals(
            DiagnosisRow(DiagnosisLevel.WARN, "La sveglia era prevista oggi 09:00 e non è ancora arrivata"),
            first(at(26, 9, 0)),
        )
        assertEquals(
            DiagnosisRow(DiagnosisLevel.WARN, "La sveglia era prevista ieri 09:00 e non è ancora arrivata"),
            first(at(25, 9, 0)),
        )
        // Entro un minuto non è ancora un ritardo.
        assertEquals(ok("Prossima sveglia: oggi 14:31"), first(now - 30_000))
    }

    @Test
    fun `an off rule has no alarm or area rows`() {
        val off = timeRule(status = AutomationStatus.DISABLED, enabled = false)

        assertEquals(
            listOf(
                DiagnosisRow(DiagnosisLevel.WARN, "La regola è spenta: riattivala per farla scattare"),
                ok("Permessi e servizi necessari: tutto pronto"),
                DiagnosisRow(DiagnosisLevel.INFO, "Non è ancora mai scattata"),
            ),
            diagnose(off, alarm = null).rows,
        )
        val offArea = geofenceRule().copy(enabled = false)
        assertTrue(diagnose(offArea, geofence = null).rows.none { it.text.contains("area") })
    }

    @Test
    fun `an immediate rule has no alarm row`() {
        val rows = diagnose(immediateRule()).rows

        assertEquals(
            listOf(
                ok("La regola è attiva"),
                ok("Permessi e servizi necessari: tutto pronto"),
                DiagnosisRow(DiagnosisLevel.INFO, "Non è ancora mai scattata"),
            ),
            rows,
        )
    }

    @Test
    fun `a stopped Shizuku and location off give two warnings with the system shortcut and no duplicate`() {
        val rule = timeRule()
        val causes = mapOf(
            ActionCapabilities.SET_WIFI to BlockCause.SHIZUKU_NOT_RUNNING,
            ActionCapabilities.SET_BLUETOOTH to BlockCause.SHIZUKU_NOT_RUNNING,
            CapabilityIds.TRIGGER_GEOFENCE to BlockCause.LOCATION_OFF,
        )

        val rows = diagnose(rule, causes, alarm = alarmFor(rule)).rows

        val warnings = rows.filter { it.level == DiagnosisLevel.WARN }
        assertEquals(
            listOf(
                DiagnosisRow(
                    DiagnosisLevel.WARN,
                    "Shizuku non è in esecuzione: avvialo",
                    DiagnosisFix.OPEN_SYSTEM,
                ),
                DiagnosisRow(
                    DiagnosisLevel.WARN,
                    "La posizione del telefono è spenta",
                    DiagnosisFix.OPEN_SYSTEM,
                ),
            ),
            warnings,
        )
        assertTrue(rows.none { it.text == "Permessi e servizi necessari: tutto pronto" })
    }

    @Test
    fun `every block cause has its own distinct text in both languages`() {
        val rule = immediateRule()

        fun texts(language: RenderLanguage) = BlockCause.entries.map { cause ->
            diagnose(rule, mapOf("cap" to cause), language = language).rows[1].text
        }

        val italian = texts(RenderLanguage.IT)
        val english = texts(RenderLanguage.EN)
        assertEquals(BlockCause.entries.size, italian.toSet().size)
        assertEquals(BlockCause.entries.size, english.toSet().size)
        assertTrue(italian.none { it.isBlank() } && english.none { it.isBlank() })
        assertEquals("Serve Shizuku, che non è installato", italian[BlockCause.SHIZUKU_NOT_INSTALLED.ordinal])
        assertEquals(
            "L'AI non è configurata: serve la chiave del provider e il consenso privacy",
            italian[BlockCause.AI_NOT_READY.ordinal],
        )
        assertEquals(
            "Una funzione richiesta non è disponibile",
            italian[BlockCause.OTHER.ordinal],
        )
    }

    @Test
    fun `a geofence with no known transition says the position is unknown`() {
        val rule = geofenceRule()

        val rows = diagnose(rule, geofence = registration(rule, last = null)).rows

        assertEquals(
            DiagnosisRow(
                DiagnosisLevel.INFO,
                "Posizione rispetto all'area: sconosciuta (si aggiorna al prossimo spostamento)",
            ),
            rows[2],
        )
    }

    @Test
    fun `the last known position follows the last transition`() {
        val rule = geofenceRule()

        fun area(last: Transition) = diagnose(rule, geofence = registration(rule, last = last)).rows[2]

        assertEquals(ok("Ultima posizione nota: dentro l'area"), area(Transition.ENTER))
        assertEquals(ok("Ultima posizione nota: dentro l'area"), area(Transition.DWELL))
        assertEquals(ok("Ultima posizione nota: fuori dall'area"), area(Transition.EXIT))
    }

    @Test
    fun `a geofence that is not registered or of an older version is reported`() {
        val rule = geofenceRule()

        assertEquals(
            DiagnosisRow(DiagnosisLevel.WARN, "L'area non è registrata nel sistema"),
            diagnose(rule, geofence = null).rows[2],
        )
        assertEquals(
            DiagnosisRow(DiagnosisLevel.WARN, "L'area non è registrata nel sistema"),
            diagnose(rule, geofence = registration(rule, active = false)).rows[2],
        )
        assertEquals(
            DiagnosisRow(
                DiagnosisLevel.INFO,
                "L'area si sta aggiornando alla nuova versione della regola",
            ),
            diagnose(
                rule,
                geofence = registration(rule, fingerprint = ApprovalFingerprint("0".repeat(64))),
            ).rows[2],
        )
    }

    @Test
    fun `a policy block more recent than the last run shows the last attempt with the log text`() {
        val rule = immediateRule()
        val fired = record(AuditKind.FIRED, now - 3_600_000, status = ExecutionStatus.SUCCEEDED)
        val blocked = record(AuditKind.BLOCKED_POLICY, now - 300_000, detail = "capability_unavailable")

        val last = diagnose(rule, log = listOf(blocked, fired)).rows.last()

        val logText = blocked.toLogRow(emptyList(), RenderLanguage.IT).summary
        assertEquals(DiagnosisLevel.WARN, last.level)
        assertEquals("Ultimo tentativo 5 min fa: $logText", last.text)
        assertTrue(logText.startsWith("bloccata dalla policy"))
        // Stesso testo in inglese, dallo stesso mapper.
        val english = diagnose(rule, log = listOf(blocked, fired), language = RenderLanguage.EN).rows.last()
        assertEquals(
            "Last attempt 5 min ago: ${blocked.toLogRow(emptyList(), RenderLanguage.EN).summary}",
            english.text,
        )
    }

    @Test
    fun `the expected reasons only inform and the other problems warn`() {
        val rule = immediateRule()

        fun lastFor(kind: AuditKind, detail: String = "") =
            diagnose(rule, log = listOf(record(kind, now - 60_000, detail))).rows.last().level

        assertEquals(DiagnosisLevel.INFO, lastFor(AuditKind.CONDITIONS_NOT_MET))
        assertEquals(DiagnosisLevel.INFO, lastFor(AuditKind.SUPPRESSED_COOLDOWN))
        assertEquals(DiagnosisLevel.WARN, lastFor(AuditKind.BLOCKED_POLICY, "capability_unavailable"))
        assertEquals(DiagnosisLevel.WARN, lastFor(AuditKind.ERROR, "shizuku_unavailable"))
        assertEquals(DiagnosisLevel.WARN, lastFor(AuditKind.SCHEDULING_FAILED, "scheduling_failed"))
        assertEquals(DiagnosisLevel.WARN, lastFor(AuditKind.ARM_FAILED, "scheduling_failed"))
        assertEquals(DiagnosisLevel.WARN, lastFor(AuditKind.ENABLE_FAILED, "review_required"))
        assertEquals(DiagnosisLevel.WARN, lastFor(AuditKind.SUPPRESSED_BUDGET))
        assertEquals(DiagnosisLevel.WARN, lastFor(AuditKind.SUPPRESSED_NOT_ELIGIBLE))
    }

    @Test
    fun `a run more recent than any problem shows the last run`() {
        val rule = immediateRule()
        val blocked = record(AuditKind.BLOCKED_POLICY, now - 3 * 3_600_000, detail = "capability_unavailable")
        val fired = record(AuditKind.FIRED, now - 600_000, status = ExecutionStatus.SUCCEEDED)

        val last = diagnose(rule, log = listOf(fired, blocked)).rows.last()

        assertEquals(ok("Ultimo scatto: 10 min fa"), last)
        assertEquals(
            ok("Last run: 10 min ago"),
            diagnose(rule, log = listOf(fired, blocked), language = RenderLanguage.EN).rows.last(),
        )
    }

    @Test
    fun `lifecycle and duplicate events are not attempts and a rule that never ran says so`() {
        val rule = immediateRule()
        val noise = listOf(
            record(AuditKind.RULE_ARMED, now - 10_000),
            record(AuditKind.RULE_ENABLED, now - 5_000),
            record(AuditKind.SUPPRESSED_DUPLICATE, now - 2_000),
        )

        assertEquals(
            DiagnosisRow(DiagnosisLevel.INFO, "Non è ancora mai scattata"),
            diagnose(rule, log = noise).rows.last(),
        )
        assertEquals(
            DiagnosisRow(DiagnosisLevel.INFO, "It has never run yet"),
            diagnose(rule, log = emptyList(), language = RenderLanguage.EN).rows.last(),
        )
    }

    @Test
    fun `an off rule and a rule to review are warnings`() {
        assertEquals(
            DiagnosisRow(
                DiagnosisLevel.WARN,
                "La regola è spenta: riattivala per farla scattare",
            ),
            diagnose(timeRule(status = AutomationStatus.DISABLED, enabled = false)).rows.first(),
        )
        assertEquals(
            DiagnosisRow(
                DiagnosisLevel.WARN,
                "La regola è spenta: riattivala per farla scattare",
            ),
            diagnose(timeRule(status = AutomationStatus.ARMED, enabled = false)).rows.first(),
        )
        assertEquals(
            DiagnosisRow(
                DiagnosisLevel.WARN,
                "La regola è da rivedere: aprila e approvala di nuovo",
            ),
            diagnose(timeRule(status = AutomationStatus.NEEDS_REVIEW)).rows.first(),
        )
    }

    @Test
    fun `the same diagnosis reads in english and italian`() {
        val rule = timeRule(precision = TimePrecision.EXACT)
        val causes = mapOf(ActionCapabilities.SET_WIFI to BlockCause.SHIZUKU_NOT_AUTHORIZED)
        val alarm = alarmFor(rule, mode = "INEXACT")

        val italian = diagnose(rule, causes, alarm = alarm)
        val english = diagnose(rule, causes, alarm = alarm, language = RenderLanguage.EN)

        assertEquals(
            listOf(
                "La regola è attiva",
                "Shizuku non ha ancora autorizzato Argus",
                "Prossima sveglia: domani 09:00",
                "Android non concede ad Argus le sveglie esatte: può ritardarla fino a un'ora",
                "Non è ancora mai scattata",
            ),
            italian.rows.map { it.text },
        )
        assertEquals(
            listOf(
                "The rule is active",
                "Shizuku has not authorized Argus yet",
                "Next alarm: tomorrow 09:00",
                "Android does not grant Argus exact alarms: it may delay it by up to an hour",
                "It has never run yet",
            ),
            english.rows.map { it.text },
        )
        // Livelli e scorciatoie non dipendono dalla lingua.
        assertEquals(italian.rows.map { it.level to it.fix }, english.rows.map { it.level to it.fix })
        assertEquals("Checked at 14:32", english.checkedAtLabel)
    }
}
