package dev.argus.nav

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.argus.ui.presentation.ChatDictation

/**
 * Dettatura nella chat (0.7.2) con il riconoscitore vocale di sistema
 * ([RecognizerIntent.ACTION_RECOGNIZE_SPEECH]): registra l'activity del riconoscitore, non Argus, quindi
 * nessun permesso microfono. La visibilità del riconoscitore (Android 11+) viene dalla `<queries>` del
 * manifest. Il testo torna con [onRecognised] e non viene mai inviato da solo.
 */
internal class SpeechDictation(
    /** false = nessun riconoscitore installato: il microfono non va mostrato. */
    val available: Boolean,
    private val start: () -> Unit,
) {
    fun launch() {
        if (available) start()
    }
}

/** Intent «parla liberamente» nella lingua in cui l'app è mostrata (tag BCP 47, es. `it-IT`). */
internal fun speechRecognitionIntent(languageTag: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

internal fun speechRecognizerInstalled(packageManager: PackageManager): Boolean {
    val probe = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return matches.isNotEmpty()
}

@Composable
internal fun rememberSpeechDictation(onRecognised: (String) -> Unit, onUnavailable: () -> Unit): SpeechDictation {
    val context = LocalContext.current
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val currentOnRecognised = rememberUpdatedState(onRecognised)
    val currentOnUnavailable = rememberUpdatedState(onUnavailable)
    val available = remember(context) { runCatching { speechRecognizerInstalled(context.packageManager) }.getOrDefault(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        ChatDictation.bestResult(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
            ?.let { currentOnRecognised.value(it) }
    }
    return remember(available, languageTag, launcher) {
        SpeechDictation(available) {
            // Il riconoscitore può sparire tra il controllo e il tocco (disinstallato, disattivato).
            runCatching { launcher.launch(speechRecognitionIntent(languageTag)) }
                .onFailure { currentOnUnavailable.value() }
        }
    }
}
