package dev.argus.data.backup

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Trigger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RulesBackupCodecTest {
    private val goodRule =
        """{"enabled":true,"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[{"type":"set_wifi","on":true}]}}"""

    private fun file(
        vararg rules: String,
        format: String = "\"argus-rules\"",
        version: String = "1",
        extra: String = "",
    ) = """{"format":$format,"version":$version,"exportedAt":"2026-10-26T10:00:00Z","appVersion":"0.4.0"$extra,"rules":[${rules.joinToString(",")}]}"""

    private fun rejected(text: String): BackupFailure =
        assertIs<DecodedBackup.Rejected>(RulesBackupCodec.decode(text)).failure

    private fun entries(text: String): List<DecodedEntry> =
        assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(text)).entries

    private fun discardedReason(rule: String): RuleDiscardReason {
        val only = entries(file(rule)).single()
        return assertIs<DecodedEntry.Discarded>(only).reason
    }

    // --- formato -------------------------------------------------------------------------------

    @Test
    fun `the file has the documented envelope and one entry per rule`() {
        val text = RulesBackupCodec.encode(
            rules = listOf(BackupRule(true, BackupFixtures.simple("a")), BackupRule(false, BackupFixtures.simple("b"))),
            exportedAt = "2026-10-26T10:00:00Z",
            appVersion = "0.4.0",
        )

        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("format", "version", "exportedAt", "appVersion", "rules"), root.keys)
        assertEquals("argus-rules", root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        assertEquals("2026-10-26T10:00:00Z", root.getValue("exportedAt").jsonPrimitive.content)
        assertEquals("0.4.0", root.getValue("appVersion").jsonPrimitive.content)
        val rules = root.getValue("rules").jsonArray
        assertEquals(2, rules.size)
        assertEquals(true, rules[0].jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(false, rules[1].jsonObject.getValue("enabled").jsonPrimitive.boolean)
        rules.forEach { assertEquals(setOf("enabled", "draft"), it.jsonObject.keys) }
    }

    @Test
    fun `every trigger, condition and action type survives a write and a strict read unchanged`() {
        val drafts = buildList {
            BackupFixtures.allTriggers.forEach {
                add(AutomationDraft(name = "t", trigger = it, actions = listOf(Action.SetWifi(true))))
            }
            add(
                AutomationDraft(
                    name = "cond",
                    trigger = Trigger.Immediate,
                    actions = listOf(Action.SetWifi(true)),
                    conditions = Condition.And(BackupFixtures.leafConditions),
                ),
            )
            BackupFixtures.leafActions.forEach {
                add(AutomationDraft(name = "a", trigger = Trigger.Immediate, actions = listOf(it)))
            }
            add(BackupFixtures.rich())
        }

        val text = RulesBackupCodec.encode(drafts.map { BackupRule(true, it) }, "now", "0.4.0")
        val decoded = entries(text).map { assertIs<DecodedEntry.Rule>(it).rule.draft }

        assertEquals(drafts, decoded)
    }

    @Test
    fun `a byte order mark at the start of the file is ignored`() {
        assertEquals(1, entries("﻿" + file(goodRule)).size)
    }

    // --- rifiuto dell'intero file --------------------------------------------------------------

    @Test
    fun `text that is not JSON is rejected`() {
        assertEquals(BackupFailure.NOT_JSON, rejected("questo non è json"))
        assertEquals(BackupFailure.NOT_JSON, rejected(""))
        assertEquals(BackupFailure.NOT_JSON, rejected("""{"format":"argus-rules","version":1,"rules":["""))
    }

    @Test
    fun `JSON that is not an Argus export is rejected as the wrong format`() {
        assertEquals(BackupFailure.WRONG_FORMAT, rejected("[1,2,3]"))
        assertEquals(BackupFailure.WRONG_FORMAT, rejected("\"argus-rules\""))
        assertEquals(BackupFailure.WRONG_FORMAT, rejected("{}"))
        assertEquals(BackupFailure.WRONG_FORMAT, rejected(file(goodRule, format = "\"other\"")))
        assertEquals(BackupFailure.WRONG_FORMAT, rejected(file(goodRule, format = "1")))
        assertEquals(BackupFailure.WRONG_FORMAT, rejected(file(goodRule, format = "null")))
        assertEquals(
            BackupFailure.WRONG_FORMAT,
            rejected("""{"format":"argus-rules","version":1,"rules":{"a":1}}"""),
        )
        assertEquals(BackupFailure.WRONG_FORMAT, rejected("""{"format":"argus-rules","version":1}"""))
    }

    @Test
    fun `a version other than 1 is rejected`() {
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, rejected(file(goodRule, version = "2")))
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, rejected(file(goodRule, version = "0")))
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, rejected(file(goodRule, version = "\"1\"")))
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, rejected(file(goodRule, version = "1.5")))
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, rejected(file(goodRule, version = "null")))
        assertEquals(
            BackupFailure.UNSUPPORTED_VERSION,
            rejected("""{"format":"argus-rules","rules":[$goodRule]}"""),
        )
    }

    @Test
    fun `more than 500 rules are rejected and exactly 500 are read`() {
        assertEquals(BackupFailure.TOO_MANY_RULES, rejected(file(*Array(501) { goodRule })))
        assertEquals(500, entries(file(*Array(500) { goodRule })).size)
    }

    @Test
    fun `a file with no rules is rejected`() {
        assertEquals(BackupFailure.NO_RULES, rejected(file()))
    }

    @Test
    fun `nesting beyond the limit is rejected before the parser runs, whatever the shape`() {
        assertEquals(BackupFailure.NOT_JSON, rejected("[".repeat(100_000)))
        assertEquals(BackupFailure.NOT_JSON, rejected("{\"a\":".repeat(100_000)))
        val deepButValid = "[".repeat(RulesBackupFormat.MAX_NESTING) + "]".repeat(RulesBackupFormat.MAX_NESTING)
        // Dentro il limite arriva al parser: è JSON valido ma non un'esportazione.
        assertEquals(BackupFailure.WRONG_FORMAT, rejected(deepButValid))
    }

    @Test
    fun `brackets inside strings and escaped quotes do not count as nesting`() {
        val noise = "[".repeat(1_000) + "\\\"" + "{".repeat(1_000)
        assertFalse(RulesBackupCodec.exceedsNesting("""{"a":"$noise"}"""))
        assertTrue(RulesBackupCodec.exceedsNesting("[".repeat(RulesBackupFormat.MAX_NESTING + 1)))
        assertFalse(RulesBackupCodec.exceedsNesting("[".repeat(RulesBackupFormat.MAX_NESTING)))
    }

    // --- scarto della singola regola -----------------------------------------------------------

    @Test
    fun `a good rule is read with its enabled flag`() {
        val rule = assertIs<DecodedEntry.Rule>(entries(file(goodRule)).single()).rule
        assertTrue(rule.enabled)
        assertEquals("x", rule.draft.name)
        assertEquals(Trigger.Immediate, rule.draft.trigger)
        assertEquals(listOf<Action>(Action.SetWifi(true)), rule.draft.actions)
    }

    @Test
    fun `entries that are not a rule are discarded as malformed`() {
        listOf(
            "1",
            "\"x\"",
            "null",
            "[]",
            "{}",
            """{"enabled":true}""",
            """{"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[]}}""",
            // enabled non è un booleano
            """{"enabled":"true","draft":{"name":"x","trigger":{"type":"immediate"},"actions":[]}}""",
            """{"enabled":1,"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[]}}""",
            // la bozza non è un oggetto
            """{"enabled":true,"draft":"x"}""",
            """{"enabled":true,"draft":[]}""",
            // chiavi in più nella voce
            """{"enabled":true,"approved":true,"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[]}}""",
        ).forEach { rule ->
            assertEquals(RuleDiscardReason.MALFORMED_ENTRY, discardedReason(rule), rule)
        }
    }

    @Test
    fun `drafts that do not decode into the domain types are discarded as invalid`() {
        listOf(
            // tipo di trigger sconosciuto
            """{"enabled":true,"draft":{"name":"x","trigger":{"type":"teleport"},"actions":[{"type":"set_wifi","on":true}]}}""",
            // tipo di azione sconosciuto
            """{"enabled":true,"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[{"type":"format_phone"}]}}""",
            // campo sconosciuto nella bozza, nel trigger, nell'azione
            """{"enabled":true,"draft":{"name":"x","armed":true,"trigger":{"type":"immediate"},"actions":[{"type":"set_wifi","on":true}]}}""",
            """{"enabled":true,"draft":{"name":"x","trigger":{"type":"immediate","extra":1},"actions":[{"type":"set_wifi","on":true}]}}""",
            """{"enabled":true,"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[{"type":"set_wifi","on":true,"force":true}]}}""",
            // campo obbligatorio mancante
            """{"enabled":true,"draft":{"trigger":{"type":"immediate"},"actions":[{"type":"set_wifi","on":true}]}}""",
            """{"enabled":true,"draft":{"name":"x","trigger":{"type":"immediate"}}}""",
            // tipo sbagliato
            """{"enabled":true,"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[{"type":"set_wifi","on":"yes"}]}}""",
            """{"enabled":true,"draft":{"name":5,"trigger":{"type":"immediate"},"actions":[]}}""",
            // valore di enum sconosciuto
            """{"enabled":true,"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[{"type":"set_dnd","mode":"LOUD"}]}}""",
        ).forEach { rule ->
            assertEquals(RuleDiscardReason.INVALID_RULE, discardedReason(rule), rule)
        }
    }

    @Test
    fun `a draft over the size limit is discarded as too large`() {
        val big = "x".repeat(RulesBackupFormat.MAX_RULE_CHARS)
        val rule = """{"enabled":true,"draft":{"name":"$big","trigger":{"type":"immediate"},"actions":[{"type":"set_wifi","on":true}]}}"""

        assertEquals(RuleDiscardReason.RULE_TOO_LARGE, discardedReason(rule))
    }

    @Test
    fun `one bad entry does not take the others down`() {
        val result = entries(file(goodRule, "1", goodRule, """{"enabled":true,"draft":{"type":"x"}}"""))

        assertEquals(
            listOf(true, false, true, false),
            result.map { it is DecodedEntry.Rule },
        )
    }

    @Test
    fun `the default file name carries the date`() {
        assertEquals(
            "argus-rules-2026-10-26.json",
            RulesBackupFormat.defaultFileName(java.time.LocalDate.of(2026, 10, 26)),
        )
    }
}
