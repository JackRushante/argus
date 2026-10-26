package dev.argus.automation.base

import dev.argus.device.RingerMode
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.URI

/**
 * `startActivity` da un contesto **background** (automazione: receiver/AlarmManager) viene bloccato
 * da Android 14+/OEM (caveat Background Activity Launch). La superficie reale la solleva così che
 * l'executor emetta il codice **onesto** `activity_start_blocked` (finisce nel journal) invece del
 * generico `action_failed`. Il percorso affidabile da background è quello privilegiato `am start`.
 */
class ActivityStartBlockedException(cause: Throwable? = null) : Exception(cause)

/**
 * Superficie Android normale per le azioni BASE (decision record §7.3): nessuno Shizuku, solo
 * NotificationManager/AudioManager/PackageManager/Intent. Astratta per il test — la logica di
 * mapping, grant e validazione vive in [AndroidBaseActionExecutor], l'adapter reale è sottile.
 */
interface BaseActionSurface {
    /** Grant `ACCESS_NOTIFICATION_POLICY`: necessario per toccare DND e per silenziare il ringer. */
    fun isDndPolicyGranted(): Boolean
    fun setInterruptionFilter(mode: DndMode)
    /**
     * Modalita' Non disturbare in vigore adesso (`NotificationManager.getCurrentInterruptionFilter()`),
     * `null` se non e' una delle tre note (solo sveglie, sconosciuto). Serve a verificare che
     * [setInterruptionFilter] abbia cambiato davvero lo stato.
     */
    fun currentDndMode(): DndMode?
    fun setRingerMode(mode: RingerMode)
    /** Avvia il launcher del package; false se non esiste una entry avviabile. */
    fun launchPackage(pkg: String): Boolean
    fun openHttpUrl(url: String)
    /** Imposta la sveglia reale via Intent `AlarmClock.ACTION_SET_ALARM`; false se nessuna app
     *  orologio risolve l'Intent. Il range è già validato dall'executor. */
    fun setAlarm(hour: Int, minute: Int, label: String?, skipUi: Boolean): Boolean
    /** Avvia il timer reale via Intent `AlarmClock.ACTION_SET_TIMER`; false se nessuna app orologio
     *  risolve l'Intent. Il range è già validato dall'executor. */
    fun setTimer(seconds: Int, label: String?, skipUi: Boolean): Boolean
    /** Volume massimo dello stream (`AudioManager.getStreamMaxVolume`): usato dall'executor per il
     *  clamp host-testabile prima di [setStreamVolume]. */
    fun maxStreamVolume(stream: VolumeStream): Int
    /** Imposta il volume assoluto dello stream (`AudioManager.setStreamVolume`, flag 0). Il livello
     *  è già validato/clampato dall'executor. */
    fun setStreamVolume(stream: VolumeStream, level: Int)
    /** Torcia on/off via `CameraManager.setTorchMode`; false se nessuna camera con flash o
     *  CameraAccessException. */
    fun setTorchMode(on: Boolean): Boolean
    /** Apre la schermata Impostazioni (enum chiuso) via Intent `Settings.ACTION_*`; false se
     *  l'Intent non risolve. */
    fun openSettingsScreen(screen: SettingsScreen, pkg: String?): Boolean
    /** Vibrazione one-shot via `Vibrator`; false se il device non ha vibratore. Durata già
     *  validata dall'executor. */
    fun vibrateOneShot(durationMs: Int): Boolean
}

/**
 * Esegue le azioni BASE con API Android normali. Fallisce sempre in modo tipizzato e non blocca
 * mai per un outage Shizuku: queste azioni non ne dipendono. Un grant mancante è un fallimento
 * pulito e distinto (`*_policy_unavailable`), non un crash né un blocco silenzioso.
 */
