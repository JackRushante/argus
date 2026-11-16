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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * 0.7.2: come [createAll], con le etichette di ogni bozza (per id). Le etichette non entrano nella bozza né
     * nella sua impronta; chi non sa conservarle le ignora (default).
     */
    suspend fun createAll(
        newDrafts: List<NewDraft>,
        labels: Map<DraftId, List<String>>,
    ): List<DraftWriteResult> = createAll(newDrafts)
}

/** Esporta e importa le regole. Il confine usato da Impostazioni. */
interface RulesBackup {
    suspend fun export(destination: Uri, stripPersonalData: Boolean): ExportOutcome

    /** Un file cifrato risponde [BackupFailure.PASSPHRASE_REQUIRED]: si richiama [importEncrypted]. */
    suspend fun import(source: Uri): ImportOutcome

    /**
     * Import di un file cifrato (`.argusbackup`) con la passphrase dell'utente; poi tutto come [import].
     * La passphrase non viene conservata; il chiamante azzera l'array dopo la chiamata.
     */
    suspend fun importEncrypted(source: Uri, passphrase: CharArray): ImportOutcome
}

/** Derivazione della chiave di un file cifrato: lenta (600 000 iterazioni), fuori dal thread principale. */
fun interface BackupKeyDeriver {
    suspend fun derive(passphrase: CharArray, salt: ByteArray): ByteArray
}

