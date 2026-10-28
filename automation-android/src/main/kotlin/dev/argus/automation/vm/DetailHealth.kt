package dev.argus.automation.vm

import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.ui.model.EngineBanner

/**
 * Banner salute del Dettaglio (0.7.0, nota n7): stesse regole della Lista, ma per UNA regola. Un banner
 * «Shizuku» vale solo se la regola usa azioni o lettori che lo richiedono ([usesShizuku]); la batteria vale
 * per tutte. [shizukuStatus] `null` = stato non noto (nessun banner Shizuku).
 */
internal fun detailEngineBanner(
    shizukuStatus: ShizukuGatewayStatus?,
    usesShizuku: Boolean,
    batteryExempt: Boolean,
): EngineBanner = when {
    usesShizuku && shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING ->
        EngineBanner.SHIZUKU_DEGRADED_AFTER_REBOOT
    usesShizuku && shizukuStatus != null && shizukuStatus != ShizukuGatewayStatus.AUTHORIZED ->
        EngineBanner.SHIZUKU_DOWN
    !batteryExempt -> EngineBanner.BATTERY_NOT_EXEMPT
    else -> EngineBanner.NONE
}

/** Vero se tra le capacità richieste c'è una che dipende da Shizuku. */
internal fun requiresShizuku(requiredCapabilities: Set<String>): Boolean =
    requiredCapabilities.any(AndroidCapabilityProbe.SHIZUKU_CAPABILITIES::contains)
