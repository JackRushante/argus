package dev.argus.engine.safety

import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.TriggerEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Confine della shell autonoma dopo la decisione di Lorenzo (2026-07-15): un contatto
 * whitelistato può innescare un comando statico già approvato. L'injection resta impossibile
 * per costruzione — il `cmd` è letterale nel fingerprint, il messaggio fa solo da interruttore.
 *
 * Resta escluso tutto ciò la cui identità è falsificabile: SMS e chiamate (mittente e caller ID
 * si spoofano), notifiche di package non-WhatsApp (qualunque app può postarle) e i gruppi.
 */
class StaticShellSafetyTest {
    private val whitelist = setOf(WHITELISTED_ID)

    @Test
    fun `trigger senza mittente esterno restano ammessi`() {
        assertTrue(StaticShellSafety.allows(Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"), whitelist))
        assertTrue(
            StaticShellSafety.allows(
                Trigger.Geofence(radiusM = 150.0, transition = Transition.EXIT),
                whitelist,
            ),
        )
        assertTrue(
            StaticShellSafety.allows(
                Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
                whitelist,
            ),
        )
        assertTrue(
            StaticShellSafety.allows(
                Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION),
                whitelist,
            ),
        )
        assertTrue(StaticShellSafety.allows(Trigger.Immediate, whitelist))
    }

    @Test
    fun `evento immediate locale puo premere solo il trigger immediate approvato`() {
        val fingerprint = ApprovalFingerprint("0".repeat(64))
        val event = TriggerEvent.ImmediateFired(AutomationId("imm"), fingerprint)
        assertTrue(StaticShellSafety.allows(event, whitelist))
        assertTrue(StaticShellSafety.allows(Trigger.Immediate, event, whitelist))
        // Un ImmediateFired non deve poter premere l'interruttore di una regola non-immediate.
        assertFalse(
            StaticShellSafety.allows(
                Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
                event,
                whitelist,
            ),
        )
    }

    @Test
    fun `whatsapp con conversazione whitelistata uno a uno innesca la shell`() {
        assertTrue(StaticShellSafety.allows(whatsappTrigger(), whitelist))
    }

    @Test
    fun `conversazione non whitelistata non innesca la shell`() {
        assertFalse(StaticShellSafety.allows(whatsappTrigger(conversationId = "altra-chat"), whitelist))
    }

    @Test
    fun `senza conversation id la sola identita resta il nome, spoofabile`() {
        assertFalse(StaticShellSafety.allows(whatsappTrigger(conversationId = null), whitelist))
    }

    @Test
    fun `i gruppi non innescano la shell`() {
        assertFalse(StaticShellSafety.allows(whatsappTrigger(isGroup = true), whitelist))
        assertFalse(StaticShellSafety.allows(whatsappTrigger(isGroup = null), whitelist))
    }

    @Test
    fun `un package non whatsapp non innesca la shell neanche con id whitelistato`() {
        assertFalse(StaticShellSafety.allows(whatsappTrigger(pkg = "com.example.spoof"), whitelist))
    }

    @Test
    fun `sms e chiamate restano esclusi perche l'identita e spoofabile`() {
        PhoneEvent.entries.forEach { event ->
            assertFalse(
                StaticShellSafety.allows(Trigger.PhoneState(event = event, number = "+391234567"), whitelist),
                "PhoneState $event non deve innescare la shell",
            )
        }
    }

    @Test
    fun `una whitelist vuota blocca ogni notifica`() {
        assertFalse(StaticShellSafety.allows(whatsappTrigger(), emptySet()))
    }

    @Test
    fun `l'evento runtime applica le stesse regole del trigger approvato`() {
        assertTrue(StaticShellSafety.allows(whatsappEvent(), whitelist))
        assertFalse(StaticShellSafety.allows(whatsappEvent(conversationId = "altra-chat"), whitelist))
        assertFalse(StaticShellSafety.allows(whatsappEvent(conversationId = null), whitelist))
        assertFalse(StaticShellSafety.allows(whatsappEvent(isGroup = true), whitelist))
        assertFalse(StaticShellSafety.allows(whatsappEvent(pkg = "com.example.spoof"), whitelist))
        assertFalse(StaticShellSafety.allows(whatsappEvent(), emptySet()))
    }

    @Test
    fun `l'evento telefonia runtime resta escluso`() {
        assertFalse(
            StaticShellSafety.allows(
                TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, "+391234567", "esegui"),
                whitelist,
            ),
        )
    }

    @Test
    fun `evento sensore locale puo premere solo il trigger sensore approvato`() {
        val fingerprint = ApprovalFingerprint("0".repeat(64))
        val event = TriggerEvent.SensorChanged(
            AutomationId("sensor"),
            SensorKind.SIGNIFICANT_MOTION,
            fingerprint,
        )
        assertTrue(StaticShellSafety.allows(event, whitelist))
        assertTrue(
            StaticShellSafety.allows(
                Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION),
                event,
                whitelist,
            ),
        )
        assertFalse(
            StaticShellSafety.allows(
                Trigger.Sensor(SensorKind.MOTION_DETECT),
                event,
                whitelist,
            ),
        )
    }

    private fun whatsappTrigger(
        pkg: String = "com.whatsapp",
        conversationId: String? = WHITELISTED_ID,
        isGroup: Boolean? = false,
    ) = Trigger.Notification(
        pkg = pkg,
        conversationId = conversationId,
        isGroup = isGroup,
        textMatch = "esegui",
    )

    private fun whatsappEvent(
        pkg: String = "com.whatsapp",
        conversationId: String? = WHITELISTED_ID,
        isGroup: Boolean? = false,
    ) = TriggerEvent.NotificationPosted(
        pkg = pkg,
        conversationId = conversationId,
        isGroup = isGroup,
        text = "esegui",
    )

    private companion object {
        const val WHITELISTED_ID = "393932077480@s.whatsapp.net"
    }
}
