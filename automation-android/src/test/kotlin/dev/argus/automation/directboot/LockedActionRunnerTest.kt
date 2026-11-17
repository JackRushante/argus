package dev.argus.automation.directboot

import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.engine.directboot.DirectBootRunner
import dev.argus.engine.model.Action
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The minimal executor of the locked path runs the allow-listed switches and nothing else. */
class LockedActionRunnerTest {
    private val surface = RecordingSurface()
    private val runner = LockedActionRunner(AndroidBaseActionExecutor(surface))

    @Test
    fun `the allow-listed switches go through the normal Android managers`() = runTest {
        listOf(
            Action.SetDnd(DndMode.PRIORITY),
            Action.SetRinger("vibrate"),
            Action.SetVolume(VolumeStream.ALARM, 50),
            Action.SetFlashlight(false),
            Action.Vibrate(300),
        ).forEach { assertEquals(ActionResult.Success, runner.run(it), "$it") }
        assertEquals(
            listOf("dnd:PRIORITY", "ringer:VIBRATE", "volume:ALARM:5", "torch:false", "vibrate:300"),
            surface.calls,
        )
    }

    @Test
    fun `anything else is refused without touching the device`() = runTest {
        listOf(
            Action.RunShell("reboot"),
            Action.ShowNotification("title", "text"),
            Action.LaunchApp("com.example.app"),
            Action.OpenUrl("https://example.com"),
            Action.OpenSettingsScreen(SettingsScreen.WIFI),
            Action.SetAlarm(7, 0),
            Action.SetTimer(60),
            Action.SetWifi(false),
            Action.SetBrightness(level = 10),
            Action.HttpRequest(HttpMethod.GET, "https://example.com"),
            Action.SendSms("+390000000", "text"),
            Action.Speak("text"),
            Action.SetRinger("\${mode}"),
            Action.Wait(1_000),
        ).forEach { action ->
            assertEquals(ActionResult.Failure(DirectBootRunner.NOT_ALLOWED), runner.run(action), "$action")
        }
        assertEquals(emptyList(), surface.calls)
    }
}
