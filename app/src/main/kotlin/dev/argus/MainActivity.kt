package dev.argus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import dev.argus.nav.ArgusNavHost
import dev.argus.nfc.nfcEntryPoint
import dev.argus.ui.components.NfcTagPrompt
import dev.argus.ui.theme.ArgusTheme

/** Host Compose edge-to-edge. Il runtime reale viene inizializzato da [ArgusApplication]. */

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** 0.8: legge il tag NFC di una regola che si sta armando, solo mentre questa schermata è in primo piano. */
    private val nfcTagReader by lazy { nfcEntryPoint().nfcTagReader() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArgusTheme {
                ArgusNavHost()
                val readingTag by nfcTagReader.prompting.collectAsState()
                if (readingTag) NfcTagPrompt(onCancel = nfcTagReader::cancel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcTagReader.attach(this)
    }

    override fun onPause() {
        nfcTagReader.detach(this)
        super.onPause()
    }
}
