package dev.argus.automation.base

import dev.argus.device.RingerMode
import dev.argus.engine.model.DndMode
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Esecuzione delle azioni BASE con API Android normali (nessuno Shizuku): mapping, grant e
 * validazione sono JVM-puri, la superficie Android è un seam iniettabile (piano P3-3 §1).
 */
class AndroidBaseActionExecutorTest {

    private data class AlarmCall(val hour: Int, val minute: Int, val label: String?, val skipUi: Boolean)
    private data class TimerCall(val seconds: Int, val label: String?, val skipUi: Boolean)

    private class FakeSurface(
        var dndGranted: Boolean = true,
        var launchable: Boolean = true,
        var alarmResolvable: Boolean = true,
        var throwOn: String? = null,
    ) : BaseActionSurface {
        val dndModes = mutableListOf<DndMode>()
        val ringerModes = mutableListOf<RingerMode>()
        val launched = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val alarms = mutableListOf<AlarmCall>()
        val timers = mutableListOf<TimerCall>()

        override fun isDndPolicyGranted(): Boolean = dndGranted
        override fun setInterruptionFilter(mode: DndMode) {
            if (throwOn == "dnd") error("boom")
            dndModes += mode
        }
        override fun setRingerMode(mode: RingerMode) {
            if (throwOn == "ringer") error("boom")
            ringerModes += mode
        }
        override fun launchPackage(pkg: String): Boolean {
            if (throwOn == "launch") error("boom")
            if (!launchable) return false
            launched += pkg
            return true
        }
        override fun openHttpUrl(url: String) {
            if (throwOn == "open") error("boom")
            opened += url
        }
        override fun setAlarm(hour: Int, minute: Int, label: String?, skipUi: Boolean): Boolean {
            if (throwOn == "alarm") error("boom")
            if (!alarmResolvable) return false
            alarms += AlarmCall(hour, minute, label, skipUi)
            return true
        }
        override fun setTimer(seconds: Int, label: String?, skipUi: Boolean): Boolean {
            if (throwOn == "timer") error("boom")
            if (!alarmResolvable) return false
            timers += TimerCall(seconds, label, skipUi)
            return true
        }
    }

    @Test
    fun `set dnd applies the mode when policy is granted`() = runTest {
        val surface = FakeSurface(dndGranted = true)
        val result = AndroidBaseActionExecutor(surface).setDnd(DndMode.PRIORITY)
        assertEquals(ActionResult.Success, result)
        assertEquals(listOf(DndMode.PRIORITY), surface.dndModes)
    }

    @Test
    fun `set dnd fails clean without policy grant and never touches the filter`() = runTest {
        val surface = FakeSurface(dndGranted = false)
        val result = AndroidBaseActionExecutor(surface).setDnd(DndMode.TOTAL)
        assertEquals(ActionResult.Failure("dnd_policy_unavailable"), result)
        assertTrue(surface.dndModes.isEmpty())
    }

    @Test
    fun `ringer normal needs no policy`() = runTest {
        val surface = FakeSurface(dndGranted = false)
        val result = AndroidBaseActionExecutor(surface).setRinger(RingerMode.NORMAL)
        assertEquals(ActionResult.Success, result)
        assertEquals(listOf(RingerMode.NORMAL), surface.ringerModes)
    }

    @Test
    fun `ringer silencing modes require policy grant`() = runTest {
        val denied = FakeSurface(dndGranted = false)
        assertEquals(
            ActionResult.Failure("ringer_policy_unavailable"),
            AndroidBaseActionExecutor(denied).setRinger(RingerMode.SILENT),
        )
        assertTrue(denied.ringerModes.isEmpty())

        val granted = FakeSurface(dndGranted = true)
        assertEquals(
            ActionResult.Success,
            AndroidBaseActionExecutor(granted).setRinger(RingerMode.SILENT),
        )
        assertEquals(listOf(RingerMode.SILENT), granted.ringerModes)
    }

    @Test
    fun `launch app succeeds for a launchable package`() = runTest {
        val surface = FakeSurface(launchable = true)
        val result = AndroidBaseActionExecutor(surface).launchApp("com.example.app")
        assertEquals(ActionResult.Success, result)
        assertEquals(listOf("com.example.app"), surface.launched)
    }

    @Test
    fun `launch app fails clean when the package has no launcher entry`() = runTest {
        val surface = FakeSurface(launchable = false)
        val result = AndroidBaseActionExecutor(surface).launchApp("com.example.app")
        assertEquals(ActionResult.Failure("launch_app_unresolved"), result)
    }

