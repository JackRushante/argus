package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.UiStep
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.RevalidatingFirePolicy
import dev.argus.engine.runtime.SpanCodes
import dev.argus.engine.runtime.SpanPhase
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 0.8 regole-intervallo: il validator cammina il trigger e le azioni di fine come quelli d'inizio, e la shell e le
 * macro fra le azioni di fine rispondono al trigger di FINE, anche annidate e di nuovo allo scatto.
 */
class DraftValidatorSpanTest {
    private val validator = DraftValidator(setOf("whatsapp_reply"))
    private val carOn = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED, "Auto")
    private val carOff = Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED, "Auto")
    private val evening = Trigger.Time(cron = "0 22 * * *", tz = "Europe/Rome")
    private val morning = Trigger.Time(cron = "0 7 * * *", tz = "Europe/Rome")
    private val home = Trigger.Geofence(45.0, 9.0, 150.0, Transition.ENTER)

    private fun draft(
        trigger: Trigger = carOn,
        actions: List<Action> = listOf(Action.SetVolume(VolumeStream.MEDIA, 100)),
        exit: ExitSpec? = ExitSpec(carOff),
        vars: List<VarBinding> = emptyList(),
        cooldownMs: Long = 0,
    ) = AutomationDraft("intervallo", trigger, actions, vars = vars, cooldownMs = cooldownMs, exit = exit)

    private fun issues(d: AutomationDraft, whitelist: Set<String> = emptySet()) = validator.validate(d, whitelist)
    private fun errors(d: AutomationDraft, whitelist: Set<String> = emptySet()) =
        issues(d, whitelist).filter { it.severity == Severity.ERROR }.map { it.code }
    private fun warnings(d: AutomationDraft) = issues(d).filter { it.severity == Severity.WARNING }.map { it.code }

    @Test
    fun `while connected to the car, volume up and back is a valid span`() {
        assertEquals(emptyList(), errors(draft()))
        assertEquals(emptyList(), errors(draft(trigger = evening, exit = ExitSpec(morning))))
        assertEquals(emptyList(), errors(draft(trigger = home, exit = ExitSpec(home.copy(transition = Transition.EXIT)))))
    }

    @Test
    fun `a span needs a start that repeats and an end that can come after it`() {
        assertTrue("exit_start_one_shot" in errors(draft(trigger = Trigger.Immediate)))
        assertTrue("exit_start_one_shot" in errors(draft(trigger = Trigger.Time(afterMs = 60_000, tz = "Europe/Rome"))))
        listOf(
            Trigger.Manual,
            Trigger.Immediate,
            Trigger.Time(afterMs = 60_000, tz = "Europe/Rome"),
            Trigger.Time(at = "2026-12-01T08:00", tz = "Europe/Rome"),
        ).forEach { end -> assertTrue("exit_trigger_unsupported" in errors(draft(exit = ExitSpec(end))), "$end") }
        assertTrue("exit_trigger_same_as_start" in errors(draft(exit = ExitSpec(carOn))))
    }

    @Test
    fun `the end trigger has the bounds of a start trigger`() {
        assertEquals(
            errors(draft(trigger = Trigger.Battery(below = 150), exit = ExitSpec(carOff))).toSet() - "exit_nothing_to_restore",
            errors(draft(exit = ExitSpec(Trigger.Battery(below = 150)))).toSet(),
        )
        assertTrue(errors(draft(exit = ExitSpec(Trigger.Battery(below = 150)))).isNotEmpty())
        assertTrue(errors(draft(exit = ExitSpec(Trigger.AppUsage("", 10)))).isNotEmpty())
        // Un sensore di fine chiede la stessa pausa di un sensore d'inizio.
        assertTrue("sensor_cooldown_invalid" in errors(draft(exit = ExitSpec(Trigger.Sensor(SensorKind.SHAKE)))))
    }

    @Test
    fun `start and end on the same kind of trigger must be told apart`() {
        assertTrue(
            "exit_geofence_mismatch" in errors(draft(trigger = home, exit = ExitSpec(home.copy(radiusM = 300.0, transition = Transition.EXIT)))),
        )
        val dwell = home.copy(transition = Transition.DWELL, loiteringDelayMs = 600_000)
        assertTrue("exit_geofence_mismatch" in errors(draft(trigger = dwell, exit = ExitSpec(home))))
        assertEquals(emptyList(), errors(draft(trigger = dwell, exit = ExitSpec(home.copy(transition = Transition.EXIT)))))
        assertTrue(
            "exit_trigger_unsupported" in errors(draft(trigger = home.copy(transition = Transition.EXIT), exit = ExitSpec(dwell))),
        )
        val meeting = Trigger.CalendarEvent(CalendarPhase.START, 0, "Riunione", null)
        assertTrue("exit_calendar_same_phase" in errors(draft(trigger = meeting, exit = ExitSpec(meeting.copy(titleMatch = "Pranzo")))))
        assertEquals(emptyList(), errors(draft(trigger = meeting, exit = ExitSpec(meeting.copy(phase = CalendarPhase.END)))))
        val shake = Trigger.Sensor(SensorKind.SHAKE)
        assertTrue("exit_sensor_same_kind" in errors(draft(trigger = shake, exit = ExitSpec(shake), cooldownMs = 60_000)))
        assertEquals(
            emptyList(),
            errors(draft(trigger = shake, exit = ExitSpec(Trigger.Sensor(SensorKind.FLIP_FACE_DOWN)), cooldownMs = 60_000)),
        )
        assertTrue("exit_nfc_resolve_unsupported" in errors(draft(exit = ExitSpec(Trigger.NfcTag(resolveTag = true)))))
    }

    @Test
    fun `an end that does nothing is refused, one with nothing to restore is flagged`() {
        assertTrue("exit_nothing_to_do" in errors(draft(exit = ExitSpec(carOff, restore = false))))
        assertTrue("exit_nothing_to_restore" in warnings(draft(actions = listOf(Action.Vibrate(100)))))
        assertFalse("exit_nothing_to_restore" in warnings(draft()))
    }

    @Test
    fun `end actions are validated like start actions`() {
        assertTrue("volume_level_invalid" in errors(draft(exit = ExitSpec(carOff, actions = listOf(Action.SetVolume(VolumeStream.MEDIA, 150))))))
        val nested = Action.If(Condition.BooleanLiteral(true), then = listOf(Action.SetVolume(VolumeStream.RING, -1)))
        assertTrue("volume_level_invalid" in errors(draft(exit = ExitSpec(carOff, actions = listOf(nested)))))
        assertTrue("exit_ask_unsupported" in errors(draft(exit = ExitSpec(carOff, actions = listOf(Action.Ask("Ok?", listOf("Sì", "No"), "a"))))))
    }

    @Test
    fun `shell and macros in the end actions answer to the END trigger, nested too`() {
        val untrustedEnds = listOf(
            Trigger.NextAlarm(10),
            Trigger.AppUsage("com.example.game", 30),
            Trigger.UserModeChanged("Casa"),
            Trigger.AppEvent("com.example.app", dev.argus.engine.model.AppEventKind.OPENED),
        )
        val shell = Action.RunShell("svc wifi enable")
        val macro = Action.UiSteps("com.example.app", listOf(UiStep.Back))
        untrustedEnds.forEach { end ->
            assertTrue("shell_external_trigger" in errors(draft(trigger = evening, exit = ExitSpec(end, actions = listOf(shell)))), "$end")
            val nested = Action.If(Condition.BooleanLiteral(true), then = listOf(Action.While(Condition.BooleanLiteral(false), listOf(shell), maxIterations = 1)))
            assertTrue("shell_external_trigger" in errors(draft(trigger = evening, exit = ExitSpec(end, actions = listOf(nested)))), "annidata $end")
            assertTrue("ui_steps_external_trigger" in errors(draft(trigger = evening, exit = ExitSpec(end, actions = listOf(macro)))), "$end")
        }
        // Una shell di fine con un trigger di fine fidato passa anche se l'inizio non è fidato (la shell non è lì).
        assertFalse(
            "shell_external_trigger" in errors(draft(trigger = Trigger.AppUsage("com.example.game", 30), exit = ExitSpec(morning, actions = listOf(shell)))),
        )
        // E una shell d'inizio non diventa fidata perché la fine lo è.
        assertTrue(
            "shell_external_trigger" in errors(draft(trigger = Trigger.NextAlarm(10), actions = listOf(shell), exit = ExitSpec(morning))),
        )
    }

    @Test
    fun `an external value used in the end actions follows the taint rules of the end trigger`() {
        val chat = Trigger.Notification(pkg = "com.whatsapp", conversationId = "c1", isGroup = false, textMatch = "parto")
        val chatEnd = chat.copy(textMatch = "arrivato")
        val text = VarBinding.TriggerPayload("msg", TriggerField.TEXT, confidentiality = ConfidentialityLabel.PRIVATE)
        // Nella posizione del comando un dato esterno sceglierebbe il programma: errore anche fra le azioni di fine.
        assertTrue(
            "interpolation_shell_command_position" in errors(
                draft(trigger = chat, vars = listOf(text), exit = ExitSpec(chatEnd, actions = listOf(Action.RunShell("\${msg} --now")))),
                whitelist = setOf("c1"),
            ),
        )
        // Come argomento quotato e in un sink locale il dato esterno è ammesso.
        assertEquals(
            emptyList(),
            errors(
                draft(
                    trigger = chat,
                    vars = listOf(text),
                    exit = ExitSpec(chatEnd, actions = listOf(Action.RunShell("echo \${msg}"), Action.ShowNotification("Fine", "\${msg}"))),
                ),
                whitelist = setOf("c1"),
            ),
        )
        // Una variabile del messaggio d'inizio non c'è alla fine se la fine non è un messaggio.
        assertTrue(
            "exit_var_trigger_payload_unsupported" in errors(
                draft(trigger = chat, vars = listOf(text), exit = ExitSpec(carOff, actions = listOf(Action.ShowNotification("Fine", "x")))),
            ),
        )
        // Le catture dell'inizio non esistono nel programma di fine.
        assertTrue(
            "interpolation_undeclared_var" in errors(
                draft(
                    trigger = evening,
                    actions = listOf(Action.RunShell("date", captureAs = "out")),
                    exit = ExitSpec(morning, actions = listOf(Action.ShowNotification("Fine", "\${out}"))),
                ),
            ),
        )
    }

    @Test
    fun `an AI action at the end counts for the generative cooldown`() {
        val ai = Action.InvokeLlm("riassumi", contextSources = emptyList(), allowedTools = emptyList(), replyTargetSender = false,
            deliver = dev.argus.engine.model.GenerativeDeliverMode.LOCAL_NOTIFICATION, notificationTitle = "AI")
        assertTrue("cooldown_raised" in warnings(draft(exit = ExitSpec(carOff, actions = listOf(ai)))))
    }

    @Test
    fun `a written setting restored at the end needs the setting reader`() {
        val write = Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "600000")
        val withRestore = CapabilityRequirements.derive(draft(actions = listOf(write)))
        assertTrue(CapabilityIds.STATE_READER_SETTING in withRestore)
        val withoutRestore = CapabilityRequirements.derive(
            draft(actions = listOf(write), exit = ExitSpec(carOff, restore = false, actions = listOf(Action.Vibrate(10)))),
        )
        assertFalse(CapabilityIds.STATE_READER_SETTING in withoutRestore)
    }

    // --- Ricontrollo allo scatto -----------------------------------------------------------------------------------

    private fun rule(d: AutomationDraft): Automation {
        val value = Automation(
            AutomationId("span"), d.name, CreatedBy.USER, AutomationStatus.ARMED, d.trigger, d.actions,
            vars = d.vars,
            cooldownMs = d.cooldownMs,
            exit = d.exit,
            schemaVersion = AutomationSchema.versionFor(d),
        )
        return value.copy(approvalFingerprint = ApprovalFingerprints.of(value))
    }

    private fun policy(available: Set<String>) = RevalidatingFirePolicy(
        FirePolicySnapshotProvider { FirePolicySnapshot(setOf("whatsapp_reply"), available, emptySet()) },
    )

    @Test
    fun `at fire time a shell in the end actions is checked on the end trigger`() = runTest {
        val rule = rule(draft(trigger = evening, exit = ExitSpec(Trigger.UserModeChanged("Casa"), actions = listOf(Action.RunShell("svc wifi enable")))))
        val all = CapabilityRequirements.derive(rule)
        val decision = policy(all).evaluate(rule, TriggerEvent.UserModeChanged("Casa", null), SpanPhase.EXIT)
        assertIs<FirePolicyDecision.Block>(decision)
        assertEquals("shell_external_trigger", decision.code)
        assertTrue(decision.needsReview)
    }

    @Test
    fun `an unverified scan of the end tag never presses the shell switch of a different tag`() = runTest {
        val end = Trigger.NfcTag("04:A2:19")
        val rule = rule(draft(trigger = evening, exit = ExitSpec(end, actions = listOf(Action.RunShell("svc wifi enable")))))
        val all = CapabilityRequirements.derive(rule)
        val other = policy(all).evaluate(rule, TriggerEvent.NfcTagScanned("FF:FF", verified = false), SpanPhase.EXIT)
        assertIs<FirePolicyDecision.Block>(other)
        assertEquals("shell_external_trigger", other.code)
        // Il tag approvato non verificato fa partire la fine; la foglia shell la ferma l'esecutore sull'evento vero.
        assertEquals(
            FirePolicyDecision.Allow,
            policy(all).evaluate(rule, TriggerEvent.NfcTagScanned("04:A2:19", verified = false), SpanPhase.EXIT),
        )
    }

    @Test
    fun `the end needs only what it runs, each restored value is checked on its own`() = runTest {
        val rule = rule(
            draft(
                trigger = evening,
                actions = listOf(Action.SetAirplane(true), Action.SetVolume(VolumeStream.MEDIA, 0)),
                exit = ExitSpec(morning),
            ),
        )
        val all = CapabilityRequirements.derive(rule)
        val event = TriggerEvent.TimeFired(rule.id, requireNotNull(rule.approvalFingerprint), SpanPhase.EXIT)
        val withoutAirplane = all - CapabilityIds.ACTION_SET_AIRPLANE
        // L'inizio chiede tutto, la fine solo il suo trigger: il volume si rimette anche senza Shizuku.
        assertIs<FirePolicyDecision.Block>(policy(withoutAirplane).evaluate(rule, event.copy(phase = SpanPhase.START), SpanPhase.START))
        assertEquals(FirePolicyDecision.Allow, policy(withoutAirplane).evaluate(rule, event, SpanPhase.EXIT))
        val restore = policy(withoutAirplane)
        val airplane = restore.evaluateRestore(Action.SetAirplane(false))
        assertIs<FirePolicyDecision.Block>(airplane)
        assertEquals(SpanCodes.RESTORE_CAPABILITY_UNAVAILABLE, airplane.code)
        assertEquals(FirePolicyDecision.Allow, restore.evaluateRestore(Action.SetVolume(VolumeStream.MEDIA, 30)))
        // Un ripristino non è mai un comando.
        assertIs<FirePolicyDecision.Block>(restore.evaluateRestore(Action.RunShell("echo")))
    }
}
