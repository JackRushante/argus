package dev.argus.automation.modes

import dev.argus.automation.manual.FakeTokenStore
import dev.argus.automation.manual.MapStore
import dev.argus.automation.manual.RecordingAudit
import dev.argus.automation.manual.TileUiState
import dev.argus.automation.manual.manualRule
import dev.argus.data.ModeEditResult
import dev.argus.data.UserModeList
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.UserModeListIssue
import dev.argus.engine.model.UserModePolicy
import dev.argus.engine.model.UserModeSnapshot
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.UserModeEventDispatcher
import dev.argus.engine.runtime.UserModeStore
import dev.argus.engine.runtime.UserModeSwitcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Elenco e modalità attiva in memoria, con le stesse regole di Room (nome senza maiuscole, al più una attiva). */
private class MemoryModes(vararg names: String, active: String? = null) : UserModeStore, UserModeList {
    val names = names.toMutableList()
    var active: String? = active

    override suspend fun snapshot() = UserModeSnapshot(names.toList(), active)

    override suspend fun activate(mode: String?, author: ChangeAuthor, atMillis: Long): ModeSwitch {
        val target = mode?.let { name -> names.firstOrNull { UserModePolicy.same(it, name) } ?: return ModeSwitch.UnknownMode }
        if (target?.lowercase() == active?.lowercase()) return ModeSwitch.Unchanged
        val before = active
        active = target
        return ModeSwitch.Changed(before, target)
    }

    override suspend fun add(raw: String): ModeEditResult {
        UserModePolicy.checkAdd(names, raw)?.let { return ModeEditResult.Refused(it) }
        val clean = UserModePolicy.normalize(raw)!!
        names += clean
        return ModeEditResult.Done(clean)
    }

    override suspend fun rename(from: String, rawTo: String): ModeEditResult {
        UserModePolicy.checkRename(names, from, rawTo)?.let { return ModeEditResult.Refused(it) }
        val clean = UserModePolicy.normalize(rawTo)!!
        val index = names.indexOfFirst { UserModePolicy.same(it, from) }
        names[index] = clean
        return ModeEditResult.Done(clean)
    }

    override suspend fun delete(name: String): ModeEditResult {
        val index = names.indexOfFirst { UserModePolicy.same(it, name) }
        if (index < 0) return ModeEditResult.Refused(UserModeListIssue.UNKNOWN)
        return ModeEditResult.Done(names.removeAt(index))
    }
}

private class RecordingModeDispatcher : UserModeEventDispatcher {
    val envelopes = mutableListOf<TriggerEnvelope>()
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        envelopes += envelope
    }
}

class UserModesTest {
    private fun rule(id: String, trigger: Trigger = Trigger.Manual, conditions: Condition? = null, actions: List<Action>? = null) =
        manualRule(id = id, trigger = trigger).let { base ->
            base.copy(conditions = conditions, actions = actions ?: base.actions)
        }

    @Test
    fun `renaming or deleting a mode moves every rule that names it to needs review`() = runTest {
        val store = MapStore(
            rule("trigger", trigger = Trigger.UserModeChanged("Casa")),
            rule("condition", conditions = Condition.Not(Condition.UserModeIs("casa"))),
            rule("action", actions = listOf(Action.If(Condition.BooleanLiteral(true), then = listOf(Action.SetUserMode("CASA"))))),
            rule("other", conditions = Condition.UserModeIs("Lavoro")),
        )
        val audit = RecordingAudit()
        val modes = MemoryModes("Casa", "Lavoro")
        val editor = UserModeEditor(modes, store, audit, nowMillis = { 7L })

        assertEquals(ModeEditResult.Done("Home"), editor.rename("Casa", "Home"))

        listOf("trigger", "condition", "action").forEach { id ->
            assertEquals(AutomationStatus.NEEDS_REVIEW, store.get(AutomationId(id))?.status, id)
        }
        assertEquals(AutomationStatus.ARMED, store.get(AutomationId("other"))?.status)
        assertEquals(List(3) { AuditKind.RULE_NEEDS_REVIEW }, audit.events.map { it.kind })
        assertEquals(setOf(UserModeEditor.REASON_RENAMED), audit.events.map { it.detail }.toSet())

        assertEquals(ModeEditResult.Done("Lavoro"), editor.delete("lavoro"))
        assertEquals(AutomationStatus.NEEDS_REVIEW, store.get(AutomationId("other"))?.status)
        assertEquals(UserModeEditor.REASON_DELETED, audit.events.last().detail)
    }

