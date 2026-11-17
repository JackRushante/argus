package dev.argus.automation.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.argus.engine.model.SensorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Campioni già ridotti a ciò che serve ai rilevatori: nessun valore lascia il backend. */
internal interface GestureSamples {
    fun onAcceleration(x: Float, y: Float, z: Float, atMillis: Long)
    fun onProximity(near: Boolean, atMillis: Long)
}

/** Confine verso SensorManager e lo stato dello schermo, separato per provare il backend senza Android. */
internal interface GestureSensorDriver {
    val accelerometerAvailable: Boolean
    val proximityAvailable: Boolean
    fun interactive(): Boolean

    /** Registra l'accelerometro (e la prossimità se [proximity]); `false` se il sistema rifiuta. */
    fun start(samples: GestureSamples, proximity: Boolean): Boolean
    fun stop()

    /** Segue accensione e spegnimento dello schermo finché non si chiama [unwatchScreen]. */
    fun watchScreen(onChange: (Boolean) -> Unit)
    fun unwatchScreen()
}

/**
 * Gesti dall'accelerometro (0.8): doppia scossa ([SensorKind.SHAKE]) e faccia in giù
 * ([SensorKind.FLIP_FACE_DOWN]).
 *
 * Batteria: i sensori sono registrati SOLO mentre una regola armata li chiede E lo schermo è acceso; a
 * schermo spento resta soltanto un receiver acceso/spento, e senza regole niente del tutto. Una sola
 * registrazione fisica serve entrambi i gesti (la prossimità solo per il faccia in giù). Il fan-out verso
 * le regole e il cooldown minimo di 60 s restano quelli di tutti i sensori (ingress e motore).
 *
 * Diversamente da `significant_motion` il sensore non si consuma allo scatto: il rearm dell'ingress trova
 * il gesto ancora registrato (`AlreadyRegistered`). Nessun campione raw nei log.
 */
class AndroidGestureSensorBackend internal constructor(
    private val driver: GestureSensorDriver,
    private val scope: CoroutineScope,
    private val onTriggered: suspend (SensorKind) -> Unit,
) : SensorTriggerBackend {
    constructor(
        context: Context,
        scope: CoroutineScope,
        onTriggered: suspend (SensorKind) -> Unit,
    ) : this(AndroidGestureSensorDriver(context), scope, onTriggered)

    private val lock = Any()
    private val desired = linkedSetOf<SensorKind>()
    private var watchingScreen = false
    private var listening: Boolean? = null
    private val shake = ShakeDetector()
    private val flip = FaceDownDetector()

    private val samples = object : GestureSamples {
        override fun onAcceleration(x: Float, y: Float, z: Float, atMillis: Long) {
            val fired = synchronized(lock) {
                buildList {
                    if (SensorKind.SHAKE in desired && shake.onSample(x, y, z, atMillis)) add(SensorKind.SHAKE)
                    if (SensorKind.FLIP_FACE_DOWN in desired && flip.onAcceleration(x, y, z, atMillis)) {
                        add(SensorKind.FLIP_FACE_DOWN)
                    }
                }
            }
            fired.forEach(::deliver)
        }

        override fun onProximity(near: Boolean, atMillis: Long) {
            val fired = synchronized(lock) {
                SensorKind.FLIP_FACE_DOWN in desired && flip.onProximity(near, atMillis)
            }
            if (fired) deliver(SensorKind.FLIP_FACE_DOWN)
        }
    }

    override fun register(kind: SensorKind): SensorRegistrationOutcome {
        if (kind !in GESTURE_KINDS) return SensorRegistrationOutcome.Unavailable
        if (!driver.accelerometerAvailable) return SensorRegistrationOutcome.Unavailable
        return synchronized(lock) {
            if (kind in desired) return@synchronized SensorRegistrationOutcome.AlreadyRegistered
            desired += kind
            try {
                applyLocked()
                SensorRegistrationOutcome.Registered
            } catch (_: RuntimeException) {
                desired -= kind
                runCatching { applyLocked() }
                SensorRegistrationOutcome.Failure("gesture_sensor_failed")
            }
        }
    }

    override fun cancel(kind: SensorKind): Boolean = synchronized(lock) {
        if (desired.remove(kind)) {
            try {
                applyLocked()
            } catch (_: RuntimeException) {
                return@synchronized false
            }
        }
        true
    }

    /** `true` se i sensori sono registrati ora (per i test e la diagnosi). */
    internal val sensorsActive: Boolean
        get() = synchronized(lock) { listening == true }

    private fun onScreen(on: Boolean) {
        synchronized(lock) {
            try {
                applyLocked(screenOn = on)
            } catch (error: RuntimeException) {
                Log.w(TAG, "gesti non aggiornati: ${error::class.java.simpleName}")
            }
        }
    }

    /** Porta registrazioni e receiver allo stato voluto. Solo sotto [lock]. */
    private fun applyLocked(screenOn: Boolean? = null) {
        if (desired.isEmpty()) {
            if (listening != false) driver.stop()
            listening = false
            if (watchingScreen) driver.unwatchScreen()
            watchingScreen = false
            resetDetectors()
            return
        }
        if (!watchingScreen) {
            driver.watchScreen(::onScreen)
            watchingScreen = true
        }
        val on = screenOn ?: driver.interactive()
        if (!on) {
            if (listening != false) driver.stop()
            listening = false
            resetDetectors()
            return
        }
        // Ricrea la registrazione: la prossimità serve solo col faccia in giù.
        if (listening == true) driver.stop()
        val proximity = SensorKind.FLIP_FACE_DOWN in desired && driver.proximityAvailable
        if (!driver.start(samples, proximity)) {
            listening = false
            throw IllegalStateException("gesture_sensor_rejected")
        }
        listening = true
    }

    private fun resetDetectors() {
        shake.reset()
        flip.reset()
        flip.proximityNear = null
    }

    private fun deliver(kind: SensorKind) {
        Log.d(TAG, "gesto: kind=${kind.wireName}")
        scope.launch { onTriggered(kind) }
    }

    internal companion object {
        val GESTURE_KINDS: Set<SensorKind> = setOf(SensorKind.SHAKE, SensorKind.FLIP_FACE_DOWN)
        private const val TAG = "ArgusGesture"
    }
}

