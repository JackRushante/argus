@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.argus.automation.bench

import dev.argus.engine.model.Action
import dev.argus.engine.model.Condition
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
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
            // 0.4.1: trigger senza Shizuku.
            "screen" to triggers { type(it) == "screen" },
            "battery" to triggers { type(it) == "battery" },
            "audio output" to triggers { type(it) == "audio_output" },
            "mode changed" to triggers { type(it) == "mode_changed" },
            "media" to triggers { type(it) == "media" },
            "manual" to triggers { type(it) == "manual" },
            "missed call" to triggers { type(it) == "phone_state" && text(it, "event") == "MISSED_CALL" },
            "sim filter" to triggers { type(it) == "phone_state" && it["sim"] != null },
            // 0.4.1: alba e tramonto, come variante del trigger time.
            "time solar" to triggers { type(it) == "time" && it["solar"] != null && !it["solar"].isAbsentMatcher() },
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
    fun `the bench phone has about twenty common apps with real packages in both languages`() = runBlocking<Unit> {
        val world = BenchWorld.create()
        val english = world.installedApps("en")
        val italian = world.installedApps("it")

        assertTrue(english.size in 18..25, "app installate: ${english.size}")
        assertEquals(english.map { it.packageName }, italian.map { it.packageName }, "stesse app, etichette nella lingua")
        assertEquals(english.size, english.map { it.packageName }.toSet().size, "pacchetti duplicati")
        val required = mapOf(
            "Maps" to "com.google.android.apps.maps",
            "WhatsApp" to "com.whatsapp",
            "Telegram" to "org.telegram.messenger",
            "Spotify" to "com.spotify.music",
            "YouTube" to "com.google.android.youtube",
            "Chrome" to "com.android.chrome",
            "Gmail" to "com.google.android.gm",
            "Calendar" to "com.google.android.calendar",
            "Camera" to "com.google.android.GoogleCamera",
            "Settings" to "com.android.settings",
            "Instagram" to "com.instagram.android",
            "Facebook" to "com.facebook.katana",
            "Netflix" to "com.netflix.mediaclient",
            "Amazon" to "com.amazon.mShop.android.shopping",
        )
        required.forEach { (label, pkg) ->
            assertTrue(english.any { it.label == label && it.packageName == pkg }, "manca $label ($pkg)")
        }
        assertTrue(italian.any { it.label == "Calcolatrice" && it.packageName == "com.google.android.calculator" })
        assertTrue(italian.any { it.label == "Impostazioni" && it.packageName == "com.android.settings" })
        val shape = Regex("^[a-z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        assertTrue(english.all { shape.matches(it.packageName) }, "pacchetto malformato")
        assertFailsWith<BenchException> { world.installedApps("fr") }
    }

    @Test
    fun `every case that names an app gets that app among the candidates the model sees`() = runBlocking<Unit> {
        val world = BenchWorld.create()
        var checked = 0
        for (case in cases) {
            val known = world.installedApps(case.lang).map { it.packageName }.toSet()
            val expected = (
                plainTexts(case.draft.params["launch_app.pkg"]) +
                    plainTexts(case.draft.conditionsParams["app_in_foreground.pkg"]) +
                    plainTexts(case.draft.trigger["pkg"])
                ).filter { it in known }
            if (expected.isEmpty()) continue
            val prompt = world.compilePrompt(case.text, case.lang)
            expected.forEach { pkg ->
                assertTrue("\"package\":\"$pkg\"" in prompt, "${case.id}: il candidato $pkg non arriva al modello:\n$prompt")
            }
            assertTrue(prompt.endsWith(case.text), case.id)
            checked++
        }
        assertTrue(checked >= 10, "casi con un'app attesa: $checked")
        // Senza app nominate la richiesta arriva nuda, con quelle nominate no.
        assertEquals("Turn off Wi-Fi in one minute", world.compilePrompt("Turn off Wi-Fi in one minute", "en"))
        assertTrue("installed_app_candidates" in world.compilePrompt("Open Instagram", "en"))
    }

    @Test
    fun `the manifest of a case speaks its language and publishes only the state keys that can be armed`() = runBlocking<Unit> {
        val world = BenchWorld.create()

        assertEquals("it", world.manifest(ManifestKind.FULL, "it").replyLanguage)
        assertEquals("en", world.manifest(ManifestKind.NO_SHIZUKU, "en").replyLanguage)
        // Il resto del manifest e' quello del probe vero, senza altre differenze.
        assertEquals(world.manifest(ManifestKind.FULL), world.manifest(ManifestKind.FULL, "en"))
        assertEquals(StateKeys.ALL, world.manifest(ManifestKind.FULL).stateKeys)
        // 0.4.0 (F19): senza Shizuku le chiavi di stato si leggono con le API pubbliche (il telefono
        // di prova ha anche il permesso Bluetooth), quindi il manifest le pubblica tutte.
        assertEquals(StateKeys.ALL, world.manifest(ManifestKind.NO_SHIZUKU).stateKeys)
        assertFailsWith<BenchException> { world.manifest(ManifestKind.FULL, "fr") }
    }

    @Test
    fun `places that cannot be resolved accept a clarification or a geofence from the current position`() {
        val places = cases.filter { it.id.startsWith("clar.place.") }
        assertTrue(places.size >= 2)
        places.forEach { case ->
            assertEquals(setOf(Outcome.CLARIFICATION, Outcome.DRAFT), case.outcomes, case.id)
            assertEquals(JsonPrimitive("geofence"), case.draft.trigger["type"], case.id)
            assertEquals(JsonPrimitive(true), case.draft.trigger["resolveCurrentLocation"], case.id)
        }
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
    fun `expected fields and enum values exist on the domain types`() {
        val actionFields = fieldsOf(Action.serializer())
        val triggerFields = fieldsOf(Trigger.serializer())
        val conditionFields = fieldsOf(Condition.serializer())

        fun checkField(
            id: String,
            fields: Map<String, SerialDescriptor>,
            owner: String,
            field: String,
            matcher: JsonElement,
        ) {
            val known = fields[owner] ?: error("$id: tipo sconosciuto '$owner'")
            val index = known.elementNames.indexOf(field)
            assertTrue(index >= 0, "$id: '$owner' non ha il campo '$field' (campi: ${known.elementNames.toList()})")
            val descriptor = known.getElementDescriptor(index)
            if (descriptor.kind == SerialKind.ENUM) {
                val allowed = descriptor.elementNames.map { it.lowercase() }.toSet()
                plainTexts(matcher).forEach { value ->
                    assertTrue(value.lowercase() in allowed, "$id: '$owner.$field' = '$value' non e' un valore valido ($allowed)")
                }
            }
        }

        for (case in cases) {
            val triggerType = (case.draft.trigger["type"] as? JsonPrimitive)?.content
            case.draft.trigger.forEach { (field, matcher) ->
                if (field != "type" && triggerType != null) {
                    checkField(case.id, triggerFields, triggerType, field, matcher)
                }
            }
            case.draft.params.forEach { (path, matcher) ->
                val segments = path.split('.')
                checkField(case.id, actionFields, segments[0], segments[1], matcher)
            }
            case.draft.conditionsParams.forEach { (path, matcher) ->
                val segments = path.split('.')
                checkField(case.id, conditionFields, segments[0], segments[1], matcher)
            }
        }
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

    /** Per ogni sottotipo sealed: nome serial -> descrittore (campi e loro tipi). */
    private fun fieldsOf(serializer: KSerializer<*>): Map<String, SerialDescriptor> {
        val container = serializer.descriptor.getElementDescriptor(1)
        return (0 until container.elementsCount).associate { index ->
            container.getElementName(index) to container.getElementDescriptor(index)
        }
    }

    /** Testi semplici di un matcher (primitivo o array di alternative): quelli confrontabili con un enum. */
    private fun plainTexts(matcher: JsonElement?): List<String> = when (matcher) {
        null -> emptyList()
        is JsonPrimitive -> if (matcher.isString) listOf(matcher.content) else emptyList()
        is JsonArray -> matcher.flatMap { plainTexts(it) }
        else -> emptyList()
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
            "screen", "battery", "audio_output", "mode_changed", "media" -> str("type")
            "manual" -> "manual"
            // Il filtro SIM e' una capability a parte: un caso che lo chiede deve trovarla nel manifest.
            "phone_state" -> if (trigger["sim"] != null) "phone_state.sim" else when (str("event")) {
                "SMS_RECEIVED" -> "phone_state.sms"
                "INCOMING_CALL", "CALL_ENDED", "MISSED_CALL" -> "phone_state.call"
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
