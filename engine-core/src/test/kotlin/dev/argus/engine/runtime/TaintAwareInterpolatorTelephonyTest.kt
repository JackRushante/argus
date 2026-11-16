package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.IntentExtra
import dev.argus.engine.model.IntentExtraType
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Forma risolta di send_sms, call_number, send_intent e screen_call (0.8). */
class TaintAwareInterpolatorTelephonyTest {
    private val interpolator = TaintAwareInterpolator()

    private fun external(text: String, provenance: ValueProvenance) = VarValue(
        text,
        VarType.TEXT,
        IntegrityLabel.TAINTED,
        ConfidentialityLabel.PRIVATE,
        setOf(provenance),
    )

    private fun resolved(action: Action, scope: VarScope = VarScope()): Action =
        assertIs<ActionResolution.Resolved>(interpolator.resolve(action, scope)).value.action

    private fun blocked(action: Action, scope: VarScope = VarScope()): String =
        assertIs<ActionResolution.Blocked>(interpolator.resolve(action, scope)).code

    @Test
    fun `the caller number fills the destination and the text`() {
        val scope = VarScope(
            mapOf(
                "caller" to external("+393331112222", ValueProvenance.PHONE),
                "body" to external("dove sei?", ValueProvenance.SMS),
            ),
        )
        assertEquals(
            Action.SendSms("+393331112222", "Hai scritto: dove sei?"),
            resolved(Action.SendSms("\${caller}", "Hai scritto: \${body}"), scope),
        )
        assertEquals(
            Action.SendSms("3331112222", "ok"),
            resolved(Action.SendSms("\${caller}", "ok"), VarScope(mapOf("caller" to external("3331112222", ValueProvenance.SMS)))),
        )
    }

    @Test
    fun `a forged or hidden caller id that is not a plain number is blocked`() {
        listOf("*21*3331112222#", "4242", "+39 333 1112222", "", "Sconosciuto", "3331112222\n").forEach { forged ->
            val scope = VarScope(mapOf("caller" to external(forged, ValueProvenance.PHONE)))
            assertEquals("sms_number_invalid", blocked(Action.SendSms("\${caller}", "ok"), scope), forged)
        }
    }

    @Test
    fun `a destination that does not come from the caller or sender is blocked`() {
        listOf(ValueProvenance.NOTIFICATION, ValueProvenance.NETWORK, ValueProvenance.MODEL, ValueProvenance.LITERAL).forEach {
            val scope = VarScope(mapOf("caller" to external("+393331112222", it)))
            assertEquals("sms_number_not_sender", blocked(Action.SendSms("\${caller}", "ok"), scope), it.name)
        }
        assertEquals("variable_unavailable", blocked(Action.SendSms("\${caller}", "ok")))
    }

    @Test
    fun `the filled text is at most 480 characters`() {
        val scope = VarScope(mapOf("body" to external("x".repeat(470), ValueProvenance.SMS)))
        assertEquals("interpolation_too_long", blocked(Action.SendSms("+393331112222", "Hai scritto: \${body}"), scope))
        assertEquals("resolved_action_invalid", blocked(Action.SendSms("+393331112222", " ")))
        assertEquals("sms_number_invalid", blocked(Action.SendSms("", "ok")))
    }

    @Test
    fun `a call needs a valid literal number`() {
        assertEquals(Action.CallNumber("+393331112222"), resolved(Action.CallNumber("+393331112222")))
        assertEquals("resolved_action_invalid", blocked(Action.CallNumber("*#06#")))
        assertEquals("resolved_action_invalid", blocked(Action.CallNumber("\${caller}")))
    }

    @Test
    fun `a string extra is filled and checked again at fire time`() {
        val intent = Action.SendIntent(
            IntentKind.BROADCAST,
            action = "com.example.SMS",
            extras = listOf(
                IntentExtra("body", IntentExtraType.STRING, "msg: \${body}"),
                IntentExtra("n", IntentExtraType.INT, "3"),
            ),
        )
        val ok = VarScope(mapOf("body" to external("ciao", ValueProvenance.SMS)))
        assertEquals(
            intent.copy(extras = listOf(IntentExtra("body", IntentExtraType.STRING, "msg: ciao"), intent.extras[1])),
            resolved(intent, ok),
        )
        val newline = VarScope(mapOf("body" to external("a\nb", ValueProvenance.SMS)))
        assertEquals("resolved_action_invalid", blocked(intent, newline))
    }

    @Test
    fun `forbidden intents are blocked at fire time too`() {
        listOf(
            Action.SendIntent(IntentKind.ACTIVITY, action = "android.intent.action.CALL", data = "tel:112"),
            Action.SendIntent(IntentKind.ACTIVITY, pkg = "dev.argus", cls = "dev.argus.MainActivity"),
            Action.SendIntent(IntentKind.ACTIVITY, action = "android.intent.action.VIEW", data = "file:///sdcard/a"),
            Action.SendIntent(IntentKind.SERVICE, action = "com.example.SYNC"),
        ).forEach { assertEquals("resolved_action_invalid", blocked(it), it.toString()) }
    }

    @Test
    fun `screen call resolves unchanged`() {
        val action = Action.ScreenCall(ScreenCallOp.REJECT, onlyUnknownCallers = true)
        assertEquals(action, resolved(action))
    }
}
