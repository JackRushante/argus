package dev.argus.engine.directboot

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.TimeSpecs
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant

/** The occurrence one rule of the locked session waits for. */
@Serializable
data class DirectBootSlot(
    val id: String,
    val fingerprint: String,
    val nextEventAt: Long,
)

/** The locked session: which boot it belongs to and what each rule waits for. */
@Serializable
data class DirectBootSessionState(
    val bootCount: Int,
    val slots: List<DirectBootSlot> = emptyList(),
)

/**
 * A minimal record the locked path queues in device-encrypted storage; after unlock the normal runtime imports it
 * into the Log. Closed codes only: no names, no content.
 */
@Serializable
data class DirectBootRecord(
    val kind: DirectBootRecordKind,
    val id: String? = null,
    val fingerprint: String? = null,
    val eventAt: Long = 0,
    val at: Long,
    val code: String? = null,
    val results: List<DirectBootActionRecord> = emptyList(),
)

@Serializable
enum class DirectBootRecordKind {
    /** Written BEFORE the actions run: the occurrence is taken, it never runs twice. */
    CLAIMED,

    /** The actions of a claimed occurrence ran, with these results. */
    DONE,

    /** The conditions did not hold (or could not be read): nothing ran. */
    NOT_MET,

    /** A copy entry was refused at locked boot. */
    REJECTED,
}

@Serializable
data class DirectBootActionRecord(val type: String, val ok: Boolean, val code: String? = null)

object DirectBootRecords {
    /** At most this many records wait for the import: a full queue stops the locked path (no record, no fire). */
    const val MAX_RECORDS = 2_000

    private val CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val FINGERPRINT = Regex("^[0-9a-f]{64}$")

    fun encode(record: DirectBootRecord): String {
        require(valid(record)) { "record not valid" }
        return ArgusJson.encodeToString(DirectBootRecord.serializer(), record)
    }

    /** One record per line; a line that is not a valid record is dropped. */
    fun decode(text: String): List<DirectBootRecord> = text.lineSequence()
        .filter { it.isNotBlank() }
        .take(MAX_RECORDS)
        .mapNotNull { line ->
            runCatching { ArgusJson.decodeFromString(DirectBootRecord.serializer(), line) }.getOrNull()
                ?.takeIf(::valid)
        }
        .toList()

    fun valid(record: DirectBootRecord): Boolean {
        val id = record.id
        if (id != null && (id.isBlank() || id.length > 256 || id.any { it.isISOControl() })) return false
        if (record.fingerprint != null && !FINGERPRINT.matches(record.fingerprint)) return false
        if (record.code != null && !CODE.matches(record.code)) return false
        if (record.eventAt < 0 || record.at < 0) return false
        if (record.results.size > DirectBootPolicy.MAX_ACTIONS) return false
        if (record.results.any { !CODE.matches(it.type) || (it.code != null && !CODE.matches(it.code)) }) return false
        return when (record.kind) {
            DirectBootRecordKind.CLAIMED, DirectBootRecordKind.NOT_MET ->
                id != null && record.fingerprint != null && record.results.isEmpty()
            DirectBootRecordKind.DONE -> id != null && record.fingerprint != null
            DirectBootRecordKind.REJECTED -> record.code != null && record.results.isEmpty()
        }
    }

    /** The event id of an occurrence: the same the normal runtime gives the same time alarm (so the journal dedups). */
    fun eventId(fingerprint: String, eventAt: Long): TriggerEventId = TriggerEventId("time:$fingerprint:$eventAt")
}

/** When the rules of the locked session fire next. Same planner as the normal runtime (`TimeSpecs`). */
object DirectBootSchedule {
    /** Next occurrence strictly after [after], or null when there is none (or the spec cannot be read). */
    fun nextFire(rule: Automation, after: Instant): Long? {
        val trigger = rule.trigger as? Trigger.Time ?: return null
        return runCatching { TimeSpecs.nextFire(trigger, after)?.toEpochMilli() }.getOrNull()
    }

