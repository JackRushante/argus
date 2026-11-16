package dev.argus.data.backup

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding

/** Un dato che una regola condivisa o un modello lascia a chi la importa (0.7.2). */
enum class FieldToComplete {
    /** La chat del trigger notifica (`conversationId`). */
    CHAT,

    /** Il mittente o il titolo della notifica. */
    SENDER,

    /** Il numero di telefono del trigger chiamata/SMS. */
    PHONE_NUMBER,

    /** Il nome della rete Wi-Fi o del dispositivo Bluetooth. */
    NETWORK,

    /** Il luogo di una condizione «solo se sono in…». */
    PLACE,

    /** Un valore segreto o un'intestazione HTTP (token, chiavi). */
    SECRET,

    /** Non un vuoto: il luogo del geofence è la posizione del telefono quando la regola si arma. */
    CURRENT_LOCATION,
}

/**
 * Riconosce nei dati di una bozza i valori «da completare» che lascia [PersonalDataStripper] (gli stessi che
 * portano i modelli). Ognuno, tranne [FieldToComplete.CURRENT_LOCATION], fa fallire la validazione finché chi
 * importa la regola non lo completa: qui si dice soltanto QUALI sono, per mostrarlo prima dell'import.
 */
object FieldsToComplete {
    fun of(draft: AutomationDraft): Set<FieldToComplete> = buildSet {
        when (val trigger = draft.trigger) {
            is Trigger.Notification -> {
                if (trigger.conversationId == PersonalDataStripper.REMOVED_TEXT) add(FieldToComplete.CHAT)
                if (trigger.sender == PersonalDataStripper.REMOVED_TEXT ||
                    trigger.titleMatch == PersonalDataStripper.REMOVED_TEXT
                ) add(FieldToComplete.SENDER)
            }
            is Trigger.PhoneState ->
                if (trigger.number == PersonalDataStripper.REMOVED_TEXT) add(FieldToComplete.PHONE_NUMBER)
            is Trigger.Connectivity ->
                if (trigger.match == PersonalDataStripper.REMOVED_TEXT) add(FieldToComplete.NETWORK)
            is Trigger.Geofence -> if (trigger.resolveCurrentLocation) add(FieldToComplete.CURRENT_LOCATION)
            else -> Unit
        }
        draft.conditions?.let { addConditions(it) }
        draft.actions.forEach { addAction(it) }
        if (draft.vars.any {
                it is VarBinding.Literal && it.confidentiality == ConfidentialityLabel.SECRET &&
                    it.value == PersonalDataStripper.REMOVED_SECRET
            }
        ) add(FieldToComplete.SECRET)
    }

    private fun MutableSet<FieldToComplete>.addConditions(condition: Condition) {
        when (condition) {
            is Condition.LocationIn ->
                if (condition.lat == PersonalDataStripper.REMOVED_LATITUDE &&
                    condition.lng == PersonalDataStripper.REMOVED_LONGITUDE
                ) add(FieldToComplete.PLACE)
            is Condition.And -> condition.all.forEach { addConditions(it) }
            is Condition.Or -> condition.any.forEach { addConditions(it) }
            is Condition.Not -> addConditions(condition.cond)
            else -> Unit
        }
    }

    private fun MutableSet<FieldToComplete>.addAction(action: Action) {
        when (action) {
            is Action.HttpRequest ->
                if (action.headers?.values?.any { it == PersonalDataStripper.REMOVED_HEADER_VALUE } == true) {
                    add(FieldToComplete.SECRET)
                }
            is Action.If -> {
                addConditions(action.condition)
                action.then.forEach { addAction(it) }
                action.orElse.forEach { addAction(it) }
            }
            is Action.While -> {
                addConditions(action.condition)
                action.body.forEach { addAction(it) }
            }
            else -> Unit
        }
    }
}
