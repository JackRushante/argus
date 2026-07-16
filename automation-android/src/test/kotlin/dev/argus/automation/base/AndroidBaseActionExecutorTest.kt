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

    private class FakeSurface(
        var dndGranted: Boolean = true,
        var launchable: Boolean = true,
        var throwOn: String? = null,
    ) : BaseActionSurface {
        val dndModes = mutableListOf<DndMode>()
        val ringerModes = mutableListOf<RingerMode>()
        val launched = mutableListOf<String>()
        val opened = mutableListOf<String>()

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
    fun `surface exceptions become typed failures`() = runTest {
        val surface = FakeSurface(dndGranted = true, throwOn = "dnd")
        val result = AndroidBaseActionExecutor(surface).setDnd(DndMode.OFF)
        assertEquals(ActionResult.Failure("action_failed"), result)
    }
}
