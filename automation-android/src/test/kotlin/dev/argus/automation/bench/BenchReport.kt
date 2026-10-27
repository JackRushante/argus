package dev.argus.automation.bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale

/** Dati di contorno del rapporto: cosa e' stato misurato e con quale configurazione. */
internal data class BenchMeta(
    val model: String,
    val baseUrl: String,
    val repeat: Int,
    val parallel: Int,
    val filter: String?,
    val startedAt: String,
    val finishedAt: String,
    val compileCases: Int,
    val actCases: Int,
    /** Valore di `reasoning_effort` inviato, o `default` se omesso. */
    val reasoning: String = "default",
)

internal data class Rate(val passed: Int, val total: Int) {
    val percent: Double get() = if (total == 0) 0.0 else passed * 100.0 / total
    fun format(): String = "$passed/$total (${percentText(percent)})"
}

internal fun percentText(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value)

/** Rapporto del banco: `report.json` con tutto il dettaglio e `summary.md` leggibile. */
internal object BenchReport {
    private val pretty = Json { prettyPrint = true }

    fun write(dir: File, meta: BenchMeta, results: List<RunResult>) {
        if (!dir.isDirectory && !dir.mkdirs()) throw BenchException("impossibile creare ${dir.path}")
        File(dir, "report.json").writeText(
            pretty.encodeToString(JsonElement.serializer(), toJson(meta, results)) + "\n",
            Charsets.UTF_8,
        )
        File(dir, "summary.md").writeText(toMarkdown(meta, results), Charsets.UTF_8)
    }

    // --- aggregati -------------------------------------------------------------------------------

    fun total(results: List<RunResult>): Rate = Rate(results.count { it.passed }, results.size)

    /** Come [total] ma senza le esecuzioni che non hanno potuto misurare il modello (rete, budget). */
    fun totalWithoutTransport(results: List<RunResult>): Rate =
        total(results.filterNot { isTransportError(it) })

    fun isTransportError(run: RunResult): Boolean = run.failures.any { it.category == FailureCategory.TRANSPORT }

