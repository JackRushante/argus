package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Variabile `clock` (0.5.0): l'ora si calcola ALLO SCATTO con un orologio iniettato, mai a compile-time. */
class ProgramInterpreterClockTest {
    private val trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC")
    private val event = TriggerEvent.TimeFired(AutomationId("a"), ApprovalFingerprint("f".repeat(64)))

    private fun clockAt(iso: String, zone: ZoneId = ZoneOffset.UTC): Clock =
        Clock.fixed(Instant.parse(iso), zone)

    private class Fired(val texts: List<String>, val seen: List<ResolvedProgramAction>, val result: ProgramExecutionResult)

    private suspend fun fire(
        clock: Clock,
        binding: VarBinding,
        text: String = "\${${binding.name}}",
        locale: Locale = Locale.ENGLISH,
    ): Fired {
        val seen = mutableListOf<ResolvedProgramAction>()
        val interpreter = ProgramInterpreter(
            runner = ProgramActionRunner { action, _ ->
                seen += action
                ProgramActionResult(ActionResult.Success)
            },
            stateProvider = { DeviceState() },
            conditionEvaluator = ConditionEvaluator(clock),
            pause = {},
            clock = clock,
            locale = { locale },
        )
        val result = interpreter.execute(
            trigger,
            event,
            bindings = listOf(binding),
            actions = listOf(Action.ShowNotification("t", text)),
        )
        return Fired(seen.map { (it.action as Action.ShowNotification).text }, seen, result)
    }

    @Test
    fun `an explicit zone gives the local time of that zone at fire time`() = runTest {
        // 2026-10-27T19:25Z = 03:25 a Bali (UTC+8, nessun cambio dell'ora).
        val bali = VarBinding.ClockTime("now_bali", tz = "Asia/Makassar")
        val run = fire(clockAt("2026-10-27T19:25:00Z"), bali, "A Bali sono le \${now_bali}")

        assertTrue(run.result.completed)
        assertEquals(listOf("A Bali sono le 03:25"), run.texts)
    }

    @Test
    fun `the same rule gives a different text at a different fire time`() = runTest {
        val bali = VarBinding.ClockTime("now_bali", tz = "Asia/Makassar")
        val first = fire(clockAt("2026-10-27T19:25:00Z"), bali)
        val second = fire(clockAt("2026-10-27T22:40:00Z"), bali)

        assertEquals(listOf("03:25"), first.texts)
        assertEquals(listOf("06:40"), second.texts)
    }

    @Test
    fun `different zones at the same instant give different local times`() = runTest {
        val instant = "2026-07-01T10:00:00Z"
        fun at(tz: String?) = VarBinding.ClockTime("t", tz = tz, pattern = "HH:mm")
        assertEquals(listOf("12:00"), fire(clockAt(instant), at("Europe/Rome")).texts)
        assertEquals(listOf("18:00"), fire(clockAt(instant), at("Asia/Makassar")).texts)
        assertEquals(listOf("06:00"), fire(clockAt(instant), at("America/New_York")).texts)
        assertEquals(listOf("10:00"), fire(clockAt(instant), at("UTC")).texts)
    }

    @Test
    fun `a null zone follows the zone of the clock, that is the phone`() = runTest {
        val rome = clockAt("2026-01-15T07:05:00Z", ZoneId.of("Europe/Rome"))
        val tokyo = clockAt("2026-01-15T07:05:00Z", ZoneId.of("Asia/Tokyo"))
        val binding = VarBinding.ClockTime("t")

        assertEquals(listOf("08:05"), fire(rome, binding).texts)
        assertEquals(listOf("16:05"), fire(tokyo, binding).texts)
    }

    @Test
    fun `the default pattern is HH mm`() = runTest {
        val run = fire(clockAt("2026-10-27T05:07:00Z"), VarBinding.ClockTime("t", tz = "UTC", pattern = null))
        assertEquals(listOf("05:07"), run.texts)
    }