class AndroidBaseActionExecutor(
    private val surface: BaseActionSurface,
    /**
     * 0.4.1 (B4): quando Android non lascia avviare un'attività dal background, invece di fallire si
     * pubblica una notifica «Tocca per aprire …» (il tocco è un gesto dell'utente). Default `null`:
     * senza, si resta sul fallimento `activity_start_blocked`.
     */
    private val deferredOpen: DeferredOpenNotifier? = null,
) {

    /**
     * Non disturbare con l'API normale, poi verifica. Su Android 15+ un'app puo' spegnere solo la
     * modalita' creata da lei, non quella attivata a mano: la chiamata ritorna senza errori ma lo
     * stato non cambia. Per questo si rilegge `getCurrentInterruptionFilter()` e, se non e'
     * cambiato, si riprova con [privilegedRetry] (il comando di sistema via shell privilegiata,
     * passato solo se Shizuku e' pronto). Se resta invariato l'esito e' `dnd_change_not_applied`,
     * mai un successo; un fallimento tipizzato del retry (`shizuku_timeout`, ...) viene restituito
     * cosi' com'e'.
     */
    suspend fun setDnd(
        mode: DndMode,
        privilegedRetry: (suspend () -> ActionResult)? = null,
    ): ActionResult = guarded {
        if (!surface.isDndPolicyGranted()) return ActionResult.Failure("dnd_policy_unavailable")
        surface.setInterruptionFilter(mode)
        if (dndApplied(mode)) return ActionResult.Success
        if (privilegedRetry == null) return ActionResult.Failure("dnd_change_not_applied")
        val retried = privilegedRetry()
        if (retried is ActionResult.Failure) return retried
        if (dndApplied(mode)) ActionResult.Success else ActionResult.Failure("dnd_change_not_applied")
    }

    /**
     * Il cambio puo' diventare visibile con qualche istante di ritardo: poche riletture brevi,
     * solo se la prima non e' gia' quella attesa. Stato illeggibile = non applicato (fail-closed).
     */
    private suspend fun dndApplied(mode: DndMode): Boolean {
        repeat(DND_VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(DND_VERIFY_DELAY_MILLIS)
            if (surface.currentDndMode() == mode) return true
        }
        return false
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
        deferrable(DeferredOpenTarget.App(pkg)) {
            if (surface.launchPackage(pkg)) ActionResult.Success
            else ActionResult.Failure("launch_app_unresolved")
        }
    }

    suspend fun openUrl(url: String): ActionResult = guarded {
        if (!validHttpUrl(url)) return ActionResult.Failure("open_url_invalid")
        deferrable(DeferredOpenTarget.Url(url)) {
            surface.openHttpUrl(url)
            ActionResult.Success
        }
    }

    suspend fun setAlarm(hour: Int, minute: Int, label: String?, skipUi: Boolean): ActionResult = guarded {
        if (hour !in 0..23 || minute !in 0..59) return ActionResult.Failure("action_invalid")
        if (surface.setAlarm(hour, minute, label, skipUi)) ActionResult.Success
        else ActionResult.Failure("alarm_app_unresolved")
    }

    suspend fun setTimer(seconds: Int, label: String?, skipUi: Boolean): ActionResult = guarded {
        if (seconds !in 1..MAX_TIMER_SECONDS) return ActionResult.Failure("action_invalid")
        if (surface.setTimer(seconds, label, skipUi)) ActionResult.Success
        else ActionResult.Failure("alarm_app_unresolved")
    }

    suspend fun setVolume(stream: VolumeStream, level: Int): ActionResult = guarded {
        // `level` è una PERCENTUALE 0..100, non un indice assoluto dello stream.
        if (level !in 0..100) return ActionResult.Failure("action_invalid")
        // Portare RING/NOTIFICATION a 0 = silenziare: su Android moderni commuta il DND e richiede
        // il policy grant, esattamente come il ringer (setRinger sopra).
        if (level == 0 &&
            (stream == VolumeStream.RING || stream == VolumeStream.NOTIFICATION) &&
            !surface.isDndPolicyGranted()
        ) {
            return ActionResult.Failure("volume_policy_unavailable")
        }
        // Mappa la percentuale sul massimo reale dello stream: 100% = max, 0% = 0. Ogni percentuale
        // > 0 non deve mai silenziare (minimo 1), altrimenti "volume al 5%" diventerebbe muto.
        val max = surface.maxStreamVolume(stream)
        val actual = if (level == 0) 0 else maxOf(1, Math.round(level / 100.0 * max).toInt())
        surface.setStreamVolume(stream, actual)
        ActionResult.Success
    }

    suspend fun setFlashlight(on: Boolean): ActionResult = guarded {
        if (surface.setTorchMode(on)) ActionResult.Success
        else ActionResult.Failure("torch_unavailable")
    }

    suspend fun openSettingsScreen(screen: SettingsScreen, pkg: String?): ActionResult = guarded {
        // APP_DETAILS è l'unico che consuma un pkg; per gli altri il pkg è ignorato. Enum chiuso:
        // niente action-string arbitraria, quindi nessun routing-sink.
        if (screen == SettingsScreen.APP_DETAILS && !PACKAGE_NAME.matches(pkg ?: "")) {
            return ActionResult.Failure("action_invalid")
        }
        deferrable(DeferredOpenTarget.SettingsPage(screen, pkg)) {
            if (surface.openSettingsScreen(screen, pkg)) ActionResult.Success
            else ActionResult.Failure("settings_screen_unresolved")
        }
    }

    suspend fun vibrate(durationMs: Int): ActionResult = guarded {
        if (durationMs !in 1..MAX_VIBRATE_MS) return ActionResult.Failure("action_invalid")
        if (surface.vibrateOneShot(durationMs)) ActionResult.Success
        else ActionResult.Failure("vibrator_unavailable")
    }

    /**
     * B4 (0.4.1): se l'avvio è bloccato perché il processo è in background, si offre all'utente un tocco
     * ([DeferredOpenNotifier]) e il journal registra [DEFERRED_TO_NOTIFICATION], distinto da
     * `activity_start_blocked`. Senza notifier o senza permesso di notifica il blocco riemerge e
     * l'esito resta quello di prima.
     */
    private inline fun deferrable(target: DeferredOpenTarget, block: () -> ActionResult): ActionResult = try {
        block()
    } catch (blocked: ActivityStartBlockedException) {
        if (deferredOpen?.publish(target) == true) ActionResult.Failure(DEFERRED_TO_NOTIFICATION) else throw blocked
    }

    private inline fun guarded(block: () -> ActionResult): ActionResult = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: ActivityStartBlockedException) {
        // Distinto da action_failed: da background l'Intent non parte, serve Shizuku (`am start`).
        ActionResult.Failure("activity_start_blocked")
    } catch (_: Exception) {
        ActionResult.Failure("action_failed")
    }

    private fun validHttpUrl(raw: String): Boolean = runCatching {
        raw.length <= MAX_URL_CHARS && URI(raw).let { uri ->
            uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank()
        }
    }.getOrDefault(false)

    private companion object {
        const val DND_VERIFY_ATTEMPTS = 3
        const val DND_VERIFY_DELAY_MILLIS = 100L
        const val MAX_URL_CHARS = 8_192
        const val MAX_TIMER_SECONDS = 86_400
        const val MAX_VIBRATE_MS = 10_000
        val HTTP_SCHEMES = setOf("http", "https")
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
