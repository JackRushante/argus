package dev.argus.automation.directboot

import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.engine.directboot.DirectBootCopy
import dev.argus.engine.directboot.DirectBootRecord
import dev.argus.engine.directboot.DirectBootRecordKind
import dev.argus.engine.directboot.DirectBootRecords
import dev.argus.engine.directboot.DirectBootRunner
import dev.argus.engine.directboot.DirectBootSessionState
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.safety.DraftValidator
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The locked path, before the first unlock: device-encrypted files only, allow-listed actions only. */
class LockedSessionTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-11-16T06:00:00Z")
    private val seven = Instant.parse("2026-11-16T07:00:00Z").toEpochMilli()
    private val sevenTomorrow = Instant.parse("2026-11-17T07:00:00Z").toEpochMilli()
    private var locked = true
    private var boot: Int? = 3
    private val surface = RecordingSurface()
    private val alarms = RecordingAlarms()

    private val storage by lazy { DirectBootStorage(dir) }

    private fun session(): LockedSession {
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = now
        }
        return LockedSession(
            storage = storage,
            validator = DraftValidator(knownTools = emptySet(), clock = clock),
            runner = DirectBootRunner(
                ConditionEvaluator(clock),
                LockedActionRunner(AndroidBaseActionExecutor(surface)),
            ) { now.toEpochMilli() },
            alarms = alarms,
            isUserLocked = { locked },
            bootCount = { boot },
            now = { now },
        )
    }

    private suspend fun writeCopy(vararg rules: dev.argus.engine.model.Automation) {
        val text = DirectBootCopy.encode(rules.map { requireNotNull(DirectBootCopy.entryFor(it)) })
        storage.locked { writeCopy(text) }
    }

    private suspend fun records(): List<DirectBootRecord> = storage.locked { records() }

    @Test
    fun `the locked boot arms the next occurrence and the alarm runs it exactly once`() = runTest {
        val rule = qualifyingRule("a")
        writeCopy(rule)

        session().onLockedBoot()
        assertEquals(seven to false, alarms.scheduled.last())
        assertEquals(emptyList(), surface.calls)

        now = Instant.ofEpochMilli(seven + 5_000)
        session().onAlarm()
        assertEquals(listOf("dnd:OFF"), surface.calls)
        assertEquals(listOf(DirectBootRecordKind.CLAIMED, DirectBootRecordKind.DONE), records().map { it.kind })
        assertEquals(seven, records().first().eventAt)
        assertEquals(sevenTomorrow to false, alarms.scheduled.last())

        // The same alarm delivered again (or a second process) does not run the occurrence twice.
        session().onAlarm()
        assertEquals(listOf("dnd:OFF"), surface.calls)
        assertEquals(2, records().size)
    }

    @Test
    fun `a repeated locked boot keeps the session and its pending occurrence`() = runTest {
        writeCopy(qualifyingRule("a"))
        session().onLockedBoot()
        now = Instant.ofEpochMilli(seven + 60_000)
        session().onLockedBoot()
        session().onAlarm()
        assertEquals(listOf("dnd:OFF"), surface.calls)
    }

    @Test
    fun `nothing runs once the user has unlocked`() = runTest {
        writeCopy(qualifyingRule("a"))
        session().onLockedBoot()
        locked = false
        now = Instant.ofEpochMilli(seven + 5_000)
        session().onAlarm()
        assertEquals(emptyList(), surface.calls)
        assertEquals(emptyList(), records())
        assertEquals(1, alarms.cancelled)

        // A locked boot delivered after the unlock (no screen lock) opens nothing.
        alarms.scheduled.clear()
        session().onLockedBoot()
        assertTrue(alarms.scheduled.isEmpty())
    }

    @Test
    fun `a boot handed over to the normal runtime is never run by the locked path`() = runTest {
        writeCopy(qualifyingRule("a"))
        session().onLockedBoot()
        storage.locked { markHandedOver(3) }
        now = Instant.ofEpochMilli(seven + 5_000)
        session().onAlarm()
        assertEquals(emptyList(), surface.calls)
        assertEquals(1, alarms.cancelled)

        // Next boot: a new session.
        boot = 4
        alarms.scheduled.clear()
        session().onLockedBoot()
        assertEquals(sevenTomorrow to false, alarms.scheduled.last())
    }

    @Test
    fun `without a readable boot count the locked path does nothing`() = runTest {
        writeCopy(qualifyingRule("a"))
        boot = null
        session().onLockedBoot()
        session().onAlarm()
        assertTrue(alarms.scheduled.isEmpty())
        assertNull(storage.locked { readSession() })
    }

    @Test
    fun `a tampered copy runs nothing and the refusal is queued once`() = runTest {
        writeCopy(qualifyingRule("a"))
        val text = requireNotNull(storage.locked { readCopy() })
        storage.locked { writeCopy(text.replace("\"mode\":\"OFF\"", "\"mode\":\"TOTAL\"")) }

        session().onLockedBoot()
        session().onLockedBoot()
        now = Instant.ofEpochMilli(seven + 5_000)
        session().onAlarm()

        assertEquals(emptyList(), surface.calls)
        assertTrue(alarms.scheduled.isEmpty())
        val refused = records().single()
        assertEquals(DirectBootRecordKind.REJECTED, refused.kind)
        assertEquals(DirectBootCopy.CORRUPT, refused.code)
    }

    @Test
    fun `a copy that does not qualify any more never reaches the executor`() = runTest {
        // Written by hand with a shell action and a matching digest: the policy is checked again on read.
        val shell = qualifyingRule("a", actions = listOf(dev.argus.engine.model.Action.RunShell("reboot")))
        val stored = shell.copy(name = "")
        val forged = dev.argus.engine.directboot.DirectBootCopyFile(
            DirectBootCopy.VERSION,
            listOf(
                dev.argus.engine.directboot.DirectBootCopyEntry(
                    shell.approvalFingerprint!!.value,
                    digest(stored),
                    stored,
                ),
            ),
        )
        storage.locked {
            writeCopy(dev.argus.engine.model.ArgusJson.encodeToString(dev.argus.engine.directboot.DirectBootCopyFile.serializer(), forged))
            writeSession(DirectBootSessionState(3, listOf(dev.argus.engine.directboot.DirectBootSlot("a", shell.approvalFingerprint!!.value, seven))))
        }
        now = Instant.ofEpochMilli(seven + 5_000)
        session().onAlarm()
        assertEquals(emptyList(), surface.calls)
        assertEquals(emptyList(), records().filter { it.kind == DirectBootRecordKind.CLAIMED })
    }

    @Test
    fun `when the queue is full nothing runs`() = runTest {
        writeCopy(qualifyingRule("a"))
        session().onLockedBoot()
        val filler = DirectBootRecord(DirectBootRecordKind.REJECTED, "x", at = 1, code = DirectBootCopy.CORRUPT)
        storage.locked { repeat(DirectBootRecords.MAX_RECORDS) { append(filler) } }
        now = Instant.ofEpochMilli(seven + 5_000)
        session().onAlarm()
        assertEquals(emptyList(), surface.calls)
        // The occurrence is skipped, not retried: the next alarm is tomorrow.
        assertEquals(sevenTomorrow to false, alarms.scheduled.last())
    }

    @Test
    fun `storage keeps session and handover and drops garbage records`() = runTest {
        storage.locked {
            writeSession(DirectBootSessionState(7))
            markHandedOver(7)
            append(DirectBootRecord(DirectBootRecordKind.REJECTED, "a", at = 1, code = DirectBootCopy.CORRUPT))
        }
        File(dir, DirectBootStorage.QUEUE_FILE).appendText("not a record\n")
        storage.locked {
            assertEquals(DirectBootSessionState(7), readSession())
            assertEquals(7, handedOverBoot())
            assertEquals(1, records().size)
            clearRecords()
            assertEquals(emptyList(), records())
            writeCopy(null)
            assertNull(readCopy())
        }
    }

    private fun digest(rule: dev.argus.engine.model.Automation): String {
        val canonical = dev.argus.engine.model.ArgusJson.encodeToString(dev.argus.engine.model.Automation.serializer(), rule)
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("argus-before-unlock-v${DirectBootCopy.VERSION}\u0000$canonical".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
