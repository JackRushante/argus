package dev.argus.automation.span

import android.app.UiModeManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.SpanSlot
import dev.argus.engine.model.SpanValue
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.SpanCodes
import dev.argus.engine.runtime.SpanDevice
import dev.argus.engine.runtime.StateReadRequest
import dev.argus.engine.runtime.UserModeSwitcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Conversioni pure fra i valori grezzi del telefono e le scale delle azioni tipate, così che rimettere un valore
 * con la sua azione torni allo stesso valore. Senza Android: si provano nei test JVM.
 */
object SpanReadings {
    /**
     * La percentuale di [Action.SetVolume] che riporta l'indice [index] di uno stream con massimo [max] (stessa
     * formula dell'esecutore: 0 = 0, altrimenti `max(1, round(p/100·max))`). null se nessuna percentuale lo dà.
     */
    fun volumePercent(index: Int, max: Int): Int? {
        if (max <= 0 || index !in 0..max) return null
        return (0..100).firstOrNull { percent ->
            val actual = if (percent == 0) 0 else maxOf(1, Math.round(percent / 100.0 * max).toInt())
            actual == index
        }
    }

    /**
     * La percentuale di [Action.SetBrightness] più vicina al valore grezzo 0..255 (l'esecutore scrive
     * `round(p·255/100)`, che non copre ogni valore). null fuori scala: un valore che non si rappresenta non si
     * indovina.
     */
    fun brightnessPercent(raw: Int): Int? {
        if (raw !in 0..255) return null
        return (0..100).minByOrNull { percent -> kotlin.math.abs(Math.round(percent * 255 / 100.0).toInt() - raw) }
    }

    /** `on|off` dei lettori builtin; altro = non letto. */
    fun flag(value: String?): Boolean? = when (value) {
        "on" -> true
        "off" -> false
        else -> null
    }

    fun dnd(value: String?): DndMode? = when (value) {
        "off" -> DndMode.OFF
        "priority" -> DndMode.PRIORITY
        "total" -> DndMode.TOTAL
        else -> null
    }

    /** `UiModeManager.getNightMode()`: solo i tre valori che `set_dark_mode` sa rimettere. */
    fun night(mode: Int): NightMode? = when (mode) {
        UiModeManager.MODE_NIGHT_NO -> NightMode.OFF
        UiModeManager.MODE_NIGHT_YES -> NightMode.ON
        UiModeManager.MODE_NIGHT_AUTO -> NightMode.AUTO
        else -> null
    }

    /** Il testo di un'impostazione letta: `settings get` scrive `null` per una chiave assente, che non si rimette. */
    fun settingText(raw: String?): String? = raw?.takeIf { it.isNotEmpty() && it != "null" }
}

/**
 * [SpanDevice] Android: legge il valore che un'azione d'inizio sta per cambiare con le API pubbliche (o col
 * lettore di stato già usato dalle regole, per suoneria, Non disturbare, Wi-Fi, Bluetooth, modalità aereo,
 * impostazioni e modalità utente). Ogni lettura che non riesce resta null: mai un valore inventato.
 */
