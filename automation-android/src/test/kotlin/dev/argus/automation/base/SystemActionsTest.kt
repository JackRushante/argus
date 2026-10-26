package dev.argus.automation.base

import android.view.KeyEvent
import dev.argus.engine.model.MediaCommand
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Lettura ad alta voce, tasti multimediali, luminosità e rotazione con fake al posto di Android. */
class SystemActionsTest {

    private class FakeSpeech(
        var outcome: SpeechOutcome = SpeechOutcome.DONE,
        var throwable: Throwable? = null,
    ) : SpeechEngine {
        val spoken = mutableListOf<Pair<String, Long>>()
        override suspend fun speak(text: String, timeoutMs: Long): SpeechOutcome {
            spoken += text to timeoutMs
            throwable?.let { throw it }
            return outcome
        }
    }

    private class FakeMedia(var failing: Boolean = false) : MediaKeySurface {
        val events = mutableListOf<Pair<Int, Boolean>>()
        override fun dispatch(keyCode: Int, down: Boolean) {
            if (failing) error("boom")
            events += keyCode to down
        }
    }

    private class FakeSettings(
        var writable: Boolean = true,
        var accepts: Boolean = true,
        var throwing: Boolean = false,
    ) : SystemSettingsSurface {
        val writes = mutableListOf<Pair<String, Int>>()
        override fun canWrite(): Boolean = writable
        override fun putInt(name: String, value: Int): Boolean {
            if (throwing) throw SecurityException("no")
            writes += name to value
            return accepts
        }
    }

    private fun executor(
        speech: FakeSpeech = FakeSpeech(),
        media: FakeMedia = FakeMedia(),
        settings: FakeSettings = FakeSettings(),
    ) = AndroidSystemActionExecutor(speech, media, settings)

    // --- speak ---------------------------------------------------------------------------------

    @Test
    fun `speak reads the text and waits at most sixty seconds`() = runTest {
        val speech = FakeSpeech()

        assertEquals(ActionResult.Success, executor(speech = speech).speak("Ciao mondo"))

        assertEquals(listOf("Ciao mondo" to 60_000L), speech.spoken)
    }

    @Test
    fun `speak fails with a clear reason when there is no text to speech engine`() = runTest {
        assertEquals(
            ActionResult.Failure("tts_unavailable"),
            executor(speech = FakeSpeech(SpeechOutcome.NO_ENGINE)).speak("x"),
        )
    }

    @Test
    fun `speak reports an engine error and a timeout with their own codes`() = runTest {
        assertEquals(
            ActionResult.Failure("tts_failed"),
            executor(speech = FakeSpeech(SpeechOutcome.FAILED)).speak("x"),
        )
        assertEquals(
            ActionResult.Failure("tts_timeout"),
            executor(speech = FakeSpeech(SpeechOutcome.TIMEOUT)).speak("x"),
        )
    }

    @Test
    fun `speak refuses an empty or oversized text without calling the engine`() = runTest {
        val speech = FakeSpeech()
        val exec = executor(speech = speech)

        assertEquals(ActionResult.Failure("action_invalid"), exec.speak(""))
        assertEquals(ActionResult.Failure("action_invalid"), exec.speak("   "))
        assertEquals(ActionResult.Failure("action_invalid"), exec.speak("a".repeat(4_001)))
        assertTrue(speech.spoken.isEmpty())
        assertEquals(ActionResult.Success, exec.speak("a".repeat(4_000)))
    }

    @Test
    fun `speak turns an unexpected engine exception into a typed failure but lets cancellation through`() = runTest {
        assertEquals(
            ActionResult.Failure("action_failed"),
            executor(speech = FakeSpeech(throwable = IllegalStateException("boom"))).speak("x"),
        )
        assertFailsWith<CancellationException> {
            executor(speech = FakeSpeech(throwable = CancellationException("stop"))).speak("x")
        }
    }

    // --- media_control -------------------------------------------------------------------------

