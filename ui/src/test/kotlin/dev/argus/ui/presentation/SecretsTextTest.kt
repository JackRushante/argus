package dev.argus.ui.presentation

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretsTextTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val now = at("2026-11-16T10:00:00")

    @Test
    fun `last use reads today yesterday or the date`() {
        assertEquals(SecretsText.LastUse.Never, SecretsText.lastUse(null, now, zone, Locale.ITALIAN))
        assertEquals(SecretsText.LastUse.Today("07:30"), SecretsText.lastUse(at("2026-11-16T07:30:00"), now, zone, Locale.ITALIAN))
        assertEquals(SecretsText.LastUse.Yesterday("23:05"), SecretsText.lastUse(at("2026-11-15T23:05:00"), now, zone, Locale.ITALIAN))
        assertEquals(SecretsText.LastUse.On("3 nov"), SecretsText.lastUse(at("2026-11-03T12:00:00"), now, zone, Locale.ITALIAN))
        assertEquals(SecretsText.LastUse.On("3 Nov 2025"), SecretsText.lastUse(at("2025-11-03T12:00:00"), now, zone, Locale.ENGLISH))
    }

    @Test
    fun `a new name must be valid and not taken`() {
        assertNull(SecretsText.nameProblem("ha_token", listOf("meteo_api")))
        assertEquals(SecretsText.NameProblem.TAKEN, SecretsText.nameProblem("ha_token", listOf("ha_token")))
        listOf("", "Ha", "1a", "ha-token", "a".repeat(33)).forEach {
            assertEquals(SecretsText.NameProblem.INVALID, SecretsText.nameProblem(it, emptyList()), it)
        }
    }
}