    /**
     * Slots for [rules] at [now]. A rule keeps the occurrence it already waits for if the revision is the same;
     * a new or changed rule starts at its next occurrence. Rules no longer in the copy are dropped.
     */
    fun plan(rules: List<Automation>, previous: List<DirectBootSlot>, now: Instant): List<DirectBootSlot> {
        val kept = previous.associateBy { it.id }
        return rules.sortedBy { it.id.value }.mapNotNull { rule ->
            val fingerprint = rule.approvalFingerprint?.value ?: return@mapNotNull null
            kept[rule.id.value]?.takeIf { it.fingerprint == fingerprint }
                ?: nextFire(rule, now)?.let { DirectBootSlot(rule.id.value, fingerprint, it) }
        }
    }

    /** Due slots, in the order the normal runtime runs a batch (priority ascending, then id). */
    fun due(rules: List<Automation>, slots: List<DirectBootSlot>, nowMillis: Long): List<Pair<Automation, DirectBootSlot>> {
        val byId = rules.associateBy { it.id.value }
        return slots.filter { it.nextEventAt <= nowMillis }
            .mapNotNull { slot -> byId[slot.id]?.takeIf { it.approvalFingerprint?.value == slot.fingerprint }?.to(slot) }
            .sortedWith(compareBy({ it.first.priority }, { it.first.id.value }))
    }

    /** After an occurrence: the next one, counted from the later of now and the occurrence (no backlog). */
    fun advance(rule: Automation, slot: DirectBootSlot, nowMillis: Long): DirectBootSlot? =
        nextFire(rule, Instant.ofEpochMilli(maxOf(nowMillis, slot.nextEventAt)))?.let { slot.copy(nextEventAt = it) }

    fun nextWake(slots: List<DirectBootSlot>): Long? = slots.minOfOrNull { it.nextEventAt }

    /** Exact if any rule waking at [wakeAt] asked for exactness. */
    fun precisionAt(rules: List<Automation>, slots: List<DirectBootSlot>, wakeAt: Long): TimePrecision {
        val ids = slots.filter { it.nextEventAt == wakeAt }.map { it.id }.toSet()
        return if (rules.any { it.id.value in ids && (it.trigger as? Trigger.Time)?.precision == TimePrecision.EXACT }) {
            TimePrecision.EXACT
        } else {
            TimePrecision.FLEXIBLE
        }
    }
}

/** Runs the allow-listed actions of a qualifying rule before unlock; anything else never reaches it. */
fun interface DirectBootActionRunner {
    suspend fun run(action: dev.argus.engine.model.Action): ActionResult
}

enum class DirectBootFireOutcome { RAN, CONDITIONS_NOT_MET, NOT_RECORDED, NOT_QUALIFYING }

/**
 * One occurrence of one rule before unlock. Conditions first (clock only); then the occurrence is CLAIMED in the
 * queue before any action runs, so it never runs twice, and if it cannot be recorded nothing runs at all; then the
 * actions, each checked against the allow-list again; then the results.
 */
class DirectBootRunner(
    private val conditions: ConditionEvaluator,
    private val actions: DirectBootActionRunner,
    private val now: () -> Long,
) {
    suspend fun fire(
        rule: Automation,
        eventAt: Long,
        append: (DirectBootRecord) -> Boolean,
    ): DirectBootFireOutcome {
        val fingerprint: ApprovalFingerprint = rule.approvalFingerprint ?: return DirectBootFireOutcome.NOT_QUALIFYING
        if (!DirectBootPolicy.qualifies(rule)) return DirectBootFireOutcome.NOT_QUALIFYING
        val id = rule.id.value

        val met = runCatching { conditions.result(rule.conditions, DeviceState()) }
            .getOrDefault(ConditionEvaluator.Result.STATE_UNAVAILABLE)
        if (met != ConditionEvaluator.Result.MET) {
            append(
                DirectBootRecord(
                    DirectBootRecordKind.NOT_MET, id, fingerprint.value, eventAt, now(),
                    code = if (met == ConditionEvaluator.Result.STATE_UNAVAILABLE) STATE_UNAVAILABLE else null,
                ),
            )
            return DirectBootFireOutcome.CONDITIONS_NOT_MET
        }

        if (!append(DirectBootRecord(DirectBootRecordKind.CLAIMED, id, fingerprint.value, eventAt, now()))) {
            return DirectBootFireOutcome.NOT_RECORDED
        }
        val results = rule.actions.map { action ->
            val type = DirectBootPolicy.journalType(action)
                ?: return@map DirectBootActionRecord(UNKNOWN_TYPE, ok = false, code = NOT_ALLOWED)
            val result = try {
                actions.run(action)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ActionResult.Failure(ACTION_FAILED)
            }
            when (result) {
                is ActionResult.Failure -> DirectBootActionRecord(type, ok = false, code = closedCode(result.reason))
                else -> DirectBootActionRecord(type, ok = true)
            }
        }
        append(DirectBootRecord(DirectBootRecordKind.DONE, id, fingerprint.value, eventAt, now(), results = results))
        return DirectBootFireOutcome.RAN
    }

    private fun closedCode(reason: String): String = reason.takeIf { CODE.matches(it) } ?: ACTION_FAILED

    companion object {
        const val STATE_UNAVAILABLE = "condition_state_unavailable"
        const val NOT_ALLOWED = "before_unlock_not_allowed"
        const val ACTION_FAILED = "action_failed"
        private const val UNKNOWN_TYPE = "unknown"
        private val CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    }
}