    @Test
    fun `changing only the case or a refused edit leaves the rules armed`() = runTest {
        val store = MapStore(rule("condition", conditions = Condition.UserModeIs("Casa")))
        val audit = RecordingAudit()
        val editor = UserModeEditor(MemoryModes("Casa", "Lavoro"), store, audit)

        assertEquals(ModeEditResult.Done("CASA"), editor.rename("Casa", "CASA"))
        assertEquals(ModeEditResult.Refused(UserModeListIssue.DUPLICATE), editor.rename("CASA", "lavoro"))
        assertEquals(ModeEditResult.Refused(UserModeListIssue.UNKNOWN), editor.delete("Notte"))

        assertEquals(AutomationStatus.ARMED, store.get(AutomationId("condition"))?.status)
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun `the widget button needs the mode token, then the name`() = runTest {
        val modes = MemoryModes("Casa", "Lavoro", active = "Casa")
        val dispatcher = RecordingModeDispatcher()
        val tokens = FakeTokenStore()
        val entry = ModeEntryPoints(UserModeSwitcher(modes, dispatcher, now = { 5L }), tokens)

        val rows = entry.widgetEntries()
        assertEquals(listOf("Casa" to true, "Lavoro" to false), rows.map { it.name to it.active })
        val token = rows.first().token

        // Un intent costruito da un'altra app indovina il nome, non il token: niente cambia.
        assertEquals(ModeIntentResult.BAD_TOKEN, entry.switchFromIntent("Lavoro", null))
        assertEquals(ModeIntentResult.BAD_TOKEN, entry.switchFromIntent("Lavoro", "forged"))
        assertEquals(ModeIntentResult.BAD_TOKEN, entry.switchFromIntent("Lavoro", tokens.tokenFor(AutomationId("r1"))))
        assertEquals("Casa", modes.active)
        assertTrue(dispatcher.envelopes.isEmpty())

        assertEquals(ModeIntentResult.UNKNOWN_MODE, entry.switchFromIntent("Notte", token))
        assertEquals(ModeIntentResult.UNKNOWN_MODE, entry.switchFromIntent("\${x}", token))
        assertEquals(ModeIntentResult.UNCHANGED, entry.switchFromIntent("casa", token))
        assertEquals(ModeIntentResult.SWITCHED, entry.switchFromIntent("lavoro", token))
        assertEquals("Lavoro", modes.active)
        val event = assertIs<TriggerEvent.UserModeChanged>(dispatcher.envelopes.single().event)
        assertEquals(TriggerEvent.UserModeChanged("Lavoro", "Casa", chained = false), event)
    }

    @Test
    fun `the tile cycles the modes and says when there are none`() = runTest {
        val empty = ModeEntryPoints(UserModeSwitcher(MemoryModes(), RecordingModeDispatcher()), FakeTokenStore())
        assertEquals(ModeTileSpec(null, TileUiState.UNAVAILABLE), empty.tileSpec(empty.snapshot()))
        assertEquals(null, empty.onTileClick())

        val modes = MemoryModes("Casa", "Lavoro", "Notte")
        val dispatcher = RecordingModeDispatcher()
        val entry = ModeEntryPoints(UserModeSwitcher(modes, dispatcher), FakeTokenStore())
        assertEquals(ModeTileSpec(null, TileUiState.INACTIVE), entry.tileSpec(entry.snapshot()))

        assertEquals(ModeSwitch.Changed(null, "Casa"), entry.onTileClick())
        assertEquals(ModeSwitch.Changed("Casa", "Lavoro"), entry.onTileClick())
        assertEquals(ModeSwitch.Changed("Lavoro", "Notte"), entry.onTileClick())
        assertEquals(ModeSwitch.Changed("Notte", "Casa"), entry.onTileClick())
        assertEquals(ModeTileSpec("Casa", TileUiState.ACTIVE), entry.tileSpec(entry.snapshot()))
        assertEquals(4, dispatcher.envelopes.size)
    }

    @Test
    fun `the dispatcher hands the change to the engine outside the caller`() = runTest {
        val launched = mutableListOf<suspend () -> Unit>()
        val dispatcher = EngineUserModeEventDispatcher(
            engine = { error("il motore si usa solo nel lavoro lanciato") },
            state = { error("nessuna lettura nel chiamante") },
            launcher = ModeWorkLauncher { block ->
                launched += block
                kotlinx.coroutines.Job()
            },
        )
        val envelope = dev.argus.engine.device.DeviceEventParser().userMode("Casa", null, 1L)!!
        dispatcher.dispatch(envelope)
        assertEquals(1, launched.size)
    }
}
