package dev.argus.automation.bench

import dev.argus.automation.apps.InstalledAppCandidate
import dev.argus.engine.model.ActionTypeIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter

/**
 * Errore del banco stesso (caso malformato, risorsa mancante, configurazione errata): fa fallire il
 * test. Un risultato pessimo del modello NON e' mai un errore del banco.
 */
internal class BenchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal enum class ManifestKind(val wire: String) {
    FULL("full"),
    NO_SHIZUKU("no_shizuku"),

    /** Il telefono completo ma con il servizio di Accessibilita' spento: `ui_steps` non si pubblica (0.7.0). */
    NO_ACCESSIBILITY("no_accessibility"),
    ;

    companion object {
        fun parse(wire: String): ManifestKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Esito atteso di una compile. [NO_DRAFT] = una qualsiasi risposta del modello senza bozza. */
internal enum class Outcome(val wire: String) {
    DRAFT("draft"),
    CLARIFICATION("clarification"),
    UNSUPPORTED("unsupported"),
    LIMIT("limit"),

    /** Rifiuto motivato con un codice scelto dal modello (es. `contact_not_whitelisted`). */
    REFUSAL("refusal"),
    NO_DRAFT("no_draft"),
    ;

    companion object {
        fun parse(wire: String): Outcome? = entries.firstOrNull { it.wire == wire }
    }
}

/** Vocabolario chiuso del dominio usato dai casi. Un nome fuori da qui e' un caso malformato. */
internal object BenchVocab {
    val TRIGGER_TYPES: Set<String> = setOf(
        "time", "immediate", "geofence", "notification", "phone_state", "connectivity", "sensor",
        "screen", "battery", "audio_output", "mode_changed", "media",
        // 0.4.1: avvio manuale.
        "manual",
        // 0.5.1: app aperta/chiusa.
        "app_event",
    )

    val ACTION_TYPES: Set<String> = setOf(
        ActionTypeIds.SET_WIFI,
        ActionTypeIds.SET_BLUETOOTH,
        ActionTypeIds.SET_DND,
        ActionTypeIds.SET_RINGER,
        ActionTypeIds.LAUNCH_APP,
        ActionTypeIds.OPEN_URL,
        ActionTypeIds.SHOW_NOTIFICATION,
        ActionTypeIds.TAP,
        ActionTypeIds.INPUT_TEXT,
        ActionTypeIds.WHATSAPP_REPLY,
        ActionTypeIds.RUN_SHELL,
        ActionTypeIds.COPY_TO_CLIPBOARD,
        ActionTypeIds.COPY_TEXT,
        ActionTypeIds.SET_MOBILE_DATA,
        ActionTypeIds.SET_ALARM,
        ActionTypeIds.SET_TIMER,
        ActionTypeIds.SET_VOLUME,
        ActionTypeIds.SET_FLASHLIGHT,
        ActionTypeIds.SET_DARK_MODE,
        ActionTypeIds.OPEN_SETTINGS_SCREEN,
        ActionTypeIds.VIBRATE,
        ActionTypeIds.WRITE_SETTING,
        ActionTypeIds.INVOKE_LLM,
        ActionTypeIds.INVOKE_LLM_V2,
        ActionTypeIds.WAIT,
        ActionTypeIds.IF,
        ActionTypeIds.WHILE,
        ActionTypeIds.SPEAK,
        ActionTypeIds.MEDIA_CONTROL,
        ActionTypeIds.SET_BRIGHTNESS,
        ActionTypeIds.SET_ROTATION,
        ActionTypeIds.HTTP_REQUEST,
        // 0.5.1: azioni privilegiate tipizzate.
        ActionTypeIds.APP_CONTROL,
        ActionTypeIds.SET_AIRPLANE,
        ActionTypeIds.SET_LOCATION,
        ActionTypeIds.SET_NFC,
        ActionTypeIds.SET_BATTERY_SAVER,
        ActionTypeIds.LOCK_SCREEN,
        ActionTypeIds.SCREENSHOT,
        ActionTypeIds.APP_PERMISSION,
        // 0.5.1: macro sullo schermo con il servizio di Accessibilità.
        ActionTypeIds.UI_STEPS,
    )

    val CONDITION_TYPES: Set<String> = setOf(
        "time_window", "state_equals", "state_compare", "app_in_foreground", "location_in",
        "and", "or", "not", "boolean_literal", "var_compare",
    )

    val VAR_TYPES: Set<String> = setOf("literal", "state", "trigger_payload", "random_int", "clock")

    val FLOW_TYPES: Set<String> = setOf(ActionTypeIds.IF, ActionTypeIds.WHILE, ActionTypeIds.WAIT)

    val LANGS: Set<String> = setOf("it", "en")

    /** Prefissi ammessi in `covers` oltre agli id d'azione: richieste che toccano un trigger o un tool. */
    const val COVERS_TRIGGER = "trigger:"
    const val COVERS_TOOL = "tool:"
}

internal data class AtExpectation(
    val daysFromToday: Int?,
    val monthDay: MonthDay?,
    val time: LocalTime?,
)

/** Aspettative sulla bozza (usate solo se `draft` e' tra gli esiti ammessi). */
internal data class DraftExpectation(
    /** Matcher per campo del trigger, chiavi come nel JSON del dominio (`type`, `cron`, `afterMs`...). */
    val trigger: Map<String, JsonElement> = emptyMap(),
    /** Nome di un contatto in whitelist: il `conversationId` del trigger deve essere il suo id reale. */
    val contact: String? = null,
    val cronFires: List<LocalDateTime> = emptyList(),
    val cronSilent: List<LocalDateTime> = emptyList(),
    val at: AtExpectation? = null,
    /** Tipi di azione presenti in qualsiasi ordine e profondita'. Una voce `a|b` ammette alternative. */
    val actionsInclude: List<String> = emptyList(),
    val actionsExclude: List<String> = emptyList(),
    /** Regex (senza distinzione di maiuscole) che devono trovare un riscontro nel JSON delle azioni. */
    val actionsText: List<String> = emptyList(),
    /** `tipo.campo[.sotto]` -> matcher: esiste un'azione di quel tipo il cui campo lo soddisfa. */
    val params: Map<String, JsonElement> = emptyMap(),
    val vars: List<String> = emptyList(),
    /** Tipi di variabile che NON devono esserci (una voce `a|b` li vieta tutti): serve a `clock` fuori posto. */
    val varsExclude: List<String> = emptyList(),
    val controlFlow: List<String> = emptyList(),
    val conditionsInclude: List<String> = emptyList(),
    val conditionsParams: Map<String, JsonElement> = emptyMap(),
    val conditionsText: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = trigger.isEmpty() && contact == null && cronFires.isEmpty() && cronSilent.isEmpty() &&
            at == null && actionsInclude.isEmpty() && actionsExclude.isEmpty() && actionsText.isEmpty() &&
            params.isEmpty() && vars.isEmpty() && varsExclude.isEmpty() && controlFlow.isEmpty() && conditionsInclude.isEmpty() &&
            conditionsParams.isEmpty() && conditionsText.isEmpty()
}

internal data class BenchCase(
    val id: String,
    val category: String,
    val lang: String,
    val text: String,
    val manifest: ManifestKind,
    val outcomes: Set<Outcome>,
    val draft: DraftExpectation,
    /** Cosa tocca la richiesta quando non c'e' una bozza: id d'azione, `trigger:<wire>` o `tool:<nome>`. */
    val covers: List<String>,
    /**
     * App in piu' sul telefono di prova per questo caso (0.7.0), oltre a quelle fisse: serve a un caso con
     * due app dello stesso tipo, che le altre richieste non devono vedere.
     */
    val extraApps: List<InstalledAppCandidate> = emptyList(),
    /**
     * Espressioni regolari (su testo senza accenti e minuscolo) che la frase di risposta del modello deve
     * contenere TUTTE: per i casi senza bozza in cui conta cosa dice (il motivo e come rimediare).
     */
    val replyMentions: List<String> = emptyList(),
    val note: String?,
)

internal data class ActCase(
    val id: String,
    val lang: String,
    val goal: String,
    val web: Boolean,
    val contextSources: List<String>,
    val expectNumber: Boolean,
    val mustContainAny: List<String>,
    val note: String?,
)

/** Caricamento e validazione strutturale dei file dei casi. Ogni anomalia e' una [BenchException]. */
internal object BenchCases {
    private const val COMPILE_RESOURCE = "ai-bench/cases.json"
    private const val ACT_RESOURCE = "ai-bench/act-cases.json"
    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val PACKAGE_SHAPE = Regex("^[a-z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    private val CASE_KEYS = setOf("id", "category", "lang", "text", "manifest", "expect", "covers", "extra_apps", "note")
    private val ACT_KEYS = setOf(
        "id", "lang", "goal", "web", "context_sources", "expect_number", "must_contain_any", "note",
    )
    private val EXPECT_KEYS = setOf(
        "outcome", "trigger", "contact", "cron_fires", "cron_silent", "at_datetime", "actions_include",
        "actions_exclude", "actions_text", "params", "vars", "vars_exclude", "control_flow", "conditions_include",
        "conditions_params", "conditions_text", "validator_errors", "reply_mentions",
    )
    private val AT_KEYS = setOf("days_from_today", "month_day", "time")

    private val json = Json

    fun loadCompile(): List<BenchCase> = parseCompile(readResource(COMPILE_RESOURCE))

    fun loadAct(): List<ActCase> = parseAct(readResource(ACT_RESOURCE))

    private fun readResource(path: String): String {
        val stream = BenchCases::class.java.classLoader?.getResourceAsStream(path)
            ?: throw BenchException("risorsa mancante: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun parseCompile(text: String): List<BenchCase> {
        val array = parseArray(text, "cases.json")
        val ids = HashSet<String>()
        return array.mapIndexed { index, element ->
            val where = "cases.json[$index]"
            val obj = element as? JsonObject ?: throw BenchException("$where: atteso un oggetto")
            val case = parseCase(obj, where)
            if (!ids.add(case.id)) throw BenchException("$where: id duplicato '${case.id}'")
            case
        }
    }

    fun parseAct(text: String): List<ActCase> {
        val array = parseArray(text, "act-cases.json")
        val ids = HashSet<String>()
        return array.mapIndexed { index, element ->
            val where = "act-cases.json[$index]"
            val obj = element as? JsonObject ?: throw BenchException("$where: atteso un oggetto")
            val case = parseActCase(obj, where)
            if (!ids.add(case.id)) throw BenchException("$where: id duplicato '${case.id}'")
            case
        }
    }

    private fun parseArray(text: String, name: String): JsonArray {
        val root = try {
            json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw BenchException("$name: JSON non valido (${error.javaClass.simpleName})", error)
        }
        return root as? JsonArray ?: throw BenchException("$name: atteso un array di casi")
    }

    private fun parseCase(obj: JsonObject, where: String): BenchCase {
        obj.checkKeys(CASE_KEYS, where)
        val id = obj.str("id", where)
        if (!ID_PATTERN.matches(id)) throw BenchException("$where: id '$id' non valido")
        val at = "$where($id)"
        val category = obj.str("category", at)
        if (category.isBlank()) throw BenchException("$at: category vuota")
        val lang = obj.str("lang", at)
        if (lang !in BenchVocab.LANGS) throw BenchException("$at: lang '$lang' non ammessa")
        val text = obj.str("text", at)
        if (text.isBlank() || text.length > 2_000) throw BenchException("$at: text vuoto o troppo lungo")
        val manifestWire = obj.optStr("manifest", at) ?: ManifestKind.FULL.wire
        val manifest = ManifestKind.parse(manifestWire)
            ?: throw BenchException("$at: manifest '$manifestWire' non ammesso")
        val expect = obj["expect"] as? JsonObject ?: throw BenchException("$at: manca 'expect'")
        val outcomes = parseOutcomes(expect, at)
        val draft = parseDraftExpectation(expect, outcomes, at)
        val covers = obj.strList("covers", at)
        covers.forEach { entry ->
            val valid = entry in BenchVocab.ACTION_TYPES ||
                (entry.startsWith(BenchVocab.COVERS_TRIGGER) && entry.length > BenchVocab.COVERS_TRIGGER.length) ||
                (entry.startsWith(BenchVocab.COVERS_TOOL) && entry.length > BenchVocab.COVERS_TOOL.length)
            if (!valid) throw BenchException("$at: covers '$entry' non valido")
        }
        val replyMentions = expect.strList("reply_mentions", at)
        replyMentions.forEach { pattern ->
            try {
                Regex(pattern)
            } catch (error: Exception) {
                throw BenchException("$at: regex non valida in reply_mentions '$pattern'", error)
            }
        }
        return BenchCase(
            id = id,
            category = category,
            lang = lang,
            text = text,
            manifest = manifest,
            outcomes = outcomes,
            draft = draft,
            covers = covers,
            extraApps = parseExtraApps(obj["extra_apps"], at),
            replyMentions = replyMentions,
            note = obj.optStr("note", at),
        )
    }

    private fun parseExtraApps(element: JsonElement?, at: String): List<InstalledAppCandidate> {
        if (element == null) return emptyList()
        val array = element as? JsonArray ?: throw BenchException("$at: extra_apps deve essere un array")
        return array.mapIndexed { index, item ->
            val where = "$at.extra_apps[$index]"
            val app = item as? JsonObject ?: throw BenchException("$where: atteso un oggetto")
            app.checkKeys(setOf("label", "package"), where)
            val pkg = app.str("package", where)
            if (!PACKAGE_SHAPE.matches(pkg)) throw BenchException("$where: package malformato '$pkg'")
            val label = app.str("label", where)
            if (label.isBlank()) throw BenchException("$where: etichetta vuota")
            InstalledAppCandidate(label, pkg)
        }
    }

    private fun parseOutcomes(expect: JsonObject, at: String): Set<Outcome> {
        val raw = expect["outcome"] ?: throw BenchException("$at: expect.outcome mancante")
        val wires = when (raw) {
            is JsonPrimitive -> if (raw.isString) listOf(raw.content) else emptyList()
            is JsonArray -> raw.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content.orEmpty() }
            else -> emptyList()
        }
        if (wires.isEmpty()) throw BenchException("$at: expect.outcome non valido")
        return wires.mapTo(LinkedHashSet()) { wire ->
            Outcome.parse(wire) ?: throw BenchException("$at: outcome '$wire' non ammesso")
        }
    }

    private fun parseDraftExpectation(expect: JsonObject, outcomes: Set<Outcome>, at: String): DraftExpectation {
        expect.checkKeys(EXPECT_KEYS, "$at.expect")
        expect["validator_errors"]?.let { value ->
            if (value !is JsonArray || value.isNotEmpty()) {
                throw BenchException("$at: validator_errors deve essere assente o un array vuoto")
            }
        }
        val trigger = (expect["trigger"] as? JsonObject).orEmptyObject(expect, "trigger", at)
        trigger.forEach { (key, matcher) -> JsonMatch.validate(matcher, "$at.expect.trigger.$key") }
        (trigger["type"] as? JsonPrimitive)?.let { type ->
            if (!type.isString || type.content !in BenchVocab.TRIGGER_TYPES) {
                throw BenchException("$at: expect.trigger.type '${type.content}' non e' un trigger del dominio")
            }
        }
        val params = matcherMap(expect, "params", at)
        params.keys.forEach { path -> requireRoot(path, BenchVocab.ACTION_TYPES, "$at.expect.params") }
        val conditionsParams = matcherMap(expect, "conditions_params", at)
        conditionsParams.keys.forEach { path ->
            requireRoot(path, BenchVocab.CONDITION_TYPES, "$at.expect.conditions_params")
        }
        val draft = DraftExpectation(
            trigger = trigger,
            contact = expect.optStr("contact", at),
            cronFires = expect.strList("cron_fires", at).map { parseLocalDateTime(it, "$at.expect.cron_fires") },
            cronSilent = expect.strList("cron_silent", at).map { parseLocalDateTime(it, "$at.expect.cron_silent") },
            at = parseAtExpectation(expect["at_datetime"], at),
            actionsInclude = expect.strList("actions_include", at),
            actionsExclude = expect.strList("actions_exclude", at),
            actionsText = expect.strList("actions_text", at),
            params = params,
            vars = expect.strList("vars", at),
            varsExclude = expect.strList("vars_exclude", at),
            controlFlow = expect.strList("control_flow", at),
            conditionsInclude = expect.strList("conditions_include", at),
            conditionsParams = conditionsParams,
            conditionsText = expect.strList("conditions_text", at),
        )
        checkAlternatives(draft.actionsInclude, BenchVocab.ACTION_TYPES, "$at.expect.actions_include")
        checkAlternatives(draft.actionsExclude, BenchVocab.ACTION_TYPES, "$at.expect.actions_exclude")
        checkAlternatives(draft.vars, BenchVocab.VAR_TYPES, "$at.expect.vars")
        checkAlternatives(draft.varsExclude, BenchVocab.VAR_TYPES, "$at.expect.vars_exclude")
        checkAlternatives(draft.controlFlow, BenchVocab.FLOW_TYPES, "$at.expect.control_flow")
        checkAlternatives(draft.conditionsInclude, BenchVocab.CONDITION_TYPES, "$at.expect.conditions_include")
        (draft.actionsText + draft.conditionsText).forEach { pattern ->
            try {
                Regex(pattern)
            } catch (error: Exception) {
                throw BenchException("$at: regex non valida '$pattern'", error)
            }
        }
        val draftAllowed = Outcome.DRAFT in outcomes
        if (draftAllowed) {
            if (draft.trigger["type"] == null) throw BenchException("$at: un caso con bozza richiede expect.trigger.type")
            if (draft.actionsInclude.isEmpty() && draft.controlFlow.isEmpty()) {
                throw BenchException("$at: un caso con bozza richiede actions_include o control_flow")
            }
            val overlap = draft.actionsInclude.filter { '|' !in it }.toSet() intersect draft.actionsExclude.toSet()
            if (overlap.isNotEmpty()) throw BenchException("$at: azioni sia incluse sia escluse: $overlap")
        } else if (!draft.isEmpty) {
            throw BenchException("$at: aspettative sulla bozza senza 'draft' tra gli esiti")
        }
        if (draft.contact != null && draft.contact !in BenchWorld.CONTACT_NAMES) {
            throw BenchException("$at: contatto '${draft.contact}' non in whitelist del banco")
        }
        if ((draft.cronFires.isNotEmpty() || draft.cronSilent.isNotEmpty()) &&
            draft.trigger["cron"] == null
        ) {
            throw BenchException("$at: cron_fires/cron_silent richiedono expect.trigger.cron")
        }
        return draft
    }

    private fun JsonObject?.orEmptyObject(expect: JsonObject, key: String, at: String): Map<String, JsonElement> {
        if (expect[key] != null && this == null) throw BenchException("$at: expect.$key deve essere un oggetto")
        return this ?: emptyMap()
    }

    private fun matcherMap(expect: JsonObject, key: String, at: String): Map<String, JsonElement> {
        val value = expect[key] ?: return emptyMap()
        val obj = value as? JsonObject ?: throw BenchException("$at: expect.$key deve essere un oggetto")
        obj.forEach { (path, matcher) -> JsonMatch.validate(matcher, "$at.expect.$key.$path") }
        return obj
    }

    private fun requireRoot(path: String, allowed: Set<String>, where: String) {
        val root = path.substringBefore('.')
        if ('.' !in path || root.isEmpty() || path.substringAfter('.').isEmpty()) {
            throw BenchException("$where: percorso '$path' deve essere tipo.campo")
        }
        if (root !in allowed) throw BenchException("$where: '$root' non e' un tipo del dominio")
    }

    private fun checkAlternatives(entries: List<String>, allowed: Set<String>, where: String) {
        entries.forEach { entry ->
            entry.split('|').forEach { alternative ->
                if (alternative !in allowed) throw BenchException("$where: '$alternative' non e' del vocabolario")
            }
        }
    }

    private fun parseLocalDateTime(value: String, where: String): LocalDateTime = try {
        LocalDateTime.parse(value)
    } catch (error: Exception) {
        throw BenchException("$where: data/ora '$value' non valida", error)
    }

    private fun parseAtExpectation(element: JsonElement?, at: String): AtExpectation? {
        if (element == null) return null
        val obj = element as? JsonObject ?: throw BenchException("$at: expect.at_datetime deve essere un oggetto")
        obj.checkKeys(AT_KEYS, "$at.expect.at_datetime")
        val days = obj["days_from_today"]?.let {
            (it as? JsonPrimitive)?.intOrNull ?: throw BenchException("$at: days_from_today non intero")
        }
        val monthDay = obj.optStr("month_day", at)?.let {
            try {
                MonthDay.parse("--$it")
            } catch (error: Exception) {
                throw BenchException("$at: month_day '$it' non valido (atteso MM-dd)", error)
            }
        }
        val time = obj.optStr("time", at)?.let {
            try {
                LocalTime.parse(it, DateTimeFormatter.ofPattern("HH:mm"))
            } catch (error: Exception) {
                throw BenchException("$at: time '$it' non valido (atteso HH:mm)", error)
            }
        }
        if (days == null && monthDay == null && time == null) {
            throw BenchException("$at: at_datetime vuoto")
        }
        return AtExpectation(days, monthDay, time)
    }

    private fun parseActCase(obj: JsonObject, where: String): ActCase {
        obj.checkKeys(ACT_KEYS, where)
        val id = obj.str("id", where)
        if (!ID_PATTERN.matches(id)) throw BenchException("$where: id '$id' non valido")
        val at = "$where($id)"
        val lang = obj.str("lang", at)
        if (lang !in BenchVocab.LANGS) throw BenchException("$at: lang '$lang' non ammessa")
        val goal = obj.str("goal", at)
        if (goal.isBlank() || goal.length > 1_000) throw BenchException("$at: goal vuoto o troppo lungo")
        val web = obj["web"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull
                ?: throw BenchException("$at: web deve essere booleano")
        } ?: false
        val expectNumber = obj["expect_number"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull
                ?: throw BenchException("$at: expect_number deve essere booleano")
        } ?: false
        val sources = obj.strList("context_sources", at)
        sources.forEach { source ->
            if (source != "state") throw BenchException("$at: context_sources ammette solo 'state' per il sink notifica")
        }
        if (sources.size != sources.toSet().size) throw BenchException("$at: context_sources duplicate")
        return ActCase(
            id = id,
            lang = lang,
            goal = goal,
            web = web,
            contextSources = sources,
            expectNumber = expectNumber,
            mustContainAny = obj.strList("must_contain_any", at),
            note = obj.optStr("note", at),
        )
    }

    private fun JsonObject.checkKeys(allowed: Set<String>, where: String) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) throw BenchException("$where: chiavi sconosciute $unknown")
    }

    private fun JsonObject.str(key: String, where: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BenchException("$where: campo '$key' mancante o non testuale")

    private fun JsonObject.optStr(key: String, where: String): String? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BenchException("$where: campo '$key' non testuale")
    }

    private fun JsonObject.strList(key: String, where: String): List<String> {
        val value = this[key] ?: return emptyList()
        val array = value as? JsonArray ?: throw BenchException("$where: '$key' deve essere un array di testi")
        return array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw BenchException("$where: '$key' contiene un elemento non testuale")
        }
    }
}

