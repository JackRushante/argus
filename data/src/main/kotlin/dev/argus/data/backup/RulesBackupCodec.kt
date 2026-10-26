package dev.argus.data.backup

import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Esito della lettura del file: o un rifiuto dell'intero file, o una voce per ogni regola. */
sealed interface DecodedBackup {
    data class Rejected(val failure: BackupFailure) : DecodedBackup
    data class Entries(val entries: List<DecodedEntry>) : DecodedBackup
}

sealed interface DecodedEntry {
    data class Rule(val rule: BackupRule) : DecodedEntry
    data class Discarded(val reason: RuleDiscardReason) : DecodedEntry
}

/**
 * Scrittura e lettura del file di backup. Il file è input NON fidato: niente viene eseguito, ogni
 * voce deve decodificarsi nei soli tipi del dominio ([AutomationDraft]); campi o tipi sconosciuti
 * scartano la regola (mai ignorati in silenzio: una regola letta in modo parziale potrebbe essere
 * più ampia di quella esportata).
 */
object RulesBackupCodec {
    /** Come [ArgusJson] ma senza tollerare chiavi sconosciute. */
    private val strictJson = Json(from = ArgusJson) { ignoreUnknownKeys = false }
    private val prettyJson = Json { prettyPrint = true }

    fun encode(rules: List<BackupRule>, exportedAt: String, appVersion: String): String {
        val root = buildJsonObject {
            put("format", RulesBackupFormat.FORMAT)
            put("version", RulesBackupFormat.VERSION)
            put("exportedAt", exportedAt)
            put("appVersion", appVersion)
            put(
                "rules",
                buildJsonArray {
                    rules.forEach { rule ->
                        add(
                            buildJsonObject {
                                put("enabled", rule.enabled)
                                put("draft", ArgusJson.encodeToJsonElement(AutomationDraft.serializer(), rule.draft))
                            },
                        )
                    }
                },
            )
        }
        return prettyJson.encodeToString(JsonElement.serializer(), root)
    }

    fun decode(text: String): DecodedBackup {
        val body = text.removePrefix("﻿")
        if (exceedsNesting(body)) return DecodedBackup.Rejected(BackupFailure.NOT_JSON)
        val root = try {
            Json.parseToJsonElement(body)
        } catch (_: Exception) {
            return DecodedBackup.Rejected(BackupFailure.NOT_JSON)
        }
        val obj = root as? JsonObject ?: return DecodedBackup.Rejected(BackupFailure.WRONG_FORMAT)

        val format = (obj["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != RulesBackupFormat.FORMAT) return DecodedBackup.Rejected(BackupFailure.WRONG_FORMAT)

        val version = (obj["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (version != RulesBackupFormat.VERSION) {
            return DecodedBackup.Rejected(BackupFailure.UNSUPPORTED_VERSION)
        }

        val rules = obj["rules"] as? JsonArray ?: return DecodedBackup.Rejected(BackupFailure.WRONG_FORMAT)
        if (rules.size > RulesBackupFormat.MAX_RULES) {
            return DecodedBackup.Rejected(BackupFailure.TOO_MANY_RULES)
        }
        if (rules.isEmpty()) return DecodedBackup.Rejected(BackupFailure.NO_RULES)
        return DecodedBackup.Entries(rules.map(::decodeEntry))
    }

    private fun decodeEntry(element: JsonElement): DecodedEntry {
        val entry = element as? JsonObject ?: return discarded(RuleDiscardReason.MALFORMED_ENTRY)
        if (entry.keys != ENTRY_KEYS) return discarded(RuleDiscardReason.MALFORMED_ENTRY)
        val enabled = (entry["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: return discarded(RuleDiscardReason.MALFORMED_ENTRY)
        val draftElement = entry["draft"] as? JsonObject
            ?: return discarded(RuleDiscardReason.MALFORMED_ENTRY)
        if (draftElement.toString().length > RulesBackupFormat.MAX_RULE_CHARS) {
            return discarded(RuleDiscardReason.RULE_TOO_LARGE)
        }
        val draft = try {
            strictJson.decodeFromJsonElement(AutomationDraft.serializer(), draftElement)
        } catch (_: Exception) {
            return discarded(RuleDiscardReason.INVALID_RULE)
        }
        return DecodedEntry.Rule(BackupRule(enabled, draft))
    }

    private fun discarded(reason: RuleDiscardReason) = DecodedEntry.Discarded(reason)

    /**
     * true se le parentesi `{`/`[` fuori dalle stringhe si annidano oltre il limite. Scansione lineare
     * prima del parser, che è ricorsivo: un file da pochi KB di `[[[[…` non deve poterlo far saltare.
     */
    internal fun exceedsNesting(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> if (++depth > RulesBackupFormat.MAX_NESTING) return true
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return false
    }

    private val ENTRY_KEYS = setOf("enabled", "draft")
}
