package dev.argus.automation.vm

import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.BgLocationState
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        exactAlarmsGranted = true,
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

    // Da Android 14 `SCHEDULE_EXACT_ALARM` e' negato di default e lo concede solo l'utente: la lettura
    // deve dire la verita' (altrimenti la riga in Sistema non avvisa) e non far mai cadere la schermata.

    @Test
    fun `before Android 12 exact alarms are granted without asking the system`() {
        var asked = false
        val granted = exactAlarmsGranted(sdkInt = 30) {
            asked = true
            false
        }

        assertTrue(granted)
        assertFalse(asked, "sotto Android 12 non esiste il permesso: niente domanda ad AlarmManager")
    }

    @Test
    fun `from Android 12 the system answer decides`() {
        assertTrue(exactAlarmsGranted(sdkInt = 31) { true })
        assertFalse(exactAlarmsGranted(sdkInt = 31) { false })
        assertTrue(exactAlarmsGranted(sdkInt = 36) { true })
        assertFalse(exactAlarmsGranted(sdkInt = 36) { false })
    }

    @Test
    fun `a failing read counts as not granted`() {
        assertFalse(exactAlarmsGranted(sdkInt = 34) { error("AlarmManager non disponibile") })
    }
}

class ShizukuUiStatusTest {
    @Test
    fun `an outdated Shizuku is its own ui status, not a stopped one`() {
        assertEquals(
            ShizukuStatus.UNSUPPORTED,
            ShizukuGatewayStatus.UNSUPPORTED.toUiStatus(degradedAfterReboot = false),
        )
        assertEquals(
            ShizukuStatus.UNSUPPORTED,
            ShizukuGatewayStatus.UNSUPPORTED.toUiStatus(degradedAfterReboot = true),
        )
    }

    @Test
    fun `every other gateway status keeps its own ui status`() {
        assertEquals(
            ShizukuStatus.NOT_INSTALLED,
            ShizukuGatewayStatus.NOT_INSTALLED.toUiStatus(degradedAfterReboot = false),
        )
        assertEquals(
            ShizukuStatus.INSTALLED_NOT_RUNNING,
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING.toUiStatus(degradedAfterReboot = false),
        )
        assertEquals(
            ShizukuStatus.DEGRADED_AFTER_REBOOT,
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING.toUiStatus(degradedAfterReboot = true),
        )
        assertEquals(
            ShizukuStatus.RUNNING_NOT_AUTHORIZED,
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED.toUiStatus(degradedAfterReboot = false),
        )
        assertEquals(
            ShizukuStatus.AUTHORIZED,
            ShizukuGatewayStatus.AUTHORIZED.toUiStatus(degradedAfterReboot = false),
        )
    }
}

class ShizukuAwaitingAnswerNoteTest {
    @Test
    fun `an unanswered permission request is described in both languages`() {
        assertEquals(
            "In attesa della tua risposta in Shizuku",
            shizukuAwaitingAnswerNote(RenderLanguage.IT),
        )
        assertEquals(
            "Waiting for your answer in Shizuku",
            shizukuAwaitingAnswerNote(RenderLanguage.EN),
        )
    }
}
