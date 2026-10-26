package dev.argus.data.backup

import android.net.Uri
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.toDraft
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID

/** Inserimento di più bozze in una sola transazione (una sola notifica alle liste che le osservano). */
fun interface BulkDraftWriter {
    /** Una voce per ogni [NewDraft], nello stesso ordine. Tutto dentro una transazione. */
    suspend fun createAll(newDrafts: List<NewDraft>): List<DraftWriteResult>
}

/** Esporta e importa le regole. Il confine usato da Impostazioni. */
interface RulesBackup {
    suspend fun export(destination: Uri, stripPersonalData: Boolean): ExportOutcome
    suspend fun import(source: Uri): ImportOutcome
}

/**
 * Backup delle regole su file.
 *
 * Export: legge dallo store le regole approvate (armate o disattivate) e quelle «da rivedere» e le
 * scrive come bozze tipate ([toDraft], l'unica copia da `Automation` a `AutomationDraft`).
 *
 * Import: ogni regola valida diventa una bozza in attesa di approvazione, passando dal repository delle
 * bozze come quelle della chat (`createdBy = IMPORT`). Il servizio non arma, non tocca le regole
 * esistenti e non scrive nella whitelist dei contatti: quel che la bozza cita lo giudica il
 * `DraftValidator` quando l'utente apre l'approvazione.
 */
class RulesBackupService(
    private val automations: AutomationStore,
    private val drafts: BulkDraftWriter,
    private val documents: BackupDocumentIo,
    private val appVersion: () -> String?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : RulesBackup {

    override suspend fun export(destination: Uri, stripPersonalData: Boolean): ExportOutcome {
        val all = try {
            automations.all()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ExportOutcome.Failed(BackupFailure.WRITE_FAILED)
        }
        val exportable = all.filter { it.isBackupExportable() }
        if (exportable.isEmpty()) return ExportOutcome.Failed(BackupFailure.NOTHING_TO_EXPORT)
        if (exportable.size > RulesBackupFormat.MAX_RULES) {
            return ExportOutcome.Failed(BackupFailure.EXPORT_TOO_LARGE)
        }
        // «Da rivedere» ma illeggibili (segnaposto senza azioni) non hanno nulla da esportare.
        val skipped = all.count { it.status in CANDIDATE_STATUSES } - exportable.size

        val rules = exportable.map { automation ->
            val draft = automation.toDraft()
            BackupRule(
                enabled = automation.status == AutomationStatus.ARMED && automation.enabled,
                draft = if (stripPersonalData) PersonalDataStripper.strip(draft) else draft,
            )
        }
        val bytes = RulesBackupCodec.encode(
            rules = rules,
            exportedAt = Instant.ofEpochMilli(nowMillis()).toString(),
            appVersion = appVersion() ?: "unknown",
        ).toByteArray(Charsets.UTF_8)
        if (bytes.size > RulesBackupFormat.MAX_FILE_BYTES) {
            return ExportOutcome.Failed(BackupFailure.EXPORT_TOO_LARGE)
        }
        return try {
            documents.write(destination, bytes)
            ExportOutcome.Done(exported = rules.size, skipped = skipped)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            ExportOutcome.Failed(BackupFailure.WRITE_FAILED)
        } catch (_: RuntimeException) {
            // SecurityException / IllegalArgumentException di un provider di documenti.
            ExportOutcome.Failed(BackupFailure.WRITE_FAILED)
        }
    }

    override suspend fun import(source: Uri): ImportOutcome {
        val read = try {
            documents.read(source, RulesBackupFormat.MAX_FILE_BYTES)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return ImportOutcome.Failed(BackupFailure.READ_FAILED)
        } catch (_: RuntimeException) {
            return ImportOutcome.Failed(BackupFailure.READ_FAILED)
        }
        val bytes = read ?: return ImportOutcome.Failed(BackupFailure.FILE_TOO_LARGE)

        val text = decodeUtf8(bytes) ?: return ImportOutcome.Failed(BackupFailure.NOT_JSON)
        val entries = when (val decoded = RulesBackupCodec.decode(text)) {
            is DecodedBackup.Rejected -> return ImportOutcome.Failed(decoded.failure)
            is DecodedBackup.Entries -> decoded.entries
        }

        val discarded = linkedMapOf<RuleDiscardReason, Int>()
        fun discard(reason: RuleDiscardReason) {
            discarded[reason] = (discarded[reason] ?: 0) + 1
        }
        val accepted = ArrayList<BackupRule>(entries.size)
        for (entry in entries) {
            when (entry) {
                is DecodedEntry.Rule -> accepted += entry.rule
                is DecodedEntry.Discarded -> discard(entry.reason)
            }
        }

        val at = nowMillis()
        val newDrafts = accepted.map { rule ->
            NewDraft(
                id = DraftId(newId()),
                automationId = AutomationId(newId()),
                draft = rule.draft,
                createdBy = CreatedBy.IMPORT,
                priority = 0,
                atMillis = at,
                expectedAutomationFingerprint = null,
            )
        }
        val results: List<DraftWriteResult> = if (newDrafts.isEmpty()) {
            emptyList()
        } else {
            try {
                drafts.createAll(newDrafts)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return ImportOutcome.Failed(BackupFailure.SAVE_FAILED)
            }
        }
        var imported = 0
        for (result in results) {
            if (result is DraftWriteResult.Saved) imported++ else discard(RuleDiscardReason.NOT_SAVED)
        }
        return ImportOutcome.Done(ImportSummary(imported = imported, discarded = discarded))
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private companion object {
        val CANDIDATE_STATUSES = setOf(
            AutomationStatus.ARMED,
            AutomationStatus.DISABLED,
            AutomationStatus.NEEDS_REVIEW,
        )
    }
}
