package dev.argus.engine.directboot

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Day
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.safety.DraftValidator
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectBootSessionTest {
    // Monday 2026-11-16, 06:00 UTC.
    private val now = Instant.parse("2026-11-16T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val validator = DraftValidator(knownTools = emptySet(), clock = clock)

    // --- copy ---------------------------------------------------------------------------------------

    @Test
    fun `the copy keeps the rule without its name and reads it back`() {
        val rule = rule("a")
        val entry = assertNotNull(DirectBootCopy.entryFor(rule))
        assertEquals("", entry.rule.name)
        assertEquals(rule.approvalFingerprint?.value, entry.fingerprint)
        assertTrue("Night" !in DirectBootCopy.encode(listOf(entry)))

        val read = DirectBootCopy.decode(DirectBootCopy.encode(listOf(entry)), validator)
        assertEquals(listOf(rule.id), read.rules.map { it.id })
        assertEquals(emptyList(), read.rejected)
    }

    @Test
    fun `only armed, enabled, qualifying and intact rules enter the copy`() {
        assertNull(DirectBootCopy.entryFor(rule("a").copy(status = AutomationStatus.DISABLED, enabled = false)))
        assertNull(DirectBootCopy.entryFor(rule("a", actions = listOf(Action.RunShell("date")))))
        // Fingerprint of another revision: the rule changed after the approval.
        assertNull(DirectBootCopy.entryFor(rule("a").copy(actions = listOf(Action.Vibrate(10)))))
    }

    @Test
    fun `a tampered entry is refused and the others still run`() {
        val good = assertNotNull(DirectBootCopy.entryFor(rule("a")))
        val other = assertNotNull(DirectBootCopy.entryFor(rule("b")))
        val text = DirectBootCopy.encode(listOf(good, other))
        // Another action written over the approved one, the digest left as it was.
        val tampered = text.replaceFirst("\"type\":\"set_dnd\",\"mode\":\"TOTAL\"", "\"type\":\"set_dnd\",\"mode\":\"OFF\"")
        assertTrue(tampered != text)

        val read = DirectBootCopy.decode(tampered, validator)
        assertEquals(listOf(AutomationId("b")), read.rules.map { it.id })
        assertEquals(listOf(DirectBootRejection(AutomationId("a"), DirectBootCopy.CORRUPT)), read.rejected)
    }

    @Test
    fun `garbage, another version and an oversized copy give no rule at all`() {
        val unreadable = listOf(DirectBootRejection(null, DirectBootCopy.UNREADABLE))
        assertEquals(unreadable, DirectBootCopy.decode("{not json", validator).rejected)
        assertEquals(emptyList(), DirectBootCopy.decode("{not json", validator).rules)
        assertEquals(unreadable, DirectBootCopy.decode("""{"version":2,"rules":[]}""", validator).rejected)
        assertEquals(unreadable, DirectBootCopy.decode("x".repeat(DirectBootCopy.MAX_CHARS + 1), validator).rejected)
    }

    @Test
    fun `entries that are not armed, not qualifying, invalid, renamed or duplicated are refused`() {
        fun forged(rule: Automation) = DirectBootCopyEntry(
            rule.approvalFingerprint!!.value,
            DirectBootCopy.digestOf(rule),
            rule,
        )
        val disarmed = signed(rule("d").copy(status = AutomationStatus.DISABLED, enabled = false, name = ""))
        val shell = signed(rule("s", actions = listOf(Action.RunShell("date"))).copy(name = ""))
        val badCron = signed(rule("c", trigger = Trigger.Time(cron = "99 99 * * *", tz = "Europe/Rome")).copy(name = ""))
        val named = rule("n").let { DirectBootCopyEntry(it.approvalFingerprint!!.value, DirectBootCopy.digestOf(it), it) }
        val ok = assertNotNull(DirectBootCopy.entryFor(rule("o")))
        val file = ArgusJson.encodeToString(
            DirectBootCopyFile.serializer(),
            DirectBootCopyFile(DirectBootCopy.VERSION, listOf(forged(disarmed), forged(shell), forged(badCron), named, ok, ok)),
        )

        val read = DirectBootCopy.decode(file, validator)
        assertEquals(listOf(AutomationId("o")), read.rules.map { it.id })
        assertEquals(
            listOf(
                DirectBootRejection(AutomationId("d"), DirectBootCopy.NOT_ARMED),
                DirectBootRejection(AutomationId("s"), DirectBootCopy.NOT_QUALIFYING),
                DirectBootRejection(AutomationId("c"), DirectBootCopy.INVALID),
                DirectBootRejection(AutomationId("n"), DirectBootCopy.CORRUPT),
                DirectBootRejection(AutomationId("o"), DirectBootCopy.DUPLICATE),
            ),
            read.rejected,
        )
    }

    // --- records ------------------------------------------------------------------------------------

    @Test
    fun `records round trip and a line that is not a valid record is dropped`() {
        val fp = rule("a").approvalFingerprint!!.value
        val claimed = DirectBootRecord(DirectBootRecordKind.CLAIMED, "a", fp, 1_000, 1_001)
        val done = DirectBootRecord(
            DirectBootRecordKind.DONE, "a", fp, 1_000, 1_002,
            results = listOf(DirectBootActionRecord("set_dnd", ok = false, code = "dnd_policy_unavailable")),
        )
        val badCode = """{"kind":"REJECTED","id":"a","at":5,"code":"Not A Code"}"""
        val text = listOf(DirectBootRecords.encode(claimed), "garbage", badCode, DirectBootRecords.encode(done)).joinToString("\n")
        assertEquals(listOf(claimed, done), DirectBootRecords.decode(text))
    }

    // --- schedule -----------------------------------------------------------------------------------

    @Test
    fun `a slot survives a re-plan of the same revision and restarts for a new one`() {
        val a = rule("a")
        val first = DirectBootSchedule.plan(listOf(a), emptyList(), now)
        assertEquals(Instant.parse("2026-11-16T06:00:00Z").plusSeconds(3600).toEpochMilli(), first.single().nextEventAt)

        val later = now.plusSeconds(7200)
        assertEquals(first, DirectBootSchedule.plan(listOf(a), first, later))

        val revised = rule("a", actions = listOf(Action.Vibrate(100)))
        val replanned = DirectBootSchedule.plan(listOf(revised), first, later)
        assertEquals(revised.approvalFingerprint!!.value, replanned.single().fingerprint)
        assertTrue(replanned.single().nextEventAt > later.toEpochMilli())
        // A rule no longer in the copy is dropped.
        assertEquals(emptyList(), DirectBootSchedule.plan(emptyList(), first, later))
    }

    @Test
    fun `due rules run by priority and advance without a backlog`() {
        val high = rule("z").copy(priority = 0).let(::signed)
        val low = rule("a").copy(priority = 5).let(::signed)
        val slots = DirectBootSchedule.plan(listOf(high, low), emptyList(), now)
        val wake = DirectBootSchedule.nextWake(slots)!!
        val late = wake + 3 * 3_600_000L
        val due = DirectBootSchedule.due(listOf(high, low), slots, late)
        assertEquals(listOf("z", "a"), due.map { it.first.id.value })
        val next = DirectBootSchedule.advance(high, due.first().second, late)!!
        assertTrue(next.nextEventAt > late)
        assertEquals(TimePrecision.FLEXIBLE, DirectBootSchedule.precisionAt(listOf(high, low), slots, wake))
    }

    // --- runner -------------------------------------------------------------------------------------

    @Test
    fun `the occurrence is claimed before any action runs`() = runTest {
        val order = mutableListOf<String>()
        val runner = DirectBootRunner(ConditionEvaluator(clock), { action -> order += "run:$action"; ActionResult.Success }) { 7L }
        val outcome = runner.fire(rule("a"), 1_000) { record -> order += "record:${record.kind}"; true }
        assertEquals(DirectBootFireOutcome.RAN, outcome)
        assertEquals(listOf("record:CLAIMED", "run:SetDnd(mode=TOTAL)", "record:DONE"), order)
    }

    @Test
    fun `if the claim cannot be recorded nothing runs`() = runTest {
        val ran = mutableListOf<Action>()
        val runner = DirectBootRunner(ConditionEvaluator(clock), { ran += it; ActionResult.Success }) { 7L }
        assertEquals(DirectBootFireOutcome.NOT_RECORDED, runner.fire(rule("a"), 1_000) { false })
        assertEquals(emptyList(), ran)
    }

    @Test
    fun `conditions not met record the occurrence and run nothing`() = runTest {
        val ran = mutableListOf<Action>()
        val records = mutableListOf<DirectBootRecord>()
        val runner = DirectBootRunner(ConditionEvaluator(clock), { ran += it; ActionResult.Success }) { 7L }
        // It is Monday: a Sunday-only rule does not run.
        val sunday = rule("a", conditions = Condition.Weekday(setOf(Day.SUN), "UTC"))
        assertEquals(DirectBootFireOutcome.CONDITIONS_NOT_MET, runner.fire(sunday, 1_000) { records += it; true })
        assertEquals(emptyList(), ran)
        assertEquals(listOf(DirectBootRecordKind.NOT_MET), records.map { it.kind })
    }

    @Test
    fun `a rule outside the allow-list never reaches the action runner`() = runTest {
        val ran = mutableListOf<Action>()
        val records = mutableListOf<DirectBootRecord>()
        val runner = DirectBootRunner(ConditionEvaluator(clock), { ran += it; ActionResult.Success }) { 7L }
        val shell = rule("a", actions = listOf(Action.SetDnd(DndMode.OFF), Action.RunShell("reboot")))
        assertEquals(DirectBootFireOutcome.NOT_QUALIFYING, runner.fire(shell, 1_000) { records += it; true })
        assertEquals(emptyList(), ran)
        assertEquals(emptyList(), records)
    }

    @Test
    fun `failures keep a closed code and an exception is action_failed`() = runTest {
        val records = mutableListOf<DirectBootRecord>()
        val runner = DirectBootRunner(
            ConditionEvaluator(clock),
            { action ->
                when (action) {
                    is Action.SetDnd -> ActionResult.Failure("dnd_policy_unavailable")
                    is Action.SetVolume -> ActionResult.Failure("Free text, not a code")
                    else -> error("boom")
                }
            },
        ) { 7L }
        val rule = rule("a", actions = listOf(Action.SetDnd(DndMode.OFF), Action.SetVolume(VolumeStream.RING, 10), Action.Vibrate(5)))
        runner.fire(rule, 1_000) { records += it; true }
        assertEquals(
            listOf(
                DirectBootActionRecord("set_dnd", ok = false, code = "dnd_policy_unavailable"),
                DirectBootActionRecord("set_volume", ok = false, code = "action_failed"),
                DirectBootActionRecord("vibrate", ok = false, code = "action_failed"),
            ),
            records.last().results,
        )
    }

    // --- import -------------------------------------------------------------------------------------

    @Test
    fun `the import pairs claims with their results and keeps the latest handled occurrence`() {
        val fp = rule("a").approvalFingerprint!!.value
        val results = listOf(DirectBootActionRecord("set_dnd", ok = true))
        val records = listOf(
            DirectBootRecord(DirectBootRecordKind.REJECTED, "x", at = 1, code = DirectBootCopy.CORRUPT),
            DirectBootRecord(DirectBootRecordKind.CLAIMED, "a", fp, 1_000, 1_001),
            DirectBootRecord(DirectBootRecordKind.DONE, "a", fp, 1_000, 1_002, results = results),
            DirectBootRecord(DirectBootRecordKind.CLAIMED, "a", fp, 1_000, 1_003),
            DirectBootRecord(DirectBootRecordKind.NOT_MET, "a", fp, 2_000, 2_001),
            DirectBootRecord(DirectBootRecordKind.CLAIMED, "a", fp, 3_000, 3_001),
        )
        val plan = DirectBootImport.plan(records)
        assertEquals(
            listOf(
                DirectBootImportItem.Rejected("x", DirectBootCopy.CORRUPT, 1),
                DirectBootImportItem.Fired("a", fp, 1_000, 1_001, results, 1_002),
                DirectBootImportItem.NotMet("a", fp, 2_000, 2_001, null),
                DirectBootImportItem.Fired("a", fp, 3_000, 3_001, null, null),
            ),
            plan,
        )
        assertEquals(mapOf(("a" to fp) to 3_000L), DirectBootImport.handledUpTo(plan))
        assertEquals("time:$fp:1000", DirectBootRecords.eventId(fp, 1_000).value)
    }

    private fun signed(rule: Automation): Automation =
        rule.copy(approvalFingerprint = null).let { it.copy(approvalFingerprint = ApprovalFingerprints.of(it)) }

    private fun rule(
        id: String,
        trigger: Trigger = Trigger.Time(cron = "0 7 * * *", tz = "UTC"),
        actions: List<Action> = listOf(Action.SetDnd(DndMode.TOTAL)),
        conditions: Condition? = null,
    ): Automation = signed(
        Automation(
            id = AutomationId(id),
            name = "Night",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = trigger,
            actions = actions,
            conditions = conditions,
        ),
    )
}
