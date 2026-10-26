package dev.argus.automation

import dev.argus.device.BatteryReading
import dev.argus.device.PublicStateReader
import dev.argus.device.PublicStateSource
import dev.argus.device.StateReader
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.RevalidatingFirePolicy
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.4.0 (F19): condizioni su batteria, schermo, suoneria e le altre chiavi di stato anche senza
 * Shizuku. Composizione del lettore pubblico con lo shell e prova end-to-end con il motore reale.
 */
class StateWithoutShizukuTest {
    // --- Composizione in LazyDeviceStateProvider --------------------------------------------------

    @Test
    fun `without Shizuku the state keys come from the public source and the shell is never used`() = runTest {
        val shell = ShellRecorder()
        val source = PublicSource(
            battery = BatteryReading(level = 64, scale = 100, status = 2),
            screen = true,
            ringer = 2,
            filter = 1,
            airplane = 0,
            wifi = true,
            bluetooth = true,
        )

        listOf(
            ShizukuGatewayStatus.NOT_INSTALLED,
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
        ).forEach { status ->
            val state = provider(shell, source, status)
                .current(StateReadRequest(keys = StateKeys.ALL.keys))

            assertEquals(
                mapOf(
                    StateKeys.RINGER to "normal",
                    StateKeys.WIFI to "on",
                    StateKeys.BLUETOOTH to "on",
                    StateKeys.DND to "off",
                    StateKeys.BATTERY to "64",
                    StateKeys.CHARGING to "true",
                    StateKeys.AIRPLANE to "off",
                    StateKeys.SCREEN to "on",
                ),
                state.values,
                status.name,
            )
        }
        assertEquals(emptyList(), shell.calls)
    }

    @Test
    fun `an authorized Shizuku is not used for keys the public source already read`() = runTest {
        val shell = ShellRecorder()
        val source = PublicSource(screen = false, ringer = 0)

        val state = provider(shell, source, ShizukuGatewayStatus.AUTHORIZED)
            .current(StateReadRequest(keys = setOf(StateKeys.SCREEN, StateKeys.RINGER)))

        assertEquals(mapOf(StateKeys.SCREEN to "off", StateKeys.RINGER to "silent"), state.values)
        assertEquals(emptyList(), shell.calls)
    }

    @Test
    fun `only the requested keys are read from the public source`() = runTest {
        val shell = ShellRecorder()
        val source = PublicSource(battery = BatteryReading(10, 100, 3), screen = true, ringer = 1)

        val state = provider(shell, source, ShizukuGatewayStatus.NOT_INSTALLED)
            .current(StateReadRequest(keys = setOf(StateKeys.CHARGING)))

        assertEquals(mapOf(StateKeys.CHARGING to "false"), state.values)
        assertEquals(listOf("battery"), source.calls)
    }

    @Test
    fun `a key the public source cannot read falls back to Shizuku only when it is authorized`() = runTest {
        // Bluetooth senza permesso: il lettore pubblico non lo da'. Con Shizuku autorizzato si usa lo
        // shell (come prima), solo per quella chiave; senza Shizuku resta assente.
        val noPermission = PublicSource(bluetooth = true, bluetoothPermission = false, screen = true)
        val keys = setOf(StateKeys.BLUETOOTH, StateKeys.SCREEN)

        val authorizedShell = ShellRecorder(settings = mapOf("bluetooth_on" to "1"))
        val authorized = provider(authorizedShell, noPermission, ShizukuGatewayStatus.AUTHORIZED)
            .current(StateReadRequest(keys = keys))
        assertEquals(mapOf(StateKeys.BLUETOOTH to "on", StateKeys.SCREEN to "on"), authorized.values)
        assertEquals(
            listOf(listOf("/system/bin/settings", "get", "global", "bluetooth_on")),
            authorizedShell.calls,
        )

        val withoutShizukuShell = ShellRecorder(settings = mapOf("bluetooth_on" to "1"))
        val without = provider(withoutShizukuShell, noPermission, ShizukuGatewayStatus.NOT_INSTALLED)
            .current(StateReadRequest(keys = keys))
        assertEquals(mapOf(StateKeys.SCREEN to "on"), without.values)
        assertEquals(emptyList(), withoutShizukuShell.calls)
    }

    @Test
    fun `an unreadable key stays absent and is never replaced by an empty string`() = runTest {
        val state = provider(ShellRecorder(), PublicSource(), ShizukuGatewayStatus.NOT_INSTALLED)
            .current(StateReadRequest(keys = StateKeys.ALL.keys))

        assertEquals(emptyMap(), state.values)
    }

