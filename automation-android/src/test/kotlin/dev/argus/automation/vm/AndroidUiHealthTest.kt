package dev.argus.automation.vm

import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.BgLocationState
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Stato UI della posizione in background. Bug segnalato da Lorenzo: concessa «sempre/precisa» ma
 * la riga restava grigia perché, senza una regola geofence, `NOT_NEEDED` prevaleva sul grant reale.
 * Un grant completo deve sempre risultare GRANTED (verde), a prescindere dal fabbisogno corrente.
 */
class AndroidUiHealthTest {

    private fun health(bg: Boolean, fg: Boolean) = AndroidUiHealth(
        batteryExempt = true,
        notificationListenerGranted = true,
        notificationsGranted = true,
        foregroundLocationGranted = fg,
        backgroundLocationGranted = bg,
    )

    @Test
    fun `full grant reads as granted even without a geofence rule`() {
        assertEquals(
            BgLocationState.GRANTED,
            health(bg = true, fg = true).backgroundLocationState(needed = false),
        )
    }

    @Test
    fun `full grant reads as granted when a rule needs it`() {
        assertEquals(
            BgLocationState.GRANTED,
            health(bg = true, fg = true).backgroundLocationState(needed = true),
        )
    }

    @Test
    fun `no grant and no rule is not needed`() {
        assertEquals(
            BgLocationState.NOT_NEEDED,
            health(bg = false, fg = false).backgroundLocationState(needed = false),
        )
    }

    @Test
    fun `foreground only with a geofence rule is while in use`() {
        assertEquals(
            BgLocationState.WHILE_IN_USE,
            health(bg = false, fg = true).backgroundLocationState(needed = true),
        )
    }

    @Test
    fun `denied with a geofence rule is a warning`() {
        assertEquals(
            BgLocationState.DENIED,
            health(bg = false, fg = false).backgroundLocationState(needed = true),
        )
    }
}

class ShizukuStatusNoteTest {
    @Test
    fun `an outdated Shizuku says it is too old in both languages`() {
        assertEquals(
            "Shizuku troppo vecchio: aggiornalo",
            ShizukuGatewayStatus.UNSUPPORTED.statusNote(RenderLanguage.IT),
        )
        assertEquals(
            "Shizuku is too old: update it",
            ShizukuGatewayStatus.UNSUPPORTED.statusNote(RenderLanguage.EN),
        )
    }

    @Test
    fun `every other status needs no extra note`() {
        ShizukuGatewayStatus.entries
            .filter { it != ShizukuGatewayStatus.UNSUPPORTED }
            .forEach { status ->
                assertNull(status.statusNote(RenderLanguage.IT), status.name)
                assertNull(status.statusNote(RenderLanguage.EN), status.name)
            }
    }

    @Test
    fun `an outdated Shizuku never reads as authorized in the ui status`() {
        val ui = ShizukuGatewayStatus.UNSUPPORTED.toUiStatus(degradedAfterReboot = false)
        assertNotEquals(ShizukuStatus.AUTHORIZED, ui)
        assertNotEquals(ShizukuStatus.RUNNING_NOT_AUTHORIZED, ui)
    }
}
