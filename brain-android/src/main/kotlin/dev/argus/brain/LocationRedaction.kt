package dev.argus.brain

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Trigger

/**
 * Le coordinate esatte di un geofence o di una condizione di luogo non lasciano mai il telefono nel
 * contesto di una modifica (S4): il modello vede `0.0, 0.0`. Dopo il compile, [restore] rimette le
 * coordinate originali dove il modello ha lasciato `0.0, 0.0` sullo stesso percorso; un luogo che
 * l'utente ha chiesto di cambiare (il modello restituisce coordinate diverse da 0,0) resta quello nuovo.
 *
 * La posizione di un trigger solare (`time.solar`, 0.4.1) si toglie del tutto e non si ripristina:
 * l'app la rilegge dal telefono all'arm e scarta comunque quella del modello.
 *
 * Il "percorso" identifica un nodo della bozza: `trigger`, `conditions`, `conditions.all[1]`,
 * `actions[2].condition`, `actions[0].then[1].condition.cond`, ... Stabile finché il modello non
 * riordina le azioni; in caso contrario le coordinate restano `0.0, 0.0` e la review le mostra.
 */
object LocationRedaction {
    data class Coordinates(val lat: Double, val lng: Double)

    data class Redacted(val draft: AutomationDraft, val originals: Map<String, Coordinates>)

    /** Sostituisce lat/lng di ogni geofence e `location_in` con 0,0; ritorna le coordinate originali per percorso. */
    fun redact(draft: AutomationDraft): Redacted {
        val originals = LinkedHashMap<String, Coordinates>()
        val walker = Walker(
            onGeofence = { path, place ->
                originals.remember(path, place.lat, place.lng)
                place.copy(lat = 0.0, lng = 0.0)
            },
            onLocationIn = { path, place ->
                originals.remember(path, place.lat, place.lng)
                place.copy(lat = 0.0, lng = 0.0)
            },
        )
        return Redacted(walker.rewrite(draft).withoutSolarLocation(), originals)
    }

    private fun AutomationDraft.withoutSolarLocation(): AutomationDraft {
        val time = trigger as? Trigger.Time ?: return this
        val solar = time.solar ?: return this
        if (solar.lat == null && solar.lng == null) return this
        return copy(trigger = time.copy(solar = solar.copy(lat = null, lng = null)))
    }

    /** Rimette le coordinate originali dove [fromModel] ha lasciato 0,0 sullo stesso percorso. */
    fun restore(fromModel: AutomationDraft, originals: Map<String, Coordinates>): AutomationDraft {
        if (originals.isEmpty()) return fromModel
        val walker = Walker(
            onGeofence = { path, place ->
                val original = originals[path]
                if (original != null && place.lat == 0.0 && place.lng == 0.0) {
                    place.copy(lat = original.lat, lng = original.lng)
                } else {
                    place
                }
            },
            onLocationIn = { path, place ->
                val original = originals[path]
                if (original != null && place.lat == 0.0 && place.lng == 0.0) {
                    place.copy(lat = original.lat, lng = original.lng)
                } else {
                    place
                }
            },
        )
        return walker.rewrite(fromModel)
    }

    private fun MutableMap<String, Coordinates>.remember(path: String, lat: Double, lng: Double) {
        // Niente da proteggere (né da ripristinare) se già a 0,0, es. "qui" con resolveCurrentLocation.
        if (lat != 0.0 || lng != 0.0) this[path] = Coordinates(lat, lng)
    }

    private class Walker(
        private val onGeofence: (String, Trigger.Geofence) -> Trigger.Geofence,
        private val onLocationIn: (String, Condition.LocationIn) -> Condition.LocationIn,
    ) {
        fun rewrite(source: AutomationDraft): AutomationDraft = source.copy(
            trigger = (source.trigger as? Trigger.Geofence)?.let { onGeofence("trigger", it) } ?: source.trigger,
            conditions = source.conditions?.let { rewriteCondition(it, "conditions") },
            actions = rewriteActions(source.actions, "actions"),
        )

        private fun rewriteActions(items: List<Action>, path: String): List<Action> =
            items.mapIndexed { index, item -> rewriteAction(item, "$path[$index]") }

        private fun rewriteAction(node: Action, path: String): Action = when (node) {
            is Action.If -> node.copy(
                condition = rewriteCondition(node.condition, "$path.condition"),
                then = rewriteActions(node.then, "$path.then"),
                orElse = rewriteActions(node.orElse, "$path.orElse"),
            )
            is Action.While -> node.copy(
                condition = rewriteCondition(node.condition, "$path.condition"),
                body = rewriteActions(node.body, "$path.body"),
            )
            else -> node
        }

        private fun rewriteCondition(node: Condition, path: String): Condition = when (node) {
            is Condition.LocationIn -> onLocationIn(path, node)
            is Condition.And -> node.copy(
                all = node.all.mapIndexed { index, child -> rewriteCondition(child, "$path.all[$index]") },
            )
            is Condition.Or -> node.copy(
                any = node.any.mapIndexed { index, child -> rewriteCondition(child, "$path.any[$index]") },
            )
            is Condition.Not -> node.copy(cond = rewriteCondition(node.cond, "$path.cond"))
            else -> node
        }
    }
}
