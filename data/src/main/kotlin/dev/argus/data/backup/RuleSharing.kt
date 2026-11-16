package dev.argus.data.backup

import android.net.Uri
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.toDraft
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant

/**
 * Il testo di una regola condivisa (0.7.2): il file di backup di sempre ([RulesBackupFormat]) con UNA
 * regola, così lo legge ogni percorso di import esistente. [text] è indentato come il file esportato;
 * [compactText] è lo stesso documento senza spazi, per il codice QR.
 */
data class SharedRuleText(val text: String, val compactText: String)

/** Le due versioni di una regola da condividere: senza dati personali ([stripped]) e integrale ([full]). */
data class SharedRuleTexts(val stripped: SharedRuleText, val full: SharedRuleText)

object RuleShareCodec {
    /**
     * Il testo di [automation] da condividere. null se la regola non è esportabile (bozza, regola illeggibile)
     * o se il testo supererebbe il limite dell'import, perché chi lo riceve non potrebbe leggerlo.
     */
    fun encode(
        automation: Automation,
        stripPersonalData: Boolean,
        exportedAt: String,
        appVersion: String,
    ): SharedRuleText? {
        if (!automation.isBackupExportable()) return null
        val draft = automation.toDraft().let { if (stripPersonalData) PersonalDataStripper.strip(it) else it }
        val rule = BackupRule(
            enabled = automation.status == AutomationStatus.ARMED && automation.enabled,
            draft = draft,
        )
        val text = RulesBackupCodec.encode(listOf(rule), exportedAt, appVersion)
        if (text.toByteArray(Charsets.UTF_8).size > RulesBackupFormat.MAX_FILE_BYTES) return null
        val compact = RulesBackupCodec.encode(listOf(rule), exportedAt, appVersion, pretty = false)
        return SharedRuleText(text = text, compactText = compact)
    }
}

/**
 * Condivisione di una regola dal Dettaglio (0.7.2): prepara i due testi e salva il file scelto dall'utente
 * con il Storage Access Framework. Non legge né scrive nient'altro.
 */
class RuleSharing(
    private val documents: BackupDocumentIo,
    private val appVersion: () -> String?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun texts(automation: Automation): SharedRuleTexts? {
        val exportedAt = Instant.ofEpochMilli(nowMillis()).toString()
        val version = appVersion() ?: "unknown"
        val stripped = RuleShareCodec.encode(automation, stripPersonalData = true, exportedAt, version) ?: return null
        val full = RuleShareCodec.encode(automation, stripPersonalData = false, exportedAt, version) ?: return null
        return SharedRuleTexts(stripped = stripped, full = full)
    }

    /** Scrive [text] nel documento scelto. false se non riesce (il chiamante lo dice all'utente). */
    suspend fun save(destination: Uri, text: String): Boolean = try {
        documents.write(destination, text.toByteArray(Charsets.UTF_8))
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        // SecurityException / IllegalArgumentException di un provider di documenti.
        false
    }
}
