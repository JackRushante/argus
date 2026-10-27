package dev.argus.engine.model

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Variabile `clock` (0.5.0): modello, serializzazione, etichette e regole di fuso e formato. */
class ClockVariableTest {
    private val trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC")

    @Test fun `clock binding round-trips with its wire discriminator and null defaults`() {
        val full: VarBinding = VarBinding.ClockTime("ora_bali", tz = "Asia/Makassar", pattern = "HH:mm")
        val bare: VarBinding = VarBinding.ClockTime("ora")
        listOf(full, bare).forEach { binding ->
            val json = ArgusJson.encodeToString(VarBinding.serializer(), binding)
            assertTrue(json.contains("\"type\":\"clock\""), json)
            assertEquals(binding, ArgusJson.decodeFromString(VarBinding.serializer(), json))
        }
        // Il modello può omettere tz, pattern e confidentiality: valgono null, null e PUBLIC.
        val decoded = ArgusJson.decodeFromString(
            VarBinding.serializer(),
            """{"type":"clock","name":"ora"}""",
        )
        assertEquals(VarBinding.ClockTime("ora", tz = null, pattern = null), decoded)
        // E la forma del registro, con i null espliciti.
        assertEquals(
            VarBinding.ClockTime("ora"),
            ArgusJson.decodeFromString(
                VarBinding.serializer(),
                """{"type":"clock","name":"ora","tz":null,"pattern":null}""",
            ),
        )
    }

    @Test fun `clock is a CLEAN PUBLIC TEXT value from the engine`() {
        val binding = VarBinding.ClockTime("ora", tz = "UTC")
        assertEquals(IntegrityLabel.CLEAN, binding.integrity)
        assertEquals(ConfidentialityLabel.PUBLIC, binding.confidentiality)
        assertEquals(VarType.TEXT, binding.declaredType)
        assertEquals(setOf(ValueProvenance.ENGINE), binding.provenance(trigger))
    }

    @Test fun `clock needs no capability`() {
        val with = CapabilityRequirements.derive(
            trigger = Trigger.Immediate,
            actions = listOf(Action.ShowNotification("t", "\${ora}")),
            vars = listOf(VarBinding.ClockTime("ora")),
        )
        val without = CapabilityRequirements.derive(
            trigger = Trigger.Immediate,
            actions = listOf(Action.ShowNotification("t", "x")),
        )
        assertEquals(without, with)
    }

    @Test fun `zones are parsed with ZoneId of and bad ids are refused`() {
        assertEquals(ZoneId.of("Asia/Makassar"), ClockVariable.parseZone("Asia/Makassar"))
        assertEquals(ZoneId.of("Europe/Rome"), ClockVariable.parseZone("Europe/Rome"))
        assertNotNull(ClockVariable.parseZone("UTC"))
        assertNotNull(ClockVariable.parseZone("+02:00"))
        listOf("", "  ", "Bali/Denpasar", "asia/makassar", "Europe/Rome ", "Not a zone", "x".repeat(65))
            .forEach { assertNull(ClockVariable.parseZone(it), "«$it»") }
    }

    @Test fun `patterns are checked with DateTimeFormatter and a trial formatting`() {
        listOf(
            "HH:mm", "HH:mm:ss", "hh:mm a", "EEEE d MMMM yyyy", "d/M/yyyy", "yyyy-MM-dd'T'HH:mm", "HH:mm 'ora' VV",
            "HH 'h' mm", "H:mm z", "dd.MM.yy HH:mm XXX",
        ).forEach { assertNotNull(ClockVariable.parseFormatter(it, Locale.ENGLISH), it) }

        listOf(
            "", "   ", "HH:mm 'unclosed", "bbbb", "ZZZZZZ", "HH:mm {x}", "#", "x".repeat(41),
        ).forEach { assertNull(ClockVariable.parseFormatter(it, Locale.ENGLISH), "«$it»") }
    }

    @Test fun `a pattern of exactly 40 characters is allowed and 41 is not`() {
        val forty = "HH:mm ".repeat(6) + "HH:m" // 36 + 4 = 40
        assertEquals(40, forty.length)
        assertEquals(40, ClockVariable.MAX_PATTERN_CHARS)
        assertNotNull(ClockVariable.parseFormatter(forty, Locale.ENGLISH))
        assertNull(ClockVariable.parseFormatter(forty + "m", Locale.ENGLISH))
    }

    @Test fun `format uses the injected clock, never the system time`() {
        val clock = Clock.fixed(Instant.parse("2026-10-27T19:25:00Z"), ZoneId.of("UTC"))
        assertEquals(
            "03:25",
            ClockVariable.format(VarBinding.ClockTime("t", tz = "Asia/Makassar"), clock, Locale.ENGLISH),
        )
        assertEquals(
            "19:25",
            ClockVariable.format(VarBinding.ClockTime("t"), clock, Locale.ENGLISH),
        )
        assertNull(ClockVariable.format(VarBinding.ClockTime("t", tz = "Nowhere/Land"), clock, Locale.ENGLISH))
        assertNull(ClockVariable.format(VarBinding.ClockTime("t", pattern = "q q 'x"), clock, Locale.ENGLISH))
    }

    @Test fun `the app locale is Italian only for an Italian phone`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            assertEquals(Locale.ITALIAN, ClockVariable.appLocale())
            Locale.setDefault(Locale.GERMANY)
            assertEquals(Locale.ENGLISH, ClockVariable.appLocale())
            Locale.setDefault(Locale.US)
            assertEquals(Locale.ENGLISH, ClockVariable.appLocale())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
