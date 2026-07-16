package dev.argus.engine.runtime

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.SensorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SensorEventIdsTest {
    @Test fun `event id is stable opaque and cannot carry a raw sensor value`() {
        val automationId = AutomationId("sensor-rule")
        val fingerprint = ApprovalFingerprint("a".repeat(64))

        val first = SensorEventIds.create(
            automationId,
            fingerprint,
            SensorKind.SIGNIFICANT_MOTION,
            detectionSequence = 12_345_678_901,
        )
        val replay = SensorEventIds.create(
            automationId,
            fingerprint,
            SensorKind.SIGNIFICANT_MOTION,
            detectionSequence = 12_345_678_901,
        )
        val next = SensorEventIds.create(
            automationId,
            fingerprint,
            SensorKind.SIGNIFICANT_MOTION,
            detectionSequence = 12_345_678_902,
        )

        assertEquals(first, replay)
        assertNotEquals(first, next)
        assertTrue(first.value.matches(Regex("sensor:significant_motion:[0-9a-f]{64}")))
        assertFalse("12345678901" in first.value)
        assertFalse(automationId.value in first.value)
    }

    @Test fun `negative detection sequence is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SensorEventIds.create(
                AutomationId("sensor-rule"),
                ApprovalFingerprint("a".repeat(64)),
                SensorKind.SIGNIFICANT_MOTION,
                -1,
            )
        }
    }
}
