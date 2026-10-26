package dev.argus.automation.manual

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Regola firmata come quella che esce dall'approvazione. */
internal fun manualRule(
    id: String = "m1",
    name: String = "Apri il garage",
    trigger: Trigger = Trigger.Manual,
    status: AutomationStatus = AutomationStatus.ARMED,
    enabled: Boolean = status == AutomationStatus.ARMED,
    cooldownMs: Long = 0,
): Automation {
    val unsigned = Automation(
        id = AutomationId(id),
        name = name,
        createdBy = CreatedBy.USER,
        status = status,
        trigger = trigger,
        actions = listOf(Action.SetFlashlight(true)),
        enabled = enabled,
        cooldownMs = cooldownMs,
    )
    return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
}

/** Store in memoria con claim idempotente (stessa regola e stesso evento = doppione). */
internal class MapStore(vararg initial: Automation) : AutomationStore {
    private val values = MutableStateFlow(initial.associateBy { it.id })
    private val claims = mutableSetOf<Pair<AutomationId, TriggerEventId>>()
    private val fired = mutableMapOf<AutomationId, Long>()

    override suspend fun get(id: AutomationId): Automation? = values.value[id]
    override suspend fun all(): List<Automation> = values.value.values.toList()
    override fun observeAll(): Flow<List<Automation>> =
        MutableStateFlow(values.value.values.toList())

    override suspend fun armed(): List<Automation> = values.value.values.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }

    override suspend fun delete(id: AutomationId) {
        values.value -= id
    }

    override suspend fun disable(id: AutomationId) {
        values.value[id]?.let {
            values.value += id to it.copy(status = AutomationStatus.DISABLED, enabled = false)
        }
    }

    override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean {
        val current = values.value[id] ?: return false
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint
        ) return false
        values.value += id to current.copy(status = AutomationStatus.DISABLED, enabled = false)
        return true
    }

    override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean = false

    override suspend fun markNeedsReview(id: AutomationId) {
        values.value[id]?.let {
            values.value += id to it.copy(status = AutomationStatus.NEEDS_REVIEW, enabled = false)
        }
    }

    override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean =
        false

    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult {
        val key = request.automationId to request.eventId
        if (!claims.add(key)) return FireClaimResult.Duplicate(request.executionId)
        val last = fired[request.automationId]
        if (last != null && request.cooldownMs > 0 && request.atMillis - last < request.cooldownMs) {
            return FireClaimResult.Cooldown(last + request.cooldownMs)
        }
        fired[request.automationId] = request.atMillis
        return FireClaimResult.Claimed
    }

    override suspend fun recordFired(id: AutomationId, atMillis: Long) {
        fired[id] = atMillis
    }

    override suspend fun lastFiredAt(id: AutomationId): Long? = fired[id]
}

internal class FakePauseStore(initial: Boolean = false) : AutomationPauseStore {
    private val state = MutableStateFlow(initial)
    override val paused: StateFlow<Boolean> = state.asStateFlow()
    override fun setPaused(paused: Boolean) {
        state.value = paused
    }
}

internal class FakeTokenStore : ManualTokenStore {
    private val tokens = mutableMapOf<AutomationId, String>()
    private var issued = 0
    override fun tokenFor(id: AutomationId): String = tokens.getOrPut(id) { "t".repeat(31) + (issued++) }
    override fun isValid(id: AutomationId, token: String?): Boolean = token != null && tokens[id] == token
    override fun forget(id: AutomationId) {
        tokens.remove(id)
    }
}

internal class FakeTileSelection(initial: String? = null) : ManualTileSelection {
    private val state = MutableStateFlow(initial)
    override val ruleId: StateFlow<String?> = state.asStateFlow()
    override fun select(id: String?) {
        state.value = id
    }
}

internal class FakePinner(
    private val supported: Boolean = true,
    private val accepts: Boolean = true,
) : ManualShortcutPinner {
    val requested = mutableListOf<ManualEntry>()
    override fun isSupported(): Boolean = supported
    override fun requestPin(entry: ManualEntry): Boolean {
        requested += entry
        return accepts
    }
}

internal class RecordingDispatcher : ManualEventDispatcher {
    val envelopes = mutableListOf<TriggerEnvelope>()
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        envelopes += envelope
    }
}

/** Lancia subito e in modo sincrono: il Job restituito è già concluso se il blocco non sospende. */
internal val ImmediateLauncher = ManualWorkLauncher { _, block ->
    CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) { block() }
}

internal class RecordingAudit : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) {
        events += e
    }
}

internal class Rig(
    rules: List<Automation>,
    paused: Boolean = false,
) {
    val store = MapStore(*rules.toTypedArray())
    val tokens = FakeTokenStore()
    val pause = FakePauseStore(paused)
    val dispatcher = RecordingDispatcher()
    private var tick = 0L
    private var counter = 0
    val runner = ManualRuleRunner(
        store = store,
        tokens = tokens,
        pause = pause,
        dispatcher = dispatcher,
        launcher = ImmediateLauncher,
        nowMillis = { 1_000L + tick++ },
        nonce = { "n${counter++}" },
    )
}

/** Servizio dell'avvio manuale con tutte le dipendenze finte e ispezionabili, per i test dei ViewModel. */
internal class ManualHarness(
    store: AutomationStore,
    paused: Boolean = false,
    pinSupported: Boolean = true,
    pinAccepts: Boolean = true,
) {
    val tokens = FakeTokenStore()
    val pause = FakePauseStore(paused)
    val dispatcher = RecordingDispatcher()
    val pinner = FakePinner(supported = pinSupported, accepts = pinAccepts)
    val tile = FakeTileSelection()
    val service = ManualEntryService(
        catalog = ManualRuleCatalog(store, tokens),
        tokens = tokens,
        runner = ManualRuleRunner(store, tokens, pause, dispatcher, ImmediateLauncher),
        pinner = pinner,
        tile = tile,
        pause = pause,
    )
}
