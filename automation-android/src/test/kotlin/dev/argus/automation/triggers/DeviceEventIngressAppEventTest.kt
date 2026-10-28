package dev.argus.automation.triggers

import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 0.5.1: l'ingresso consegna «app aperta/chiusa» al motore, con un id stabile per transizione. */
class DeviceEventIngressAppEventTest {
    private val dispatched = mutableListOf<TriggerEnvelope>()
    private val ingress = DeviceEventIngress(DeviceEventParser(), InMemoryBatteryLevelStore()) { dispatched += it }
    private val youtube = "com.google.android.youtube"

    @Test
    fun `an app event is delivered as the domain event`() = runTest {
        ingress.onAppEvent(youtube, AppEventKind.OPENED, 1_000L)
        ingress.onAppEvent(youtube, AppEventKind.CLOSED, 9_000L)

        assertEquals(
            listOf<TriggerEvent>(
                TriggerEvent.AppForegroundChanged(youtube, AppEventKind.OPENED),
                TriggerEvent.AppForegroundChanged(youtube, AppEventKind.CLOSED),
            ),
            dispatched.map { it.event },
        )
    }

    @Test
    fun `the same transition read twice has the same id so the engine runs it once`() = runTest {
        ingress.onAppEvent(youtube, AppEventKind.OPENED, 1_000L)
        ingress.onAppEvent(youtube, AppEventKind.OPENED, 1_000L)
        ingress.onAppEvent(youtube, AppEventKind.OPENED, 2_000L)

        assertEquals(3, dispatched.size)
        assertEquals(dispatched[0].id, dispatched[1].id)
        assertTrue(dispatched[0].id != dispatched[2].id)
    }

    @Test
    fun `a package that is not an Android package is dropped`() = runTest {
        ingress.onAppEvent("", AppEventKind.OPENED, 1_000L)
        ingress.onAppEvent("not a package", AppEventKind.CLOSED, 1_000L)

        assertTrue(dispatched.isEmpty())
    }
}
