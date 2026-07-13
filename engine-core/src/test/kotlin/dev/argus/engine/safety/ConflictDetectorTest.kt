package dev.argus.engine.safety
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class ConflictDetectorTest {
    private fun a(id: String, t: Trigger, act: Action) = Automation(AutomationId(id), id, CreatedBy.LLM,
        AutomationStatus.ARMED, t, listOf(act))
    private val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")

    @Test fun `opposite wifi on overlapping triggers conflict`() {
        val w = ConflictDetector().detect(listOf(a("1", time, Action.SetWifi(true)), a("2", time, Action.SetWifi(false))))
        assertTrue(w.any { it.targetKey == "wifi" })
    }
    @Test fun `same direction no conflict`() {
        assertEquals(emptyList(), ConflictDetector().detect(listOf(a("1", time, Action.SetWifi(true)), a("2", time, Action.SetWifi(true)))))
    }
    @Test fun `complementary geofence enter-exit pair is legitimate, no conflict`() {
        val exit = a("1", Trigger.Geofence(45.4, 11.0, 100.0, Transition.EXIT), Action.SetWifi(false))
        val enter = a("2", Trigger.Geofence(45.4, 11.0, 100.0, Transition.ENTER), Action.SetWifi(true))
        assertEquals(emptyList(), ConflictDetector().detect(listOf(exit, enter)))
    }
    @Test fun `complementary connectivity pair is legitimate, no conflict`() {
        val on = a("1", Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"), Action.SetBluetooth(false))
        val off = a("2", Trigger.Connectivity(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa"), Action.SetBluetooth(true))
        assertEquals(emptyList(), ConflictDetector().detect(listOf(on, off)))
    }
}