private class AndroidGestureSensorDriver(context: Context) : GestureSensorDriver {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proximitySensor = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var listener: SensorEventListener? = null
    private var screenReceiver: BroadcastReceiver? = null

    override val accelerometerAvailable: Boolean get() = accelerometer != null
    override val proximityAvailable: Boolean get() = proximitySensor != null

    override fun interactive(): Boolean =
        appContext.getSystemService(PowerManager::class.java)?.isInteractive == true

    override fun start(samples: GestureSamples, proximity: Boolean): Boolean {
        val manager = manager ?: return false
        val accelerometer = accelerometer ?: return false
        val near = proximitySensor?.maximumRange?.coerceAtMost(NEAR_CENTIMETERS) ?: NEAR_CENTIMETERS
        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val atMillis = event.timestamp / 1_000_000L
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER ->
                        samples.onAcceleration(event.values[0], event.values[1], event.values[2], atMillis)
                    Sensor.TYPE_PROXIMITY -> samples.onProximity(event.values[0] < near, atMillis)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (!manager.registerListener(callback, accelerometer, SensorManager.SENSOR_DELAY_GAME)) return false
        if (proximity) proximitySensor?.let { manager.registerListener(callback, it, SensorManager.SENSOR_DELAY_NORMAL) }
        listener = callback
        return true
    }

    override fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
    }

    override fun watchScreen(onChange: (Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> onChange(true)
                    Intent.ACTION_SCREEN_OFF -> onChange(false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Acceso/spento li invia system_server: basta un receiver non esportato.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    override fun unwatchScreen() {
        screenReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        screenReceiver = null
    }

    private companion object {
        /** Coperto: la maggior parte dei sensori di prossimità è binaria (0 o la portata massima). */
        const val NEAR_CENTIMETERS = 5f
    }
}

/**
 * Un backend per famiglia di sensori dietro l'unico confine del coordinator: `significant_motion` passa da
 * `requestTriggerSensor`, i gesti dall'accelerometro. Un kind senza backend è `Unavailable`.
 */
class CompositeSensorTriggerBackend(
    private val backends: Map<SensorKind, SensorTriggerBackend>,
) : SensorTriggerBackend {
    override fun register(kind: SensorKind): SensorRegistrationOutcome =
        backends[kind]?.register(kind) ?: SensorRegistrationOutcome.Unavailable

    override fun cancel(kind: SensorKind): Boolean = backends[kind]?.cancel(kind) ?: true
}
