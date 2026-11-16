package dev.argus.importing

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.argus.MainActivity
import dev.argus.automation.vm.ReceivedImportViewModel
import dev.argus.automation.vm.ReceivedPayload
import dev.argus.ui.model.ReceivedImportCallbacks
import dev.argus.ui.screens.ReceivedImportScreen
import dev.argus.ui.theme.ArgusTheme

/**
 * 0.7.2: riceve una regola da un'altra app: testo dal foglio di condivisione, file di backup da un file
 * manager. Activity separata e dedicata: [MainActivity] di proposito non legge i dati degli Intent.
 *
 * Superficie d'attacco, quindi: legge solo testo o un documento `content://` di un'altra app ([ReceivedIntent]),
 * mostra cosa è arrivato e non fa nulla finché l'utente non conferma QUI. La conferma crea soltanto bozze da
 * approvare con l'import esistente (invariante 1): nessuna regola armata, nessun'altra scrittura. I tocchi che
 * arrivano mentre un'altra finestra la copre vengono scartati.
 */
@AndroidEntryPoint
class ImportRuleActivity : ComponentActivity() {
    private val viewModel: ReceivedImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nessun tocco «attraverso» una finestra sovrapposta: la conferma deve venire dall'utente.
        window.decorView.filterTouchesWhenObscured = true
        viewModel.receive(payloadOf(intent))
        setContent {
            ArgusTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ReceivedImportScreen(
                    state = state,
                    callbacks = object : ReceivedImportCallbacks {
                        override fun onConfirm() = viewModel.onConfirm()
                        override fun onCancel() = finish()
                        override fun onOpenArgus() {
                            // Si apre Argus com'è, senza dati: le bozze aspettano nell'elenco delle automazioni.
                            runCatching {
                                startActivity(
                                    Intent(this@ImportRuleActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                )
                            }
                            finish()
                        }
                    },
                )
            }
        }
    }

    private fun payloadOf(intent: Intent?): ReceivedPayload {
        if (intent == null) return ReceivedPayload.Unsupported
        val stream = runCatching { streamOf(intent) }.getOrNull()
        val data = intent.data
        val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull()
        return when (
            val decision = ReceivedIntent.decide(
                action = intent.action,
                text = text,
                stream = stream?.let { UriParts(it.scheme, it.authority) },
                data = data?.let { UriParts(it.scheme, it.authority) },
                ownPackage = packageName,
            )
        ) {
            is ReceivedDecision.Text -> ReceivedPayload.Text(decision.text)
            is ReceivedDecision.Document -> when (decision.source) {
                DocumentSource.STREAM -> stream?.let(ReceivedPayload::Document) ?: ReceivedPayload.Unsupported
                DocumentSource.DATA -> data?.let(ReceivedPayload::Document) ?: ReceivedPayload.Unsupported
            }
            ReceivedDecision.Unsupported -> ReceivedPayload.Unsupported
        }
    }

    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
