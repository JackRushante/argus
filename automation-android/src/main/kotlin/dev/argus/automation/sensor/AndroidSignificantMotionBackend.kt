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
class AndroidSignificantMotionBackend(
    context: Context,
    private val scope: CoroutineScope,
    private val onTriggered: suspend (SensorKind) -> Unit,
) : SensorTriggerBackend {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val listeners = mutableMapOf<SensorKind, TriggerEventListener>()

    override fun register(kind: SensorKind): SensorRegistrationOutcome {
        if (kind != SensorKind.SIGNIFICANT_MOTION) return SensorRegistrationOutcome.Unavailable
        val manager = sensorManager ?: return SensorRegistrationOutcome.Unavailable
        val sensor = manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION, true)
            ?: return SensorRegistrationOutcome.Unavailable
        // Il probe già filtra su questi, ma il backend non si fida di uno stato calcolato altrove.
        if (sensor.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT || !sensor.isWakeUpSensor) {
            return SensorRegistrationOutcome.Unavailable
        }
        if (listeners.containsKey(kind)) return SensorRegistrationOutcome.AlreadyRegistered

        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                // One-shot consumato: rimuovi il listener prima di delegare, così un rearm
                // concorrente non lo crede ancora vivo. Nessun accesso a event.values.
                listeners.remove(kind)
                Log.d(TAG, "sensor trigger: kind=${kind.wireName}")
                scope.launch { onTriggered(kind) }
            }
        }
        return if (manager.requestTriggerSensor(listener, sensor)) {
            listeners[kind] = listener
            SensorRegistrationOutcome.Registered
        } else {
            SensorRegistrationOutcome.Failure("request_trigger_sensor_rejected")
        }
    }

    override fun cancel(kind: SensorKind): Boolean {
        val listener = listeners.remove(kind) ?: return true
        val manager = sensorManager ?: return true
        val sensor = manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION, true) ?: return true
        return manager.cancelTriggerSensor(listener, sensor)
    }

    private companion object {
        const val TAG = "ArgusSensor"
    }
}