/** What the normal runtime imports after unlock, grouped from the queue. */
sealed interface DirectBootImportItem {
    val id: String

    /** A claimed occurrence; [results] null = it was claimed but its results never arrived (process died). */
    data class Fired(
        override val id: String,
        val fingerprint: String,
        val eventAt: Long,
        val at: Long,
        val results: List<DirectBootActionRecord>?,
        val completedAt: Long?,
    ) : DirectBootImportItem

    data class NotMet(
        override val id: String,
        val fingerprint: String,
        val eventAt: Long,
        val at: Long,
        val code: String?,
    ) : DirectBootImportItem

    data class Rejected(override val id: String, val code: String, val at: Long) : DirectBootImportItem
}

object DirectBootImport {
    /** Groups the queue into what the Log gets, in time order. A DONE without its CLAIM is dropped. */
    fun plan(records: List<DirectBootRecord>): List<DirectBootImportItem> {
        val done = records.filter { it.kind == DirectBootRecordKind.DONE }
            .associateBy { Triple(it.id, it.fingerprint, it.eventAt) }
        val seen = mutableSetOf<Triple<String?, String?, Long>>()
        return records.mapNotNull { record ->
            when (record.kind) {
                DirectBootRecordKind.CLAIMED -> {
                    val key = Triple(record.id, record.fingerprint, record.eventAt)
                    if (!seen.add(key)) return@mapNotNull null
                    val finished = done[key]
                    DirectBootImportItem.Fired(
                        requireNotNull(record.id), requireNotNull(record.fingerprint), record.eventAt, record.at,
                        finished?.results, finished?.at,
                    )
                }
                DirectBootRecordKind.NOT_MET -> DirectBootImportItem.NotMet(
                    requireNotNull(record.id), requireNotNull(record.fingerprint), record.eventAt, record.at, record.code,
                )
                DirectBootRecordKind.REJECTED -> record.id?.let {
                    DirectBootImportItem.Rejected(it, requireNotNull(record.code), record.at)
                }
                DirectBootRecordKind.DONE -> null
            }
        }.sortedBy { item ->
            when (item) {
                is DirectBootImportItem.Fired -> item.at
                is DirectBootImportItem.NotMet -> item.at
                is DirectBootImportItem.Rejected -> item.at
            }
        }
    }

    /**
     * The latest occurrence the locked path handled (ran or evaluated) for each rule and revision. A due time alarm
     * the normal runtime kept from before the restart and that is not later than this is superseded: it must not
     * be recovered after unlock, or the rule would run again for an occurrence already handled.
     */
    fun handledUpTo(items: List<DirectBootImportItem>): Map<Pair<String, String>, Long> = buildMap {
        items.forEach { item ->
            val (key, eventAt) = when (item) {
                is DirectBootImportItem.Fired -> (item.id to item.fingerprint) to item.eventAt
                is DirectBootImportItem.NotMet -> (item.id to item.fingerprint) to item.eventAt
                is DirectBootImportItem.Rejected -> return@forEach
            }
            put(key, maxOf(get(key) ?: Long.MIN_VALUE, eventAt))
        }
    }
}
