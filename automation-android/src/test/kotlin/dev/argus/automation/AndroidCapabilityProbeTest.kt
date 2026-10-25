package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.AUTOMATION_SCHEMA_VERSION_P4
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQueryFamily
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCapabilityProbeTest {
    @Test
    fun `authorized device yields coherent manifest policy snapshot and whitelist`() = runTest {
        val probe = probe(
            state().copy(
                notificationsGranted = true,
                notificationListenerGranted = true,
                foregroundLocationGranted = true,
                exactAlarmsGranted = true,
            ),
            listOf(WhitelistedContact("Moglie", "jid:42")),
        )

        val manifest = probe.probe(DeviceState())
        val snapshot = probe.current()

        assertEquals("CPH2747", manifest.deviceModel)
        assertEquals(16, manifest.androidVersion)
        assertEquals(36, manifest.androidApi)
        assertTrue(manifest.shizukuAvailable)
        assertEquals(StateKeys.ALL, manifest.stateKeys)
        assertEquals(listOf(WhitelistedContact("Moglie", "jid:42")), manifest.whitelistedContacts)
        assertTrue("notification_listener" in manifest.grantedPermissions)
        assertFalse(AndroidCapabilityProbe.TOOL_STATE_READ in manifest.availableTools)
        assertFalse(AndroidCapabilityProbe.TOOL_STATE_READ in manifest.unavailableTools)
        assertTrue(AndroidCapabilityProbe.TOOL_STATE_READ in snapshot.availableCapabilities)
        assertTrue(AndroidCapabilityProbe.TOOL_NOTIFY_SHOW in manifest.availableTools)
        assertTrue(ActionTypeIds.SET_DND in manifest.availableTools)
        assertTrue(ActionTypeIds.SET_WIFI in manifest.availableTools)
        assertTrue(ActionTypeIds.RUN_SHELL in manifest.availableTools)
        assertTrue(ActionTypeIds.SHOW_NOTIFICATION in manifest.availableTools)
        assertTrue("vision.analyze" in manifest.unavailableTools)
        assertTrue(ActionCapabilities.SET_DND in snapshot.availableCapabilities)
        assertTrue(ActionCapabilities.RUN_SHELL in snapshot.availableCapabilities)
        assertTrue(ActionCapabilities.SHOW_NOTIFICATION in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.state(StateKeys.RINGER) in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.STATE_READER_SYSFS in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.STATE_READER_DUMPSYS_FIELD in snapshot.availableCapabilities)
        assertEquals(StateQueryFamily.entries, manifest.stateReaders.families)
        assertEquals(setOf("jid:42"), snapshot.whitelistedConversationIds)
        assertEquals(emptySet(), snapshot.transientlyUnavailableCapabilities)
    }

    @Test
    fun `stopped but still granted Shizuku is transient while revoked permission is structural`() = runTest {
        val stopped = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
            ),
        ).current()
        assertFalse(ActionCapabilities.SET_DND in stopped.availableCapabilities)
        assertTrue(ActionCapabilities.SET_DND in stopped.transientlyUnavailableCapabilities)
        assertTrue(CapabilityIds.STATE_READER_SYSFS in stopped.transientlyUnavailableCapabilities)
        assertEquals(
            emptyList(),
            probe(
                state().copy(
                    shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                    shizukuPermissionGranted = true,
                ),
            ).probe(DeviceState()).stateReaders.families,
        )
        assertFalse(ActionTypeIds.SET_DND in probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
            ),
        ).probe(DeviceState()).availableTools)

        val revokedProbe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                shizukuPermissionGranted = false,
            ),
        )
        val revoked = revokedProbe.current()
        assertFalse(ActionCapabilities.SET_DND in revoked.availableCapabilities)
        assertFalse(ActionCapabilities.SET_DND in revoked.transientlyUnavailableCapabilities)
        val revokedManifest = revokedProbe.probe(DeviceState())
        assertFalse(ActionTypeIds.SET_DND in revokedManifest.availableTools)
        assertTrue(ActionTypeIds.SET_DND in revokedManifest.unavailableTools)
        assertFalse(ActionTypeIds.RUN_SHELL in revokedManifest.availableTools)
        assertTrue(ActionTypeIds.RUN_SHELL in revokedManifest.unavailableTools)
    }

    @Test
    fun `copy text is a base action always published while mobile data is gated on shizuku`() = runTest {
        val authorized = probe(state())
        val manifest = authorized.probe(DeviceState())
        val snapshot = authorized.current()

        // copy_text: BASE, sempre disponibile come copy_to_clipboard.
        assertTrue(ActionTypeIds.COPY_TEXT in manifest.availableTools)
        assertFalse(ActionTypeIds.COPY_TEXT in manifest.unavailableTools)
        assertTrue(ActionCapabilities.COPY_TEXT in snapshot.availableCapabilities)

        // set_mobile_data: PRIVILEGED, presente con Shizuku autorizzato.
        assertTrue(ActionTypeIds.SET_MOBILE_DATA in manifest.availableTools)
        assertTrue(ActionCapabilities.SET_MOBILE_DATA in snapshot.availableCapabilities)

        // set_dark_mode: PRIVILEGED come set_mobile_data (`cmd uimode night ...`).
        assertTrue(ActionTypeIds.SET_DARK_MODE in manifest.availableTools)
        assertTrue(ActionCapabilities.SET_DARK_MODE in snapshot.availableCapabilities)

        // Shizuku revocato: copy_text resta, set_mobile_data cade con ragione.
        val revoked = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                shizukuPermissionGranted = false,
            ),
        )
        val revokedManifest = revoked.probe(DeviceState())
        assertTrue(ActionTypeIds.COPY_TEXT in revokedManifest.availableTools)
        assertFalse(ActionTypeIds.SET_MOBILE_DATA in revokedManifest.availableTools)
        assertTrue(ActionTypeIds.SET_MOBILE_DATA in revokedManifest.unavailableTools)
        assertFalse(ActionCapabilities.SET_MOBILE_DATA in revoked.current().availableCapabilities)

        // set_dark_mode segue lo stesso gate: cade con Shizuku revocato.
        assertFalse(ActionTypeIds.SET_DARK_MODE in revokedManifest.availableTools)
        assertTrue(ActionTypeIds.SET_DARK_MODE in revokedManifest.unavailableTools)
        assertFalse(ActionCapabilities.SET_DARK_MODE in revoked.current().availableCapabilities)
    }

    @Test
    fun `write setting is a privileged action gated on shizuku`() = runTest {
        val authorized = probe(state())
        val manifest = authorized.probe(DeviceState())
        val snapshot = authorized.current()
        assertTrue(ActionTypeIds.WRITE_SETTING in manifest.availableTools)
        assertFalse(ActionTypeIds.WRITE_SETTING in manifest.unavailableTools)
        assertTrue(CapabilityIds.ACTION_WRITE_SETTING in snapshot.availableCapabilities)

        val revokedProbe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                shizukuPermissionGranted = false,
            ),
        )
        val revokedManifest = revokedProbe.probe(DeviceState())
        assertFalse(ActionTypeIds.WRITE_SETTING in revokedManifest.availableTools)
        assertTrue(ActionTypeIds.WRITE_SETTING in revokedManifest.unavailableTools)
        assertFalse(
            CapabilityIds.ACTION_WRITE_SETTING in revokedProbe.current().availableCapabilities,
        )

        // Shizuku fermo ma ancora autorizzato: indisponibilità transiente, non strutturale.
        val stopped = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
            ),
        ).current()
        assertTrue(
            CapabilityIds.ACTION_WRITE_SETTING in stopped.transientlyUnavailableCapabilities,
        )
    }

    @Test
    fun `location condition capability depends on location grants and not on Shizuku`() = runTest {
        val withoutShizuku = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
                foregroundLocationGranted = true,
                backgroundLocationGranted = true,
            ),
        ).current()
        assertTrue(CapabilityIds.STATE_LOCATION in withoutShizuku.availableCapabilities)
        assertFalse(
            CapabilityIds.STATE_LOCATION in withoutShizuku.transientlyUnavailableCapabilities,
        )

        val foregroundOnly = probe(
            state().copy(
                foregroundLocationGranted = true,
                backgroundLocationGranted = false,
            ),
        ).current()
        assertFalse(CapabilityIds.STATE_LOCATION in foregroundOnly.availableCapabilities)
    }

    @Test
    fun `missing listener grant keeps notification trigger and raw reply unavailable with exact reason`() = runTest {
        val probe = probe(
            state().copy(
                notificationsGranted = true,
                notificationListenerGranted = false,
                batteryOptimizationExempt = true,
            ),
        )

        val snapshot = probe.current()
        assertFalse(CapabilityIds.TRIGGER_NOTIFICATION in snapshot.availableCapabilities)
        assertFalse(GenerativeContract.TOOL_WHATSAPP_REPLY in snapshot.availableCapabilities)
        assertFalse(CapabilityIds.TRIGGER_NOTIFICATION in snapshot.transientlyUnavailableCapabilities)
        // Il runtime generativo è pronto: solo il canale notification/reply resta strutturalmente giù.
        assertTrue(CapabilityIds.ACTION_INVOKE_LLM in snapshot.availableCapabilities)

        val manifest = probe.probe(DeviceState())
        assertFalse(GenerativeContract.TOOL_WHATSAPP_REPLY in manifest.availableTools)
        assertEquals(
            AndroidCapabilityProbe.REASON_NOTIFICATION_LISTENER,
            manifest.unavailableTools[GenerativeContract.TOOL_WHATSAPP_REPLY],
        )
    }

    @Test
    fun `listener grant without generative readiness keeps invoke_llm unavailable`() = runTest {
        for (
        notReady in listOf(
            GenerativeReadiness(bridgeConfigured = false, privacyAccepted = true),
            GenerativeReadiness(bridgeConfigured = true, privacyAccepted = false),
        )
        ) {
            val snapshot = probe(
                state().copy(
                    notificationListenerGranted = true,
                    batteryOptimizationExempt = true,
                ),
                readiness = notReady,
            ).current()
            assertTrue(CapabilityIds.TRIGGER_NOTIFICATION in snapshot.availableCapabilities)
            assertTrue(GenerativeContract.TOOL_WHATSAPP_REPLY in snapshot.availableCapabilities)
            assertFalse(CapabilityIds.ACTION_INVOKE_LLM in snapshot.availableCapabilities)
            assertFalse(CapabilityIds.ACTION_INVOKE_LLM in snapshot.transientlyUnavailableCapabilities)
        }

        // D7: l'esenzione dall'ottimizzazione batteria NON è più un requisito della lane generativa.
        val withoutBattery = probe(
            state().copy(
                notificationListenerGranted = true,
                batteryOptimizationExempt = false,
            ),
        ).current()
        assertTrue(CapabilityIds.ACTION_INVOKE_LLM in withoutBattery.availableCapabilities)
        assertTrue(GenerativeContract.TOOL_WEB_SEARCH in withoutBattery.availableCapabilities)
    }

    @Test
    fun `ready generative runtime publishes trigger raw reply and invoke_llm`() = runTest {
        val probe = probe(
            state().copy(
                notificationsGranted = true,
                notificationListenerGranted = true,
                batteryOptimizationExempt = true,
            ),
        )

        val snapshot = probe.current()
        assertTrue(CapabilityIds.TRIGGER_NOTIFICATION in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.ACTION_INVOKE_LLM in snapshot.availableCapabilities)
        // web.search e' una capability richiesta all'arm (CapabilityRequirements.InvokeLlm): deve
        // stare nel set available, non solo in availableTools, o una regola reply+web non si arma.
        assertTrue(GenerativeContract.TOOL_WEB_SEARCH in snapshot.availableCapabilities)
        assertTrue(GenerativeContract.TOOL_WHATSAPP_REPLY in snapshot.availableCapabilities)
        // La reply statica ha un executor reale via NotificationReplyGateway: segue il listener.
        assertTrue(ActionCapabilities.WHATSAPP_REPLY in snapshot.availableCapabilities)

        // Il compilatore Hermes usa SOLO manifest.available_tools: senza invoke_llm lì il
        // modello ripiega su una reply statica (bug osservato live nella P1-7 reale).
        val manifest = probe.probe(DeviceState())
        assertTrue(GenerativeContract.TOOL_WHATSAPP_REPLY in manifest.availableTools)
        assertTrue(ActionTypeIds.INVOKE_LLM in manifest.availableTools)
        assertTrue(ActionTypeIds.INVOKE_LLM_V2 in manifest.availableTools)
        assertFalse(ActionTypeIds.INVOKE_LLM in manifest.unavailableTools)
        // web.search segue invoke_llm: disponibile ⇔ runtime generativo pronto (F2 web tool).
        assertTrue(GenerativeContract.TOOL_WEB_SEARCH in manifest.availableTools)
        assertFalse(GenerativeContract.TOOL_WEB_SEARCH in manifest.unavailableTools)
    }

    @Test
    fun `generative runtime not ready keeps invoke_llm out of the manifest with a reason`() = runTest {
        val manifest = probe(
            state().copy(
                notificationsGranted = true,
                notificationListenerGranted = true,
                batteryOptimizationExempt = true,
            ),
            readiness = GenerativeReadiness(bridgeConfigured = true, privacyAccepted = false),
        ).probe(DeviceState())

        assertFalse(ActionTypeIds.INVOKE_LLM in manifest.availableTools)
        assertFalse(ActionTypeIds.INVOKE_LLM_V2 in manifest.availableTools)
        assertEquals(
            AndroidCapabilityProbe.REASON_GENERATIVE_RUNTIME,
            manifest.unavailableTools[ActionTypeIds.INVOKE_LLM],
        )
        assertEquals(
            AndroidCapabilityProbe.REASON_GENERATIVE_RUNTIME,
            manifest.unavailableTools[ActionTypeIds.INVOKE_LLM_V2],
        )
        // web.search cade con invoke_llm e riporta la stessa ragione di runtime non pronto.
        assertFalse(GenerativeContract.TOOL_WEB_SEARCH in manifest.availableTools)
        assertEquals(
            AndroidCapabilityProbe.REASON_GENERATIVE_RUNTIME,
            manifest.unavailableTools[GenerativeContract.TOOL_WEB_SEARCH],
        )
    }

    @Test
    fun `battery optimization does not gate generative actions and the reason is english`() = runTest {
        val probe = probe(
            state().copy(
                notificationsGranted = true,
                notificationListenerGranted = true,
                batteryOptimizationExempt = false,
            ),
        )
        val manifest = probe.probe(DeviceState())

        assertTrue(ActionTypeIds.INVOKE_LLM in manifest.availableTools)
        assertTrue(ActionTypeIds.INVOKE_LLM_V2 in manifest.availableTools)
        assertTrue(GenerativeContract.TOOL_WEB_SEARCH in manifest.availableTools)
        assertTrue(CapabilityIds.ACTION_INVOKE_LLM in probe.current().availableCapabilities)
        assertEquals(
            "generative runtime not ready (provider key or privacy consent missing)",
            AndroidCapabilityProbe.REASON_GENERATIVE_RUNTIME,
        )
    }

    @Test
    fun `every unavailable tool reason is english because it reaches the model`() = runTest {
        val shizukuReasons = mapOf(
            ShizukuGatewayStatus.NOT_INSTALLED to "Shizuku not installed",
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING to "Shizuku not running",
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED to "Shizuku permission not granted",
            ShizukuGatewayStatus.UNSUPPORTED to "Shizuku version not supported",
        )
        val reasons = mutableSetOf<String>()
        shizukuReasons.forEach { (status, expected) ->
            val manifest = probe(
                state().copy(shizukuStatus = status, shizukuPermissionGranted = false),
            ).probe(DeviceState())
            assertEquals(expected, manifest.unavailableTools[ActionTypeIds.RUN_SHELL])
            assertEquals(expected, manifest.unavailableTools[ActionTypeIds.LAUNCH_APP])
            reasons += manifest.unavailableTools.values
        }

        val worstCase = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
                shizukuPermissionGranted = false,
                notificationsGranted = false,
                notificationListenerGranted = false,
                dndPolicyGranted = false,
            ),
            readiness = GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false),
            baseTierActive = true,
        ).probe(DeviceState())
        reasons += worstCase.unavailableTools.values

        assertEquals(
            "notification permission missing",
            worstCase.unavailableTools[ActionTypeIds.SHOW_NOTIFICATION],
        )
        assertEquals(
            "notification access not granted",
            worstCase.unavailableTools[GenerativeContract.TOOL_WHATSAPP_REPLY],
        )
        assertEquals(
            "Do Not Disturb access not granted",
            worstCase.unavailableTools[ActionTypeIds.SET_DND],
        )
        assertEquals(
            AndroidCapabilityProbe.REASON_GENERATIVE_RUNTIME,
            worstCase.unavailableTools[ActionTypeIds.INVOKE_LLM],
        )
        // Nessun motivo può contenere caratteri non ASCII (accenti, «») né parole italiane note.
        val italianMarkers = listOf(
            " non ", "mancant", "permesso", "accesso", "azione", "installazione", "conferma",
            "ciclo", "sostituito", "disponibile", "concesso", "supportata", "esecuzione",
        )
        reasons.forEach { reason ->
            assertTrue(reason.all { it.code < 128 }, "motivo non ASCII: $reason")
            italianMarkers.forEach { marker ->
                assertFalse(marker in " ${reason.lowercase()} ", "motivo non inglese: $reason")
            }
        }
        assertTrue(reasons.size >= 10, "attesi tutti i motivi del probe, trovati ${reasons.size}")
    }

    @Test
    fun `static reply capability follows the listener grant`() = runTest {
        val withoutListener = probe(
            state().copy(
                notificationListenerGranted = false,
                batteryOptimizationExempt = true,
            ),
        ).current()
        assertFalse(ActionCapabilities.WHATSAPP_REPLY in withoutListener.availableCapabilities)
        assertFalse(
            ActionCapabilities.WHATSAPP_REPLY in withoutListener.transientlyUnavailableCapabilities,
        )
    }

    @Test
    fun `telephony triggers follow their distinct runtime grants`() = runTest {
        val both = probe(
            state().copy(
                receiveSmsGranted = true,
                readPhoneStateGranted = true,
                readCallLogGranted = true,
            ),
        ).current()
        assertTrue(CapabilityIds.TRIGGER_PHONE_SMS in both.availableCapabilities)
        assertTrue(CapabilityIds.TRIGGER_PHONE_CALL in both.availableCapabilities)
        assertTrue(
            "phone_state.call" in probe(
                state().copy(readPhoneStateGranted = true, readCallLogGranted = true),
            ).probe(DeviceState()).availableTriggers,
        )

        val smsOnly = probe(state().copy(receiveSmsGranted = true)).current()
        assertTrue(CapabilityIds.TRIGGER_PHONE_SMS in smsOnly.availableCapabilities)
        assertFalse(CapabilityIds.TRIGGER_PHONE_CALL in smsOnly.availableCapabilities)

        val phoneStateOnly = probe(state().copy(readPhoneStateGranted = true)).current()
        assertFalse(
            CapabilityIds.TRIGGER_PHONE_CALL in phoneStateOnly.availableCapabilities,
            "senza READ_CALL_LOG il numero chiamante non è disponibile",
        )
        assertFalse(
            "phone_state.call" in probe(state().copy(readPhoneStateGranted = true))
                .probe(DeviceState()).availableTriggers,
        )

        val none = probe(state()).current()
        assertFalse(CapabilityIds.TRIGGER_PHONE_SMS in none.availableCapabilities)
        assertFalse(CapabilityIds.TRIGGER_PHONE_CALL in none.availableCapabilities)
    }

    @Test
    fun `connectivity capabilities separate always available media from runtime grants`() = runTest {
        val base = probe(state()).current().availableCapabilities
        assertTrue(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI in base)
        assertTrue(CapabilityIds.TRIGGER_CONNECTIVITY_POWER in base)
        assertFalse(CapabilityIds.TRIGGER_CONNECTIVITY_BT in base)
        assertFalse(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY in base)

        val nearby = probe(state().copy(bluetoothConnectGranted = true))
            .current().availableCapabilities
        assertTrue(CapabilityIds.TRIGGER_CONNECTIVITY_BT in nearby)

        val location = probe(
            state().copy(
                foregroundLocationGranted = true,
                backgroundLocationGranted = true,
            ),
        ).current().availableCapabilities
        assertTrue(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY in location)
        val locationManifest = probe(
            state().copy(
                foregroundLocationGranted = true,
                backgroundLocationGranted = true,
            ),
        ).probe(DeviceState())
        assertTrue("connectivity.wifi.identity" in locationManifest.availableTriggers)
    }

    @Test
    fun `geofence is advertised only with precise foreground and background location`() = runTest {
        val none = probe(state()).probe(DeviceState())
        assertFalse("geofence" in none.availableTriggers)
        assertFalse(CapabilityIds.TRIGGER_GEOFENCE in probe(state()).current().availableCapabilities)

        val foregroundOnly = state().copy(foregroundLocationGranted = true)
        assertFalse(
            CapabilityIds.TRIGGER_GEOFENCE in probe(foregroundOnly).current().availableCapabilities,
        )

        val ready = foregroundOnly.copy(backgroundLocationGranted = true)
        assertTrue(CapabilityIds.TRIGGER_GEOFENCE in probe(ready).current().availableCapabilities)
        assertTrue("geofence" in probe(ready).probe(DeviceState()).availableTriggers)
    }

    @Test
    fun `location services off make geofence and wifi identity unavailable with a reason for the model`() = runTest {
        val ready = state().copy(foregroundLocationGranted = true, backgroundLocationGranted = true)
        val on = probe(ready)
        assertTrue(CapabilityIds.TRIGGER_GEOFENCE in on.current().availableCapabilities)
        assertTrue("geofence" in on.probe(DeviceState()).availableTriggers)
        assertFalse(CapabilityIds.TRIGGER_GEOFENCE in on.probe(DeviceState()).unavailableTools)

        val off = probe(ready.copy(locationEnabled = false))
        val snapshot = off.current()
        assertFalse(CapabilityIds.TRIGGER_GEOFENCE in snapshot.availableCapabilities)
        assertFalse(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY in snapshot.availableCapabilities)
        val manifest = off.probe(DeviceState())
        assertFalse("geofence" in manifest.availableTriggers)
        assertFalse("connectivity.wifi.identity" in manifest.availableTriggers)
        assertEquals(
            "location services are off",
            manifest.unavailableTools[CapabilityIds.TRIGGER_GEOFENCE],
        )
        assertEquals(
            "location services are off",
            manifest.unavailableTools[CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY],
        )
        // Il resto non cambia: il Wi-Fi senza identita' e gli altri trigger restano disponibili.
        assertTrue("connectivity.wifi" in manifest.availableTriggers)
        assertTrue(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI in snapshot.availableCapabilities)
        assertTrue("time" in manifest.availableTriggers)
        // Come un permesso revocato: non e' un outage "transitorio" per l'armo.
        assertFalse(CapabilityIds.TRIGGER_GEOFENCE in snapshot.transientlyUnavailableCapabilities)
    }

    @Test
    fun `location services off are exposed as the location_disabled detail only while they block something`() = runTest {
        val ready = state().copy(foregroundLocationGranted = true, backgroundLocationGranted = true)

        assertEquals(emptyMap(), probe(ready).capabilityBlockDetails())
        assertEquals(
            mapOf(
                CapabilityIds.TRIGGER_GEOFENCE to "location_disabled",
                CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY to "location_disabled",
            ),
            probe(ready.copy(locationEnabled = false)).capabilityBlockDetails(),
        )
    }

    @Test
    fun `reconciler blocks a geofence rule temporarily when location is switched off and never sends it to review`() = runTest {
        val rule = geofenceRule("home")
        val ready = state().copy(foregroundLocationGranted = true, backgroundLocationGranted = true)

        val healthy = CapabilityReconciler(ReconcileAutomationStore(rule), probe(ready)).reconcile()
        assertEquals(emptyList(), healthy.needsReview)
        assertEquals(emptyList(), healthy.temporarilyBlocked)

        val store = ReconcileAutomationStore(rule)
        val audit = ReconcilerAuditRecorder()
        val off = CapabilityReconciler(store, probe(ready.copy(locationEnabled = false)), audit)
            .reconcile()

        assertEquals(emptyList(), off.needsReview)
        assertEquals(listOf(rule.id), off.temporarilyBlocked)
        assertEquals(AutomationStatus.ARMED, store.get(rule.id)?.status)
        assertTrue(store.get(rule.id)?.enabled == true)
        assertTrue(audit.events.isEmpty())

        // Riaccesa la posizione la regola e' di nuovo a posto, senza interventi.
        val back = CapabilityReconciler(store, probe(ready)).reconcile()
        assertEquals(emptyList(), back.temporarilyBlocked)
    }

    @Test
    fun `manifest lists armable triggers so hermes never proposes a dead one`() = runTest {
        val manifest = probe(
            state().copy(notificationListenerGranted = true, receiveSmsGranted = true),
        ).probe(DeviceState())

        assertEquals(
            listOf(
                "time",
                "immediate",
                "notification",
                "phone_state.sms",
                "connectivity.wifi",
                "connectivity.power",
            ),
            manifest.availableTriggers,
        )
        assertTrue("TRIGGER DISPONIBILI" in manifest.render())

        // Retrocompatibilità: senza lista la riga non compare (manifest legacy nei test brain).
        assertFalse("TRIGGER DISPONIBILI" in manifest.copy(availableTriggers = emptyList()).render())
    }

    @Test
    fun `immediate trigger is always available with no OS dependency`() = runTest {
        // Nessun grant: fira on-arm, quindi deve comparire sia come capability sia nella lista wire.
        val bare = probe(state())
        assertTrue(CapabilityIds.TRIGGER_IMMEDIATE in bare.current().availableCapabilities)
        assertTrue("immediate" in bare.probe(DeviceState()).availableTriggers)
    }

    @Test
    fun `structural control flow tools are always published regardless of device state`() = runTest {
        // wait/if/while sono contenitori STRUTTURALI del programma P4: non dipendono da Shizuku,
        // permessi o hardware. Devono comparire in available_tools in OGNI stato, altrimenti il
        // compilatore (che usa solo manifest.available_tools) crede che `wait` non esista e rifiuta
        // una pausa con unsupported_capability (bug device-found).
        val bareState = state().copy(
            shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
            shizukuPermissionGranted = false,
            notificationsGranted = false,
            notificationListenerGranted = false,
            foregroundLocationGranted = false,
            backgroundLocationGranted = false,
            batteryOptimizationExempt = false,
            dndPolicyGranted = false,
        )
        val structural = listOf(ActionTypeIds.WAIT, ActionTypeIds.IF, ActionTypeIds.WHILE)

        // Nessun grant, nessuno Shizuku, tier base spento: comunque tutti presenti.
        val bareManifest = probe(bareState).probe(DeviceState())
        structural.forEach { type ->
            assertTrue(type in bareManifest.availableTools, "atteso $type sempre in available_tools")
            assertFalse(type in bareManifest.unavailableTools, "$type strutturale non può essere indisponibile")
        }

        // Shizuku revocato: le azioni privilegiate cadono, il control-flow resta.
        val revokedManifest = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                shizukuPermissionGranted = false,
            ),
        ).probe(DeviceState())
        structural.forEach { type ->
            assertTrue(type in revokedManifest.availableTools, "atteso $type con Shizuku revocato")
        }

        // Dispositivo pienamente autorizzato: restano presenti (nessuna regressione).
        val authorizedManifest = probe(state()).probe(DeviceState())
        structural.forEach { type ->
            assertTrue(type in authorizedManifest.availableTools, "atteso $type su device autorizzato")
        }
    }

    @Test
    fun `sensor is advertised only when hardware grant and a real backend all agree`() = runTest {
        val significant = AndroidSensorCapability(
            kind = SensorKind.SIGNIFICANT_MOTION,
            reportingMode = SensorReportingMode.ONE_SHOT,
            wakeUp = true,
            fifoMaxEventCount = 0,
            minDelayUs = 0,
            maxDelayUs = 0,
            permissionGranted = true,
        )
        val hardware = AndroidSensorCapabilitySource { _ -> listOf(significant) }

        val domainOnly = probe(state(), sensors = hardware).probe(DeviceState())
        assertFalse("sensor.significant_motion" in domainOnly.availableTriggers)

        val ready = probe(
            state(),
            sensors = hardware,
            implementedSensorKinds = setOf(SensorKind.SIGNIFICANT_MOTION),
        )
        assertTrue("sensor.significant_motion" in ready.probe(DeviceState()).availableTriggers)
        assertTrue(
            CapabilityIds.triggerSensor(SensorKind.SIGNIFICANT_MOTION) in
                ready.current().availableCapabilities,
        )

        val wrongMode = AndroidSensorCapabilitySource { _ ->
            listOf(significant.copy(reportingMode = SensorReportingMode.CONTINUOUS))
        }
        val rejected = probe(
            state(),
            sensors = wrongMode,
            implementedSensorKinds = setOf(SensorKind.SIGNIFICANT_MOTION),
        )
        assertFalse("sensor.significant_motion" in rejected.probe(DeviceState()).availableTriggers)
    }

    @Test
    fun `unimplemented raw tools are never advertised even with Shizuku`() = runTest {
        val authorized = probe(state())
        val manifest = authorized.probe(DeviceState())
        val snapshot = authorized.current()
        AndroidCapabilityProbe.UNIMPLEMENTED_RAW_TOOLS.forEach { tool ->
            assertFalse(tool in manifest.availableTools, "$tool non ha un Action wire eseguibile")
            assertTrue(tool in manifest.unavailableTools, "manca la ragione per $tool")
            assertFalse(tool in snapshot.availableCapabilities, "$tool non deve armare regole")
            assertFalse(tool in snapshot.transientlyUnavailableCapabilities)
        }
    }

    @Test
    fun `reconciler only blocks a generative rule temporarily on privacy or listener revocation`() = runTest {
        val rule = generativeRule("generative")
        val contacts = listOf(WhitelistedContact("Moglie", CONVERSATION_ID))
        val readyState = state().copy(
            notificationListenerGranted = true,
            batteryOptimizationExempt = true,
        )

        val healthyStore = ReconcileAutomationStore(rule)
        val healthy = CapabilityReconciler(healthyStore, probe(readyState, contacts)).reconcile()
        assertEquals(emptyList(), healthy.needsReview)
        assertEquals(emptyList(), healthy.temporarilyBlocked)
        assertEquals(AutomationStatus.ARMED, healthyStore.get(rule.id)?.status)

        // D6: una capability mancante (privacy revocata, accesso notifiche perso) è un blocco
        // TEMPORANEO: lo stato della regola non cambia, al fire-time FirePolicy blocca e audita.
        val privacyRevokedStore = ReconcileAutomationStore(rule)
        val privacyRevokedAudit = ReconcilerAuditRecorder()
        val privacyRevoked = CapabilityReconciler(
            privacyRevokedStore,
            probe(
                readyState,
                contacts,
                readiness = GenerativeReadiness(bridgeConfigured = true, privacyAccepted = false),
            ),
            privacyRevokedAudit,
        ).reconcile()
        assertEquals(emptyList(), privacyRevoked.needsReview)
        assertEquals(listOf(rule.id), privacyRevoked.temporarilyBlocked)
        assertEquals(AutomationStatus.ARMED, privacyRevokedStore.get(rule.id)?.status)
        assertTrue(privacyRevokedStore.get(rule.id)?.enabled == true)
        assertTrue(privacyRevokedAudit.events.isEmpty())

        val listenerRevokedStore = ReconcileAutomationStore(rule)
        val listenerRevoked = CapabilityReconciler(
            listenerRevokedStore,
            probe(readyState.copy(notificationListenerGranted = false), contacts),
        ).reconcile()
        assertEquals(emptyList(), listenerRevoked.needsReview)
        assertEquals(listOf(rule.id), listenerRevoked.temporarilyBlocked)
        assertEquals(AutomationStatus.ARMED, listenerRevokedStore.get(rule.id)?.status)
    }

    @Test
    fun `reconciler blocks revoked and stopped Shizuku without touching the rule`() = runTest {
        val rule = signedRule("dnd")
        val revokedStore = ReconcileAutomationStore(rule)
        val revokedProbe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
                shizukuPermissionGranted = false,
            ),
        )
        val revokedAudit = ReconcilerAuditRecorder()
        val revoked = CapabilityReconciler(revokedStore, revokedProbe, revokedAudit).reconcile()
        assertEquals(emptyList(), revoked.needsReview)
        assertEquals(listOf(rule.id), revoked.temporarilyBlocked)
        assertEquals(AutomationStatus.ARMED, revokedStore.get(rule.id)?.status)
        assertTrue(revokedAudit.events.isEmpty(), "una revoca non è un evento lifecycle: audita il fire")

        val temporaryStore = ReconcileAutomationStore(rule)
        val temporaryProbe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
                shizukuPermissionGranted = true,
            ),
        )
        val temporaryAudit = ReconcilerAuditRecorder()
        val temporary = CapabilityReconciler(temporaryStore, temporaryProbe, temporaryAudit).reconcile()
        assertEquals(listOf(rule.id), temporary.temporarilyBlocked)
        assertEquals(AutomationStatus.ARMED, temporaryStore.get(rule.id)?.status)
        assertTrue(temporaryAudit.events.isEmpty(), "un outage transiente non è un evento lifecycle")
    }

    @Test
    fun `reconciler quarantines only on validation failure or changed requirements`() = runTest {
        val invalid = sign(
            signedRule("invalid").copy(
                trigger = Trigger.Time(cron = "not a cron", tz = "Europe/Rome"),
                approvalFingerprint = null,
            ),
        )
        val validationStore = ReconcileAutomationStore(invalid)
        val validationAudit = ReconcilerAuditRecorder()
        val validation = CapabilityReconciler(validationStore, probe(state()), validationAudit).reconcile()
        assertEquals(listOf(invalid.id), validation.needsReview)
        assertEquals(AutomationStatus.NEEDS_REVIEW, validationStore.get(invalid.id)?.status)
        assertEquals("validation_failed", validationAudit.events.single().detail)

        val stale = sign(
            signedRule("stale").copy(
                requiredCapabilities = emptySet(),
                approvalFingerprint = null,
            ),
        )
        val staleStore = ReconcileAutomationStore(stale)
        val staleAudit = ReconcilerAuditRecorder()
        val changed = CapabilityReconciler(staleStore, probe(state()), staleAudit).reconcile()
        assertEquals(listOf(stale.id), changed.needsReview)
        assertEquals(AutomationStatus.NEEDS_REVIEW, staleStore.get(stale.id)?.status)
        assertEquals("requirements_changed", staleAudit.events.single().detail)
        assertEquals(AuditKind.RULE_NEEDS_REVIEW, staleAudit.events.single().kind)
    }

    @Test
    fun `reconciler does not flag armed P4 rules with variables as needing review`() = runTest {
        // Regressione B1: validazione e requisiti venivano calcolati SENZA le var dichiarate, quindi
        // ogni regola con `random_int`/`${var}` risultava "da rivedere" al primo reconcile.
        val branching = signedP4Rule(
            id = "branching",
            actions = listOf(
                Action.If(
                    Condition.VarCompare("dice", CmpOp.IS_EVEN),
                    then = listOf(Action.ShowNotification("pari", "pari")),
                    orElse = listOf(Action.ShowNotification("dispari", "dispari")),
                ),
            ),
        )
        val interpolating = signedP4Rule(
            id = "interpolating",
            actions = listOf(Action.ShowNotification("dado", "uscito \${dice}")),
        )
        val audit = ReconcilerAuditRecorder()
        val shownNotifications = state().copy(notificationsGranted = true)

        listOf(branching, interpolating).forEach { rule ->
            val store = ReconcileAutomationStore(rule)
            val report = CapabilityReconciler(store, probe(shownNotifications), audit).reconcile()

            assertEquals(emptyList(), report.needsReview, rule.name)
            assertEquals(emptyList(), report.temporarilyBlocked, rule.name)
            assertEquals(AutomationStatus.ARMED, store.get(rule.id)?.status, rule.name)
        }
        assertTrue(
            audit.events.none { it.kind == AuditKind.RULE_NEEDS_REVIEW },
            "nessun audit RULE_NEEDS_REVIEW per regole P4 integre",
        )
    }

    @Test
    fun `reconciler cannot quarantine a newer approved revision`() = runTest {
        val original = sign(
            signedRule("race").copy(requiredCapabilities = emptySet(), approvalFingerprint = null),
        )
        val revised = sign(signedRule("race").copy(name = "nuova revisione", approvalFingerprint = null))
        val store = ReconcileAutomationStore(original).apply {
            beforeConditionalMark = { replace(revised) }
        }

        assertEquals(emptyList(), CapabilityReconciler(store, probe(state())).reconcile().needsReview)
        assertEquals(revised, store.get(original.id))
    }

    private fun probe(
        state: AndroidCapabilityState,
        contacts: List<WhitelistedContact> = emptyList(),
        readiness: GenerativeReadiness = GenerativeReadiness(
            bridgeConfigured = true,
            privacyAccepted = true,
        ),
        sensors: AndroidSensorCapabilitySource = EmptyAndroidSensorCapabilitySource,
        implementedSensorKinds: Set<SensorKind> = emptySet(),
        baseTierActive: Boolean = false,
    ) = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { state },
        FakeWhitelist(contacts),
        GenerativeRuntimeReadiness { readiness },
        sensors,
        implementedSensorKinds,
        baseTierActive,
    )

    @Test
    fun `base tier active publishes DND and launch without Shizuku when policy granted`() = runTest {
        val probe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
                shizukuPermissionGranted = false,
                dndPolicyGranted = true,
                notificationsGranted = true,
            ),
            baseTierActive = true,
        )
        val snapshot = probe.current()
        val manifest = probe.probe(DeviceState())

        // Base disponibili senza Shizuku.
        assertTrue(ActionCapabilities.SET_DND in snapshot.availableCapabilities)
        assertTrue(ActionCapabilities.SET_RINGER in snapshot.availableCapabilities)
        assertTrue(ActionCapabilities.LAUNCH_APP in snapshot.availableCapabilities)
        assertTrue(ActionCapabilities.OPEN_URL in snapshot.availableCapabilities)
        assertTrue(ActionTypeIds.SET_DND in manifest.availableTools)
        assertTrue(ActionTypeIds.LAUNCH_APP in manifest.availableTools)
        // Privilegiate ancora bloccate senza Shizuku.
        assertFalse(ActionCapabilities.RUN_SHELL in snapshot.availableCapabilities)
        assertFalse(ActionCapabilities.SET_WIFI in snapshot.availableCapabilities)
        assertFalse(ActionTypeIds.RUN_SHELL in manifest.availableTools)
    }

    @Test
    fun `base tier active still gates DND on the policy grant but launch stays free`() = runTest {
        val probe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
                shizukuPermissionGranted = false,
                dndPolicyGranted = false,
            ),
            baseTierActive = true,
        )
        val snapshot = probe.current()
        val manifest = probe.probe(DeviceState())

        assertFalse(ActionCapabilities.SET_DND in snapshot.availableCapabilities)
        assertFalse(ActionTypeIds.SET_DND in manifest.availableTools)
        assertTrue(ActionTypeIds.SET_DND in manifest.unavailableTools)
        // LaunchApp/OpenUrl non richiedono alcun grant: restano disponibili.
        assertTrue(ActionCapabilities.LAUNCH_APP in snapshot.availableCapabilities)
        assertTrue(ActionTypeIds.OPEN_URL in manifest.availableTools)
    }

    @Test
    fun `set alarm and set timer are always published as base actions without Shizuku or grants`() = runTest {
        // Nessuno Shizuku, nessun grant runtime: SET_ALARM è un permesso normal auto-concesso,
        // quindi sveglia/timer restano armabili come copy_to_clipboard.
        val probe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
                shizukuPermissionGranted = false,
                dndPolicyGranted = false,
                notificationsGranted = false,
            ),
            baseTierActive = false,
        )
        val manifest = probe.probe(DeviceState())
        val snapshot = probe.current()

        assertTrue(ActionTypeIds.SET_ALARM in manifest.availableTools)
        assertTrue(ActionTypeIds.SET_TIMER in manifest.availableTools)
        assertFalse(ActionTypeIds.SET_ALARM in manifest.unavailableTools)
        assertFalse(ActionTypeIds.SET_TIMER in manifest.unavailableTools)
        assertTrue(CapabilityIds.ACTION_SET_ALARM in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.ACTION_SET_TIMER in snapshot.availableCapabilities)
    }

    @Test
    fun `manager pack base actions are always published without Shizuku or grants`() = runTest {
        // Volume/torcia/schermata impostazioni/vibrazione: solo permesso normal VIBRATE, nessun
        // grant runtime pubblicabile, quindi sempre armabili come sveglia/clipboard.
        val probe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
                shizukuPermissionGranted = false,
                dndPolicyGranted = false,
                notificationsGranted = false,
            ),
            baseTierActive = false,
        )
        val manifest = probe.probe(DeviceState())
        val snapshot = probe.current()

        listOf(
            ActionTypeIds.SET_VOLUME,
            ActionTypeIds.SET_FLASHLIGHT,
            ActionTypeIds.OPEN_SETTINGS_SCREEN,
            ActionTypeIds.VIBRATE,
        ).forEach { type ->
            assertTrue(type in manifest.availableTools, "atteso $type pubblicato")
            assertFalse(type in manifest.unavailableTools, "$type non deve essere indisponibile")
        }
        assertTrue(CapabilityIds.ACTION_SET_VOLUME in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.ACTION_SET_FLASHLIGHT in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.ACTION_OPEN_SETTINGS_SCREEN in snapshot.availableCapabilities)
        assertTrue(CapabilityIds.ACTION_VIBRATE in snapshot.availableCapabilities)
    }

    @Test
    fun `base tier inactive keeps every base action gated on Shizuku`() = runTest {
        // Default (tier base non attivo): nessun cambiamento rispetto al legacy.
        val probe = probe(
            state().copy(
                shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
                shizukuPermissionGranted = false,
                dndPolicyGranted = true,
            ),
        )
        val snapshot = probe.current()
        assertFalse(ActionCapabilities.SET_DND in snapshot.availableCapabilities)
        assertFalse(ActionCapabilities.LAUNCH_APP in snapshot.availableCapabilities)
    }

    private fun state() = AndroidCapabilityState(
        deviceModel = "CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuStatus = ShizukuGatewayStatus.AUTHORIZED,
        shizukuPermissionGranted = true,
        notificationsGranted = false,
        notificationListenerGranted = false,
        foregroundLocationGranted = false,
        backgroundLocationGranted = false,
        exactAlarmsGranted = false,
        batteryOptimizationExempt = false,
    )

    private fun signedRule(id: String): Automation = sign(
        Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        ),
    )

    private fun geofenceRule(id: String): Automation = sign(
        Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Geofence(45.0, 9.0, 150.0, Transition.EXIT),
            actions = listOf(Action.SetWifi(false)),
        ),
    )

    private fun signedP4Rule(id: String, actions: List<Action>): Automation = sign(
        Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = actions,
            vars = listOf(VarBinding.RandomInt("dice", max = 6)),
            schemaVersion = AUTOMATION_SCHEMA_VERSION_P4,
        ),
    )

    private fun generativeRule(id: String): Automation = sign(
        Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Notification(
                pkg = "com.whatsapp",
                conversationId = CONVERSATION_ID,
                isGroup = false,
            ),
            actions = listOf(
                Action.InvokeLlm(
                    goal = "rispondi",
                    contextSources = listOf("notification"),
                    allowedTools = listOf("whatsapp_reply"),
                    replyTargetSender = true,
                ),
            ),
            cooldownMs = 60_000,
        ),
    )

    private fun sign(value: Automation): Automation =
        value.copy(approvalFingerprint = ApprovalFingerprints.of(value))

    private companion object {
        const val CONVERSATION_ID = "shortcut:com.whatsapp:hash"
    }
}

private class FakeWhitelist(initial: List<WhitelistedContact>) : ContactWhitelistStore {
    private val values = MutableStateFlow(initial)
    override suspend fun all(): List<WhitelistedContact> = values.value
    override fun observeAll(): Flow<List<WhitelistedContact>> = values
    override suspend fun upsert(contact: WhitelistedContact) {
        values.value = values.value.filterNot { it.id == contact.id } + contact
    }
    override suspend fun remove(conversationId: String) {
        values.value = values.value.filterNot { it.id == conversationId }
    }
}

private class ReconcilerAuditRecorder : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) { events += e }
}

private class ReconcileAutomationStore(initial: Automation) : AutomationStore {
    private val values = MutableStateFlow(mapOf(initial.id to initial))
    var beforeConditionalMark: (() -> Unit)? = null

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
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean {
        beforeConditionalMark?.also { beforeConditionalMark = null }?.invoke()
        val current = values.value[id] ?: return false
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return false
        values.value += id to current.copy(status = AutomationStatus.NEEDS_REVIEW, enabled = false)
        return true
    }
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
        FireClaimResult.NotEligible
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null

    fun replace(value: Automation) { values.value += value.id to value }
}
