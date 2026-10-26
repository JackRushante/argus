package dev.argus.automation.base

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/**
 * `TextToSpeech` reale. Un'istanza per lettura, chiusa a fine parlato: nessun servizio di sintesi
 * resta collegato fra una regola e l'altra. Le letture sono serializzate da un [Mutex] e usano
 * `QUEUE_ADD`, quindi due regole che parlano insieme si mettono in coda invece di sovrapporsi.
 *
 * [languageTag] è la lingua dell'app (inglese o italiano). Se il motore non ha i dati di quella
 * lingua si legge con la sua lingua predefinita: meglio un parlato imperfetto di nessun parlato.
 */
class AndroidSpeechEngine(
    context: Context,
    private val languageTag: () -> String,
) : SpeechEngine {
    private val appContext = context.applicationContext
    private val queue = Mutex()

    override suspend fun speak(text: String, timeoutMs: Long): SpeechOutcome = queue.withLock {
        if (!hasEngine()) return@withLock SpeechOutcome.NO_ENGINE
        val ready = CompletableDeferred<Int>()
        var tts: TextToSpeech? = null
        try {
            val engine = TextToSpeech(appContext) { status -> ready.complete(status) }
            tts = engine
            val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() }
                ?: return@withLock SpeechOutcome.FAILED
            if (status != TextToSpeech.SUCCESS) return@withLock SpeechOutcome.FAILED

            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Lingua dell'app; se mancano i dati si tiene la lingua predefinita del motore.
            runCatching { engine.setLanguage(Locale.forLanguageTag(languageTag())) }

            val finished = CompletableDeferred<SpeechOutcome>()
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        finished.complete(SpeechOutcome.DONE)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finished.complete(SpeechOutcome.FAILED)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        finished.complete(SpeechOutcome.FAILED)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        finished.complete(if (interrupted) SpeechOutcome.FAILED else SpeechOutcome.DONE)
                    }
                },
            )
            val spoken = text.take(TextToSpeech.getMaxSpeechInputLength())
            val queued = engine.speak(spoken, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
            if (queued != TextToSpeech.SUCCESS) return@withLock SpeechOutcome.FAILED

            val outcome = withTimeoutOrNull(timeoutMs) { finished.await() }
            if (outcome == null) {
                engine.stop()
                SpeechOutcome.TIMEOUT
            } else {
                outcome
            }
        } finally {
            tts?.shutdown()
        }
    }

    /** C'è almeno un motore di sintesi vocale installato (serve `<queries>` per il TTS nel manifest). */
    private fun hasEngine(): Boolean = runCatching {
        appContext.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .isNotEmpty()
    }.getOrDefault(true)

    private companion object {
        const val INIT_TIMEOUT_MS = 10_000L
    }
}

/** `AudioManager.dispatchMediaKeyEvent`: lo stesso percorso di un tasto multimediale di una cuffia. */
class AndroidMediaKeySurface(context: Context) : MediaKeySurface {
    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    override fun dispatch(keyCode: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, action, keyCode, 0))
    }
}

/** `Settings.System` reale, dietro il permesso speciale `WRITE_SETTINGS`. */
class AndroidSystemSettingsSurface(context: Context) : SystemSettingsSurface {
    private val appContext = context.applicationContext

    override fun canWrite(): Boolean = Settings.System.canWrite(appContext)

    override fun putInt(name: String, value: Int): Boolean =
        Settings.System.putInt(appContext.contentResolver, name, value)
}
