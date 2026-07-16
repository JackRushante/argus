package dev.argus.automation

import dev.argus.device.StateReader
import dev.argus.engine.model.StateKeys
import dev.argus.engine.runtime.GeoPoint
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LazyDeviceStateProviderTest {
    @Test
    fun `location-only request bypasses unavailable Shizuku and never invokes shell`() = runTest {
        val shell = RecordingStateShell()
        var locationReads = 0
        val provider = LazyDeviceStateProvider(
            StateReader(shell),
            { ShizukuGatewayStatus.INSTALLED_NOT_RUNNING },
            CurrentLocationProvider {
                locationReads++
                DeviceLocation(45.0, 9.0)
            },
        )

        val state = provider.current(StateReadRequest(includeLocation = true))

        assertEquals(GeoPoint(45.0, 9.0), state.location)
        assertEquals(1, locationReads)
        assertEquals(emptyList(), shell.calls)
    }

    @Test
    fun `authorized key request executes only the requested state command`() = runTest {
        val shell = RecordingStateShell()
        var locationReads = 0
        val provider = LazyDeviceStateProvider(
            StateReader(shell),
            { ShizukuGatewayStatus.AUTHORIZED },
            CurrentLocationProvider {
                locationReads++
                DeviceLocation(45.0, 9.0)
            },
        )

        val state = provider.current(StateReadRequest(keys = setOf(StateKeys.WIFI)))

        assertEquals(mapOf(StateKeys.WIFI to "on"), state.values)
        assertEquals(0, locationReads)
        assertEquals(
            listOf(listOf("/system/bin/settings", "get", "global", "wifi_on")),
            shell.calls,
        )
    }

    @Test
    fun `mixed request preserves location when privileged state is unavailable`() = runTest {
        val shell = RecordingStateShell()
        val provider = LazyDeviceStateProvider(
            StateReader(shell),
            { ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED },
            CurrentLocationProvider { DeviceLocation(45.0, 9.0) },
        )

        val state = provider.current(
            StateReadRequest(
                keys = setOf(StateKeys.WIFI),
                includeLocation = true,
            ),
        )

        assertEquals(emptyMap(), state.values)
        assertEquals(GeoPoint(45.0, 9.0), state.location)
        assertEquals(emptyList(), shell.calls)
    }

    @Test
    fun `location outage is unknown while cancellation still propagates`() = runTest {
        val unavailable = LazyDeviceStateProvider(
            StateReader(RecordingStateShell()),
            { ShizukuGatewayStatus.AUTHORIZED },
            CurrentLocationProvider { error("provider down") },
        )
        assertEquals(
            null,
            unavailable.current(StateReadRequest(includeLocation = true)).location,
        )

        val cancelled = LazyDeviceStateProvider(
            StateReader(RecordingStateShell()),
            { ShizukuGatewayStatus.AUTHORIZED },
            CurrentLocationProvider { throw CancellationException("cancelled") },
        )
        assertFailsWith<CancellationException> {
            cancelled.current(StateReadRequest(includeLocation = true))
        }
    }
}

private class RecordingStateShell : PrivilegedShell {
    val calls = mutableListOf<List<String>>()

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult {
        calls += command
        val stdout = if (command.lastOrNull() == "wifi_on") "1" else ""
        return ShellResult(exitCode = 0, stdout = stdout.toByteArray())
    }

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = error("not used")
}