class AndroidSpanDevice(
    context: Context,
    private val state: DeviceStateSnapshotProvider,
    private val modes: () -> UserModeSwitcher,
) : SpanDevice {
    private val appContext = context.applicationContext

    override suspend fun read(slot: SpanSlot): SpanValue? = try {
        when (slot) {
            is SpanSlot.Volume -> volume(slot.stream)
            SpanSlot.Ringer -> builtin(StateKeys.RINGER)?.takeIf { it in RINGER_MODES }?.let(SpanValue::Ringer)
            SpanSlot.Dnd -> SpanReadings.dnd(builtin(StateKeys.DND))?.let(SpanValue::Dnd)
            SpanSlot.Wifi -> SpanReadings.flag(builtin(StateKeys.WIFI))?.let(SpanValue::Flag)
            SpanSlot.Bluetooth -> SpanReadings.flag(builtin(StateKeys.BLUETOOTH))?.let(SpanValue::Flag)
            SpanSlot.Airplane -> SpanReadings.flag(builtin(StateKeys.AIRPLANE))?.let(SpanValue::Flag)
            SpanSlot.Brightness -> brightness()
            SpanSlot.Rotation -> systemInt(Settings.System.ACCELEROMETER_ROTATION)?.let { SpanValue.Flag(it != 0) }
            SpanSlot.MobileData -> mobileData()?.let(SpanValue::Flag)
            SpanSlot.Location -> appContext.getSystemService(LocationManager::class.java)
                ?.isLocationEnabled?.let(SpanValue::Flag)
            SpanSlot.Nfc -> NfcAdapter.getDefaultAdapter(appContext)?.isEnabled?.let(SpanValue::Flag)
            SpanSlot.BatterySaver -> appContext.getSystemService(PowerManager::class.java)
                ?.isPowerSaveMode?.let(SpanValue::Flag)
            SpanSlot.DarkMode -> appContext.getSystemService(UiModeManager::class.java)
                ?.nightMode?.let(SpanReadings::night)?.let(SpanValue::Night)
            SpanSlot.Flashlight -> torch()?.let(SpanValue::Flag)
            is SpanSlot.Setting -> setting(StateQuery.Setting(slot.namespace, slot.name))?.let(SpanValue::Text)
            SpanSlot.UserMode -> state.current(StateReadRequest(includeUserMode = true)).userMode
                ?.let { SpanValue.Mode(it.active) }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    override suspend fun clearUserMode(context: FireContext): ActionResult = try {
        when (modes().switchTo(null, ChangeAuthor.Rule(context.automationId))) {
            is ModeSwitch.Changed, ModeSwitch.Unchanged -> ActionResult.Success
            ModeSwitch.UnknownMode -> ActionResult.Failure(SpanCodes.RESTORE_FAILED)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ActionResult.Failure(SpanCodes.RESTORE_FAILED)
    }

    private suspend fun builtin(key: String): String? =
        state.current(StateReadRequest(keys = setOf(key))).values[key]

    private suspend fun setting(query: StateQuery.Setting): String? =
        SpanReadings.settingText(state.current(StateReadRequest(queries = setOf(query))).queryValues[query.canonicalId])

    private fun volume(stream: VolumeStream): SpanValue? {
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return null
        val type = when (stream) {
            VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
            VolumeStream.RING -> AudioManager.STREAM_RING
            VolumeStream.ALARM -> AudioManager.STREAM_ALARM
            VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        }
        return SpanReadings.volumePercent(audio.getStreamVolume(type), audio.getStreamMaxVolume(type))
            ?.let(SpanValue::Percent)
    }

    private fun brightness(): SpanValue? {
        val raw = systemInt(Settings.System.SCREEN_BRIGHTNESS) ?: return null
        val mode = systemInt(Settings.System.SCREEN_BRIGHTNESS_MODE) ?: return null
        val level = SpanReadings.brightnessPercent(raw) ?: return null
        return SpanValue.Brightness(level, auto = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
    }

    private fun systemInt(name: String): Int? = try {
        Settings.System.getInt(appContext.contentResolver, name)
    } catch (_: Settings.SettingNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Dati mobili: API pubblica (rete o stato del telefono), poi il lettore privilegiato dell'impostazione. */
    private suspend fun mobileData(): Boolean? {
        val telephony = appContext.getSystemService(TelephonyManager::class.java)
        val public = try {
            telephony?.isDataEnabled
        } catch (_: SecurityException) {
            null
        }
        if (public != null) return public
        return when (setting(StateQuery.Setting(dev.argus.engine.model.SettingNamespace.GLOBAL, "mobile_data"))) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    /** La torcia non ha un'impostazione: il primo callback del sistema dice lo stato della camera col flash. */
    private suspend fun torch(): Boolean? {
        val cameras = appContext.getSystemService(CameraManager::class.java) ?: return null
        val flashId = cameras.cameraIdList.firstOrNull { id ->
            cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return null
        return withTimeoutOrNull(TORCH_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId != flashId) return
                        cameras.unregisterTorchCallback(this)
                        if (continuation.isActive) continuation.resume(enabled)
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cameraId != flashId) return
                        cameras.unregisterTorchCallback(this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { runCatching { cameras.unregisterTorchCallback(callback) } }
                cameras.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
            }
        }
    }

    private companion object {
        val RINGER_MODES = setOf("normal", "vibrate", "silent")
        const val TORCH_TIMEOUT_MILLIS = 1_500L
    }
}
