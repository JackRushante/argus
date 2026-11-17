package dev.argus.automation.telephony

import dev.argus.automation.base.ActivityStartBlockedException
import dev.argus.automation.base.DEFERRED_TO_NOTIFICATION
import dev.argus.automation.base.DeferredOpenNotifier
import dev.argus.automation.base.DeferredOpenTarget
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.IntentExtra
import dev.argus.engine.model.IntentExtraType
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelephonyActionRunnerTest {
    private class FakeSurface : TelephonySurface {
        override val ownPackage = "dev.argus"
        var telephony = true
        var smsGranted = true
        var callGranted = true
        var foreground = true
        var resolved: Set<String> = emptySet()
        var resolvable = true
        var queryFails = false
        val sent = mutableListOf<Pair<String, String>>()
        val calls = mutableListOf<String>()
        val broadcasts = mutableListOf<Action.SendIntent>()
        val activities = mutableListOf<Action.SendIntent>()

        override fun hasTelephony() = telephony
        override fun canSendSms() = smsGranted
        override fun canCall() = callGranted
        override fun sendSms(number: String, text: String) {
            sent += number to text
        }
        override fun placeCall(number: String) {
            if (!foreground) throw ActivityStartBlockedException()
            calls += number
        }
        override fun receivers(intent: Action.SendIntent): Set<String> =
            if (queryFails) throw IllegalStateException("package manager") else resolved
        override fun sendBroadcast(intent: Action.SendIntent) {
            broadcasts += intent
        }
        override fun startActivity(intent: Action.SendIntent): Boolean {
            if (!foreground) throw ActivityStartBlockedException()
            if (resolvable) activities += intent
            return resolvable
        }
    }

    private val surface = FakeSurface()
    private val audits = mutableListOf<AuditEvent>()
    private val privileged = mutableListOf<List<String>>()
    private val deferred = mutableListOf<DeferredOpenTarget>()
    private var now = 10_000_000L
    private val runner = TelephonyActionRunner(
        surface = surface,
        limiter = TelephonyRateLimiter(InMemoryLedgerStore()) { ClockReading(now, 1, now) },
        audit = object : AuditSink {
            override suspend fun record(e: AuditEvent) {
                audits += e
            }
        },
        privileged = PrivilegedAm { _, argv, _ -> privileged += argv },
        deferredOpen = DeferredOpenNotifier { deferred += it; true },
        now = { now },
    )
    private val ctx = FireContext(
        event = TriggerEvent.PhoneStateChanged(PhoneEvent.MISSED_CALL, "+393331112222"),
        state = DeviceState(),
        automationId = AutomationId("rule-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    @Test
    fun `an sms is sent once checked`() = runTest {
        assertEquals(ActionResult.Success, runner.sendSms(Action.SendSms("+393331112222", "Ti richiamo"), ctx))
        assertEquals(listOf("+393331112222" to "Ti richiamo"), surface.sent)
    }

    @Test
    fun `an sms is refused without telephony, permission or a valid shape`() = runTest {
        assertEquals(ActionResult.Failure("action_invalid"), runner.sendSms(Action.SendSms("*21*333#", "x"), ctx))
        assertEquals(ActionResult.Failure("action_invalid"), runner.sendSms(Action.SendSms("+393331112222", "x".repeat(481)), ctx))
        surface.smsGranted = false
        assertEquals(ActionResult.Failure("sms_send_permission_missing"), runner.sendSms(Action.SendSms("+393331112222", "x"), ctx))
        surface.telephony = false
        assertEquals(ActionResult.Failure("telephony_unavailable"), runner.sendSms(Action.SendSms("+393331112222", "x"), ctx))
        assertEquals(emptyList(), surface.sent)
    }

    @Test
    fun `the hourly caps stop the send and are written in the log without the number`() = runTest {
        repeat(3) { runner.sendSms(Action.SendSms("+393331112222", "x"), ctx) }
        assertEquals(
            ActionResult.Failure("sms_destination_rate_limited"),
            runner.sendSms(Action.SendSms("+393331112222", "x"), ctx),
        )
        assertEquals(3, surface.sent.size)
        val audit = audits.single()
        assertEquals(AuditKind.BLOCKED_POLICY, audit.kind)
        assertEquals("sms_destination_rate_limited", audit.detail)
        assertEquals(AutomationId("rule-1"), audit.automationId)
        assertTrue("3331112222" !in audit.toString())
    }

    @Test
    fun `a call goes through am with the privileged backend`() = runTest {
        assertEquals(ActionResult.Success, runner.callNumber(Action.CallNumber("+393331112222"), ctx, privilegedReady = true))
        assertEquals(
            listOf(listOf("am", "start", "--user", "current", "-a", "android.intent.action.CALL", "-d", "tel:+393331112222")),
            privileged,
        )
        assertEquals(emptyList(), surface.calls)
    }

    @Test
    fun `without the privileged backend a call starts in the foreground or becomes a tap to call notification`() = runTest {
        assertEquals(ActionResult.Success, runner.callNumber(Action.CallNumber("+393331112222"), ctx, privilegedReady = false))
        assertEquals(listOf("+393331112222"), surface.calls)
        surface.foreground = false
        assertEquals(
            ActionResult.Failure(DEFERRED_TO_NOTIFICATION),
            runner.callNumber(Action.CallNumber("+393331112222"), ctx, privilegedReady = false),
        )
        assertEquals(listOf<DeferredOpenTarget>(DeferredOpenTarget.Call("+393331112222")), deferred)
    }

    @Test
    fun `three calls an hour, then the cap`() = runTest {
        repeat(3) { runner.callNumber(Action.CallNumber("+393331112222"), ctx, privilegedReady = true) }
        assertEquals(
            ActionResult.Failure("call_rate_limited"),
            runner.callNumber(Action.CallNumber("+393331112222"), ctx, privilegedReady = true),
        )
        assertEquals(3, privileged.size)
        assertEquals(listOf("call_rate_limited"), audits.map { it.detail })
    }

    @Test
    fun `a call needs the permission and a plain number`() = runTest {
        assertEquals(ActionResult.Failure("action_invalid"), runner.callNumber(Action.CallNumber("*#06#"), ctx, true))
        surface.callGranted = false
        assertEquals(ActionResult.Failure("call_permission_missing"), runner.callNumber(Action.CallNumber("+393331112222"), ctx, true))
        assertEquals(emptyList(), privileged)
    }

    @Test
    fun `a broadcast is sent from the app`() = runTest {
        val intent = Action.SendIntent(IntentKind.BROADCAST, action = "com.example.PING")
        surface.resolved = setOf("com.example.app")
        assertEquals(ActionResult.Success, runner.sendIntent(intent, ctx, privilegedReady = true))
        assertEquals(listOf(intent), surface.broadcasts)
    }

    @Test
    fun `an intent that would reach argus is refused`() = runTest {
        surface.resolved = setOf("com.example.app", "dev.argus")
        assertEquals(
            ActionResult.Failure("intent_target_forbidden"),
            runner.sendIntent(Action.SendIntent(IntentKind.ACTIVITY, action = "android.intent.action.SEND"), ctx, true),
        )
        assertEquals(
            ActionResult.Failure("intent_target_forbidden"),
            runner.sendIntent(Action.SendIntent(IntentKind.BROADCAST, pkg = "dev.argus", action = "a.B"), ctx, true),
        )
        assertEquals(
            ActionResult.Failure("intent_action_forbidden"),
            runner.sendIntent(Action.SendIntent(IntentKind.ACTIVITY, action = "android.intent.action.CALL"), ctx, true),
        )
        assertEquals(emptyList(), privileged)
        assertEquals(emptyList(), surface.broadcasts)
    }

    @Test
    fun `a broadcast without package that argus itself would receive is refused, and so is any doubt`() = runTest {
        // Il receiver di Argus (anche non esportato o registrato a runtime) compare tra chi lo riceverebbe.
        surface.resolved = setOf("com.example.app", "dev.argus")
        val implicit = Action.SendIntent(IntentKind.BROADCAST, action = "android.intent.action.SCREEN_ON")
        assertEquals(ActionResult.Failure("intent_target_forbidden"), runner.sendIntent(implicit, ctx, true))
        // Una variante di Argus o un nome di Argus in qualunque grafia.
        surface.resolved = setOf("dev.argus.debug")
        assertEquals(ActionResult.Failure("intent_target_forbidden"), runner.sendIntent(implicit, ctx, true))
        assertEquals(
            ActionResult.Failure("intent_target_forbidden"),
            runner.sendIntent(Action.SendIntent(IntentKind.BROADCAST, action = "Dev.Argus.RUN"), ctx, true),
        )
        // Se non si sa chi lo riceverebbe, non parte.
        surface.resolved = emptySet()
        surface.queryFails = true
        assertEquals(ActionResult.Failure("intent_target_forbidden"), runner.sendIntent(implicit, ctx, true))
        assertEquals(emptyList(), surface.broadcasts)
        // Un componente esplicito di un'altra app non ha bisogno della risoluzione.
        val explicit = Action.SendIntent(IntentKind.BROADCAST, pkg = "com.example.app", cls = "com.example.app.Rx")
        assertEquals(ActionResult.Success, runner.sendIntent(explicit, ctx, true))
        assertEquals(listOf(explicit), surface.broadcasts)
    }

    @Test
    fun `an activity starts through am, the app, or a tap to open notification`() = runTest {
        val intent = Action.SendIntent(
            IntentKind.ACTIVITY,
            pkg = "com.example.notes",
            cls = "com.example.notes.TodayActivity",
            extras = listOf(IntentExtra("note", IntentExtraType.STRING, "--ez x true; rm -rf /")),
        )
        assertEquals(ActionResult.Success, runner.sendIntent(intent, ctx, privilegedReady = true))
        assertEquals(
            listOf(
                "am", "start", "--user", "current", "-n", "com.example.notes/com.example.notes.TodayActivity",
                "--es", "note", "--ez x true; rm -rf /",
            ),
            privileged.single(),
        )
        assertEquals(ActionResult.Success, runner.sendIntent(intent, ctx, privilegedReady = false))
        assertEquals(listOf(intent), surface.activities)
        surface.foreground = false
        assertEquals(ActionResult.Failure(DEFERRED_TO_NOTIFICATION), runner.sendIntent(intent, ctx, privilegedReady = false))
        assertEquals(listOf<DeferredOpenTarget>(DeferredOpenTarget.IntentActivity(intent)), deferred)
        surface.foreground = true
        surface.resolvable = false
        assertEquals(ActionResult.Failure("intent_unresolved"), runner.sendIntent(intent, ctx, privilegedReady = false))
    }

    @Test
    fun `a service starts only with the privileged backend`() = runTest {
        val intent = Action.SendIntent(IntentKind.SERVICE, pkg = "com.example.app", cls = "com.example.app.Sync", action = "com.example.SYNC")
        assertEquals(ActionResult.Failure("shizuku_unavailable"), runner.sendIntent(intent, ctx, privilegedReady = false))
        assertEquals(ActionResult.Success, runner.sendIntent(intent, ctx, privilegedReady = true))
        assertEquals(
            listOf(
                "am", "startservice", "--user", "current", "-a", "com.example.SYNC",
                "-n", "com.example.app/com.example.app.Sync",
            ),
            privileged.single(),
        )
    }

    @Test
    fun `am carries every field and typed extra as separate arguments`() {
        val intent = Action.SendIntent(
            IntentKind.ACTIVITY,
            action = "android.intent.action.VIEW",
            pkg = "com.example.app",
            data = "geo:45.46,9.19",
            mimeType = "text/plain",
            extras = listOf(
                IntentExtra("s", IntentExtraType.STRING, "a b"),
                IntentExtra("i", IntentExtraType.INT, "1"),
                IntentExtra("l", IntentExtraType.LONG, "2"),
                IntentExtra("b", IntentExtraType.BOOLEAN, "true"),
                IntentExtra("f", IntentExtraType.FLOAT, "0.5"),
            ),
        )
        assertEquals(
            listOf(
                "-a", "android.intent.action.VIEW", "-d", "geo:45.46,9.19", "-t", "text/plain", "-p", "com.example.app",
                "--es", "s", "a b", "--ei", "i", "1", "--el", "l", "2", "--ez", "b", "true", "--ef", "f", "0.5",
            ),
            AmIntentCommand.intentArgs(intent),
        )
    }
}
