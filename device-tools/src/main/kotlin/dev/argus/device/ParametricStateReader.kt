package dev.argus.device

import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.shizuku.PrivilegedShell
import java.io.File
import java.util.concurrent.CancellationException

/** Reader read-only: costruisce soltanto argv chiusi e non esegue mai shell/template. */
class ParametricStateReader(
    private val shell: PrivilegedShell,
    private val sysfsResolver: (String) -> String? = ::resolveSysfsPath,
) {
    private val builtinReader = StateReader(shell)

    suspend fun read(queries: Set<StateQuery>): Map<String, String> {
        val valid = queries.filter { StateQueryPolicy.validQuery(it) }
        if (valid.isEmpty()) return emptyMap()
        val values = linkedMapOf<String, String>()

        val builtins = valid.filterIsInstance<StateQuery.Builtin>()
        if (builtins.isNotEmpty()) {
            val state = builtinReader.readBounded(
                keys = builtins.mapTo(linkedSetOf()) { it.key },
                timeoutMillis = StateQueryPolicy.QUERY_TIMEOUT_MILLIS,
                maxOutputBytes = StateQueryPolicy.MAX_QUERY_OUTPUT_BYTES,
            )
            builtins.forEach { query ->
                state.values[query.key]?.let { values[query.canonicalId] = it }
            }
        }

        valid.filterNot { it is StateQuery.Builtin || it is StateQuery.DumpsysField }
            .sortedBy(StateQuery::canonicalId)
            .forEach { query ->
                readSingle(query)?.let { values[query.canonicalId] = it }
            }

        valid.filterIsInstance<StateQuery.DumpsysField>()
            .groupBy(StateQuery.DumpsysField::service)
            .toSortedMap()
            .forEach { (service, fields) ->
                val raw = commandOutput(listOf(DUMPSYS, service)) ?: return@forEach
                fields.forEach { query ->
                    parseDumpsysField(raw, query.field)?.let { value ->
                        values[query.canonicalId] = value
                    }
                }
            }

        return values.toMap()
    }

    private suspend fun readSingle(query: StateQuery): String? = when (query) {
        is StateQuery.Setting -> commandOutput(
            listOf(SETTINGS, "get", query.namespace.wireName, query.key),
        )?.let(::normalizeScalar)?.takeUnless { it == SETTINGS_MISSING_VALUE }
        is StateQuery.SystemProperty -> commandOutput(
            listOf(GETPROP, query.name),
        )?.let(::normalizeScalar)
        is StateQuery.Sysfs -> sysfsResolver(query.path)
            ?.takeIf(StateQueryPolicy::validSysfsResolvedPath)
            ?.let { commandOutput(listOf(CAT, it)) }
            ?.let(::normalizeScalar)
        is StateQuery.Builtin,
        is StateQuery.DumpsysField,
        -> null
    }

    private suspend fun commandOutput(command: List<String>): String? = try {
        shell.run(
            command = command,
            timeoutMillis = StateQueryPolicy.QUERY_TIMEOUT_MILLIS,
            maxOutputBytes = StateQueryPolicy.MAX_QUERY_OUTPUT_BYTES,
        ).takeIf { result ->
            result.successful && !result.truncated && !result.timedOut
        }?.stdoutText
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    internal companion object {
        const val SETTINGS = "/system/bin/settings"
        const val GETPROP = "/system/bin/getprop"
        const val CAT = "/system/bin/cat"
        const val DUMPSYS = "/system/bin/dumpsys"
        const val SETTINGS_MISSING_VALUE = "null"

        fun normalizeScalar(raw: String): String? = raw.trim().takeIf { value ->
            value.isNotEmpty() && value.length <= StateQueryPolicy.MAX_SCALAR_CHARS &&
                value.none { it.isISOControl() }
        }

        /** Un campo ambiguo fallisce chiuso: non scegliamo arbitrariamente la prima occorrenza. */
        fun parseDumpsysField(raw: String, wanted: String): String? = raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else {
                    val key = line.substring(0, separator).trim()
                    if (!key.equals(wanted, ignoreCase = true)) null
                    else normalizeScalar(line.substring(separator + 1))
                }
            }
            .toList()
            .singleOrNull()

        private fun resolveSysfsPath(path: String): String? = runCatching {
            val root = File("/sys").canonicalFile.toPath()
            val resolved = File(path).canonicalFile.toPath()
            resolved.takeIf { it != root && it.startsWith(root) }?.toString()
        }.getOrNull()
    }
}

private val SettingNamespace.wireName: String
    get() = name.lowercase()
