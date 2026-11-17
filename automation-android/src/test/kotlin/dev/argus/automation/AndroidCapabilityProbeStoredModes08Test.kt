package dev.argus.automation

import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Valori conservati e modalità: vivono nel database di Argus, quindi sono sempre disponibili, anche senza Shizuku. */
class AndroidCapabilityProbeStoredModes08Test {
    private val bare = AndroidCapabilityState(
        deviceModel = "CPH2747",
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
    )

    private val probe = AndroidCapabilityProbe(
        AndroidCapabilityStateSource { bare },
        object : ContactWhitelistStore {
            override suspend fun all(): List<WhitelistedContact> = emptyList()
            override fun observeAll(): Flow<List<WhitelistedContact>> = flowOf(emptyList())
            override suspend fun upsert(contact: WhitelistedContact) = Unit
            override suspend fun remove(conversationId: String) = Unit
        },
        GenerativeRuntimeReadiness { GenerativeReadiness(bridgeConfigured = false, privacyAccepted = false) },
        baseTierActive = true,
    )

    @Test
    fun `none of them is still marked not available yet`() {
        AndroidCapabilityProbe.STORED_MODE_CAPABILITIES.forEach {
            assertFalse(it in AndroidCapabilityProbe.NOT_YET_AVAILABLE_CAPABILITIES, it)
        }
    }

    @Test
    fun `a rule with every stored and mode type can be armed with no permission at all`() = runTest {
        val draft = AutomationDraft(
            name = "modalità",
            trigger = Trigger.UserModeChanged("Casa"),
            conditions = Condition.And(
                listOf(
                    Condition.UserModeIs("Casa"),
                    Condition.StoredCompare("caffe_oggi", StateValueType.NUMBER, CmpOp.LT, "3"),
                ),
            ),
            actions = listOf(Action.SetStored("caffe_oggi", StoredOp.INCREMENT), Action.SetUserMode("Lavoro")),
        )
        val required = CapabilityRequirements.derive(draft)
        assertTrue(required.containsAll(AndroidCapabilityProbe.STORED_MODE_CAPABILITIES), "$required")
        val available = probe.current().availableCapabilities
        assertTrue(available.containsAll(required), "${required - available}")
    }

    @Test
    fun `the model is told about the trigger and the two actions`() = runTest {
        val manifest = probe.probe(DeviceState())
        assertTrue("user_mode_changed" in manifest.availableTriggers, "${manifest.availableTriggers}")
        assertTrue(ActionTypeIds.SET_STORED in manifest.availableTools, "${manifest.availableTools}")
        assertTrue(ActionTypeIds.SET_USER_MODE in manifest.availableTools, "${manifest.availableTools}")
    }
}
