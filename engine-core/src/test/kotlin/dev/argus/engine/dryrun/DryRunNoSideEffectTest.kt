package dev.argus.engine.dryrun

import dev.argus.engine.brain.Brain
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfirmSpec
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.IntentExtra
import dev.argus.engine.model.IntentExtraType
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.MediaCommand
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.SecretRef
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.StoredWrite
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.UiStep
import dev.argus.engine.model.UserModeSnapshot
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.AllowAllFirePolicy
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationPause
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.CallerScopeGate
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.ConfirmationGate
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.QuestionAsker
import dev.argus.engine.runtime.ResolvedActionExecutor
import dev.argus.engine.runtime.SpanDevice
import dev.argus.engine.runtime.SpanPhase
import dev.argus.engine.runtime.SpanStore
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.StoredValueStore
import dev.argus.engine.runtime.SubmittedActionJournal
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.engine.runtime.UserModeStore
import dev.argus.engine.secrets.SecretEntry
import dev.argus.engine.secrets.SecretVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The core safety property of «Prova a secco»: a dry run cannot act. The world below holds every real sink as a
 * fake that FAILS the test when touched — executors, journal, audit, the store's claims and writes, spans, stored
 * values and modes (writes), the vault (value reads), the brain, confirmations and questions. The dry run is built
 * from that world the way the app builds it: only read functions go in. Then every action type runs through it.
 */
class DryRunNoSideEffectTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-17T18:12:00Z"), ZoneOffset.UTC)

    private inner class World {
        val touched: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val executor = forbidden<ActionExecutor>()
        val resolvedExecutor = forbidden<ResolvedActionExecutor>()
        val audit = forbidden<AuditSink>()
        val journal = forbidden<ExecutionJournal>()
        val submitted = forbidden<SubmittedActionJournal>()
        val store = forbidden<AutomationStore>()
        val spans = forbidden<SpanStore>()
        val spanDevice = forbidden<SpanDevice>()
        val brain = forbidden<Brain>()
        val confirmations = forbidden<ConfirmationGate>()
        val questions = forbidden<QuestionAsker>()

        val storedValues = object : StoredValueStore {
            override suspend fun read(keys: Set<String>): Map<String, StoredValue> = mapOf(
                "count" to StoredValue(
                    "count", dev.argus.engine.model.StateValueType.NUMBER, "4",
                    IntegrityLabel.CLEAN, ConfidentialityLabel.PRIVATE, ChangeAuthor.User, 0,
                ),
            ).filterKeys(keys::contains)

            override suspend fun apply(
                action: Action.SetStored,
                integrity: IntegrityLabel,
                confidentiality: ConfidentialityLabel,
                author: ChangeAuthor,
                atMillis: Long,
            ): StoredWrite = touch("StoredValueStore.apply")
        }

        val modes = object : UserModeStore {
            override suspend fun snapshot() = UserModeSnapshot(listOf("Casa", "Lavoro"), active = "Lavoro")
            override suspend fun activate(mode: String?, author: ChangeAuthor, atMillis: Long): ModeSwitch =
                touch("UserModeStore.activate")
        }

        val vault = object : SecretVault {
            override fun observe(): Flow<List<SecretEntry>> = flowOf(listOf(SecretEntry("ha_token")))
            override suspend fun entries() = listOf(SecretEntry("ha_token"))
            override suspend fun valueFor(name: String): String? = touch("SecretVault.valueFor")
            override suspend fun save(name: String, value: String): Boolean = touch("SecretVault.save")
            override suspend fun delete(name: String): Boolean = touch("SecretVault.delete")
        }

        /** The read-only view the app hands to the dry run: state readers and facts, nothing else. */
        suspend fun readState(request: StateReadRequest): DeviceState = request.project(
            DeviceState(
                values = mapOf("wifi" to "on", "dnd" to "off"),
                stored = storedValues.read(request.storedKeys),
                userMode = modes.snapshot(),
            ),
        )

        fun simulator() = DryRunSimulator(
            firePolicy = AllowAllFirePolicy,
            readState = ::readState,
            facts = DryRunFactsSource {
                DryRunFacts(
                    whitelistedConversationIds = setOf(CONV),
                    privilegedReady = true,
                    secretNames = vault.names(),
                    hasTelephony = true,
                    canSendSms = true,
                    canCall = true,
                    ownPackage = "dev.argus",
                )
            },
            pauses = AutomationPause.NEVER,
            callerScope = CallerScopeGate { _, _ -> null },
            clock = clock,
            locale = { Locale.ITALY },
        )

        fun <T> touch(what: String): T {
            touched += what
            throw AssertionError("A dry run touched $what")
        }

        private inline fun <reified T : Any> forbidden(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "forbidden ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> touch<Any?>("${T::class.java.simpleName}.${method.name}")
            }
        } as T
    }

    private val chat = Trigger.Notification("com.whatsapp", conversationId = CONV, isGroup = false)
    private val msg = VarBinding.TriggerPayload("msg", TriggerField.TEXT, confidentiality = ConfidentialityLabel.PRIVATE)

    /** One of every action type: the coverage check below fails when a new type is added without a sample here. */
    private val everyAction: List<Action> = listOf(
        Action.SetWifi(true),
        Action.SetBluetooth(true),
        Action.SetMobileData(false),
        Action.SetDnd(DndMode.PRIORITY),
        Action.SetRinger("vibrate"),
        Action.LaunchApp("com.example.app"),
        Action.OpenUrl("https://example.com/?q=\${msg}"),
        Action.ShowNotification("Nuovo", "\${msg}"),
        Action.Tap(10, 20),
        Action.InputText("ciao"),
        Action.WhatsAppReply("Ricevuto: \${msg}"),
        Action.RunShell("echo \${msg}"),
        Action.CopyToClipboard(),
        Action.CopyText("\${msg}"),
        Action.SetAlarm(7, 30, label = "Sveglia"),
        Action.SetTimer(60),
        Action.SetVolume(VolumeStream.MEDIA, 80),
        Action.SetFlashlight(true),
        Action.SetDarkMode(NightMode.ON),
        Action.OpenSettingsScreen(SettingsScreen.WIFI),
        Action.Vibrate(200),
        Action.Wait(60_000),
        Action.WriteSetting(SettingNamespace.SYSTEM, "screen_brightness", "100"),
        Action.InvokeLlm("Rispondi a \${msg}", listOf("notification"), listOf("whatsapp_reply"), replyTargetSender = true),
        Action.InvokeLlmV2("Rispondi", emptyList(), listOf("whatsapp_reply"), replyTargetSender = true, timeoutMs = 60_000),
        Action.If(Condition.BooleanLiteral(true), then = listOf(Action.Vibrate(1))),
        Action.While(Condition.BooleanLiteral(true), body = listOf(Action.Vibrate(1)), maxIterations = 2),
        Action.Speak("\${msg}"),
        Action.MediaControl(MediaCommand.PAUSE),
        Action.SetBrightness(level = 40),
        Action.SetRotation(true),
        Action.HttpRequest(
            HttpMethod.POST,
            "https://example.com/api",
            body = "{\"t\":\"\${msg}\"}",
            contentType = "application/json",
            captureAs = "resp",
            secretHeaders = listOf(SecretRef("Authorization", "ha_token", "Bearer ")),
        ),
        Action.AppControl("com.example.app", AppControlOp.FORCE_STOP),
        Action.SetAirplane(true),
        Action.SetLocation(true),
        Action.SetNfc(true),
        Action.SetBatterySaver(true),
        Action.LockScreen,
        Action.Screenshot,
        Action.AppPermission("com.example.app", "android.permission.CAMERA", grant = true),
        Action.UiSteps(pkg = "com.example.app", steps = listOf(UiStep.Type("\${msg}"))),
        Action.Ask("Rispondo, \${msg}?", listOf("si", "no"), "answer"),
        Action.SetStored("count", StoredOp.INCREMENT),
        Action.SetUserMode("Casa"),
        Action.NotificationReply("Ricevuto"),
        Action.NotificationAction(NotificationActionOp.DISMISS),
        Action.SendSms("+393331234567", "Arrivo: \${msg}"),
        Action.CallNumber("+393331234567"),
        Action.SendIntent(
            IntentKind.BROADCAST,
            action = "com.example.PING",
            extras = listOf(IntentExtra("m", IntentExtraType.STRING, "\${msg}")),
        ),
        Action.ScreenCall(ScreenCallOp.SILENCE),
        Action.InvokeLlm(
            "È urgente?", emptyList(), emptyList(), replyTargetSender = false,
            deliver = GenerativeDeliverMode.CAPTURE_ONLY, captureAs = "urgent",
            output = OutputSpec(OutputKind.CHOICE, listOf("si", "no")),
        ),
        Action.RunShell("date", captureAs = "now"),
    )

    private fun rule(
        trigger: Trigger,
        actions: List<Action>,
        vars: List<VarBinding> = emptyList(),
        confirm: ConfirmSpec? = null,
        exit: ExitSpec? = null,
    ): Automation {
        val draft = AutomationDraft("Tutto", trigger, actions, vars = vars, confirm = confirm, exit = exit)
        val unsigned = Automation(
            AutomationId("every"), "Tutto", CreatedBy.USER, AutomationStatus.ARMED, trigger, actions,
            vars = vars,
            confirm = confirm,
            exit = exit,
            schemaVersion = AutomationSchema.versionFor(draft),
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private val sample = DryRunSample(sender = "Marta", text = "Sei libero stasera?")

    @Test
    fun `the sample covers every action type`() {
        val declared = Action::class.java.declaredClasses
            .filter { Action::class.java.isAssignableFrom(it) && it != Action::class.java }
            .map { it.simpleName }
            .toSet()
        assertEquals(declared, everyAction.map { it.javaClass.simpleName }.toSet())
    }

    @Test
    fun `every action type, alone, as a program and as a flat rule, touches no sink`() = runTest {
        val world = World()
        for (action in everyAction) {
            for (vars in listOf(listOf(msg), emptyList())) {
                val report = world.simulator().simulate(
                    DryRunRequest(rule(chat, listOf(action), vars), SpanPhase.START, sample, defaultText = "x", conversationLabel = "Marta"),
                )
                assertTrue(report.end != DryRunEnd.NOT_MATCHED && report.end != DryRunEnd.BLOCKED, "$action: ${report.end}")
                assertTrue(report.steps.size > 1, "$action was not reached")
                assertEquals(emptyList(), world.touched.toList(), "$action")
            }
        }
    }

    @Test
    fun `one rule with every action, confirm and a span end touches no sink in either phase`() = runTest {
        val world = World()
        val exit = ExitSpec(Trigger.Manual, actions = everyAction)
        val automation = rule(chat, everyAction, vars = listOf(msg), confirm = ConfirmSpec(60_000), exit = exit)
        for (phase in SpanPhase.entries) {
            val report = world.simulator().simulate(
                DryRunRequest(automation, phase, sample, defaultText = "x", conversationLabel = "Marta"),
            )
            assertTrue(report.steps.size > 1, "$phase: ${report.end}")
        }
        assertEquals(emptyList(), world.touched.toList())
    }

    @Test
    fun `the simulator holds nothing that could act`() {
        val sinks = listOf(
            ActionExecutor::class.java, ResolvedActionExecutor::class.java, AuditSink::class.java,
            ExecutionJournal::class.java, SubmittedActionJournal::class.java, AutomationStore::class.java,
            SpanStore::class.java, SpanDevice::class.java, StoredValueStore::class.java, UserModeStore::class.java,
            SecretVault::class.java, Brain::class.java, ConfirmationGate::class.java, QuestionAsker::class.java,
            Engine::class.java,
        )
        val held = DryRunSimulator::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() } +
            DryRunSimulator::class.java.declaredFields.map { it.type }
        val leaks = held.filter { type -> sinks.any { sink -> sink.isAssignableFrom(type) } }
        assertEquals(emptyList(), leaks)
        // What it does hold: the policy, read functions, values and lookups.
        assertTrue(held.isNotEmpty())
    }

    @Test
    fun `the fakes do fail when something acts, as the real engine on the same world shows`() = runTest {
        val world = World()
        val engine = Engine(
            store = world.store,
            executor = world.executor,
            evaluator = ConditionEvaluator(clock),
            matcher = TriggerMatcher(),
            firePolicy = AllowAllFirePolicy,
            audit = world.audit,
            journal = world.journal,
            resolvedExecutor = world.resolvedExecutor,
            confirmations = world.confirmations,
            questions = world.questions,
            spans = world.spans,
            spanDevice = world.spanDevice,
            now = clock::millis,
        )
        val envelope = TriggerEnvelope(
            TriggerEventId("e1"),
            dev.argus.engine.runtime.TriggerEvent.NotificationPosted("com.whatsapp", CONV, "Marta", null, "ciao", false, "k"),
        )
        assertFailsWith<AssertionError> { engine.onTrigger(envelope) { DeviceState() } }
        assertTrue(world.touched.isNotEmpty())
    }

    private companion object {
        const val CONV = "wa:marta"
    }
}
