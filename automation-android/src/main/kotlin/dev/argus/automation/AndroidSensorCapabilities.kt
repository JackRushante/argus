package dev.argus.automation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import dev.argus.engine.model.SensorKind

internal enum class SensorReportingMode {
    CONTINUOUS,
    ON_CHANGE,
    ONE_SHOT,
    SPECIAL_TRIGGER,
    UNKNOWN,
}

/** Snapshot redatto dell'hardware: nessun nome vendor o sample lascia questo confine. */
internal data class AndroidSensorCapability(
    val kind: SensorKind,
    val reportingMode: SensorReportingMode,
    val wakeUp: Boolean,
    val fifoMaxEventCount: Int,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val permissionGranted: Boolean,
)

internal fun interface AndroidSensorCapabilitySource {
    fun read(activityRecognitionGranted: Boolean): List<AndroidSensorCapability>
}

internal object EmptyAndroidSensorCapabilitySource : AndroidSensorCapabilitySource {
    override fun read(activityRecognitionGranted: Boolean): List<AndroidSensorCapability> = emptyList()
}

internal class SystemAndroidSensorCapabilitySource(context: Context) : AndroidSensorCapabilitySource {
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)

    override fun read(activityRecognitionGranted: Boolean): List<AndroidSensorCapability> =
        runCatching {
            SensorKind.entries.mapNotNull { kind ->
                val type = kind.androidType ?: return@mapNotNull null
                sensorManager.getDefaultSensor(type)?.let { sensor ->
                    AndroidSensorCapability(
                        kind = kind,
                        reportingMode = sensor.reportingMode.toReportingMode(),
                        wakeUp = sensor.isWakeUpSensor,
                        fifoMaxEventCount = sensor.fifoMaxEventCount.coerceAtLeast(0),
                        minDelayUs = sensor.minDelay.coerceAtLeast(0),
                        maxDelayUs = sensor.maxDelay.coerceAtLeast(0),
                        permissionGranted = !kind.requiresActivityRecognition ||
                            activityRecognitionGranted,
                    )
                }
            }
        }.getOrDefault(emptyList())

    /** null = gesto derivato da un altro sensore, senza un tipo Android proprio. */
    private val SensorKind.androidType: Int?
        get() = when (this) {
            SensorKind.SIGNIFICANT_MOTION -> Sensor.TYPE_SIGNIFICANT_MOTION
            SensorKind.STATIONARY_DETECT -> Sensor.TYPE_STATIONARY_DETECT
            SensorKind.MOTION_DETECT -> Sensor.TYPE_MOTION_DETECT
            SensorKind.STEP_DETECTOR -> Sensor.TYPE_STEP_DETECTOR
            SensorKind.STEP_COUNTER -> Sensor.TYPE_STEP_COUNTER
            // 0.8: gesti dall'accelerometro (la prossimità, se c'è, aiuta solo il faccia in giù).
            SensorKind.SHAKE -> Sensor.TYPE_ACCELEROMETER
            SensorKind.FLIP_FACE_DOWN -> Sensor.TYPE_ACCELEROMETER
        }

    private val SensorKind.requiresActivityRecognition: Boolean
        get() = this == SensorKind.STEP_DETECTOR || this == SensorKind.STEP_COUNTER

    private fun Int.toReportingMode(): SensorReportingMode = when (this) {
        Sensor.REPORTING_MODE_CONTINUOUS -> SensorReportingMode.CONTINUOUS
        Sensor.REPORTING_MODE_ON_CHANGE -> SensorReportingMode.ON_CHANGE
        Sensor.REPORTING_MODE_ONE_SHOT -> SensorReportingMode.ONE_SHOT
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> SensorReportingMode.SPECIAL_TRIGGER
        else -> SensorReportingMode.UNKNOWN
    }
}

internal object SensorCapabilityPolicy {
    /**
     * Intersezione fra hardware corretto, grant e backend realmente collegato. Un sensore presente
     * ma non ancora implementato non deve mai comparire in available_triggers.
     */
    fun armableKinds(
        capabilities: List<AndroidSensorCapability>,
        implementedKinds: Set<SensorKind>,
    ): List<SensorKind> {
        val byKind = capabilities.groupBy { it.kind }
        return SensorKind.entries.filter { kind ->
            kind in implementedKinds && byKind[kind]?.singleOrNull()?.isCompatible() == true
        }
    }

    private fun AndroidSensorCapability.isCompatible(): Boolean = permissionGranted && when (kind) {
        SensorKind.SIGNIFICANT_MOTION ->
            reportingMode == SensorReportingMode.ONE_SHOT && wakeUp
        SensorKind.STATIONARY_DETECT,
        SensorKind.MOTION_DETECT,
        -> reportingMode == SensorReportingMode.ONE_SHOT
        SensorKind.STEP_DETECTOR -> reportingMode == SensorReportingMode.SPECIAL_TRIGGER
        SensorKind.STEP_COUNTER -> reportingMode == SensorReportingMode.ON_CHANGE
        // 0.8: gesti ricavati dall'accelerometro continuo (AndroidGestureSensorBackend).
        SensorKind.SHAKE,
        SensorKind.FLIP_FACE_DOWN,
        -> reportingMode == SensorReportingMode.CONTINUOUS
    }
}
