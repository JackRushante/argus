package dev.argus.automation.vm

import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.EngineBanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Banner salute del Dettaglio (0.7.0, nota n7): stesse regole della Lista, per una sola regola. */
class DetailHealthTest {
    @Test
    fun `a rule that uses shizuku while it is stopped shows the shizuku banner`() {
        assertEquals(
            EngineBanner.SHIZUKU_DEGRADED_AFTER_REBOOT,
            detailEngineBanner(ShizukuGatewayStatus.INSTALLED_NOT_RUNNING, usesShizuku = true, batteryExempt = true),
        )
        assertEquals(
            EngineBanner.SHIZUKU_DOWN,
            detailEngineBanner(ShizukuGatewayStatus.NOT_INSTALLED, usesShizuku = true, batteryExempt = true),
        )
        assertEquals(
            EngineBanner.SHIZUKU_DOWN,
            detailEngineBanner(ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED, usesShizuku = true, batteryExempt = true),
        )
    }

    @Test
    fun `no shizuku banner for a rule that does not use it, or when shizuku is authorized or unknown`() {
        assertEquals(
            EngineBanner.NONE,
            detailEngineBanner(ShizukuGatewayStatus.NOT_INSTALLED, usesShizuku = false, batteryExempt = true),
        )
        assertEquals(
            EngineBanner.NONE,
            detailEngineBanner(ShizukuGatewayStatus.AUTHORIZED, usesShizuku = true, batteryExempt = true),
        )
        assertEquals(EngineBanner.NONE, detailEngineBanner(null, usesShizuku = true, batteryExempt = true))
    }

    @Test
    fun `battery optimisation shows its banner, but a shizuku problem comes first`() {
        assertEquals(
            EngineBanner.BATTERY_NOT_EXEMPT,
            detailEngineBanner(ShizukuGatewayStatus.AUTHORIZED, usesShizuku = true, batteryExempt = false),
        )
        assertEquals(
            EngineBanner.SHIZUKU_DOWN,
            detailEngineBanner(ShizukuGatewayStatus.NOT_INSTALLED, usesShizuku = true, batteryExempt = false),
        )
    }

    @Test
    fun `a capability needs shizuku exactly when the probe says so`() {
        assertFalse(requiresShizuku(emptySet()))
        val one = AndroidCapabilityProbe.SHIZUKU_CAPABILITIES.first()
        assertTrue(requiresShizuku(setOf(one)))
        assertFalse(requiresShizuku(setOf("trigger.time")))
    }
}
