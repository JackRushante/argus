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

    // --- 0.8: valori conservati e modalità ------------------------------------------------------------

    private class MemoryPersistentState(
        private val fail: Boolean = false,
    ) : dev.argus.engine.runtime.StoredValueStore, dev.argus.engine.runtime.UserModeStore {
        val askedKeys = mutableListOf<Set<String>>()
        var snapshots = 0

        override suspend fun read(keys: Set<String>): Map<String, dev.argus.engine.model.StoredValue> {
            askedKeys += keys
            if (fail) error("db")
            return keys.filter { it == "caffe_oggi" }.associateWith {
                dev.argus.engine.model.StoredValue(
                    it, dev.argus.engine.model.StateValueType.NUMBER, "2",
                    dev.argus.engine.model.IntegrityLabel.TAINTED, dev.argus.engine.model.ConfidentialityLabel.PRIVATE,
                    dev.argus.engine.model.ChangeAuthor.User, 1L,
                )
            }
        }

        override suspend fun apply(
            action: dev.argus.engine.model.Action.SetStored,
            integrity: dev.argus.engine.model.IntegrityLabel,
            confidentiality: dev.argus.engine.model.ConfidentialityLabel,
            author: dev.argus.engine.model.ChangeAuthor,
            atMillis: Long,
        ): dev.argus.engine.model.StoredWrite = error("not used")

        override suspend fun snapshot(): dev.argus.engine.model.UserModeSnapshot {
            snapshots++
            if (fail) error("db")
            return dev.argus.engine.model.UserModeSnapshot(listOf("Casa"), "Casa")
        }

        override suspend fun activate(
            mode: String?,
            author: dev.argus.engine.model.ChangeAuthor,
            atMillis: Long,
        ): dev.argus.engine.model.ModeSwitch = error("not used")
    }

    @Test
    fun `stored values and the mode are read without Shizuku, only when asked, with their labels`() = runTest {
        val shell = RecordingStateShell()
        val memory = MemoryPersistentState()
        val provider = LazyDeviceStateProvider(
            StateReader(shell),
            { ShizukuGatewayStatus.NOT_INSTALLED },
            CurrentLocationProvider { error("non va letta") },
            persistentState = PersistentStateReader(memory, memory),
        )

        val state = provider.current(StateReadRequest(storedKeys = setOf("caffe_oggi", "assente"), includeUserMode = true))

        val saved = state.stored.getValue("caffe_oggi")
        assertEquals("2", saved.text)
        assertEquals(dev.argus.engine.model.IntegrityLabel.TAINTED, saved.integrity)
        // Una chiave che non c'è resta assente: UNKNOWN, mai un valore vuoto.
        assertEquals(setOf("caffe_oggi"), state.stored.keys)
        assertEquals("Casa", state.userMode?.active)
        assertEquals(emptyList(), shell.calls)

        provider.current(StateReadRequest(keys = emptySet(), includeLocation = false, storedKeys = emptySet()))
        provider.current(StateReadRequest(storedKeys = setOf("caffe_oggi")))
        assertEquals(listOf(setOf("caffe_oggi", "assente"), setOf("caffe_oggi")), memory.askedKeys)
        assertEquals(1, memory.snapshots)
    }

    @Test
    fun `an unreadable store is unknown, never a default`() = runTest {
        val memory = MemoryPersistentState(fail = true)
        val provider = LazyDeviceStateProvider(
            StateReader(RecordingStateShell()),
            { ShizukuGatewayStatus.AUTHORIZED },
            CurrentLocationProvider { null },
            persistentState = PersistentStateReader(memory, memory),
        )
        val state = provider.current(StateReadRequest(storedKeys = setOf("caffe_oggi"), includeUserMode = true))
        assertEquals(emptyMap(), state.stored)
        assertEquals(null, state.userMode)
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