val DefaultBackupKeyDeriver = BackupKeyDeriver { passphrase, salt ->
    withContext(Dispatchers.Default) { BackupKeyDerivation.derive(passphrase, salt) }
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
    private val keyDeriver: BackupKeyDeriver = DefaultBackupKeyDeriver,
    /**
     * 0.7.2: etichette per id di regola (`RoomRuleLabelStore.snapshot`). Viaggiano nell'export completo; la
     * versione da condividere senza dati personali non le scrive (un'etichetta può dire «Moglie»).
     */
    private val labels: suspend () -> Map<String, List<String>> = { emptyMap() },
) : RulesBackup {

    override suspend fun export(destination: Uri, stripPersonalData: Boolean): ExportOutcome {
        val built = when (val result = build(stripPersonalData)) {
            is BuiltBackup.Failed -> return ExportOutcome.Failed(result.failure)
            is BuiltBackup.Ready -> result
        }
        return try {
            documents.write(destination, built.bytes)
            ExportOutcome.Done(exported = built.exported, skipped = built.skipped)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            ExportOutcome.Failed(BackupFailure.WRITE_FAILED)
        } catch (_: RuntimeException) {
            // SecurityException / IllegalArgumentException di un provider di documenti.
            ExportOutcome.Failed(BackupFailure.WRITE_FAILED)
        }
    }

    /**
     * Il testo dell'export COMPLETO (con i dati personali), lo stesso di «Esporta» senza l'opzione: è
     * quello che il backup automatico cifra.
     */
    suspend fun buildFullBackup(): BuiltBackup = build(stripPersonalData = false)

    private suspend fun build(stripPersonalData: Boolean): BuiltBackup {
        val all = try {
            automations.all()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return BuiltBackup.Failed(BackupFailure.WRITE_FAILED)
        }
        val exportable = all.filter { it.isBackupExportable() }
        if (exportable.isEmpty()) return BuiltBackup.Failed(BackupFailure.NOTHING_TO_EXPORT)
        if (exportable.size > RulesBackupFormat.MAX_RULES) {
            return BuiltBackup.Failed(BackupFailure.EXPORT_TOO_LARGE)
        }
        // «Da rivedere» ma illeggibili (segnaposto senza azioni) non hanno nulla da esportare.
        val skipped = all.count { it.status in CANDIDATE_STATUSES } - exportable.size
        // Le etichette sono organizzazione: se non si leggono, il backup delle regole si fa lo stesso.
        val labelsById = if (stripPersonalData) {
            emptyMap()
        } else {
            try {
                labels()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyMap()
            }
        }

        val rules = exportable.map { automation ->
            val draft = automation.toDraft()
            BackupRule(
                enabled = automation.status == AutomationStatus.ARMED && automation.enabled,
                draft = if (stripPersonalData) PersonalDataStripper.strip(draft) else draft,
                labels = labelsById[automation.id.value].orEmpty(),
            )
        }
        val bytes = RulesBackupCodec.encode(
            rules = rules,
            exportedAt = Instant.ofEpochMilli(nowMillis()).toString(),
            appVersion = appVersion() ?: "unknown",
        ).toByteArray(Charsets.UTF_8)
        if (bytes.size > RulesBackupFormat.MAX_FILE_BYTES) {
            return BuiltBackup.Failed(BackupFailure.EXPORT_TOO_LARGE)
        }
        return BuiltBackup.Ready(bytes, exported = rules.size, skipped = skipped)
    }

    override suspend fun import(source: Uri): ImportOutcome {
        val bytes = when (val read = readImport(source)) {
            is ImportRead.Failed -> return ImportOutcome.Failed(read.failure)
            is ImportRead.Bytes -> read.bytes
        }
        if (EncryptedBackupFormat.hasMagic(bytes)) return ImportOutcome.Failed(BackupFailure.PASSPHRASE_REQUIRED)
        return importPlain(bytes)
    }

    override suspend fun importEncrypted(source: Uri, passphrase: CharArray): ImportOutcome {
        val bytes = when (val read = readImport(source)) {
            is ImportRead.Failed -> return ImportOutcome.Failed(read.failure)
            is ImportRead.Bytes -> read.bytes
        }
        val salt = when (val header = EncryptedBackupFormat.header(bytes)) {
            // Non cifrato (il file è cambiato dopo la scelta): si importa come sempre.
            EncryptedBackupFormat.Header.WrongMagic -> return importPlain(bytes)
            EncryptedBackupFormat.Header.Truncated -> return ImportOutcome.Failed(BackupFailure.ENCRYPTED_DAMAGED)
            is EncryptedBackupFormat.Header.Valid -> header.salt
        }
        val key = try {
            keyDeriver.derive(passphrase, salt)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ImportOutcome.Failed(BackupFailure.WRONG_PASSPHRASE)
        }
        val opened = try {
            EncryptedBackupFormat.open(bytes, key)
        } finally {
            key.fill(0)
        }
        return when (opened) {
            is EncryptedBackupFormat.Opened.Plain -> importPlain(opened.bytes)
            EncryptedBackupFormat.Opened.WrongKeyOrDamaged -> ImportOutcome.Failed(BackupFailure.WRONG_PASSPHRASE)
            EncryptedBackupFormat.Opened.Truncated -> ImportOutcome.Failed(BackupFailure.ENCRYPTED_DAMAGED)
            EncryptedBackupFormat.Opened.WrongMagic -> importPlain(bytes)
        }
    }

    private sealed interface ImportRead {
        class Bytes(val bytes: ByteArray) : ImportRead
        data class Failed(val failure: BackupFailure) : ImportRead
    }

    /**
     * Legge il file scelto. Il limite è quello di sempre per un file in chiaro; un file cifrato può
     * superarlo solo dei byte dell'intestazione e del tag.
     */
    private suspend fun readImport(source: Uri): ImportRead {
        val read = try {
            documents.read(source, RulesBackupFormat.MAX_FILE_BYTES + EncryptedBackupFormat.OVERHEAD_BYTES)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return ImportRead.Failed(BackupFailure.READ_FAILED)
        } catch (_: RuntimeException) {
            return ImportRead.Failed(BackupFailure.READ_FAILED)
        }
        val bytes = read ?: return ImportRead.Failed(BackupFailure.FILE_TOO_LARGE)
        if (!EncryptedBackupFormat.hasMagic(bytes) && bytes.size > RulesBackupFormat.MAX_FILE_BYTES) {
            return ImportRead.Failed(BackupFailure.FILE_TOO_LARGE)
        }
        return ImportRead.Bytes(bytes)
    }

    private suspend fun importPlain(bytes: ByteArray): ImportOutcome {
        if (bytes.size > RulesBackupFormat.MAX_FILE_BYTES) return ImportOutcome.Failed(BackupFailure.FILE_TOO_LARGE)
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
        // 0.7.2: le etichette viaggiano accanto alla bozza, mai dentro: la bozza e la sua impronta non cambiano.
        val labelsByDraft = newDrafts.zip(accepted)
            .filter { (_, rule) -> rule.labels.isNotEmpty() }
            .associate { (draft, rule) -> draft.id to rule.labels }
        val results: List<DraftWriteResult> = if (newDrafts.isEmpty()) {
            emptyList()
        } else {
            try {
                drafts.createAll(newDrafts, labelsByDraft)
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
