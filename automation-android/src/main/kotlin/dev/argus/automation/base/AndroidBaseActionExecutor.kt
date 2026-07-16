package dev.argus.automation.base

import dev.argus.device.RingerMode
import dev.argus.engine.model.DndMode
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * Superficie Android normale per le azioni BASE (decision record §7.3): nessuno Shizuku, solo
 * NotificationManager/AudioManager/PackageManager/Intent. Astratta per il test — la logica di
 * mapping, grant e validazione vive in [AndroidBaseActionExecutor], l'adapter reale è sottile.
 */
interface BaseActionSurface {
    /** Grant `ACCESS_NOTIFICATION_POLICY`: necessario per toccare DND e per silenziare il ringer. */
    fun isDndPolicyGranted(): Boolean
    fun setInterruptionFilter(mode: DndMode)
    fun setRingerMode(mode: RingerMode)
    /** Avvia il launcher del package; false se non esiste una entry avviabile. */
    fun launchPackage(pkg: String): Boolean
    fun openHttpUrl(url: String)
}

/**
 * Esegue le azioni BASE con API Android normali. Fallisce sempre in modo tipizzato e non blocca
 * mai per un outage Shizuku: queste azioni non ne dipendono. Un grant mancante è un fallimento
 * pulito e distinto (`*_policy_unavailable`), non un crash né un blocco silenzioso.
 */
class AndroidBaseActionExecutor(private val surface: BaseActionSurface) {

    suspend fun setDnd(mode: DndMode): ActionResult = guarded {
        if (!surface.isDndPolicyGranted()) return ActionResult.Failure("dnd_policy_unavailable")
        surface.setInterruptionFilter(mode)
        ActionResult.Success
    }

    suspend fun setRinger(mode: RingerMode): ActionResult = guarded {
        // Silenziare (SILENT/VIBRATE) commuta il DND su Android moderni: richiede il policy grant.
        if (mode != RingerMode.NORMAL && !surface.isDndPolicyGranted()) {
            return ActionResult.Failure("ringer_policy_unavailable")
        }
        surface.setRingerMode(mode)
        ActionResult.Success
    }

    suspend fun launchApp(pkg: String): ActionResult = guarded {
        if (!PACKAGE_NAME.matches(pkg)) return ActionResult.Failure("action_invalid")
        if (surface.launchPackage(pkg)) ActionResult.Success
        else ActionResult.Failure("launch_app_unresolved")
    }

    suspend fun openUrl(url: String): ActionResult = guarded {
        if (!validHttpUrl(url)) return ActionResult.Failure("open_url_invalid")
        surface.openHttpUrl(url)
        ActionResult.Success
    }

    private inline fun guarded(block: () -> ActionResult): ActionResult = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ActionResult.Failure("action_failed")
    }

    private fun validHttpUrl(raw: String): Boolean = runCatching {
        raw.length <= MAX_URL_CHARS && URI(raw).let { uri ->
            uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank()
        }
    }.getOrDefault(false)

    private companion object {
        const val MAX_URL_CHARS = 8_192
        val HTTP_SCHEMES = setOf("http", "https")
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
