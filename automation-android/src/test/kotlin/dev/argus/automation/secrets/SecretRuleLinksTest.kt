package dev.argus.automation.secrets

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.SecretRef
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.secrets.SecretEntry
import dev.argus.engine.secrets.SecretVault
import dev.argus.automation.vm.secretRows
import dev.argus.ui.model.SecretRowUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRuleLinksTest {
    private class MemoryVault(vararg names: String) : SecretVault {
        val values = names.associateWith { "value-of-$it" }.toMutableMap()
        var failNames = false
        override fun observe(): Flow<List<SecretEntry>> = flowOf(values.keys.sorted().map { SecretEntry(it) })
        override suspend fun entries(): List<SecretEntry> {
            check(!failNames) { "keystore" }
            return values.keys.sorted().map { SecretEntry(it) }
        }
        override suspend fun save(name: String, value: String): Boolean { values[name] = value; return true }
        override suspend fun delete(name: String): Boolean = values.remove(name) != null
        override suspend fun valueFor(name: String): String? = values[name]
    }

    private class Store(vararg rules: Automation) : AutomationStore {
        val rules = MutableStateFlow(rules.associateBy { it.id })
        val reviewed = mutableListOf<AutomationId>()
        override suspend fun get(id: AutomationId) = rules.value[id]
        override suspend fun all() = rules.value.values.toList()
        override fun observeAll(): Flow<List<Automation>> = flowOf(rules.value.values.toList())
        override suspend fun armed() = all().filter { it.status == AutomationStatus.ARMED }
        override suspend fun delete(id: AutomationId) = Unit
        override suspend fun disable(id: AutomationId) = Unit
        override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun markNeedsReview(id: AutomationId) { reviewed += id }
        override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean {
            if (rules.value[id]?.approvalFingerprint != fingerprint) return false
            reviewed += id
            return true
        }
        override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = FireClaimResult.NotEligible
        override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
        override suspend fun lastFiredAt(id: AutomationId): Long? = null
    }

    private fun recording(events: MutableList<AuditEvent>) = object : AuditSink {
        override suspend fun record(e: AuditEvent) { events += e }
    }

    private fun rule(id: String, status: AutomationStatus, secret: String?) = Automation(
        id = AutomationId(id),
        name = id,
        createdBy = CreatedBy.USER,
        status = status,
        trigger = Trigger.Manual,
        actions = listOf(
            Action.HttpRequest(
                HttpMethod.GET,
                "https://ha.example.org/api",
                secretHeaders = secret?.let { listOf(SecretRef("Authorization", it, "Bearer ")) },
            ),
        ),
        approvalFingerprint = ApprovalFingerprint(Integer.toHexString(id.hashCode()).padStart(64, '0')),
    )

    @Test
    fun `deleting a secret moves the armed and switched off rules that use it to review`() = runTest {
        val store = Store(
            rule("armed", AutomationStatus.ARMED, "ha_token"),
            rule("off", AutomationStatus.DISABLED, "ha_token"),
            rule("other", AutomationStatus.ARMED, "meteo_api"),
            rule("plain", AutomationStatus.ARMED, null),
            rule("already", AutomationStatus.NEEDS_REVIEW, "ha_token"),
        )
        val events = mutableListOf<AuditEvent>()
        val deletion = SecretDeletion(MemoryVault("ha_token", "meteo_api"), store, recording(events)) { 7L }

        assertTrue(deletion.delete("ha_token"))

        assertEquals(listOf(AutomationId("armed"), AutomationId("off")), store.reviewed)
        assertEquals(listOf(AuditKind.RULE_NEEDS_REVIEW, AuditKind.RULE_NEEDS_REVIEW), events.map { it.kind })
        assertTrue(events.all { it.detail == "secret_deleted" && "ha_token" !in it.detail })
    }

    @Test
    fun `the secrets screen counts the rules that use each name and never sees a value`() {
        val rows = secretRows(
            listOf(SecretEntry("ha_token", 5L), SecretEntry("meteo_api"), SecretEntry("unused")),
            listOf(
                rule("a", AutomationStatus.ARMED, "ha_token"),
                rule("b", AutomationStatus.DISABLED, "ha_token"),
                rule("c", AutomationStatus.NEEDS_REVIEW, "meteo_api"),
            ),
        )
        assertEquals(
            listOf(SecretRowUi("ha_token", 2, 5L), SecretRowUi("meteo_api", 1, null), SecretRowUi("unused", 0, null)),
            rows,
        )
    }

    @Test
    fun `a secret that is not there touches no rule`() = runTest {
        val store = Store(rule("armed", AutomationStatus.ARMED, "ha_token"))
        val deletion = SecretDeletion(MemoryVault(), store, recording(mutableListOf()))
        assertFalse(deletion.delete("ha_token"))
        assertTrue(store.reviewed.isEmpty())
    }

    @Test
    fun `the compile manifest carries the saved names, or none if the vault cannot be read`() = runTest {
        val base = CapabilityManifest(
            deviceModel = "Pixel",
            androidVersion = 16,
            androidApi = 36,
            shizukuAvailable = false,
            grantedPermissions = emptyList(),
            availableTools = emptyList(),
            unavailableTools = emptyMap(),
            whitelistedContacts = emptyList(),
        )
        val probe = object : CapabilityProbe { override suspend fun probe(currentState: DeviceState) = base }
        val vault = MemoryVault("meteo_api", "ha_token")
        assertEquals(listOf("ha_token", "meteo_api"), SecretNamesCapabilityProbe(probe, vault).probe(DeviceState()).savedSecrets)
        vault.failNames = true
        assertEquals(emptyList(), SecretNamesCapabilityProbe(probe, vault).probe(DeviceState()).savedSecrets)
    }
}
