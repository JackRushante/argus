package dev.argus.automation.base

import android.view.KeyEvent
import dev.argus.engine.model.MediaCommand
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.CancellationException

/** Esito della lettura ad alta voce, senza dettagli del motore e senza il testo letto. */
enum class SpeechOutcome {
    /** Il parlato è finito. */
    DONE,

    /** Nessun motore di sintesi vocale installato sul telefono. */
    NO_ENGINE,

    /** Il motore non si è avviato o ha segnalato un errore. */
    FAILED,

    /** Il parlato non è finito entro il tempo concesso. */
    TIMEOUT,
}

/** Sintesi vocale: l'adapter reale è [AndroidSpeechEngine], i test usano un fake. */
interface SpeechEngine {
    /**
     * Legge [text] e aspetta la fine del parlato, al massimo [timeoutMs]. Non solleva eccezioni per gli
     * errori del motore: li riporta in [SpeechOutcome].
     */
    suspend fun speak(text: String, timeoutMs: Long): SpeechOutcome
}

/** Tasti multimediali: l'adapter reale è [AndroidMediaKeySurface] (`AudioManager.dispatchMediaKeyEvent`). */
interface MediaKeySurface {
    fun dispatch(keyCode: Int, down: Boolean)
}

/**
 * `Settings.System`: l'adapter reale è [AndroidSystemSettingsSurface]. Scrivere richiede il permesso
 * speciale «Modifica impostazioni di sistema» (`WRITE_SETTINGS`), che l'utente concede dalle
 * impostazioni di sistema e che [canWrite] riflette.
 */
interface SystemSettingsSurface {
    fun canWrite(): Boolean

    /** `Settings.System.putInt`; false se il sistema ha rifiutato la scrittura. */
    fun putInt(name: String, value: Int): Boolean
}

/**
 * Azioni 0.4.1 senza Shizuku e senza una schermata: lettura ad alta voce, tasti multimediali,
 * luminosità e rotazione. Mapping e validazione sono JVM-puri; le API Android stanno dietro i tre
 * seam qui sopra. Ogni fallimento ha un codice tipizzato, mai un crash.
 */
class AndroidSystemActionExecutor(
    private val speech: SpeechEngine,
    private val media: MediaKeySurface,
    private val settings: SystemSettingsSurface,
) {
    /** Legge [text] ad alta voce. Testo vuoto o oltre il limite del motore: `action_invalid`. */
    suspend fun speak(text: String): ActionResult = guarded {
        if (text.isBlank() || text.length > MAX_SPEECH_CHARS) return ActionResult.Failure("action_invalid")
        when (speech.speak(text, SPEECH_TIMEOUT_MS)) {
            SpeechOutcome.DONE -> ActionResult.Success
            SpeechOutcome.NO_ENGINE -> ActionResult.Failure("tts_unavailable")
            SpeechOutcome.FAILED -> ActionResult.Failure("tts_failed")
            SpeechOutcome.TIMEOUT -> ActionResult.Failure("tts_timeout")
        }
    }

    /** Un tasto multimediale: pressione e rilascio, come farebbe un tasto fisico. */
    suspend fun mediaControl(command: MediaCommand): ActionResult = guarded {
        val keyCode = keyCodeOf(command)
        media.dispatch(keyCode, down = true)
        media.dispatch(keyCode, down = false)
        ActionResult.Success
    }

    /**
     * Luminosità. Con [level] (0..100, mappato su 0..255) passa prima alla modalità manuale, altrimenti
     * il sistema la riporta al valore automatico; con [auto] imposta solo la modalità. ESATTAMENTE uno
     * dei due, come impone il validatore.
     */
    suspend fun setBrightness(level: Int?, auto: Boolean?): ActionResult = guarded {
        if ((level == null) == (auto == null)) return ActionResult.Failure("action_invalid")
        if (level != null && level !in 0..100) return ActionResult.Failure("action_invalid")
        if (!settings.canWrite()) return ActionResult.Failure(WRITE_SETTINGS_UNAVAILABLE)
        val written = if (level != null) {
            settings.putInt(BRIGHTNESS_MODE, BRIGHTNESS_MODE_MANUAL) &&
                settings.putInt(BRIGHTNESS, brightnessValue(level))
        } else {
            settings.putInt(
                BRIGHTNESS_MODE,
                if (auto == true) BRIGHTNESS_MODE_AUTOMATIC else BRIGHTNESS_MODE_MANUAL,
            )
        }
        if (written) ActionResult.Success else ActionResult.Failure("settings_write_failed")
    }

    /** Rotazione automatica dello schermo on/off. */
    suspend fun setRotation(auto: Boolean): ActionResult = guarded {
        if (!settings.canWrite()) return ActionResult.Failure(WRITE_SETTINGS_UNAVAILABLE)
        if (settings.putInt(ACCELEROMETER_ROTATION, if (auto) 1 else 0)) ActionResult.Success
        else ActionResult.Failure("settings_write_failed")
    }

    private inline fun guarded(block: () -> ActionResult): ActionResult = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ActionResult.Failure("action_failed")
    }

    companion object {
        /** Permesso «Modifica impostazioni di sistema» non concesso. */
        const val WRITE_SETTINGS_UNAVAILABLE = "write_settings_unavailable"

        /** Lunghezza massima accettata dai motori Android (`TextToSpeech.getMaxSpeechInputLength()`). */
        const val MAX_SPEECH_CHARS = 4_000

        /** Attesa massima della fine del parlato. */
        const val SPEECH_TIMEOUT_MS = 60_000L

        const val BRIGHTNESS = "screen_brightness"
        const val BRIGHTNESS_MODE = "screen_brightness_mode"
        const val ACCELEROMETER_ROTATION = "accelerometer_rotation"
        const val BRIGHTNESS_MODE_MANUAL = 0
        const val BRIGHTNESS_MODE_AUTOMATIC = 1

        /** 0..100 → 0..255, arrotondato al più vicino. */
        fun brightnessValue(level: Int): Int = Math.round(level * 255 / 100.0).toInt()

        fun keyCodeOf(command: MediaCommand): Int = when (command) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
    }
}
