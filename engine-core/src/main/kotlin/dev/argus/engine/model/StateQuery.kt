package dev.argus.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class SettingNamespace { SYSTEM, SECURE, GLOBAL }
enum class StateValueType { TEXT, NUMBER, BOOLEAN }

enum class StateQueryFamily(val wireName: String, val capabilityId: String) {
    BUILTIN("builtin", CapabilityIds.STATE_READER_BUILTIN),
    SETTING("setting", CapabilityIds.STATE_READER_SETTING),
    SYSTEM_PROPERTY("system_property", CapabilityIds.STATE_READER_SYSTEM_PROPERTY),
    SYSFS("sysfs", CapabilityIds.STATE_READER_SYSFS),
    DUMPSYS_FIELD("dumpsys_field", CapabilityIds.STATE_READER_DUMPSYS_FIELD),
}

/** Policy pura condivisa da validator e reader runtime; il canonical path sysfs si verifica dopo. */
object StateQueryPolicy {
    /** Versione della policy read-only inclusa nel materiale approvato di StateCompare. */
    const val VERSION = 1
    const val MAX_QUERY_NAME_LENGTH = 96
    const val MAX_SYSFS_PATH_LENGTH = 256
    const val MAX_EXPECTED_LENGTH = 1_024
    const val QUERY_TIMEOUT_MILLIS = 10_000L
    const val MAX_QUERY_OUTPUT_BYTES = 64 * 1_024
    const val MAX_SCALAR_CHARS = 4_096

    private val QUERY_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$")
    private val DUMPSYS_SERVICE = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    private val DUMPSYS_FIELD = Regex("^[A-Za-z0-9][A-Za-z0-9_. -]{0,95}$")

    fun validQuery(query: StateQuery, stateKeys: Set<String> = StateKeys.ALL.keys): Boolean =
        when (query) {
            is StateQuery.Builtin -> query.key in stateKeys && query.key in StateKeys.ALL
            is StateQuery.Setting -> validName(query.key)
            is StateQuery.SystemProperty -> validName(query.name)
            is StateQuery.Sysfs -> validSysfsPath(query.path)
            is StateQuery.DumpsysField ->
                DUMPSYS_SERVICE.matches(query.service) &&
                    DUMPSYS_FIELD.matches(query.field) && query.field == query.field.trim()
        }

    /** Seconda barriera dopo File.canonicalFile: il comando non riceve mai un path fuori /sys. */
    fun validSysfsResolvedPath(path: String): Boolean = validSysfsPath(path)

    fun validComparison(
        query: StateQuery,
        valueType: StateValueType,
        op: CmpOp,
        expected: String,
        stateKeys: Set<String> = StateKeys.ALL.keys,
    ): Boolean {
        if (!validQuery(query, stateKeys)) return false
        val expectedSafe = expected.length <= MAX_EXPECTED_LENGTH &&
            expected.none { it.isISOControl() } &&
            expected.isNotBlank() && expected == expected.trim()
        val genericValid = expectedSafe && when (valueType) {
            StateValueType.TEXT -> op in setOf(CmpOp.EQ, CmpOp.NEQ, CmpOp.CONTAINS)
            StateValueType.NUMBER -> op in setOf(CmpOp.EQ, CmpOp.NEQ, CmpOp.GT, CmpOp.LT) &&
                expected.toDoubleOrNull()?.isFinite() == true
            StateValueType.BOOLEAN -> op in setOf(CmpOp.EQ, CmpOp.NEQ) &&
                expected in setOf("true", "false")
        }
        val builtinValid = (query as? StateQuery.Builtin)?.let { builtin ->
            when (builtin.key) {
                StateKeys.BATTERY -> valueType == StateValueType.NUMBER &&
                    expected.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true
                StateKeys.CHARGING -> valueType == StateValueType.BOOLEAN
                else -> valueType == StateValueType.TEXT && op in setOf(CmpOp.EQ, CmpOp.NEQ) &&
                    expected in StateKeys.ALL.getValue(builtin.key).split('|')
            }
        } ?: true
        return genericValid && builtinValid
    }

    private fun validName(value: String): Boolean =
        value.length <= MAX_QUERY_NAME_LENGTH && QUERY_NAME.matches(value)

    private fun validSysfsPath(path: String): Boolean {
        if (path.length !in 6..MAX_SYSFS_PATH_LENGTH || !path.startsWith("/sys/")) return false
        if ('\\' in path || path.any { it.isISOControl() }) return false
        val segments = path.split('/').drop(2)
        return segments.isNotEmpty() && segments.none { it.isBlank() || it == "." || it == ".." }
    }
}

/** Conversione unica per probe e fire-time: un valore non tipizzabile resta UNKNOWN. */
object StateValueCoercion {
    fun number(raw: String): Double? = raw.toDoubleOrNull()?.takeIf(Double::isFinite)

    fun boolean(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true", "1", "on" -> true
        "false", "0", "off" -> false
        else -> null
    }

    fun compatible(raw: String, type: StateValueType): Boolean = when (type) {
        StateValueType.TEXT -> true
        StateValueType.NUMBER -> number(raw) != null
        StateValueType.BOOLEAN -> boolean(raw) != null
    }
}

/** Famiglie chiuse, parametri aperti e fingerprintati. Nessuna query può scrivere stato. */
@Serializable
sealed interface StateQuery {
    val family: StateQueryFamily

    /** ID opaco e stabile; i parametri esatti restano nel JSON approvato, non nell'audit. */
    val canonicalId: String
        get() {
            val material = canonicalParts().joinToString(separator = "") { part ->
                val bytes = part.toByteArray(StandardCharsets.UTF_8)
                "${bytes.size}:$part"
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(
                "argus-state-query-v1\u0000$material".toByteArray(StandardCharsets.UTF_8),
            )
            return "state.reader.${family.wireName}.v1.${digest.toHex()}"
        }

    @Serializable
    @SerialName("builtin")
    data class Builtin(val key: String) : StateQuery {
        override val family: StateQueryFamily get() = StateQueryFamily.BUILTIN
    }

    @Serializable
    @SerialName("setting")
    data class Setting(val namespace: SettingNamespace, val key: String) : StateQuery {
        override val family: StateQueryFamily get() = StateQueryFamily.SETTING
    }

    @Serializable
    @SerialName("system_property")
    data class SystemProperty(val name: String) : StateQuery {
        override val family: StateQueryFamily get() = StateQueryFamily.SYSTEM_PROPERTY
    }

    @Serializable
    @SerialName("sysfs")
    data class Sysfs(val path: String) : StateQuery {
        override val family: StateQueryFamily get() = StateQueryFamily.SYSFS
    }

    @Serializable
    @SerialName("dumpsys_field")
    data class DumpsysField(val service: String, val field: String) : StateQuery {
        override val family: StateQueryFamily get() = StateQueryFamily.DUMPSYS_FIELD
    }

    private fun canonicalParts(): List<String> = when (this) {
        is Builtin -> listOf(family.wireName, key)
        is Setting -> listOf(family.wireName, namespace.name, key)
        is SystemProperty -> listOf(family.wireName, name)
        is Sysfs -> listOf(family.wireName, path)
        is DumpsysField -> listOf(family.wireName, service, field)
    }
}

private fun ByteArray.toHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val unsigned = byte.toInt() and 0xff
            append(alphabet[unsigned ushr 4])
            append(alphabet[unsigned and 0x0f])
        }
    }
}