    @Test
    fun `the foreground app still goes through Shizuku while the keys stay public`() = runTest {
        val shell = ShellRecorder(
            dumpsys = mapOf(
                "activity" to "  Display #0 (activities from top to bottom):\n" +
                    "    topResumedActivity=ActivityRecord{1 u0 com.example.app/.Main t1}\n",
            ),
        )
        val source = PublicSource(screen = true)

        val authorized = provider(shell, source, ShizukuGatewayStatus.AUTHORIZED).current(
            StateReadRequest(keys = setOf(StateKeys.SCREEN), includeForegroundApp = true),
        )
        assertEquals(mapOf(StateKeys.SCREEN to "on"), authorized.values)
        assertEquals("com.example.app", authorized.foregroundApp)
        assertEquals(listOf(listOf("/system/bin/dumpsys", "activity", "activities")), shell.calls)

        val shellOff = ShellRecorder()
        val without = provider(shellOff, source, ShizukuGatewayStatus.NOT_INSTALLED).current(
            StateReadRequest(keys = setOf(StateKeys.SCREEN), includeForegroundApp = true),
        )
        assertEquals(mapOf(StateKeys.SCREEN to "on"), without.values)
        assertEquals(null, without.foregroundApp)
        assertEquals(emptyList(), shellOff.calls)
    }

    @Test
    fun `a provider without a public reader behaves exactly as before`() = runTest {
        val shell = ShellRecorder(settings = mapOf("wifi_on" to "1"))
        val legacy = LazyDeviceStateProvider(
            StateReader(shell),
            { ShizukuGatewayStatus.NOT_INSTALLED },
            CurrentLocationProvider { null },
        )

        assertEquals(emptyMap(), legacy.current(StateReadRequest(keys = setOf(StateKeys.WIFI))).values)
        assertEquals(emptyList(), shell.calls)
    }

    // --- Motore reale: «solo se in carica» scatta senza Shizuku ----------------------------------

    @Test
    fun `a charging only rule fires without Shizuku and the shell is never touched`() = runTest {
        val rule = rule("charging", Condition.StateEquals(StateKeys.CHARGING, CmpOp.EQ, "true"))
        val run = Run(rule, PublicSource(battery = BatteryReading(40, 100, 2)))

        run.fire("evt-1")

        assertEquals(listOf<Action>(Action.Vibrate(200)), run.executed)
        assertEquals(emptyList(), run.shell.calls)
        assertTrue(run.audit.none { it.kind == AuditKind.BLOCKED_POLICY }, "nessun blocco di capability")
    }

    @Test
    fun `a rule on the screen being off fires only when the screen is off`() = runTest {
        val rule = rule("screen-off", Condition.StateEquals(StateKeys.SCREEN, CmpOp.EQ, "off"))

        val off = Run(rule, PublicSource(screen = false))
        off.fire("evt-1")
        assertEquals(1, off.executed.size)

        val on = Run(rule, PublicSource(screen = true))
        on.fire("evt-1")
        assertEquals(emptyList(), on.executed)
        assertTrue(on.audit.any { it.kind == AuditKind.CONDITIONS_NOT_MET })
    }

    @Test
    fun `a rule on the ringer and on the battery level fires on the public values`() = runTest {
        val rule = rule(
            "ringer-battery",
            Condition.And(
                listOf(
                    Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "normal"),
                    Condition.StateEquals(StateKeys.BATTERY, CmpOp.LT, "20"),
                ),
            ),
        )

        val low = Run(rule, PublicSource(ringer = 2, battery = BatteryReading(15, 100, 3)))
        low.fire("evt-1")
        assertEquals(1, low.executed.size)

        val high = Run(rule, PublicSource(ringer = 2, battery = BatteryReading(80, 100, 3)))
        high.fire("evt-1")
        assertEquals(emptyList(), high.executed)

