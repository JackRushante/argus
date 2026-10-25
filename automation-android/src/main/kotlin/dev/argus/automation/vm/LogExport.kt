package dev.argus.automation.vm

import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Intestazione dell'esportazione: solo versione dell'app e modello del telefono. */
internal data class LogExportHeader(
    val appVersion: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
)

/**
 * Testo dell'esportazione del Log «senza dati personali» (F11). Whitelist, non blacklist: si leggono
 * solo data/ora, tipo di evento, stato, codici chiusi e tipi di azione. Mai nomi di regole, testi,
 * contatti, numeri, posizioni o risposte AI; della regola resta solo un id breve (8 caratteri di un
 * hash dell'id), sufficiente a distinguere le regole fra loro e a collegare due segnalazioni.
 */
internal object LogExport {
    const val MAX_ENTRIES = 500

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private val REASON_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val RETRY_AT = Regex("^retry_at=[0-9]{1,19}$")
    private val BUDGET = Regex("^(hour|day|month_cost|month_tokens|unpriced_model):(global|[a-z][a-z0-9_]{0,32})$")
    private val HEADER_TEXT = Regex("[^A-Za-z0-9 ._+-]")

    /** Primi 8 caratteri dello SHA-256 dell'id della regola. */
    fun shortRuleId(automationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(automationId.toByteArray(StandardCharsets.UTF_8))
        return buildString(8) {
            for (byte in digest.take(4)) append("%02x".format(byte.toInt() and 0xff))
        }
    }

    fun build(
        header: LogExportHeader,
        records: List<AuditLogRecord>,
        actions: List<ActionResultEntity>,
        zone: ZoneId,
    ): String {
        val entries = records.take(MAX_ENTRIES)
        val actionsByExecution = actions.groupBy { it.executionId }
        val printedExecutions = hashSetOf<String>()
        return buildString {
            appendLine("Argus log export (no personal data)")
            appendLine("app: ${header.appVersion.headerText()}")
            appendLine("android_sdk: ${header.sdkInt}")
            appendLine("device: ${header.manufacturer.headerText()} ${header.model.headerText()}")
            appendLine("entries: ${entries.size}")
            appendLine()
            for (record in entries) {
                val time = TIME_FORMAT.format(Instant.ofEpochMilli(record.atMillis).atZone(zone))
                append(time)
                append(" | rule ").append(shortRuleId(record.automationId))
                append(" | ").append(record.kind.name)
                append(" | status ").append(record.executionStatus?.name ?: "-")
                append(" | code ").append(safeCode(record.detail))
                appendLine()
                val executionId = record.executionId
                if (executionId != null && printedExecutions.add(executionId)) {
                    actionsByExecution[executionId].orEmpty()
                        .sortedBy { it.actionIndex }
                        .forEach { action ->
                            append("    action ").append(action.actionPath.ifBlank { (action.actionIndex + 1).toString() })
                            append(' ').append(safeCode(action.actionType))
                            append(" | ").append(action.outcome.name)
                            append(" | code ").append(safeCode(action.errorCode.orEmpty()))
                            appendLine()
                        }
                }
            }
        }
    }

    /** Un codice chiuso passa, qualunque altra cosa (anche righe vecchie) diventa «-». */
    private fun safeCode(value: String): String = when {
        value.isEmpty() -> "-"
        REASON_CODE.matches(value) || RETRY_AT.matches(value) || BUDGET.matches(value) -> value
        // VALIDATION_REJECTED salva fino a otto codici separati da virgola.
        value.split(',').let { parts ->
            parts.size <= MAX_CODE_LIST && parts.all(REASON_CODE::matches)
        } -> value
        else -> "-"
    }

    private fun String.headerText(): String =
        replace(HEADER_TEXT, "").trim().take(MAX_HEADER_CHARS).ifBlank { "unknown" }

    private const val MAX_CODE_LIST = 8
    private const val MAX_HEADER_CHARS = 40
}