    @Test
    fun `each media command sends key down then key up`() = runTest {
        val expected = mapOf(
            MediaCommand.PLAY to KeyEvent.KEYCODE_MEDIA_PLAY,
            MediaCommand.PAUSE to KeyEvent.KEYCODE_MEDIA_PAUSE,
            MediaCommand.PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            MediaCommand.NEXT to KeyEvent.KEYCODE_MEDIA_NEXT,
            MediaCommand.PREVIOUS to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        MediaCommand.entries.forEach { command ->
            val media = FakeMedia()
            assertEquals(ActionResult.Success, executor(media = media).mediaControl(command), command.name)
            val key = expected.getValue(command)
            assertEquals(listOf(key to true, key to false), media.events, command.name)
        }
        assertEquals(MediaCommand.entries.toSet(), expected.keys)
    }

    @Test
    fun `a media key failure is a typed failure`() = runTest {
        assertEquals(
            ActionResult.Failure("action_failed"),
            executor(media = FakeMedia(failing = true)).mediaControl(MediaCommand.NEXT),
        )
    }

    // --- set_brightness ------------------------------------------------------------------------

    @Test
    fun `a brightness level goes to manual mode and maps zero to one hundred onto zero to 255`() = runTest {
        val cases = mapOf(0 to 0, 1 to 3, 25 to 64, 40 to 102, 50 to 128, 100 to 255)
        cases.forEach { (level, value) ->
            val settings = FakeSettings()
            assertEquals(ActionResult.Success, executor(settings = settings).setBrightness(level, null), "$level")
            assertEquals(
                listOf("screen_brightness_mode" to 0, "screen_brightness" to value),
                settings.writes,
                "$level",
            )
        }
    }

    @Test
    fun `automatic brightness only flips the mode`() = runTest {
        val on = FakeSettings()
        assertEquals(ActionResult.Success, executor(settings = on).setBrightness(null, true))
        assertEquals(listOf("screen_brightness_mode" to 1), on.writes)

        val off = FakeSettings()
        assertEquals(ActionResult.Success, executor(settings = off).setBrightness(null, false))
        assertEquals(listOf("screen_brightness_mode" to 0), off.writes)
    }

    @Test
    fun `brightness needs the special permission and never writes without it`() = runTest {
        val settings = FakeSettings(writable = false)
        val exec = executor(settings = settings)

        assertEquals(ActionResult.Failure("write_settings_unavailable"), exec.setBrightness(50, null))
        assertEquals(ActionResult.Failure("write_settings_unavailable"), exec.setBrightness(null, true))
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `brightness refuses a bad combination or an out of range level`() = runTest {
        val settings = FakeSettings()
        val exec = executor(settings = settings)

        assertEquals(ActionResult.Failure("action_invalid"), exec.setBrightness(null, null))
        assertEquals(ActionResult.Failure("action_invalid"), exec.setBrightness(10, true))
        assertEquals(ActionResult.Failure("action_invalid"), exec.setBrightness(-1, null))
        assertEquals(ActionResult.Failure("action_invalid"), exec.setBrightness(101, null))
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `a rejected or throwing settings write is a typed failure`() = runTest {
        assertEquals(
            ActionResult.Failure("settings_write_failed"),
            executor(settings = FakeSettings(accepts = false)).setBrightness(10, null),
        )
        assertEquals(
            ActionResult.Failure("action_failed"),
            executor(settings = FakeSettings(throwing = true)).setBrightness(10, null),
        )
    }

    // --- set_rotation --------------------------------------------------------------------------

    @Test
    fun `rotation writes the accelerometer flag`() = runTest {
        val on = FakeSettings()
        assertEquals(ActionResult.Success, executor(settings = on).setRotation(true))
        assertEquals(listOf("accelerometer_rotation" to 1), on.writes)

        val off = FakeSettings()
        assertEquals(ActionResult.Success, executor(settings = off).setRotation(false))
        assertEquals(listOf("accelerometer_rotation" to 0), off.writes)
    }

    @Test
    fun `rotation needs the special permission`() = runTest {
        val settings = FakeSettings(writable = false)
        assertEquals(ActionResult.Failure("write_settings_unavailable"), executor(settings = settings).setRotation(true))
        assertTrue(settings.writes.isEmpty())
        assertEquals(
            ActionResult.Failure("settings_write_failed"),
            executor(settings = FakeSettings(accepts = false)).setRotation(true),
        )
    }

    @Test
    fun `the brightness mapping is rounded to the nearest step`() {
        assertEquals(0, AndroidSystemActionExecutor.brightnessValue(0))
        assertEquals(255, AndroidSystemActionExecutor.brightnessValue(100))
        assertEquals(128, AndroidSystemActionExecutor.brightnessValue(50))
        (0..100).fold(-1) { previous, level ->
            val value = AndroidSystemActionExecutor.brightnessValue(level)
            assertTrue(value in 0..255 && value >= previous, "livello $level")
            value
        }
    }
}
