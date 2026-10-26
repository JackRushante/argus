package dev.argus.nav

import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.ShizukuStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * «Correggi» sulla riga Shizuku di Sistema e il pulsante dello step Shizuku dell'onboarding fanno
 * la stessa cosa per lo stesso stato. Un Shizuku troppo vecchio non si riavvia: si aggiorna, quindi
 * si apre direttamente la pagina di download, non l'app.
 */
class ShizukuFixTest {
    @Test
    fun `an outdated Shizuku opens the download page in both screens`() {
        assertEquals(ShizukuFix.OPEN_DOWNLOAD, shizukuFixFor(ShizukuStatus.UNSUPPORTED))
        assertEquals(ShizukuFix.OPEN_DOWNLOAD, shizukuFixFor(ShizukuGatewayStatus.UNSUPPORTED))
    }

    @Test
    fun `a running Shizuku without permission asks for the permission in both screens`() {
        assertEquals(
            ShizukuFix.REQUEST_PERMISSION,
            shizukuFixFor(ShizukuStatus.RUNNING_NOT_AUTHORIZED),
        )
        assertEquals(
            ShizukuFix.REQUEST_PERMISSION,
            shizukuFixFor(ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED),
        )
    }

    @Test
    fun `every other state opens the Shizuku app`() {
        listOf(
            ShizukuStatus.NOT_INSTALLED,
            ShizukuStatus.INSTALLED_NOT_RUNNING,
            ShizukuStatus.AUTHORIZED,
            ShizukuStatus.DEGRADED_AFTER_REBOOT,
        ).forEach { assertEquals(ShizukuFix.OPEN_MANAGER, shizukuFixFor(it), it.name) }
        listOf(
            ShizukuGatewayStatus.NOT_INSTALLED,
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            ShizukuGatewayStatus.AUTHORIZED,
        ).forEach { assertEquals(ShizukuFix.OPEN_MANAGER, shizukuFixFor(it), it.name) }
    }

    @Test
    fun `the download page is the one the fallback already used`() {
        assertEquals("https://shizuku.rikka.app/download/", SHIZUKU_DOWNLOAD_URL)
    }
}
