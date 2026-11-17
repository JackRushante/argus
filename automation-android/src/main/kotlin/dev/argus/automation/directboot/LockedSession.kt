package dev.argus.automation.directboot

import dev.argus.engine.directboot.DirectBootCopy
import dev.argus.engine.directboot.DirectBootCopyRead
import dev.argus.engine.directboot.DirectBootRecord
import dev.argus.engine.directboot.DirectBootRecordKind
import dev.argus.engine.directboot.DirectBootRunner
import dev.argus.engine.directboot.DirectBootSchedule
import dev.argus.engine.directboot.DirectBootSessionState
import dev.argus.engine.directboot.DirectBootSlot
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.safety.DraftValidator
import java.time.Instant

/** The one alarm of the locked session (the earliest occurrence any rule waits for). */
interface LockedAlarms {
    fun schedule(atMillis: Long, exact: Boolean)
    fun cancel()
}

/**
 * 0.8, rules before the first unlock: the path that runs between a restart and the first unlock. It depends on
 * nothing of the app's main graph: only the device-encrypted [storage], the copy codec, the clock and the allow-listed
 * actions behind [runner]. It reads the copy back and checks every entry again at every wake-up, and it stands down
 * as soon as the user has unlocked or the normal runtime has taken over this boot.
 */
class LockedSession(
    private val storage: DirectBootStorage,
    private val validator: DraftValidator,
    private val runner: DirectBootRunner,
    private val alarms: LockedAlarms,
    private val isUserLocked: () -> Boolean,
    /** `Settings.Global.BOOT_COUNT`; null = unreadable, and then the locked path does nothing (fail closed). */
    private val bootCount: () -> Int?,
    private val now: () -> Instant,
) {
    /** `LOCKED_BOOT_COMPLETED`: opens the session of this boot and arms its first alarm. */
    suspend fun onLockedBoot() {
        val boot = bootCount() ?: return
        if (!isUserLocked()) return
        storage.locked {
            if (handedOverBoot() == boot) return@locked
            val read = decode(readCopy())
            val previous = readSession()
            val sameBoot = previous?.bootCount == boot
            // A repeated broadcast of the same boot keeps its session and does not log the refusals twice.
            if (!sameBoot) {
                read.rejected.forEach { refused ->
                    refused.automationId?.let { id ->
                        append(DirectBootRecord(DirectBootRecordKind.REJECTED, id.value, at = now().toEpochMilli(), code = refused.code))
                    }
                }
            }
            val slots = DirectBootSchedule.plan(read.rules, if (sameBoot) previous?.slots.orEmpty() else emptyList(), now())
            writeSession(DirectBootSessionState(boot, slots))
            arm(read, slots)
        }
    }

    /** The session's alarm: runs every due occurrence once, then arms the next alarm. */
    suspend fun onAlarm() {
        val boot = bootCount() ?: return
        storage.locked {
            if (!isUserLocked() || handedOverBoot() == boot) {
                alarms.cancel()
                return@locked
            }
            val session = readSession()?.takeIf { it.bootCount == boot } ?: return@locked
            val read = decode(readCopy())
            var slots = DirectBootSchedule.plan(read.rules, session.slots, now())
            try {
                val claimed = records()
                    .filter { it.kind == DirectBootRecordKind.CLAIMED }
                    .map { Triple(it.id, it.fingerprint, it.eventAt) }
                    .toSet()
                for ((rule, slot) in DirectBootSchedule.due(read.rules, slots, now().toEpochMilli())) {
                    // Unlocked in the meantime: the normal runtime owns the rest.
                    if (!isUserLocked()) break
                    // The session moves on BEFORE the actions: a crash in the middle never runs the occurrence again.
                    slots = slots.mapNotNull { if (it.id == slot.id) DirectBootSchedule.advance(rule, slot, now().toEpochMilli()) else it }
                    writeSession(DirectBootSessionState(boot, slots))
                    if (Triple(slot.id, slot.fingerprint, slot.nextEventAt) in claimed) continue
                    runner.fire(rule, slot.nextEventAt) { record -> append(record) }
                }
            } finally {
                writeSession(DirectBootSessionState(boot, slots))
                if (isUserLocked()) arm(read, slots) else alarms.cancel()
            }
        }
    }

    private fun decode(text: String?): DirectBootCopyRead =
        if (text == null) DirectBootCopyRead(emptyList(), emptyList()) else DirectBootCopy.decode(text, validator)

    private fun arm(read: DirectBootCopyRead, slots: List<DirectBootSlot>) {
        val wake = DirectBootSchedule.nextWake(slots)
        if (wake == null) {
            alarms.cancel()
            return
        }
        alarms.schedule(wake, DirectBootSchedule.precisionAt(read.rules, slots, wake) == TimePrecision.EXACT)
    }
}
