package dev.argus.automation.triggers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.argus.device.AndroidUsageEventsSource
import dev.argus.device.BatteryReading
import dev.argus.device.PublicStateReader
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.ScreenEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Registra sulla sentinella in primo piano le sorgenti di eventi di sistema che una regola armata
 * richiede (schermo, batteria, uscita audio, modalità, riproduzione) e le toglie quando non servono
 * più. Nessun contenuto utente nei log: solo il nome della sorgente e la classe dell'errore.
 *
 * Android non consegna questi broadcast ai receiver del manifest: servono receiver dinamici, vivi
 * finché vive il servizio. La registrazione è idempotente per sorgente.
 */
class AndroidDeviceEventSources(
    context: Context,
    private val ingress: DeviceEventIngress,
    private val scope: CoroutineScope,
    /** `true` se il monitor precedente è morto senza uno stop esplicito (vedi `ConnectivityMonitorMarker`). */
    private val recoverMissed: Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 0.5.1: package delle app osservate da una regola armata `app_event` (letto a ogni consegna). */
    private val appPackages: () -> Set<String> = { emptySet() },
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val registered = LinkedHashMap<DeviceEventSource, Registration>()
    private var closed = false

    /**
     * [unregister] toglie il receiver; [forget] dimentica lo stato della sorgente e vale solo quando è
     * la DOMANDA a venire meno (non alla chiusura del servizio, che può essere un kill).
     */
    private class Registration(val unregister: () -> Unit, val forget: () -> Unit = {})

    /** Porta le registrazioni a [required]: registra le sorgenti nuove, toglie quelle non più richieste. */
    fun apply(required: Set<DeviceEventSource>) {
        synchronized(lock) {
            if (closed) return
            (registered.keys - required).forEach { source ->
                registered.remove(source)?.let { registration ->
                    runCatching(registration.unregister)
                    runCatching(registration.forget)
                }
                Log.i(TAG, "sorgente ${source.name} rimossa")
            }
            (required - registered.keys).forEach { source ->
                try {
                    registered[source] = register(source)
                    Log.i(TAG, "sorgente ${source.name} registrata")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Una sorgente che non si registra non deve fermare le altre né il servizio.
                    Log.w(TAG, "sorgente ${source.name} non registrata: ${error::class.java.simpleName}")
                }
            }
        }
    }

    /** Toglie tutti i receiver senza dimenticare lo stato: il servizio può rinascere dopo un kill. */
    fun close() {
        synchronized(lock) {
            closed = true
            registered.values.forEach { runCatching(it.unregister) }
            registered.clear()
        }
    }

    private fun register(source: DeviceEventSource): Registration = when (source) {
        DeviceEventSource.SCREEN -> registerScreen()
        DeviceEventSource.BATTERY -> registerBattery()
        DeviceEventSource.AUDIO_OUTPUT -> registerAudioOutput()
        DeviceEventSource.MODE_DND -> registerDnd()
        DeviceEventSource.MODE_RINGER -> registerRinger()
        DeviceEventSource.MEDIA -> registerMedia()
        DeviceEventSource.APP_EVENT -> registerAppEvents()
    }

    /** Una consegna: parte sul thread del callback, nell'ordine di arrivo, e prosegue sul dispatcher. */
    private fun deliver(label: String, block: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "dispatch $label fallito: ${error::class.java.simpleName}")
            }
        }
    }

    // --- Schermo -------------------------------------------------------------------------------

    private fun registerScreen(): Registration {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val event = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> ScreenEvent.ON
                    Intent.ACTION_SCREEN_OFF -> ScreenEvent.OFF
                    Intent.ACTION_USER_PRESENT -> ScreenEvent.UNLOCKED
                    else -> return
                }
                val at = nowMillis()
                deliver("screen") { ingress.onScreen(event, at) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // ESPORTATO di proposito: USER_PRESENT lo invia SystemUI (KeyguardViewMediator), che non ha
        // l'uid di sistema, e Android non lo consegna a un receiver RECEIVER_NOT_EXPORTED (lo «sblocco»
        // non scattava mai; schermo acceso/spento li invia system_server e arrivavano). Nessun rischio:
        // le tre azioni sono broadcast protetti, che un'altra app non può inviare.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return Registration(unregister = { appContext.unregisterReceiver(receiver) })
    }

    // --- Batteria ------------------------------------------------------------------------------

    private fun registerBattery(): Registration {
        // La prima osservazione dopo questo punto è la base (o il recupero dopo un kill).
        ingress.startBatteryObservation(recoverMissed)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                val level = batteryPercent(intent) ?: return
                val at = nowMillis()
                deliver("battery") { ingress.onBatteryLevel(level, at) }
            }
        }
        // L'intent sticky arriva anche al receiver appena registrato: è lui la prima osservazione.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Senza domanda il livello salvato invecchia: la prossima volta si riparte da una base nuova.
        return Registration(
            unregister = { appContext.unregisterReceiver(receiver) },
            forget = { ingress.stopBatteryObservation() },
        )
    }

    // --- Uscita audio --------------------------------------------------------------------------

    private fun registerAudioOutput(): Registration {
        val audio = appContext.getSystemService(AudioManager::class.java)
        // La base è letta PRIMA di registrare: la consegna iniziale dei dispositivi già presenti
        // arriva come «aggiunti» ma sono già noti al tracker, quindi non scatta nulla.
        val tracker = AudioOutputTracker(
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { portOf(it) },
        )
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                tracker.onAdded(addedDevices.map { portOf(it) }).forEach { emitAudio(it) }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                tracker.onRemoved(removedDevices.map { portOf(it) }).forEach { emitAudio(it) }
            }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return Registration(unregister = { audio.unregisterAudioDeviceCallback(callback) })
    }

    private fun emitAudio(observation: AudioOutputObservation) {
        val at = nowMillis()
        deliver("audio_output") {
            ingress.onAudioOutput(observation.kind, observation.state, observation.sourceIdentity, at)
        }
    }

    private fun portOf(info: AudioDeviceInfo) = AudioPort(info.id, info.type, info.isSink)

    // --- Modalità ------------------------------------------------------------------------------

    private fun registerDnd(): Registration {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        // Come il lettore di stato: «solo sveglie» e valori ignoti non hanno un valore nel dominio.
        fun current(): String? = PublicStateReader.parseDnd(manager.currentInterruptionFilter)
        current()?.let { ingress.setModeBaseline(ModeKind.DND, it) } ?: ingress.forgetMode(ModeKind.DND)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) return
                val value = current() ?: return
                val at = nowMillis()
                deliver("mode_dnd") { ingress.onMode(ModeKind.DND, value, at) }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return Registration(
            unregister = { appContext.unregisterReceiver(receiver) },
            forget = { ingress.forgetMode(ModeKind.DND) },
        )
    }

    private fun registerRinger(): Registration {
        val audio = appContext.getSystemService(AudioManager::class.java)
        PublicStateReader.parseRinger(audio.ringerMode)
            ?.let { ingress.setModeBaseline(ModeKind.RINGER, it) }
            ?: ingress.forgetMode(ModeKind.RINGER)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AudioManager.RINGER_MODE_CHANGED_ACTION) return
                val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, UNKNOWN_RINGER)
                val value = PublicStateReader.parseRinger(mode) ?: return
                val at = nowMillis()
                deliver("mode_ringer") { ingress.onMode(ModeKind.RINGER, value, at) }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return Registration(
            unregister = { appContext.unregisterReceiver(receiver) },
            forget = { ingress.forgetMode(ModeKind.RINGER) },
        )
    }

    // --- Riproduzione multimediale ---------------------------------------------------------------

    private fun registerMedia(): Registration {
        val audio = appContext.getSystemService(AudioManager::class.java)
        val tracker = MediaPlaybackTracker(
            scope = scope,
            initiallyPlaying = playingMedia(audio.activePlaybackConfigurations),
        ) { state ->
            val at = nowMillis()
            try {
                ingress.onMedia(state, at)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "dispatch media fallito: ${error::class.java.simpleName}")
            }
        }
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                tracker.onPlayback(playingMedia(configs))
            }
        }
        audio.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        return Registration(
            unregister = {
                audio.unregisterAudioPlaybackCallback(callback)
                tracker.close()
            },
        )
    }

    // --- App aperta/chiusa (0.5.1) ---------------------------------------------------------------

    /**
     * Legge gli eventi di utilizzo (`UsageStatsManager`) a intervalli brevi SOLO a schermo acceso: allo
     * spegnimento si ferma, alla riaccensione riparte da dove era arrivata. Gli eventi schermo sono quelli
     * del sistema (ACTION_SCREEN_ON/OFF), con un receiver proprio perché questa sorgente funzioni anche
     * senza regole sullo schermo. Nessun wakelock.
     */
    private fun registerAppEvents(): Registration {
        val power = appContext.getSystemService(PowerManager::class.java)
        val poller = AppEventPoller(
            scope = scope,
            source = AndroidUsageEventsSource(appContext),
            nowMillis = nowMillis,
            watched = appPackages,
            emit = { transition ->
                try {
                    ingress.onAppEvent(transition.pkg, transition.event, transition.atMillis)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "dispatch app_event fallito: ${error::class.java.simpleName}")
                }
            },
        )
        poller.start(screenOn = false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> poller.onScreenOn()
                    Intent.ACTION_SCREEN_OFF -> poller.onScreenOff()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Acceso/spento li invia system_server: bastano i receiver non esportati.
        try {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (error: RuntimeException) {
            poller.close()
            throw error
        }
        // Lo stato attuale si legge DOPO la registrazione: un cambio in mezzo arriva comunque dal receiver.
        if (power?.isInteractive == true) poller.onScreenOn()
        return Registration(
            unregister = {
                appContext.unregisterReceiver(receiver)
                poller.close()
            },
        )
    }

    /** Le configurazioni riportate sono quelle attive: basta una con uso MEDIA. */
    private fun playingMedia(configs: List<AudioPlaybackConfiguration>): Boolean =
        configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }

    private companion object {
        const val TAG = "ArgusDeviceEvents"
        const val UNKNOWN_RINGER = -1
    }
}

/** Percentuale 0..100 dall'intent `ACTION_BATTERY_CHANGED`; `null` se i dati sono incoerenti. */
internal fun batteryPercent(intent: Intent): Int? {
    if (!intent.hasExtra(BatteryManager.EXTRA_LEVEL) || !intent.hasExtra(BatteryManager.EXTRA_SCALE)) return null
    return PublicStateReader.parseBatteryLevel(
        BatteryReading(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
        ),
    )
}
