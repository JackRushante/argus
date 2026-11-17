package dev.argus.automation.vm

import dev.argus.automation.manual.manualRule
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.UserModeSnapshot
import dev.argus.ui.model.ModeRowUi
import dev.argus.ui.model.StoredValueTypeUi
import dev.argus.ui.presentation.RenderLanguage
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModesUiMapperTest {
    private val zone = ZoneOffset.UTC
    private val now = java.time.Instant.parse("2026-11-16T18:50:00Z").toEpochMilli()
    private val today0810 = java.time.Instant.parse("2026-11-16T08:10:00Z").toEpochMilli()
    private val saturday = java.time.Instant.parse("2026-11-14T09:00:00Z").toEpochMilli()

    private fun value(key: String, text: String, confidentiality: ConfidentialityLabel, writer: ChangeAuthor, at: Long) =
        StoredValue(key, StateValueType.TEXT, text, IntegrityLabel.TAINTED, confidentiality, writer, at)

    @Test
    fun `a secret value never reaches the screen state`() {
        val state = ModesUiMapper.state(
            UserModeSnapshot(emptyList(), null),
            listOf(
                value("pin_cancello", "4321", ConfidentialityLabel.SECRET, ChangeAuthor.User, today0810),
                value("nota", "ciao", ConfidentialityLabel.PRIVATE, ChangeAuthor.User, today0810),
            ),
            emptyList(),
            RenderLanguage.IT,
            now,
            zone,
        )
        val secret = state.values.first { it.key == "pin_cancello" }
        assertTrue(secret.masked)
        assertEquals("", secret.value)
        assertFalse("4321" in state.toString())
        val plain = state.values.first { it.key == "nota" }
        assertFalse(plain.masked)
        assertEquals("ciao", plain.value)
    }

    @Test
    fun `rows say who changed what and when, a deleted rule is never named`() {
        val rule = manualRule(id = "r1", name = "Conta i caffè", trigger = Trigger.UserModeChanged("Casa"))
            .copy(conditions = Condition.UserModeIs("casa"))
        val state = ModesUiMapper.state(
            UserModeSnapshot(listOf("Casa", "Lavoro"), "Casa", ChangeAuthor.Rule(AutomationId("r1")), today0810),
            listOf(
                StoredValue("caffe_oggi", StateValueType.NUMBER, "2", IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, ChangeAuthor.Rule(AutomationId("r1")), today0810),
                value("ultima_palestra", "14/11", ConfidentialityLabel.PRIVATE, ChangeAuthor.Rule(AutomationId("gone")), saturday),
            ),
            listOf(rule),
            RenderLanguage.IT,
            now,
            zone,
        )
        assertEquals("Cambiata oggi alle 08:10 dalla regola «Conta i caffè».", state.lastChange)
        assertEquals(listOf(ModeRowUi("Casa", 1, 1), ModeRowUi("Lavoro", 0, 0)), state.modes)
        assertEquals("Casa", state.activeMode)
        assertEquals("Numero · aggiornata oggi 08:10 da «Conta i caffè»", state.values[0].detail)
        assertEquals(StoredValueTypeUi.NUMBER, state.values[0].type)
        assertEquals("Testo · aggiornata sabato da una regola eliminata", state.values[1].detail)
        assertTrue(state.canAddMode)
    }

    @Test
    fun `english texts and the twelve mode limit`() {
        val state = ModesUiMapper.state(
            UserModeSnapshot((1..12).map { "M$it" }, null, ChangeAuthor.User, saturday),
            emptyList(),
            emptyList(),
            RenderLanguage.EN,
            now,
            zone,
        )
        assertEquals("Changed Saturday at 09:00 by you.", state.lastChange)
        assertFalse(state.canAddMode)
    }
}
