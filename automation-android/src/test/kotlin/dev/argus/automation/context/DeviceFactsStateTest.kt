package dev.argus.automation.context

import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.LazyDeviceStateProvider
import dev.argus.automation.calendar.PrefsCalendarFiredStore
import dev.argus.device.StateReader
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState
import dev.argus.engine.runtime.BluetoothLink
import dev.argus.engine.runtime.DeviceFact
import dev.argus.engine.runtime.DeviceFacts
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.WifiLink
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Le letture delle condizioni di contesto passano dal provider dello stato solo se chieste. */
class DeviceFactsStateTest {
    private val noShell = object : PrivilegedShell {
        override suspend fun run(
            command: List<String>,
            priority: Int,
            timeoutMillis: Long,
            maxOutputBytes: Int,
            executionId: String?,
        ): ShellResult = error("niente shell: $command")

        override suspend fun runToFile(
            command: List<String>,
            destination: File,
            priority: Int,
            timeoutMillis: Long,
            maxOutputBytes: Int,
            executionId: String?,
        ): ShellResult = error("niente shell")
    }

    private fun provider(reader: DeviceFactsReader?) = LazyDeviceStateProvider(
        StateReader(noShell),
        { ShizukuGatewayStatus.NOT_INSTALLED },
        CurrentLocationProvider { null },
        factsReader = reader,
    )

    @Test
    fun `facts are read only when a rule asks for them, without shell`() = runTest {
        val asked = mutableListOf<Set<DeviceFact>>()
        val all = DeviceFacts(wifi = WifiLink(true, "Casa"), audioOutputs = setOf(AudioOutputKind.WIRED))
        val provider = provider(DeviceFactsReader { facts -> asked += facts; all })

        assertEquals(DeviceFacts(), provider.current(StateReadRequest(includeLocation = true)).facts)
        assertEquals(emptyList(), asked)

        val state = provider.current(StateReadRequest(facts = setOf(DeviceFact.AUDIO_OUTPUTS)))
        assertEquals(listOf(setOf(DeviceFact.AUDIO_OUTPUTS)), asked)
        // Solo il fatto chiesto: il nome della rete letto per altro non passa.
        assertEquals(DeviceFacts(audioOutputs = setOf(AudioOutputKind.WIRED)), state.facts)
    }

    @Test
    fun `a failing or missing reader leaves every fact unknown`() = runTest {
        val request = StateReadRequest(facts = setOf(DeviceFact.WIFI, DeviceFact.CALENDAR_NOW))
        assertEquals(DeviceFacts(), provider(DeviceFactsReader { error("boom") }).current(request).facts)
        assertEquals(DeviceFacts(), provider(null).current(request).facts)
    }

    @Test
    fun `the bluetooth tracker follows acl connections by address`() {
        BluetoothConnectionTracker.clear()
        BluetoothConnectionTracker.observe("aa:bb:cc:dd:ee:ff", "Auto", ConnState.CONNECTED)
        BluetoothConnectionTracker.observe("11:22:33:44:55:66", null, ConnState.CONNECTED)
        BluetoothConnectionTracker.observe("11:22:33:44:55:66", null, ConnState.DISCONNECTED)
        assertEquals(listOf(BluetoothLink("AA:BB:CC:DD:EE:FF", "Auto")), BluetoothConnectionTracker.connected())
        BluetoothConnectionTracker.clear()
    }

    @Test
    fun `the calendar dedupe drops entries that can no longer fire`() {
        val day = 24 * 60 * 60 * 1_000L
        val now = 10 * day
        val kept = PrefsCalendarFiredStore.prune(
            setOf("1:100:START@${now - day}", "2:100:END@${now - 3 * day}", "malformed", "3:1:START@x"),
            now,
        )
        assertEquals(setOf("1:100:START@${now - day}"), kept)
    }
}