        val silent = Run(rule, PublicSource(ringer = 0, battery = BatteryReading(15, 100, 3)))
        silent.fire("evt-1")
        assertEquals(emptyList(), silent.executed)
    }

    @Test
    fun `an unreadable state keeps the rule from firing even under not`() = runTest {
        val plain = rule("plain", Condition.StateEquals(StateKeys.CHARGING, CmpOp.EQ, "true"))
        val negated = rule(
            "negated",
            Condition.Not(Condition.StateEquals(StateKeys.CHARGING, CmpOp.EQ, "true")),
        )

        listOf(plain, negated).forEach { rule ->
            val run = Run(rule, PublicSource(battery = null))
            run.fire("evt-1")

            assertEquals(emptyList(), run.executed, rule.name)
            val skipped = run.audit.single { it.kind == AuditKind.CONDITIONS_NOT_MET }
            assertEquals("condition_state_unavailable", skipped.detail, rule.name)
        }
    }

    @Test
    fun `a bluetooth rule is blocked without the permission and never reviewed`() = runTest {
        val rule = rule("bluetooth", Condition.StateEquals(StateKeys.BLUETOOTH, CmpOp.EQ, "on"))
        val run = Run(rule, PublicSource(bluetooth = true, bluetoothPermission = false), bluetoothGranted = false)

        run.fire("evt-1")

        assertEquals(emptyList(), run.executed)
        assertEquals(
            "capability_unavailable",
            run.audit.single { it.kind == AuditKind.BLOCKED_POLICY }.detail,
        )
        assertEquals(AutomationStatus.ARMED, run.store.get(rule.id)?.status)

        val granted = Run(rule, PublicSource(bluetooth = true, bluetoothPermission = true), bluetoothGranted = true)
        granted.fire("evt-1")
        assertEquals(1, granted.executed.size)
    }

    // --- Fixture -----------------------------------------------------------------------------------

    private fun provider(
        shell: PrivilegedShell,
        source: PublicStateSource,
        status: ShizukuGatewayStatus,
    ) = LazyDeviceStateProvider(
        StateReader(shell),
        { status },
        CurrentLocationProvider { null },
        publicReader = PublicStateReader(source),
    )

    private fun rule(id: String, condition: Condition): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            conditions = condition,
            actions = listOf(Action.Vibrate(200)),
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    /** Motore reale + probe reale + lettore pubblico finto, con Shizuku NON installato. */
    private class Run(
        private val rule: Automation,
        source: PublicStateSource,
        bluetoothGranted: Boolean = true,
    ) {
        val shell = ShellRecorder()
        val store = StateRuleStore(rule)
        val audit = mutableListOf<AuditEvent>()
        val executed = mutableListOf<Action>()

        private val probe = AndroidCapabilityProbe(
            AndroidCapabilityStateSource {
                AndroidCapabilityState(
                    deviceModel = "test",
                    androidVersion = 16,
                    androidApi = 36,
                    shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
                    shizukuPermissionGranted = false,
                    notificationsGranted = false,
                    notificationListenerGranted = false,
                    foregroundLocationGranted = false,
                    backgroundLocationGranted = false,
                    exactAlarmsGranted = false,
                    batteryOptimizationExempt = false,
                    bluetoothConnectGranted = bluetoothGranted,
                )
            },
            NoContacts,
            GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
        )
        private val state = LazyDeviceStateProvider(
            StateReader(shell),
            { ShizukuGatewayStatus.NOT_INSTALLED },
            CurrentLocationProvider { null },
            publicReader = PublicStateReader(source),
        )
        private val engine = Engine(
            store = store,
            executor = ActionExecutor { action, _ ->
                executed += action
                ActionResult.Success
            },
            evaluator = ConditionEvaluator(Clock.systemUTC()),
            matcher = TriggerMatcher(),
            firePolicy = RevalidatingFirePolicy(probe),
            audit = object : AuditSink {
                override suspend fun record(e: AuditEvent) {
                    audit += e
                }
            },
            now = { 1_000 },
        )

        suspend fun fire(eventId: String) {
            engine.onTrigger(
                TriggerEnvelope(
                    TriggerEventId(eventId),
                    TriggerEvent.TimeFired(rule.id, requireNotNull(rule.approvalFingerprint)),
                ),
            ) { request -> state.current(request) }
        }
    }
}

internal class PublicSource(
    private val battery: BatteryReading? = null,
    private val screen: Boolean? = null,
    private val ringer: Int? = null,
    private val filter: Int? = null,
    private val airplane: Int? = null,
    private val wifi: Boolean? = null,
    private val bluetooth: Boolean? = null,
    private val bluetoothPermission: Boolean = true,
) : PublicStateSource {
    val calls = mutableListOf<String>()

    private fun <T> record(name: String, value: T?): T? {
        calls += name
        return value
    }

    override fun battery() = record("battery", battery)
    override fun screenInteractive() = record("screen", screen)
    override fun ringerMode() = record("ringer", ringer)
    override fun interruptionFilter() = record("dnd", filter)
    override fun airplaneMode() = record("airplane", airplane)
    override fun wifiEnabled() = record("wifi", wifi)
    override fun canReadBluetooth() = bluetoothPermission
    override fun bluetoothEnabled() = record("bluetooth", bluetooth)
}

internal class ShellRecorder(
    private val settings: Map<String, String> = emptyMap(),
    private val dumpsys: Map<String, String> = emptyMap(),
) : PrivilegedShell {
    val calls = mutableListOf<List<String>>()

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        calls += command
        val stdout = when {
            command.getOrNull(1) == "get" -> settings[command.lastOrNull()].orEmpty()
            command.firstOrNull()?.endsWith("dumpsys") == true -> dumpsys[command.getOrNull(1)].orEmpty()
            else -> ""
        }
        return ShellResult(exitCode = 0, stdout = stdout.toByteArray())
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = error("not used")
}

internal object NoContacts : ContactWhitelistStore {
    override suspend fun all(): List<WhitelistedContact> = emptyList()
    override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
    override suspend fun upsert(contact: WhitelistedContact) = Unit
    override suspend fun remove(conversationId: String) = Unit
}

internal class StateRuleStore(initial: Automation) : AutomationStore {
    private val values = MutableStateFlow(mapOf(initial.id to initial))
    private val claims = mutableSetOf<Pair<AutomationId, TriggerEventId>>()

    override suspend fun get(id: AutomationId): Automation? = values.value[id]
    override suspend fun all(): List<Automation> = values.value.values.toList()
    override fun observeAll(): Flow<List<Automation>> = flowOf(values.value.values.toList())
    override suspend fun armed(): List<Automation> = values.value.values.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }
    override suspend fun delete(id: AutomationId) { values.value -= id }
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) =
        false
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        if (claims.add(request.automationId to request.eventId)) {
            FireClaimResult.Claimed
        } else {
            FireClaimResult.NotEligible
        }
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null
}