/**
 * Matcher JSON dei casi. Forme ammesse per il matcher:
 * - primitivo: uguaglianza (testo senza maiuscole/accenti, numero, booleano, `null` = assente);
 * - array: una qualsiasi delle alternative;
 * - oggetto con UN operatore: `present`, `contains` (testo o tutti i testi di un array), `contains_any`,
 *   `range` ([min, max] inclusi), `regex`, `not`, `any`.
 */
internal object JsonMatch {
    fun validate(matcher: JsonElement, where: String) {
        when (matcher) {
            is JsonNull -> Unit
            is JsonPrimitive -> Unit
            is JsonArray -> {
                if (matcher.isEmpty()) throw BenchException("$where: array di alternative vuoto")
                matcher.forEach { validate(it, where) }
            }
            is JsonObject -> validateOperator(matcher, where)
        }
    }

    private fun validateOperator(matcher: JsonObject, where: String) {
        if (matcher.size != 1) throw BenchException("$where: un matcher oggetto ha esattamente un operatore")
        val (op, arg) = matcher.entries.single()
        when (op) {
            "present" -> if ((arg as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null) {
                throw BenchException("$where: present vuole un booleano")
            }
            "contains" -> if (!isText(arg) && !isTextArray(arg)) {
                throw BenchException("$where: contains vuole un testo o un array di testi")
            }
            "contains_any" -> if (!isTextArray(arg)) throw BenchException("$where: contains_any vuole un array di testi")
            "range" -> {
                val numbers = (arg as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
                if (numbers == null || numbers.size != 2 || numbers.any { it == null } ||
                    numbers[0]!! > numbers[1]!!
                ) {
                    throw BenchException("$where: range vuole [min, max]")
                }
            }
            "regex" -> {
                if (!isText(arg)) throw BenchException("$where: regex vuole un testo")
                try {
                    Regex((arg as JsonPrimitive).content)
                } catch (error: Exception) {
                    throw BenchException("$where: regex non valida", error)
                }
            }
            "not" -> validate(arg, where)
            "any" -> {
                if (arg !is JsonArray || arg.isEmpty()) throw BenchException("$where: any vuole un array non vuoto")
                arg.forEach { validate(it, where) }
            }
            else -> throw BenchException("$where: operatore '$op' sconosciuto")
        }
    }

    private fun isText(element: JsonElement) = element is JsonPrimitive && element.isString
    private fun isTextArray(element: JsonElement) =
        element is JsonArray && element.isNotEmpty() && element.all { isText(it) }

    fun matches(actual: JsonElement?, matcher: JsonElement): Boolean = when (matcher) {
        is JsonNull -> actual == null || actual is JsonNull
        is JsonPrimitive -> primitiveMatches(actual, matcher)
        is JsonArray -> matcher.any { matches(actual, it) }
        is JsonObject -> operatorMatches(actual, matcher)
    }

    private fun primitiveMatches(actual: JsonElement?, matcher: JsonPrimitive): Boolean {
        val value = actual as? JsonPrimitive ?: return false
        if (value is JsonNull) return false
        return when {
            matcher.isString -> BenchText.normalize(value.content) == BenchText.normalize(matcher.content)
            matcher.booleanOrNull != null -> value.booleanOrNull == matcher.booleanOrNull
            else -> {
                val left = value.doubleOrNull
                val right = matcher.doubleOrNull
                left != null && right != null && left == right
            }
        }
    }

    private fun operatorMatches(actual: JsonElement?, matcher: JsonObject): Boolean {
        val (op, arg) = matcher.entries.single()
        val present = actual != null && actual !is JsonNull
        return when (op) {
            "present" -> present == ((arg as JsonPrimitive).booleanOrNull == true)
            "contains" -> present && containsAll(textOf(actual!!), arg)
            "contains_any" -> present && (arg as JsonArray).any { part ->
                BenchText.normalize(textOf(actual!!)).contains(BenchText.normalize((part as JsonPrimitive).content))
            }
            "range" -> {
                val number = (actual as? JsonPrimitive)?.doubleOrNull
                val bounds = (arg as JsonArray).map { (it as JsonPrimitive).doubleOrNull!! }
                number != null && number >= bounds[0] && number <= bounds[1]
            }
            "regex" -> present &&
                Regex((arg as JsonPrimitive).content, RegexOption.IGNORE_CASE).containsMatchIn(textOf(actual!!))
            "not" -> !matches(actual, arg)
            "any" -> (arg as JsonArray).any { matches(actual, it) }
            else -> false
        }
    }

    private fun containsAll(text: String, arg: JsonElement): Boolean {
        val haystack = BenchText.normalize(text)
        val needles = when (arg) {
            is JsonArray -> arg.map { (it as JsonPrimitive).content }
            else -> listOf((arg as JsonPrimitive).content)
        }
        return needles.all { haystack.contains(BenchText.normalize(it)) }
    }

    private fun textOf(element: JsonElement): String =
        if (element is JsonPrimitive) element.content else element.toString()
}
