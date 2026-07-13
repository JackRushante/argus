package dev.argus.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.StateKeys
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuPermissionResult
import dev.argus.shizuku.ShizukuPrivilegedShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceToolsInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun safeDeviceRoundTripAndRestoredDndMutation() = runBlocking {
        val gateway = ShizukuGateway(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shell = ShizukuPrivilegedShell(context, gateway, scope)
        val tools = DeviceTools(context, shell)
        val reader = StateReader(shell)
        try {
            if (gateway.status() != ShizukuGatewayStatus.AUTHORIZED) {
                assertEquals(
                    "Approva il dialog Shizuku sul dispositivo",
                    ShizukuPermissionResult.GRANTED,
                    withTimeout(60_000) { gateway.requestPermission() },
                )
            }

            val png = withTimeout(40_000) { tools.capture() }
            assertTrue(png.size > 8)
            assertEquals(0x89.toByte(), png[0])

            val hierarchy = withTimeout(40_000) { tools.dumpUi() }
            assertTrue(hierarchy.contains("<hierarchy"))
            assertTrue(hierarchy.endsWith("</hierarchy>"))

            val state = reader.read(StateKeys.ALL.keys, includeForegroundApp = true)
            assertTrue(state.values.keys.all(StateKeys.ALL::containsKey))
            setOf(
                StateKeys.RINGER,
                StateKeys.WIFI,
                StateKeys.BLUETOOTH,
                StateKeys.BATTERY,
                StateKeys.CHARGING,
                StateKeys.AIRPLANE,
            ).forEach { key -> assertTrue("stato mancante: $key", key in state.values) }
            state.values[StateKeys.BATTERY]?.let { assertTrue(it.toInt() in 0..100) }
            assertTrue("foreground app non rilevata", !state.foregroundApp.isNullOrBlank())

            val rawZen = shell.run(
                listOf("/system/bin/settings", "get", "global", "zen_mode"),
            ).stdoutText.trim().toInt()
            try {
                tools.setDnd(DndMode.TOTAL)
                val observed = withTimeout(5_000) {
                    var value: String?
                    do {
                        value = reader.read(setOf(StateKeys.DND)).values[StateKeys.DND]
                        if (value != "total") delay(100)
                    } while (value != "total")
                    value
                }
                assertEquals("total", observed)
            } finally {
                val restoreMode = when (rawZen) {
                    0 -> "all"
                    1 -> "priority"
                    2 -> "none"
                    3 -> "alarms"
                    else -> error("zen_mode originale non supportato: $rawZen")
                }
                assertTrue(
                    shell.run(
                        listOf("/system/bin/cmd", "notification", "set_dnd", restoreMode),
                    ).successful,
                )
                val restored = withTimeout(5_000) {
                    var value: Int
                    do {
                        value = shell.run(
                            listOf("/system/bin/settings", "get", "global", "zen_mode"),
                        ).stdoutText.trim().toInt()
                        if (value != rawZen) delay(100)
                    } while (value != rawZen)
                    value
                }
                assertEquals(rawZen, restored)
            }
        } finally {
            shell.close()
            scope.cancel()
            gateway.close()
        }
    }
}