    fun rateBy(results: List<RunResult>, key: (RunResult) -> String?): Map<String, Rate> =
        results.mapNotNull { run -> key(run)?.let { it to run } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .mapValues { (_, runs) -> total(runs) }

    /** Esecuzioni che hanno almeno un fallimento della categoria, per categoria d'errore. */
    fun failureCategories(results: List<RunResult>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (run in results) {
            for (category in run.failures.map { it.category }.toSet()) {
                counts[category] = (counts[category] ?: 0) + 1
            }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .associate { it.key to it.value }
    }

    fun percentile(sorted: List<Long>, fraction: Double): Long? {
        if (sorted.isEmpty()) return null
        val index = (Math.ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    // --- report.json -----------------------------------------------------------------------------

    fun toJson(meta: BenchMeta, results: List<RunResult>): JsonObject = buildJsonObject {
        putJsonObject("meta") {
            put("model", meta.model)
            put("reasoning", meta.reasoning)
            put("baseUrl", meta.baseUrl)
            put("repeat", meta.repeat)
            put("parallel", meta.parallel)
            put("filter", meta.filter)
            put("startedAt", meta.startedAt)
            put("finishedAt", meta.finishedAt)
            put("compileCases", meta.compileCases)
            put("actCases", meta.actCases)
        }
        putJsonObject("totals") {
            val all = total(results)
            put("runs", all.total)
            put("passed", all.passed)
            put("percent", round1(all.percent))
            put("transportErrors", results.count { isTransportError(it) })
            put("percentWithoutTransport", round1(totalWithoutTransport(results).percent))
            put("retryUsed", results.count { it.retryUsed })
            put("repairUsed", results.count { it.repairUsed })
            put("tokensIn", results.sumOf { it.tokensIn ?: 0L })
            put("tokensOut", results.sumOf { it.tokensOut ?: 0L })
        }
        putRates("byCategory", rateBy(results) { it.category })
        putRates("byLang", rateBy(results) { it.lang })
        putRates("byManifest", rateBy(results) { it.manifest })
        putRates("byKind", rateBy(results) { it.kind })
        putJsonObject("failureCategories") {
            failureCategories(results).forEach { (category, count) -> put(category, count) }
        }
        putJsonArray("results") {
            results.forEach { run -> addJsonObject { putRun(run) } }
        }
    }

    private fun JsonObjectBuilder.putRates(name: String, rates: Map<String, Rate>) {
        putJsonObject(name) {
            rates.forEach { (key, rate) ->
                putJsonObject(key) {
                    put("passed", rate.passed)
                    put("total", rate.total)
                    put("percent", round1(rate.percent))
                }
            }
        }
    }

    private fun JsonObjectBuilder.putRun(run: RunResult) {
        put("caseId", run.caseId)
        put("kind", run.kind)
        put("category", run.category)
        put("lang", run.lang)
        put("manifest", run.manifest)
        put("repetition", run.repetition)
        put("passed", run.passed)
        put("actual", run.actual)
        put("expected", run.expected)
        putJsonArray("failures") {
            run.failures.forEach { failure ->
                addJsonObject {
                    put("category", failure.category)
                    put("message", failure.message)
                }
            }
        }
        put("retryUsed", run.retryUsed)
        put("repairUsed", run.repairUsed)
        put("attempts", run.attempts)
        put("tokensIn", run.tokensIn)
        put("tokensOut", run.tokensOut)
        put("latencyMs", run.latencyMs)
        put("metaError", run.metaError)
        put("reply", run.reply)
        // Il testo grezzo esce SOLO per i fallimenti ed e' gia' troncato dal runner.
        put("raw", if (run.passed) null else run.raw?.let { BenchText.clip(it, BenchRunner.RAW_MAX_CHARS) })
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    // --- summary.md ------------------------------------------------------------------------------

    fun toMarkdown(meta: BenchMeta, results: List<RunResult>): String = buildString {
        val all = total(results)
        appendLine("# Banco di prova AI di Argus")
        appendLine()
        appendLine("- Modello: `${meta.model}` · Ragionamento: `${meta.reasoning}`")
        appendLine("- Endpoint: `${meta.baseUrl}`")
        appendLine("- Casi: ${meta.compileCases} di compile + ${meta.actCases} generativi")
        appendLine(
            "- Ripetizioni: ${meta.repeat} · Parallelismo: ${meta.parallel} · " +
                "Filtro: ${meta.filter?.let { "`$it`" } ?: "nessuno"}",
        )
        appendLine("- Esecuzioni: ${results.size} · Avvio: ${meta.startedAt} · Fine: ${meta.finishedAt}")
        appendLine()
        appendLine("## Risultato")
        appendLine()
        appendLine("**Successo totale: ${all.format()}**")
        val transport = results.count { isTransportError(it) }
        if (transport > 0) {
            appendLine()
            appendLine(
                "Errori di trasporto (rete, chiave, limiti del provider): $transport. " +
                    "Escludendoli: ${totalWithoutTransport(results).format()}.",
            )
        }
        appendRates("Per categoria", "Categoria", rateBy(results) { it.category })
        appendRates("Per lingua", "Lingua", rateBy(results) { it.lang })
        appendRates("Per manifest", "Manifest", rateBy(results) { it.manifest })
        appendRates("Per tipo", "Tipo", rateBy(results) { it.kind })

        appendLine()
        appendLine("## Categorie d'errore")
        appendLine()
        val categories = failureCategories(results)
        if (categories.isEmpty()) {
            appendLine("Nessun fallimento.")
        } else {
            appendLine("Esecuzioni con almeno un fallimento della categoria.")
            appendLine()
            appendLine("| Categoria | Esecuzioni |")
            appendLine("|---|---:|")
            categories.forEach { (category, count) -> appendLine("| $category | $count |") }
        }

        appendLine()
        appendLine("## Ritentativo")
        appendLine()
        val compileRuns = results.filter { it.kind == "compile" }
        val retried = compileRuns.filter { it.retryUsed }
        appendLine(
            "Ritentativo con nota di correzione usato in ${retried.size} di ${compileRuns.size} compile " +
                "(${percentText(if (compileRuns.isEmpty()) 0.0 else retried.size * 100.0 / compileRuns.size)}); " +
                "riuscite dopo il ritentativo: ${retried.count { it.passed }}.",
        )
        val repaired = compileRuns.filter { it.repairUsed }
        appendLine()
        appendLine(
            "Giro di riparazione sugli errori del validatore usato in ${repaired.size} di ${compileRuns.size} compile " +
                "(${percentText(if (compileRuns.isEmpty()) 0.0 else repaired.size * 100.0 / compileRuns.size)}); " +
                "riuscite dopo la riparazione: ${repaired.count { it.passed }}.",
        )

        appendLine()
        appendLine("## Token e latenza")
        appendLine()
        appendLine(
            "Token in ingresso: ${results.sumOf { it.tokensIn ?: 0L }} · " +
                "in uscita: ${results.sumOf { it.tokensOut ?: 0L }}",
        )
        val latencies = results.map { it.latencyMs }.sorted()
        appendLine(
            "Latenza per esecuzione: mediana ${percentile(latencies, 0.5) ?: 0} ms · " +
                "p95 ${percentile(latencies, 0.95) ?: 0} ms · massima ${latencies.lastOrNull() ?: 0} ms",
        )

        if (meta.repeat > 1) {
            val unstable = results.groupBy { it.caseId }
                .filterValues { runs -> runs.any { it.passed } && runs.any { !it.passed } }
            appendLine()
            appendLine("## Casi instabili")
            appendLine()
            if (unstable.isEmpty()) {
                appendLine("Nessuno: ogni caso ha dato lo stesso esito in tutte le ripetizioni.")
            } else {
                unstable.toSortedMap().forEach { (id, runs) ->
                    appendLine("- `$id`: ${runs.count { it.passed }}/${runs.size} riuscite")
                }
            }
        }

        appendLine()
        appendLine("## Fallimenti")
        appendLine()
        val failed = results.filterNot { it.passed }
        if (failed.isEmpty()) {
            appendLine("Nessuno.")
        } else {
            failed.forEach { run ->
                val reps = if (meta.repeat > 1) " (ripetizione ${run.repetition})" else ""
                appendLine("- `${run.caseId}`$reps · ${run.category}/${run.lang} · esito `${run.actual}`, atteso `${run.expected}`")
                run.failures.forEach { failure ->
                    appendLine("  - ${failure.category}: ${failure.message}")
                }
            }
        }
    }

    private fun StringBuilder.appendRates(title: String, header: String, rates: Map<String, Rate>) {
        if (rates.isEmpty()) return
        appendLine()
        appendLine("## $title")
        appendLine()
        appendLine("| $header | Riuscite | Totale | % |")
        appendLine("|---|---:|---:|---:|")
        rates.forEach { (key, rate) ->
            appendLine("| $key | ${rate.passed} | ${rate.total} | ${percentText(rate.percent)} |")
        }
    }
}