    @Test
    fun `launch app rejects a malformed package before touching Android`() = runTest {
        val surface = FakeSurface()
        val result = AndroidBaseActionExecutor(surface).launchApp("not a package")
        assertEquals(ActionResult.Failure("action_invalid"), result)
        assertTrue(surface.launched.isEmpty())
    }

    @Test
    fun `open url accepts http and https and rejects the rest`() = runTest {
        val surface = FakeSurface()
        val executor = AndroidBaseActionExecutor(surface)
        assertEquals(ActionResult.Success, executor.openUrl("https://example.org/a"))
        assertEquals(ActionResult.Failure("open_url_invalid"), executor.openUrl("javascript:alert(1)"))
        assertEquals(ActionResult.Failure("open_url_invalid"), executor.openUrl("ftp://example.org"))
        assertEquals(listOf("https://example.org/a"), surface.opened)
    }

    @Test
    fun `set alarm forwards the extras to the surface for a valid time`() = runTest {
        val surface = FakeSurface()
        val result = AndroidBaseActionExecutor(surface).setAlarm(7, 30, "Palestra", skipUi = true)
        assertEquals(ActionResult.Success, result)
        assertEquals(listOf(AlarmCall(7, 30, "Palestra", true)), surface.alarms)
    }

    @Test
    fun `set alarm rejects an out-of-range time before touching Android`() = runTest {
        val surface = FakeSurface()
        val executor = AndroidBaseActionExecutor(surface)
        assertEquals(ActionResult.Failure("action_invalid"), executor.setAlarm(24, 0, null, skipUi = true))
        assertEquals(ActionResult.Failure("action_invalid"), executor.setAlarm(-1, 0, null, skipUi = true))
        assertEquals(ActionResult.Failure("action_invalid"), executor.setAlarm(7, 60, null, skipUi = true))
        assertEquals(ActionResult.Failure("action_invalid"), executor.setAlarm(7, -1, null, skipUi = true))
        assertTrue(surface.alarms.isEmpty())
    }

    @Test
    fun `set alarm fails clean when no clock app resolves the intent`() = runTest {
        val surface = FakeSurface(alarmResolvable = false)
        val result = AndroidBaseActionExecutor(surface).setAlarm(8, 0, null, skipUi = true)
        assertEquals(ActionResult.Failure("alarm_app_unresolved"), result)
    }

    @Test
    fun `set timer forwards the length to the surface for a valid duration`() = runTest {
        val surface = FakeSurface()
        val result = AndroidBaseActionExecutor(surface).setTimer(300, "Pasta", skipUi = true)
        assertEquals(ActionResult.Success, result)
        assertEquals(listOf(TimerCall(300, "Pasta", true)), surface.timers)
    }

    @Test
    fun `set timer rejects a non-positive or over-day duration`() = runTest {
        val surface = FakeSurface()
        val executor = AndroidBaseActionExecutor(surface)
        assertEquals(ActionResult.Failure("action_invalid"), executor.setTimer(0, null, skipUi = true))
        assertEquals(ActionResult.Failure("action_invalid"), executor.setTimer(-5, null, skipUi = true))
        assertEquals(ActionResult.Failure("action_invalid"), executor.setTimer(86_401, null, skipUi = true))
        assertTrue(surface.timers.isEmpty())
    }

    @Test
    fun `set timer accepts the inclusive one-day boundary`() = runTest {
        val surface = FakeSurface()
        assertEquals(ActionResult.Success, AndroidBaseActionExecutor(surface).setTimer(86_400, null, skipUi = true))
        assertEquals(listOf(TimerCall(86_400, null, true)), surface.timers)
    }

    @Test
    fun `set timer fails clean when no clock app resolves the intent`() = runTest {
        val surface = FakeSurface(alarmResolvable = false)
        val result = AndroidBaseActionExecutor(surface).setTimer(60, null, skipUi = true)
        assertEquals(ActionResult.Failure("alarm_app_unresolved"), result)
    }

    @Test
    fun `set alarm surface exceptions become typed failures`() = runTest {
        val surface = FakeSurface(throwOn = "alarm")
        val result = AndroidBaseActionExecutor(surface).setAlarm(7, 0, null, skipUi = true)
        assertEquals(ActionResult.Failure("action_failed"), result)
    }

    @Test
    fun `surface exceptions become typed failures`() = runTest {
        val surface = FakeSurface(dndGranted = true, throwOn = "dnd")
        val result = AndroidBaseActionExecutor(surface).setDnd(DndMode.OFF)
        assertEquals(ActionResult.Failure("action_failed"), result)
    }
}
