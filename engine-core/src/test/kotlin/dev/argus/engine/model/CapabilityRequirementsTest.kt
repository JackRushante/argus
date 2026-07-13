package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilityRequirementsTest {
    @Test
    fun `requirements include trigger condition and action capabilities`() {
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_TIME,
                CapabilityIds.STATE_LOCATION,
                CapabilityIds.ACTION_SET_DND,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                conditions = Condition.LocationIn(45.46, 9.19, 100.0),
                actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
            ),
        )
    }

    @Test
    fun `nested conditions retain every state dependency`() {
        val conditions = Condition.And(
            listOf(
                Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "normal"),
                Condition.Or(
                    listOf(
                        Condition.AppInForeground("com.example"),
                        Condition.Not(Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "off")),
                    ),
                ),
            ),
        )

        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_CONNECTIVITY,
                CapabilityIds.state(StateKeys.RINGER),
                CapabilityIds.STATE_FOREGROUND_APP,
                CapabilityIds.state(StateKeys.WIFI),
                CapabilityIds.ACTION_SHOW_NOTIFICATION,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
                conditions = conditions,
                actions = listOf(Action.ShowNotification("Argus", "test")),
            ),
        )
    }
}
