package dev.argus.automation

import dev.argus.engine.model.SensorKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SensorCapabilityPolicyTest {
    @Test fun `policy enforces reporting mode wakeup permission and canonical order`() {
        val capabilities = listOf(
            capability(SensorKind.STEP_COUNTER, SensorReportingMode.ON_CHANGE, permission = true),
            capability(SensorKind.SIGNIFICANT_MOTION, SensorReportingMode.ONE_SHOT, wakeUp = true),
            capability(SensorKind.STEP_DETECTOR, SensorReportingMode.SPECIAL_TRIGGER, permission = false),
            capability(SensorKind.MOTION_DETECT, SensorReportingMode.CONTINUOUS),
            capability(SensorKind.STATIONARY_DETECT, SensorReportingMode.ONE_SHOT),
        )

        assertEquals(
            listOf(
                SensorKind.SIGNIFICANT_MOTION,
                SensorKind.STATIONARY_DETECT,
                SensorKind.STEP_COUNTER,
            ),
            SensorCapabilityPolicy.armableKinds(capabilities, SensorKind.entries.toSet()),
        )
    }

    @Test fun `duplicate hardware descriptors fail closed`() {
        val capability = capability(
            SensorKind.SIGNIFICANT_MOTION,
            SensorReportingMode.ONE_SHOT,
            wakeUp = true,
        )
        assertEquals(
            emptyList(),
            SensorCapabilityPolicy.armableKinds(
                listOf(capability, capability.copy(fifoMaxEventCount = 64)),
                setOf(SensorKind.SIGNIFICANT_MOTION),
            ),
        )
    }

    private fun capability(
        kind: SensorKind,
        mode: SensorReportingMode,
        wakeUp: Boolean = false,
        permission: Boolean = true,
    ) = AndroidSensorCapability(
        kind = kind,
        reportingMode = mode,
        wakeUp = wakeUp,
        fifoMaxEventCount = 0,
        minDelayUs = 0,
        maxDelayUs = 0,
        permissionGranted = permission,
    )
}
