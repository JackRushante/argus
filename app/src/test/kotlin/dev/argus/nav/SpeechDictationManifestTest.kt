package dev.argus.nav

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dettatura in chat (0.7.2): il riconoscitore di sistema deve essere visibile (Android 11+) e Argus non
 * chiede il microfono, perché registra l'activity del riconoscitore.
 */
class SpeechDictationManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `the speech recognizer is declared in queries`() {
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue("android.speech.action.RECOGNIZE_SPEECH" in queries)
    }

    @Test
    fun `argus does not ask for the microphone`() {
        assertFalse("android.permission.RECORD_AUDIO" in manifest)
    }
}
