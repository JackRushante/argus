package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Validatore della variabile `clock` (0.5.0): fuso con ZoneId.of, formato con DateTimeFormatter. */
class DraftValidatorClockTest {
    private val validator = DraftValidator(knownTools = setOf("notify.show"))

    private fun errors(vararg vars: VarBinding): List<String> {
        val draft = AutomationDraft(
            name = "ora",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.ShowNotification("Ora", "\${ora}")),
            vars = vars.toList(),
        )
        return validator.validate(draft, emptySet())
            .filter { it.severity == Severity.ERROR }
            .map { it.code }
    }

    @Test fun `a clock with a real zone and a good pattern is valid`() {
        assertEquals(emptyList(), errors(VarBinding.ClockTime("ora", tz = "Asia/Makassar", pattern = "HH:mm")))
        assertEquals(emptyList(), errors(VarBinding.ClockTime("ora", tz = null, pattern = null)))
        assertEquals(emptyList(), errors(VarBinding.ClockTime("ora", tz = "UTC", pattern = "EEEE d MMMM yyyy")))
    }

    @Test fun `a zone that does not exist is an error`() {
        listOf("Bali/Denpasar", "asia/makassar", "", "  ", "Mars/Olympus_Mons", "x".repeat(65)).forEach { tz ->
            assertEquals(
                listOf("var_clock_tz_invalid"),
                errors(VarBinding.ClockTime("ora", tz = tz)),
                "«$tz»",
            )
        }
    }

    @Test fun `a pattern that DateTimeFormatter refuses is an error`() {
        listOf("HH:mm 'unclosed", "bbbb", "ZZZZZZ", "{HH}", "", "   ", "x".repeat(41)).forEach { pattern ->
            assertEquals(
                listOf("var_clock_pattern_invalid"),
                errors(VarBinding.ClockTime("ora", tz = "UTC", pattern = pattern)),
                "«$pattern»",
            )
        }
    }

    @Test fun `both errors are reported when both fields are wrong`() {
        val codes = errors(VarBinding.ClockTime("ora", tz = "Nowhere/Land", pattern = "bbbb"))
        assertTrue("var_clock_tz_invalid" in codes)
        assertTrue("var_clock_pattern_invalid" in codes)
    }

    @Test fun `a clock variable can be interpolated and does not trip the taint rules`() {
        val codes = errors(VarBinding.ClockTime("ora", tz = "UTC"))
        assertFalse(codes.any { it.startsWith("interpolation") || it.startsWith("var_") }, codes.toString())
    }

    @Test fun `the name rules apply to a clock like to any other variable`() {
        assertTrue("var_name_invalid" in errors(VarBinding.ClockTime("Ora Bali")))
        assertTrue(
            "var_name_duplicate" in errors(VarBinding.ClockTime("ora"), VarBinding.ClockTime("ora", tz = "UTC")),
        )
    }
}
