@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.argus.automation.bench

import dev.argus.engine.model.Action
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Validazione OFFLINE dei casi del banco (gira sempre, senza rete): un caso scritto male si scopre
 * nei gate normali, non durante una corsa da costare token.
 */
class AiBenchCasesTest {
    @Test
    fun `case files load and ids are unique across both files`() {
        val ids = cases.map { it.id } + acts.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "id duplicati tra cases.json e act-cases.json")
        assertTrue(cases.size >= 110, "servono circa 120 casi di compile, trovati ${cases.size}")
    }

    @Test
    fun `languages are balanced and texts are in the declared language`() {
        val italian = cases.count { it.lang == "it" }
        val english = cases.count { it.lang == "en" }
        assertTrue(italian * 100 >= cases.size * 40, "troppo pochi casi italiani: $italian/${cases.size}")
        assertTrue(english * 100 >= cases.size * 40, "troppo pochi casi inglesi: $english/${cases.size}")
        cases.forEach { case ->
            assertTrue(LanguageCheck.looksLike(case.lang, case.text), "${case.id}: il testo non sembra '${case.lang}'")
        }
        acts.forEach { act ->
            assertTrue(LanguageCheck.looksLike(act.lang, act.goal), "${act.id}: l'obiettivo non sembra '${act.lang}'")
        }
        assertTrue(acts.any { it.lang == "it" } && acts.any { it.lang == "en" }, "act: servono entrambe le lingue")
    }

    @Test
    fun `minimum coverage per outcome and manifest`() {
        fun count(category: String) = cases.count { it.category == category }
        assertTrue(count("clarification") >= 8, "chiarimenti: ${count("clarification")}")
        assertTrue(count("unsupported") >= 6, "funzioni non supportate: ${count("unsupported")}")
        assertTrue(count("limit") >= 4, "limiti: ${count("limit")}")
        assertTrue(count("p4") >= 8, "programmi P4: ${count("p4")}")
        assertTrue(count("condition") >= 6, "condizioni: ${count("condition")}")
        assertTrue(cases.count { it.manifest == ManifestKind.NO_SHIZUKU } >= 10, "casi senza Shizuku")
        val multi = cases.count { case -> case.draft.actionsInclude.count { '|' !in it } >= 2 }
        assertTrue(multi >= 25, "richieste con 2-3 azioni insieme: $multi")
        // Le categorie di esito devono coincidere con l'esito atteso principale.
        cases.filter { it.category == "clarification" }.forEach {
            assertTrue(Outcome.CLARIFICATION in it.outcomes || Outcome.NO_DRAFT in it.outcomes, it.id)
        }
        cases.filter { it.category == "unsupported" }.forEach { assertTrue(Outcome.UNSUPPORTED in it.outcomes, it.id) }
        cases.filter { it.category == "limit" }.forEach { assertTrue(Outcome.LIMIT in it.outcomes, it.id) }
    }

    @Test
    fun `every trigger kind is covered`() {
        fun triggers(predicate: (Map<String, JsonElement>) -> Boolean) =
            cases.count { Outcome.DRAFT in it.outcomes && predicate(it.draft.trigger) }
        fun type(t: Map<String, JsonElement>) = (t["type"] as? JsonPrimitive)?.content
        fun text(t: Map<String, JsonElement>, key: String) = (t[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val required = mapOf(
            "time cron" to triggers { type(it) == "time" && it["cron"].isPresentMatcher() },
            "time at" to triggers { type(it) == "time" && it["at"].isPresentMatcher() },
            "time afterMs" to triggers { type(it) == "time" && it["afterMs"] != null && !it["afterMs"].isAbsentMatcher() },
            "time exact" to triggers { type(it) == "time" && text(it, "precision") == "EXACT" },
            "immediate" to triggers { type(it) == "immediate" },
            "notification" to triggers { type(it) == "notification" },
            "geofence" to triggers { type(it) == "geofence" },
            "wifi" to triggers { type(it) == "connectivity" && text(it, "medium") == "WIFI" },
            "wifi ssid" to triggers { type(it) == "connectivity" && text(it, "medium") == "WIFI" && it["match"] is JsonObject && !it["match"].isAbsentMatcher() },
            "bluetooth" to triggers { type(it) == "connectivity" && text(it, "medium") == "BT" },
            "power" to triggers { type(it) == "connectivity" && text(it, "medium") == "POWER" },
            "sensor" to triggers { type(it) == "sensor" },
            "sms" to triggers { type(it) == "phone_state" && text(it, "event") == "SMS_RECEIVED" },
            "call" to triggers { type(it) == "phone_state" && text(it, "event") != "SMS_RECEIVED" },
        )
        required.forEach { (name, found) -> assertTrue(found >= 2, "trigger '$name' coperto $found volte, ne servono 2") }
        val contactCases = cases.count { it.draft.contact != null }
        assertTrue(contactCases >= 2, "notifiche con contatto in whitelist: $contactCases")
        val aliasFreeSender = cases.count { c ->
            Outcome.DRAFT in c.outcomes && c.draft.trigger["sender"] != null
        }
        assertTrue(aliasFreeSender >= 1, "notifiche con mittente per nome: $aliasFreeSender")
        val conditions = cases.flatMap { it.draft.conditionsInclude }.flatMap { it.split('|') }
        assertTrue(conditions.count { it == "time_window" } >= 2, "condizioni di fascia oraria")
        assertTrue(conditions.count { it == "app_in_foreground" } >= 2, "condizioni di app in primo piano")
        assertTrue(conditions.count { it == "state_equals" || it == "state_compare" } >= 2, "condizioni di stato")
    }

    @Test
    fun `every action of the catalog is covered at least twice`() {
        val covered = HashMap<String, Int>()
        for (case in cases) {
            val entries = case.draft.actionsInclude.filter { '|' !in it } +
                case.draft.controlFlow.filter { '|' !in it } +
                case.covers.filter { it in BenchVocab.ACTION_TYPES }
            entries.toSet().forEach { covered[it] = (covered[it] ?: 0) + 1 }
        }
        val thin = BenchVocab.ACTION_TYPES.filter { (covered[it] ?: 0) < 2 }
        assertTrue(thin.isEmpty(), "azioni coperte meno di 2 volte: ${thin.map { it to (covered[it] ?: 0) }}")
        // invoke_llm con e senza web, invoke_llm_v2 con stato.
        val withWeb = cases.count { c -> c.draft.params.any { (k, v) -> k == "invoke_llm.allowedTools" && v.toString().contains("web.search") } }
        assertTrue(withWeb >= 2, "invoke_llm con web: $withWeb")
        val withoutWeb = cases.count { c ->
            c.draft.params.any { (k, v) -> k == "invoke_llm.allowedTools" && v.toString().contains("not") }
        }
        assertTrue(withoutWeb >= 1, "invoke_llm senza web: $withoutWeb")
        val v2 = cases.count { c -> c.draft.params.keys.any { it == "invoke_llm_v2.stateContext" } }
        assertTrue(v2 >= 2, "invoke_llm_v2 con stato: $v2")
    }

    @Test
    fun `expectations agree with the capabilities the real probe publishes`() = runBlocking {
        val world = BenchWorld.create()
        for (case in cases) {
            val manifest = world.manifest(case.manifest)
            if (case.outcomes == setOf(Outcome.DRAFT)) {
                // Un caso che pretende SOLO una bozza non puo' chiedere cio' che il telefono non pubblica.
                case.draft.actionsInclude.forEach { entry ->
                    assertTrue(
                        entry.split('|').any { it in manifest.availableTools },
                        "${case.id}: l'azione '$entry' non e' in available_tools del manifest ${case.manifest.wire}",
                    )
                }
                triggerWire(case.draft.trigger)?.let { wire ->
                    assertTrue(
                        wire in manifest.availableTriggers,
                        "${case.id}: il trigger '$wire' non e' armabile nel manifest ${case.manifest.wire}",
                    )
                }
            }
            if (Outcome.DRAFT !in case.outcomes && case.covers.isNotEmpty()) {
                assertTrue(
                    case.covers.any { cover -> isUnavailable(cover, manifest.availableTools, manifest.availableTriggers) },
                    "${case.id}: nulla di quanto elencato in covers e' davvero indisponibile nel manifest ${case.manifest.wire}",
                )
            }
            if (case.category == "unsupported") {
                assertTrue(case.covers.isNotEmpty(), "${case.id}: un caso 'unsupported' deve dire cosa manca (covers)")
            }
        }
        // Il telefono di prova ha le azioni che i casi si aspettano di trovare o di non trovare.
        val full = world.manifest(ManifestKind.FULL)
        val noShizuku = world.manifest(ManifestKind.NO_SHIZUKU)
        assertTrue("set_wifi" in full.availableTools && "set_wifi" !in noShizuku.availableTools)
        assertTrue("set_dnd" in noShizuku.availableTools, "DND e' del tier base")
        assertTrue("tap" !in full.availableTools && "input_text" !in full.availableTools)
        assertTrue("sensor.significant_motion" in full.availableTriggers)
        assertEquals(world.contacts.size, full.whitelistedContacts.size)
    }

    @Test
    fun `contacts named by the cases exist in the bench whitelist`() {
        cases.mapNotNull { it.draft.contact }.forEach { name ->
            assertTrue(name in BenchWorld.CONTACT_NAMES, "contatto sconosciuto: $name")
        }
    }

    @Test
    fun `the two requests that failed on the phone are in the suite`() {
        val texts = cases.map { it.text }
        assertTrue("Spegni subito il non disturbare" in texts)
        assertTrue("Cerca adesso il prezzo del bitcoin e mandami una notifica" in texts)
    }

    @Test
    fun `the vocabulary tracks the domain model`() {
        assertEquals(variants(Action.serializer().descriptor), BenchVocab.ACTION_TYPES, "azioni")
        assertEquals(variants(Trigger.serializer().descriptor), BenchVocab.TRIGGER_TYPES, "trigger")
        assertEquals(variants(Condition.serializer().descriptor), BenchVocab.CONDITION_TYPES, "condizioni")
        assertEquals(variants(VarBinding.serializer().descriptor), BenchVocab.VAR_TYPES, "variabili")
    }

    @Test
    fun `generative cases are well formed`() {
        assertTrue(acts.size in 8..15, "casi act: ${acts.size}")
        assertTrue(acts.count { it.web } >= 3, "casi act con web")
        assertTrue(acts.count { !it.web } >= 3, "casi act senza web")
        assertTrue(acts.any { "state" in it.contextSources }, "almeno un caso act con contesto state")
        val priceWords = Regex("(?i)prezzo|price|cambio|exchange|costa|cost|gold|oro")
        acts.filter { priceWords.containsMatchIn(it.goal) }.forEach {
            assertTrue(it.expectNumber, "${it.id}: una domanda sui prezzi richiede expect_number")
        }
        acts.filter { it.web }.forEach { assertTrue(!it.goal.contains("http"), it.id) }
    }

    @Test
    fun `a malformed case is reported as a bench error`() {
        fun parse(json: String) = BenchCases.parseCompile("[$json]")
        val ok = """{"id":"x.ok.it.01","category":"c","lang":"it","text":"Spegni subito il non disturbare",
            "expect":{"outcome":"draft","trigger":{"type":"immediate"},"actions_include":["set_dnd"]}}"""
        assertEquals(1, parse(ok).size)
        assertFailsWith<BenchException>("lingua sconosciuta") { parse(ok.replace("\"it\"", "\"fr\"")) }
        assertFailsWith<BenchException>("azione inventata") { parse(ok.replace("set_dnd", "set_moon")) }
        assertFailsWith<BenchException>("trigger inventato") { parse(ok.replace("immediate", "lunar")) }
        assertFailsWith<BenchException>("chiave sconosciuta") { parse(ok.replace("\"category\"", "\"categoria\"")) }
        assertFailsWith<BenchException>("esito sconosciuto") { parse(ok.replace("\"draft\"", "\"maybe\"")) }
        assertFailsWith<BenchException>("operatore sconosciuto") {
            parse(ok.replace("\"actions_include\"", "\"params\":{\"set_dnd.mode\":{\"almost\":1}},\"actions_include\""))
        }
        assertFailsWith<BenchException>("id duplicato") { BenchCases.parseCompile("[$ok,$ok]") }
        assertFailsWith<BenchException>("json rotto") { BenchCases.parseCompile("[{") }
        assertFailsWith<BenchException>("bozza senza trigger") {
            parse(ok.replace("\"trigger\":{\"type\":\"immediate\"},", ""))
        }
    }

    private fun variants(descriptor: SerialDescriptor): Set<String> =
        descriptor.getElementDescriptor(1).elementNames.toSet()

    private fun JsonElement?.isPresentMatcher(): Boolean =
        this is JsonObject && this["present"].let { it is JsonPrimitive && it.booleanOrNull == true }

    private fun JsonElement?.isAbsentMatcher(): Boolean =
        this is JsonObject && this["present"].let { it is JsonPrimitive && it.booleanOrNull == false }

    private fun isUnavailable(cover: String, tools: List<String>, triggers: List<String>): Boolean = when {
        cover.startsWith(BenchVocab.COVERS_TRIGGER) -> cover.removePrefix(BenchVocab.COVERS_TRIGGER) !in triggers
        cover.startsWith(BenchVocab.COVERS_TOOL) -> cover.removePrefix(BenchVocab.COVERS_TOOL) !in tools
        else -> cover !in tools
    }

    private fun triggerWire(trigger: Map<String, JsonElement>): String? {
        fun str(key: String) = (trigger[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (str("type")) {
            "time", "immediate", "geofence", "notification" -> str("type")
            "phone_state" -> when (str("event")) {
                "SMS_RECEIVED" -> "phone_state.sms"
                "INCOMING_CALL", "CALL_ENDED" -> "phone_state.call"
                else -> null
            }
            "connectivity" -> when (str("medium")) {
                "WIFI" -> if (trigger["match"].let { it is JsonObject && !it.isAbsentMatcher() }) {
                    "connectivity.wifi.identity"
                } else {
                    "connectivity.wifi"
                }
                "BT" -> "connectivity.bt"
                "POWER" -> "connectivity.power"
                else -> null
            }
            "sensor" -> str("kind")?.let { "sensor.$it" }
            else -> null
        }
    }

    private companion object {
        val cases: List<BenchCase> by lazy { BenchCases.loadCompile() }
        val acts: List<ActCase> by lazy { BenchCases.loadAct() }
    }
}
