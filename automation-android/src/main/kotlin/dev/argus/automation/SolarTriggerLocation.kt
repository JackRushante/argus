package dev.argus.automation

import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.SolarTriggerPolicy
import dev.argus.engine.model.Trigger

/**
 * Posizione del trigger solare (`time.solar`, 0.4.1), con lo stesso trattamento di
 * `resolveCurrentLocation` della geofence: non passa mai dal modello e si congela all'arm.
 *
 * - Una bozza che arriva dal modello non porta mai coordinate: se le ha scritte il modello, qui
 *   vengono scartate ([withoutModelLocation]). Un luogo inventato non può finire in una regola.
 * - All'arm l'app legge la posizione del telefono ([freeze]) e la scrive nella regola, arrotondata a
 *   un decimo di grado ([SolarTriggerPolicy.coarsen]); l'impronta approvata la include.
 */
internal object SolarTriggerLocation {
    /** true se il trigger è un alba/tramonto a cui l'app deve ancora dare la posizione. */
    fun pending(trigger: Trigger): Boolean {
        val solar = (trigger as? Trigger.Time)?.solar ?: return false
        return !solar.located
    }

    /** La bozza senza le coordinate di alba/tramonto che il modello può aver scritto. */
    fun withoutModelLocation(draft: AutomationDraft): AutomationDraft {
        val time = draft.trigger as? Trigger.Time ?: return draft
        val solar = time.solar ?: return draft
        if (solar.lat == null && solar.lng == null) return draft
        return draft.copy(trigger = time.copy(solar = solar.copy(lat = null, lng = null)))
    }

    /** Il trigger con la posizione del telefono, arrotondata. */
    fun freeze(trigger: Trigger.Time, location: DeviceLocation): Trigger.Time {
        val solar = requireNotNull(trigger.solar) { "solar_spec_missing" }
        return trigger.copy(
            solar = solar.copy(
                lat = SolarTriggerPolicy.coarsen(location.latitude),
                lng = SolarTriggerPolicy.coarsen(location.longitude),
            ),
        )
    }
}
