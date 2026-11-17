package dev.argus.automation.sensor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Doppia scossa dall'accelerometro (0.8, `sensor.shake`). Logica pura: campioni in m/s² con l'istante in
 * millisecondi, nessun Android, nessun valore conservato oltre la finestra.
 *
 * Un **colpo** è un campione la cui accelerazione si discosta dalla gravità di almeno [joltThreshold] (circa
 * 2,4 g in tutto: camminare o posare il telefono non ci arrivano). Conta solo il fronte di salita (una
 * vibrazione continua è un colpo solo) e colpi più vicini di [minJoltSpacingMillis] contano come uno. Una scossa avanti-indietro dà due colpi: una **doppia scossa chiara** sono almeno
 * [joltsForShake] colpi entro [windowMillis]. Dopo uno scatto il rilevatore resta sordo per [refractoryMillis].
 */
class ShakeDetector(
    private val joltThreshold: Float = DEFAULT_JOLT_THRESHOLD,
    private val joltsForShake: Int = DEFAULT_JOLTS,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val minJoltSpacingMillis: Long = DEFAULT_MIN_SPACING_MILLIS,
    private val refractoryMillis: Long = DEFAULT_REFRACTORY_MILLIS,
) {
    private val jolts = ArrayDeque<Long>()
    private var lastFiredAt: Long? = null
    private var above = false

    /** `true` se questo campione completa una doppia scossa. */
    fun onSample(x: Float, y: Float, z: Float, atMillis: Long): Boolean {
        lastFiredAt?.let { if (atMillis - it < refractoryMillis) return false }
        val magnitude = sqrt(x * x + y * y + z * z)
        val wasAbove = above
        above = abs(magnitude - GRAVITY) >= joltThreshold
        if (!above || wasAbove) return false
        val last = jolts.lastOrNull()
        if (last != null && atMillis - last < minJoltSpacingMillis) return false
        jolts.addLast(atMillis)
        while (jolts.isNotEmpty() && atMillis - jolts.first() > windowMillis) jolts.removeFirst()
        if (jolts.size < joltsForShake) return false
        jolts.clear()
        lastFiredAt = atMillis
        return true
    }

    fun reset() {
        jolts.clear()
        lastFiredAt = null
        above = false
    }

    companion object {
        const val GRAVITY = 9.81f
        const val DEFAULT_JOLT_THRESHOLD = 14f
        const val DEFAULT_JOLTS = 4
        const val DEFAULT_WINDOW_MILLIS = 1_200L
        const val DEFAULT_MIN_SPACING_MILLIS = 100L
        const val DEFAULT_REFRACTORY_MILLIS = 2_000L
    }
}

/**
 * Telefono girato a faccia in giù (0.8, `sensor.flip_face_down`). Logica pura.
 *
 * La gravità (filtrata dall'accelerometro) deve puntare fuori dallo schermo: asse z sotto [faceDownZ] e
 * inclinazione piccola sugli altri due assi. Se il telefono ha il sensore di prossimità, deve anche essere
 * coperto (il tavolo): un telefono tenuto sopra la testa a faccia in giù non conta. La posizione va tenuta
 * per [holdMillis]; scatta una volta sola e si riarma solo dopo che il telefono è stato rialzato.
 */
class FaceDownDetector(
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    private val faceDownZ: Float = DEFAULT_FACE_DOWN_Z,
    private val releaseZ: Float = DEFAULT_RELEASE_Z,
    private val maxTilt: Float = DEFAULT_MAX_TILT,
    private val smoothing: Float = DEFAULT_SMOOTHING,
) {
    private var gx = 0f
    private var gy = 0f
    private var gz = ShakeDetector.GRAVITY
    private var primed = false
    private var downSince: Long? = null
    private var fired = false

    /** `null` = il telefono non ha il sensore di prossimità (basta la gravità). */
    var proximityNear: Boolean? = null

    /** `true` se questo campione completa un secondo di faccia in giù. */
    fun onAcceleration(x: Float, y: Float, z: Float, atMillis: Long): Boolean {
        if (!primed) {
            gx = x; gy = y; gz = z
            primed = true
        } else {
            gx += smoothing * (x - gx)
            gy += smoothing * (y - gy)
            gz += smoothing * (z - gz)
        }
        return evaluate(atMillis)
    }

    /** Un cambio della prossimità conta come un campione: il tempo di tenuta resta quello della gravità. */
    fun onProximity(near: Boolean, atMillis: Long): Boolean {
        proximityNear = near
        return primed && evaluate(atMillis)
    }

    fun reset() {
        primed = false
        downSince = null
        fired = false
    }

    private fun evaluate(atMillis: Long): Boolean {
        if (gz > releaseZ) {
            // Rialzato: il prossimo faccia in giù è un gesto nuovo.
            fired = false
            downSince = null
            return false
        }
        val faceDown = gz <= faceDownZ && abs(gx) <= maxTilt && abs(gy) <= maxTilt && proximityNear != false
        if (!faceDown) {
            downSince = null
            return false
        }
        if (fired) return false
        val since = downSince ?: atMillis.also { downSince = it }
        if (atMillis - since < holdMillis) return false
        fired = true
        return true
    }

    companion object {
        const val DEFAULT_HOLD_MILLIS = 1_000L
        const val DEFAULT_FACE_DOWN_Z = -8.5f
        const val DEFAULT_RELEASE_Z = -4f
        const val DEFAULT_MAX_TILT = 3f
        const val DEFAULT_SMOOTHING = 0.25f
    }
}