    @Test
    fun `the offset changes across the daylight saving switch`() = runTest {
        val rome = VarBinding.ClockTime("t", tz = "Europe/Rome", pattern = "yyyy-MM-dd HH:mm XXX")
        // Europe/Rome passa all'ora legale il 2026-03-29 alle 01:00Z (02:00 -> 03:00).
        assertEquals(
            listOf("2026-03-29 01:59 +01:00"),
            fire(clockAt("2026-03-29T00:59:59Z"), rome).texts,
        )
        assertEquals(
            listOf("2026-03-29 03:00 +02:00"),
            fire(clockAt("2026-03-29T01:00:00Z"), rome).texts,
        )
        // E torna all'ora solare il 2026-10-25 alle 01:00Z (03:00 -> 02:00).
        assertEquals(
            listOf("2026-10-25 02:59 +02:00"),
            fire(clockAt("2026-10-25T00:59:59Z"), rome).texts,
        )
        assertEquals(
            listOf("2026-10-25 02:00 +01:00"),
            fire(clockAt("2026-10-25T01:00:00Z"), rome).texts,
        )
    }

    @Test
    fun `the text follows the language of the app`() = runTest {
        val date = VarBinding.ClockTime("t", tz = "Europe/Rome", pattern = "EEEE d MMMM yyyy")
        val clock = clockAt("2026-10-27T07:00:00Z")

        assertEquals(listOf("Tuesday 27 October 2026"), fire(clock, date, locale = Locale.ENGLISH).texts)
        assertEquals(listOf("martedì 27 ottobre 2026"), fire(clock, date, locale = Locale.ITALIAN).texts)
    }

    @Test
    fun `the value is engine-made so CLEAN and PUBLIC with ENGINE provenance`() = runTest {
        val run = fire(clockAt("2026-10-27T07:00:00Z"), VarBinding.ClockTime("t", tz = "UTC"))
        val resolved = run.seen.single()

        assertEquals(IntegrityLabel.CLEAN, resolved.inputIntegrity)
        assertEquals(ConfidentialityLabel.PUBLIC, resolved.inputConfidentiality)
        assertEquals(setOf(ValueProvenance.ENGINE), resolved.inputProvenance)
    }

    @Test
    fun `the value is resolved once so a long wait does not change it within a run`() = runTest {
        var now = Instant.parse("2026-10-27T07:00:00Z")
        val moving = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now.also { now = now.plusSeconds(3_600) }
        }
        val seen = mutableListOf<String>()
        val interpreter = ProgramInterpreter(
            runner = ProgramActionRunner { action, _ ->
                seen += (action.action as Action.ShowNotification).text
                ProgramActionResult(ActionResult.Success)
            },
            stateProvider = { DeviceState() },
            conditionEvaluator = ConditionEvaluator(moving),
            pause = {},
            clock = moving,
            locale = { Locale.ENGLISH },
        )

        interpreter.execute(
            trigger,
            event,
            bindings = listOf(VarBinding.ClockTime("t", tz = "UTC")),
            actions = listOf(
                Action.ShowNotification("t", "\${t}"),
                Action.Wait(1_000),
                Action.ShowNotification("t", "\${t}"),
            ),
        )

        assertEquals(listOf("07:00", "07:00"), seen)
    }

    @Test
    fun `an unusable zone or pattern stops the run as binding_unavailable and runs nothing`() = runTest {
        listOf(
            VarBinding.ClockTime("t", tz = "Bali/Denpasar"),
            VarBinding.ClockTime("t", tz = "UTC", pattern = "HH:mm 'unclosed"),
            VarBinding.ClockTime("t", tz = "UTC", pattern = "x".repeat(41)),
        ).forEach { binding ->
            val run = fire(clockAt("2026-10-27T07:00:00Z"), binding)
            assertFalse(run.result.completed)
            assertEquals("binding_unavailable", run.result.stopCode)
            assertTrue(run.seen.isEmpty())
        }
    }

    @Test
    fun `the live clock reads the zone of the phone at every use`() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(ZoneId.of("Asia/Tokyo"), dev.argus.engine.model.LiveSystemClock.zone)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Rome"))
            assertEquals(ZoneId.of("Europe/Rome"), dev.argus.engine.model.LiveSystemClock.zone)
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
        assertIs<Clock>(dev.argus.engine.model.LiveSystemClock)
    }
}
