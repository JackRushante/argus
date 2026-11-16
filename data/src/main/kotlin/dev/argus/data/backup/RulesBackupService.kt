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

    /**
     * 0.7.2: legge un documento con il limite dell'import (oltre: [BackupFailure.FILE_TOO_LARGE], senza
     * leggerlo tutto), per mostrarne l'anteprima prima di importare.
     */
    suspend fun read(source: Uri): DocumentRead

    /**
     * 0.7.2: importa byte già in memoria (testo incollato o ricevuto, file letto, modello): stesso limite,
     * stessa decodifica stretta e stesse bozze `IMPORT` di [import]. Non arma nulla.
     */
    suspend fun importBytes(bytes: ByteArray): ImportOutcome

    /** 0.7.2: cosa importerebbe [importBytes] con gli stessi byte, senza scrivere nulla. */
    fun preview(bytes: ByteArray): ImportPreview
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

    override suspend fun import(source: Uri): ImportOutcome = when (val read = read(source)) {
        is DocumentRead.Failed -> ImportOutcome.Failed(read.failure)
        is DocumentRead.Bytes -> importBytes(read.bytes)
    }

    override suspend fun read(source: Uri): DocumentRead {
        val read = try {
            documents.read(source, RulesBackupFormat.MAX_FILE_BYTES)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return DocumentRead.Failed(BackupFailure.READ_FAILED)
        } catch (_: RuntimeException) {
            return DocumentRead.Failed(BackupFailure.READ_FAILED)
        }
        return read?.let(DocumentRead::Bytes) ?: DocumentRead.Failed(BackupFailure.FILE_TOO_LARGE)
    }

    override fun preview(bytes: ByteArray): ImportPreview {
        val parsed = when (val outcome = parse(bytes)) {
            is Parsed.Failed -> return ImportPreview.Rejected(outcome.failure)
            is Parsed.Rules -> outcome
        }
        return ImportPreview.Ready(
            ruleNames = parsed.accepted.map { displayName(it.draft.name) },
            discarded = parsed.discarded,
            sizeBytes = bytes.size,
        )
    }

    override suspend fun importBytes(bytes: ByteArray): ImportOutcome {
        val parsed = when (val outcome = parse(bytes)) {
            is Parsed.Failed -> return ImportOutcome.Failed(outcome.failure)
            is Parsed.Rules -> outcome
        }
        val accepted = parsed.accepted
        val discarded = LinkedHashMap(parsed.discarded)
        fun discard(reason: RuleDiscardReason) {
            discarded[reason] = (discarded[reason] ?: 0) + 1
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
        val draftIds = ArrayList<String>(results.size)
        for (result in results) {
            if (result is DraftWriteResult.Saved) draftIds += result.snapshot.id.value else discard(RuleDiscardReason.NOT_SAVED)
        }
        return ImportOutcome.Done(ImportSummary(imported = draftIds.size, discarded = discarded), draftIds)
    }

    private sealed interface Parsed {
        data class Failed(val failure: BackupFailure) : Parsed
        data class Rules(val accepted: List<BackupRule>, val discarded: Map<RuleDiscardReason, Int>) : Parsed
    }

    /** Limite, UTF-8 stretto e decodifica stretta: l'unica lettura del contenuto, per l'import e l'anteprima. */
    private fun parse(bytes: ByteArray): Parsed {
        // Il limite si controlla prima di guardare il contenuto, qualunque sia la provenienza dei byte.
        if (bytes.size > RulesBackupFormat.MAX_FILE_BYTES) return Parsed.Failed(BackupFailure.FILE_TOO_LARGE)
        val text = decodeUtf8(bytes) ?: return Parsed.Failed(BackupFailure.NOT_JSON)
        val entries = when (val decoded = RulesBackupCodec.decode(text)) {
            is DecodedBackup.Rejected -> return Parsed.Failed(decoded.failure)
            is DecodedBackup.Entries -> decoded.entries
        }
        val discarded = linkedMapOf<RuleDiscardReason, Int>()
        val accepted = ArrayList<BackupRule>(entries.size)
        for (entry in entries) {
            when (entry) {
                is DecodedEntry.Rule -> accepted += entry.rule
                is DecodedEntry.Discarded -> discarded[entry.reason] = (discarded[entry.reason] ?: 0) + 1
            }
        }
        return Parsed.Rules(accepted, discarded)
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

    companion object {
        /** Lunghezza massima di un nome mostrato in anteprima (come il limite del validatore). */
        const val PREVIEW_NAME_CHARS = 120

        /**
         * Il nome di una regola ricevuta, per la sola anteprima: via i caratteri di controllo e quelli che
         * invertono la direzione del testo (potrebbero far leggere un nome diverso), spazi compattati, al
         * massimo [PREVIEW_NAME_CHARS] caratteri. Il nome della bozza resta quello del file.
         */
        fun displayName(name: String): String {
            val clean = buildString(name.length) {
                name.forEach { char ->
                    when {
                        char in BIDI_CONTROLS -> Unit
                        char.isISOControl() -> append(' ')
                        else -> append(char)
                    }
                }
            }.replace(Regex("\\s+"), " ").trim()
            return if (clean.length <= PREVIEW_NAME_CHARS) clean else clean.take(PREVIEW_NAME_CHARS - 1).trimEnd() + "…"
        }

        private val BIDI_CONTROLS: Set<Char> =
            ('‪'..'‮').toSet() + ('⁦'..'⁩').toSet() + setOf('‎', '‏', '؜')

        private val CANDIDATE_STATUSES = setOf(
            AutomationStatus.ARMED,
            AutomationStatus.DISABLED,
            AutomationStatus.NEEDS_REVIEW,
        )
    }
}
