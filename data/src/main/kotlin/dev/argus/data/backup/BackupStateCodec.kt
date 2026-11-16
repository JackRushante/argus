package dev.argus.data.backup

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.StoredValuePolicy
import dev.argus.engine.model.UserModePolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Le chiavi `modes` e `storedValues` del file di backup (0.8, vedi [BackupState]). */
internal object BackupStateCodec {
    private const val MODES_KEY = "modes"
    private const val VALUES_KEY = "storedValues"

    /** Voci lette al più prima dei controlli: un elenco enorme non va nemmeno scorso. */
    private const val MAX_CANDIDATES = 200

    fun encodeInto(root: JsonObjectBuilder, state: BackupState) {
        if (state.modes.isNotEmpty()) {
            root.put(
                MODES_KEY,
                buildJsonObject {
                    put("names", buildJsonArray { state.modes.forEach { add(JsonPrimitive(it)) } })
                    state.activeMode?.let { put("active", it) }
                },
            )
        }
        if (state.values.isNotEmpty()) {
            root.put(
                VALUES_KEY,
                buildJsonArray {
                    state.values.forEach { value ->
                        add(
                            buildJsonObject {
                                put("key", value.key)
                                put("type", value.type.name)
                                put("value", value.text)
                                put("integrity", value.integrity.name)
                                put("confidentiality", value.confidentiality.name)
                                put("writerRule", (value.writer as? ChangeAuthor.Rule)?.id?.value)
                                put("updatedAt", value.updatedAtMillis)
                            },
                        )
                    }
                },
            )
        }
    }

    /** Lettura tollerante: ciò che non ha la forma attesa si salta. */
    fun decode(root: JsonObject): BackupState {
        val modesObject = root[MODES_KEY] as? JsonObject
        val modes = (modesObject?.get("names") as? JsonArray)
            ?.take(MAX_CANDIDATES)
            ?.mapNotNull { string(it) }
            ?.mapNotNull(UserModePolicy::normalize)
            ?.distinctBy(UserModePolicy::key)
            ?.take(UserModePolicy.MAX_MODES)
            .orEmpty()
        val active = string(modesObject?.get("active"))?.let { name -> modes.firstOrNull { UserModePolicy.same(it, name) } }
        val values = (root[VALUES_KEY] as? JsonArray)
            ?.take(MAX_CANDIDATES)
            ?.mapNotNull { decodeValue(it as? JsonObject ?: return@mapNotNull null) }
            ?.distinctBy { it.key }
            ?.take(StoredValuePolicy.MAX_KEYS)
            .orEmpty()
        return BackupState(modes, active, values)
    }

    private fun decodeValue(entry: JsonObject): StoredValue? {
        val key = string(entry["key"])?.takeIf(StoredValuePolicy::validKey) ?: return null
        val type = enumOrNull<StateValueType>(string(entry["type"])) ?: return null
        val text = string(entry["value"])?.takeIf { StoredValuePolicy.canonical(it, type) == it } ?: return null
        val writer = when (val rule = entry["writerRule"]) {
            null, is JsonNull -> ChangeAuthor.User
            else -> string(rule)?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }
                ?.let { ChangeAuthor.Rule(AutomationId(it)) } ?: return null
        }
        return StoredValue(
            key = key,
            type = type,
            text = text,
            // Un'etichetta mancante o sconosciuta è il caso peggiore, mai uno migliore.
            integrity = enumOrNull<IntegrityLabel>(string(entry["integrity"])) ?: IntegrityLabel.TAINTED,
            confidentiality = enumOrNull<ConfidentialityLabel>(string(entry["confidentiality"]))
                ?: ConfidentialityLabel.SECRET,
            writer = writer,
            updatedAtMillis = (entry["updatedAt"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?.coerceAtLeast(0) ?: 0L,
        )
    }

    private fun string(element: Any?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    private const val MAX_ID_CHARS = 128
}
