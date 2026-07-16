package dev.argus.automation.geofence

import dev.argus.automation.CurrentLocationProvider
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.CancellationException

/**
 * Chiede una seconda opinione alla posizione reale prima di accettare il bordo annunciato dal
 * framework.
 *
 * Nasce da un bug di campo (2026-07-15): con il device fermo al centro di un'area da 200 m, il
 * framework ha annunciato EXIT due volte in tre minuti. Il dedup non poteva accorgersene, perché
 * fra i due EXIT era arrivato un ENTER altrettanto spurio: per lo store erano bordi reali.
 *
 * L'isteresi è la stessa del recupero post-crash ([GeofenceCoordinator]): un bordo viene creduto
 * solo se la posizione lo conferma **oltre** il margine di rumore. Vicino alla circonferenza non
 * decidiamo noi: lì il framework resta l'unica autorità e accettiamo quello che dice.
 */
class LocationBackedTransitionVerifier(
    /** Solo la geometria della regola: dipendenza volutamente più stretta dell'intero store. */
    private val triggerOf: suspend (AutomationId) -> Trigger?,
    private val location: CurrentLocationProvider,
) : GeofenceTransitionVerifier {

    override suspend fun accepts(automationId: AutomationId, transition: Transition): Boolean {
        val trigger = try {
            triggerOf(automationId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } as? Trigger.Geofence ?: return true

        val fix = try {
            location.current()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (fix?.usableAsContradictoryEvidence != true) return true

        val distance = distanceMeters(trigger.lat, trigger.lng, fix.latitude, fix.longitude)
        val accuracy = requireNotNull(fix.horizontalAccuracyMeters)
        return when (transition) {
            // Veto solo se l'intero disco d'incertezza è dentro/fuori oltre l'isteresi.
            // Se il disco tocca la zona ambigua, il framework conserva l'ultima parola.
            Transition.EXIT -> distance + accuracy > trigger.radiusM - HYSTERESIS_METERS
            Transition.ENTER -> distance - accuracy < trigger.radiusM + HYSTERESIS_METERS
            // DWELL non è supportato dal runtime: non inventiamo un giudizio.
            Transition.DWELL -> true
        }
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * earthRadiusM * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    private companion object {
        /** Stesso margine del recupero post-crash: una sola definizione di "rumore". */
        const val HYSTERESIS_METERS = 25.0
    }
}
