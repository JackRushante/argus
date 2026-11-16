package dev.argus.automation.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.automation.RenderLanguageSource
import dev.argus.data.backup.BackupFailure
import dev.argus.data.backup.DocumentRead
import dev.argus.data.backup.ImportPreview
import dev.argus.data.backup.RulesBackup
import dev.argus.data.backup.RulesBackupFormat
import dev.argus.ui.model.ReceivedImportState
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Ciò che un'altra app ha mandato ad Argus, già filtrato dall'Activity che lo riceve. */
sealed interface ReceivedPayload {
    /** Testo condiviso (`EXTRA_TEXT`). */
    data class Text(val text: String) : ReceivedPayload

    /** Documento `content://` di un'altra app (condiviso o aperto da un file manager). */
    data class Document(val uri: Uri) : ReceivedPayload

    /** Nulla di utilizzabile (azione, tipo o indirizzo non ammessi). */
    data object Unsupported : ReceivedPayload
}

/**
 * «Importa in Argus» (0.7.2): la schermata che riceve una regola da un'altra app. È una superficie d'attacco,
 * quindi fa il minimo:
 *  - legge ciò che è arrivato UNA volta, con il limite dell'import prima di guardare il contenuto;
 *  - ne mostra l'anteprima (quante regole, i nomi, la dimensione) senza scrivere nulla;
 *  - solo su conferma dell'utente importa ESATTAMENTE quei byte con [RulesBackup.importBytes]: bozze `IMPORT`
 *    da approvare, mai una regola armata, mai altro (impostazioni, whitelist, chiavi).
 */
@HiltViewModel
class ReceivedImportViewModel @Inject constructor(
    private val backup: RulesBackup,
    private val languageSource: RenderLanguageSource,
) : ViewModel() {
    /** Per i test: dove leggere e decodificare. */
    internal var dispatcher: CoroutineDispatcher = Dispatchers.Default

    private val mutableState = MutableStateFlow<ReceivedImportState>(ReceivedImportState.Loading)
    val state: StateFlow<ReceivedImportState> = mutableState.asStateFlow()

    private val language: RenderLanguage get() = languageSource.current
    private val received = AtomicBoolean(false)
    private val importing = AtomicBoolean(false)

    /** I byte mostrati in anteprima: la conferma importa questi, non una nuova lettura. */
    @Volatile
    private var bytes: ByteArray? = null

    /** Riceve il contenuto una sola volta (una ricreazione dell'Activity non lo rilegge). */
    fun receive(payload: ReceivedPayload) {
        if (!received.compareAndSet(false, true)) return
        viewModelScope.launch {
            mutableState.value = try {
                withContext(dispatcher) { previewOf(payload) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ReceivedImportState.Rejected(RulesBackupText.failure(BackupFailure.READ_FAILED, language))
            }
        }
    }

    private suspend fun previewOf(payload: ReceivedPayload): ReceivedImportState {
        val text = payload is ReceivedPayload.Text
        val read: ByteArray = when (payload) {
            is ReceivedPayload.Text -> RulesBackupFormat.utf8WithinCap(payload.text)
                ?: return rejected(BackupFailure.FILE_TOO_LARGE, text)
            is ReceivedPayload.Document -> when (val document = backup.read(payload.uri)) {
                is DocumentRead.Failed -> return rejected(document.failure, text)
                is DocumentRead.Bytes -> document.bytes
            }
            ReceivedPayload.Unsupported -> return rejected(BackupFailure.WRONG_FORMAT, text)
        }
        return when (val preview = backup.preview(read)) {
            is ImportPreview.Rejected -> rejected(preview.failure, text)
            is ImportPreview.Ready -> {
                bytes = read
                ReceivedImportState.Ready(ImportPreviewText.of(preview, language))
            }
        }
    }

    private fun rejected(failure: BackupFailure, text: Boolean) = ReceivedImportState.Rejected(
        if (text) ImportPreviewText.textFailure(failure, language) else RulesBackupText.failure(failure, language),
    )

    /** Conferma dell'utente: importa i byte mostrati, una volta sola. */
    fun onConfirm() {
        val current = mutableState.value as? ReceivedImportState.Ready ?: return
        if (!current.preview.canImport) return
        val payload = bytes ?: return
        if (!importing.compareAndSet(false, true)) return
        mutableState.value = current.copy(working = true)
        viewModelScope.launch {
            mutableState.value = try {
                val (message, imported) = ImportPreviewText.outcome(backup.importBytes(payload), language, text = false)
                bytes = null
                ReceivedImportState.Done(message, imported)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ReceivedImportState.Rejected(RulesBackupText.failure(BackupFailure.SAVE_FAILED, language))
            }
        }
    }
}
