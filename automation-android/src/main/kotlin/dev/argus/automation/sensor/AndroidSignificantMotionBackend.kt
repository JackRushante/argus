package dev.argus.automation.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import dev.argus.engine.model.SensorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Boundary minimo verso SensorManager, separato per poter provare callback sincroni e cancel retry. */
internal interface SignificantMotionDriver {
    val available: Boolean
    fun request(listener: TriggerEventListener): Boolean
    fun cancel(listener: TriggerEventListener): Boolean
}

private class AndroidSignificantMotionDriver(context: Context) : SignificantMotionDriver {
    private val manager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION, true)

    override val available: Boolean
        get() = sensor?.let {
            it.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT && it.isWakeUpSensor
        } == true

    override fun request(listener: TriggerEventListener): Boolean {
        val manager = manager ?: return false
        val sensor = sensor ?: return false
        return manager.requestTriggerSensor(listener, sensor)
    }

    override fun cancel(listener: TriggerEventListener): Boolean {
        val manager = manager ?: return true
        val sensor = sensor ?: return true
        return manager.cancelTriggerSensor(listener, sensor)
    }
}

/**
 * Backend fisico per i sensori one-shot via [SensorManager.requestTriggerSensor]. P3-2B copre solo
 * `SIGNIFICANT_MOTION`; gli altri kind restano `Unavailable` finché non hanno un backend proprio.
 *
 * Non usa Shizuku: la capability sopravvive a un outage Shizuku (handoff §7.3). Non logga mai
 * `TriggerEvent.values`: il sample raw non lascia il framework.
 *
 * Un `requestTriggerSensor` si disattiva da solo quando scatta: il listener rimuove sé stesso e
 * delega all'ingress, che marca consumed e ri-arma. Una registrazione fisica per kind, condivisa.
 */
class AndroidSignificantMotionBackend internal constructor(
    private val driver: SignificantMotionDriver,
    private val scope: CoroutineScope,
    private val onTriggered: suspend (SensorKind) -> Unit,
) : SensorTriggerBackend {
    constructor(
        context: Context,
        scope: CoroutineScope,
        onTriggered: suspend (SensorKind) -> Unit,
    ) : this(AndroidSignificantMotionDriver(context), scope, onTriggered)

    private val listeners = ConcurrentHashMap<SensorKind, TriggerEventListener>()

    override fun register(kind: SensorKind): SensorRegistrationOutcome {
        if (kind != SensorKind.SIGNIFICANT_MOTION) return SensorRegistrationOutcome.Unavailable
        // Il probe già filtra hardware/mode, ma il backend non si fida di uno stato calcolato altrove.
        if (!driver.available) return SensorRegistrationOutcome.Unavailable

        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                // One-shot consumato: rimuovi il listener prima di delegare, così un rearm
                // concorrente non lo crede ancora vivo. Nessun accesso a event.values.
                listeners.remove(kind, this)
                Log.d(TAG, "sensor trigger: kind=${kind.wireName}")
                scope.launch { onTriggered(kind) }
            }
        }
        // Prenota PRIMA di request(): alcuni driver possono consegnare il callback immediatamente.
        // Inserire dopo lascerebbe nella mappa un listener già consumato e il sensore morto.
        if (listeners.putIfAbsent(kind, listener) != null) {
            return SensorRegistrationOutcome.AlreadyRegistered
        }
        return try {
            if (driver.request(listener)) {
                SensorRegistrationOutcome.Registered
            } else {
                listeners.remove(kind, listener)
                SensorRegistrationOutcome.Failure("request_trigger_sensor_rejected")
            }
        } catch (_: RuntimeException) {
            listeners.remove(kind, listener)
            SensorRegistrationOutcome.Failure("request_trigger_sensor_failed")
        }
    }

    override fun cancel(kind: SensorKind): Boolean {
        val listener = listeners[kind] ?: return true
        return try {
            driver.cancel(listener).also { cancelled ->
                // Un cancel fallito conserva il riferimento: il reconcile successivo può ritentare.
                if (cancelled) listeners.remove(kind, listener)
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private companion object {
        const val TAG = "ArgusSensor"
    }
}
