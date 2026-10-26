package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.4.1: i trigger di sistema senza Shizuku si annunciano solo dove funzionano. */
class AndroidCapabilityProbeSystemTriggersTest {
    private fun state(
        shizuku: ShizukuGatewayStatus = ShizukuGatewayStatus.NOT_INSTALLED,
        multiSim: Boolean = false,
        receiveSms: Boolean = false,
        calls: Boolean = false,
    ) = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = shizuku,
        shizukuPermissionGranted = false,
        notificationsGranted = false,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = false,
        batteryOptimizationExempt = false,
        receiveSmsGranted = receiveSms,
        readPhoneStateGranted = calls,
        readCallLogGranted = calls,
        multiSim = multiSim,
    )

    private fun probe(state: AndroidCapabilityState) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        object : ContactWhitelistStore {
            override suspend fun all(): List<WhitelistedContact> = emptyList()
            override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
            override suspend fun upsert(contact: WhitelistedContact) = Unit
            override suspend fun remove(conversationId: String) = Unit
        },
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
    )

    private val systemTriggers = listOf("screen", "battery", "audio_output", "mode_changed", "media")

    @Test
    fun `the system triggers are announced and armable without Shizuku and without any grant`() = runTest {
        val probe = probe(state())

        val manifest = probe.probe(DeviceState())
        val snapshot = probe.current()

        systemTriggers.forEach { wire ->
            assertTrue(wire in manifest.availableTriggers, "$wire manca da available_triggers")
        }
        AndroidCapabilityProbe.DEVICE_EVENT_TRIGGER_CAPABILITIES.forEach { capability ->
            assertTrue(capability in snapshot.availableCapabilities, "$capability non pubblicata")
        }
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_SCREEN,
                CapabilityIds.TRIGGER_BATTERY,
                CapabilityIds.TRIGGER_AUDIO_OUTPUT,
                CapabilityIds.TRIGGER_MODE_CHANGED,
                CapabilityIds.TRIGGER_MEDIA,
            ),
            AndroidCapabilityProbe.DEVICE_EVENT_TRIGGER_CAPABILITIES,
        )
    }

    @Test
    fun `every system trigger requires a capability the probe really publishes`() = runTest {
        val snapshot = probe(state()).current()
        val triggers = listOf(
            Trigger.Screen(ScreenEvent.ON),
            Trigger.Battery(below = 20),
            Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED),
            Trigger.ModeChanged(ModeKind.DND),
            Trigger.Media(MediaPlayState.PLAYING),
        )
        triggers.forEach { trigger ->
            val required = CapabilityRequirements.derive(trigger, listOf<Action>(Action.Vibrate(100)))
                .filterNot { it.startsWith("action.") }
            assertTrue(required.isNotEmpty())
            assertTrue(snapshot.availableCapabilities.containsAll(required), "$trigger: $required")
        }
    }

    @Test
    fun `the sim filter is announced only on a multi sim phone with sms or call grants`() = runTest {
        // Telefono a una SIM: il filtro non ha senso e non si pubblica, nemmeno con i permessi.
        val single = probe(state(multiSim = false, receiveSms = true, calls = true))
        assertFalse("phone_state.sim" in single.probe(DeviceState()).availableTriggers)
        assertFalse(CapabilityIds.TRIGGER_PHONE_SIM in single.current().availableCapabilities)

        // Due SIM ma nessun permesso: non c'è niente su cui filtrare.
        val noGrants = probe(state(multiSim = true))
        assertFalse("phone_state.sim" in noGrants.probe(DeviceState()).availableTriggers)

        val sms = probe(state(multiSim = true, receiveSms = true))
        assertTrue("phone_state.sim" in sms.probe(DeviceState()).availableTriggers)
        val calls = probe(state(multiSim = true, calls = true))
        assertTrue("phone_state.sim" in calls.probe(DeviceState()).availableTriggers)
        assertTrue(CapabilityIds.TRIGGER_PHONE_SIM in calls.current().availableCapabilities)
    }

    @Test
    fun `a sim filtered rule needs both the event grant and the sim capability`() = runTest {
        val snapshot = probe(state(multiSim = true, receiveSms = true)).current()
        val smsSim = CapabilityRequirements.derive(Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, sim = 2), emptyList())
        assertTrue(snapshot.availableCapabilities.containsAll(smsSim))

        // Con le sole autorizzazioni SMS, una chiamata persa filtrata per SIM non è armabile.
        val missedSim = CapabilityRequirements.derive(Trigger.PhoneState(PhoneEvent.MISSED_CALL, sim = 1), emptyList())
        assertFalse(snapshot.availableCapabilities.containsAll(missedSim))
        // Una chiamata persa senza filtro richiede i permessi delle chiamate, come INCOMING_CALL.
        assertEquals(
            CapabilityRequirements.derive(Trigger.PhoneState(PhoneEvent.INCOMING_CALL), emptyList()),
            CapabilityRequirements.derive(Trigger.PhoneState(PhoneEvent.MISSED_CALL), emptyList()),
        )
    }

    @Test
    fun `the phone call wire name also covers the missed call`() = runTest {
        val manifest = probe(state(calls = true)).probe(DeviceState())
        assertTrue("phone_state.call" in manifest.availableTriggers)
        assertTrue(CapabilityIds.TRIGGER_PHONE_CALL in probe(state(calls = true)).current().availableCapabilities)
    }
}
