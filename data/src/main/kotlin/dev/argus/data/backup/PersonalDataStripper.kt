package dev.argus.data.backup

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Trigger

/**
 * Opzione «Togli posizioni e contatti (per condividere)» dell'export.
 *
 * Cosa toglie, tipo per tipo:
 *  - [Trigger.Geofence]: le coordinate. La regola chiede la posizione corrente di chi la arma
 *    (`resolveCurrentLocation = true`, già previsto dal modello e dal validatore).
 *  - [Condition.LocationIn]: le coordinate diventano [REMOVED_LATITUDE]/[REMOVED_LONGITUDE], fuori
 *    intervallo: il [dev.argus.engine.safety.DraftValidator] la segnala (`location_invalid`) e la regola
 *    non si arma finché non viene rifatta. La condizione NON viene eliminata: toglierla renderebbe la
 *    regola più ampia di quella condivisa.
 *  - [Trigger.Notification]: `conversationId` e `sender`. Resta il package (e i filtri sul testo).
 *  - [Trigger.PhoneState]: il `number`. Restano evento e filtro sul testo.
 *
 * Nessuna azione porta oggi un contatto o una posizione tipati; le azioni sono comunque attraversate
 * (if/while contengono condizioni e azioni annidate) e il `when` esaustivo fa fallire la compilazione
 * se un nuovo tipo viene aggiunto senza decidere cosa farne. I testi liberi (notifiche, comandi, URL)
 * non vengono toccati: chi condivide deve rileggerli.
 */
object PersonalDataStripper {
    /** Latitudine/longitudine fuori intervallo che marcano una `location_in` svuotata. */
    const val REMOVED_LATITUDE = 91.0
    const val REMOVED_LONGITUDE = 181.0

    fun strip(draft: AutomationDraft): AutomationDraft = draft.copy(
        trigger = stripTrigger(draft.trigger),
        conditions = draft.conditions?.let(::stripCondition),
        actions = draft.actions.map(::stripAction),
    )

    private fun stripTrigger(trigger: Trigger): Trigger = when (trigger) {
        is Trigger.Geofence -> trigger.copy(lat = 0.0, lng = 0.0, resolveCurrentLocation = true)
        is Trigger.Notification -> trigger.copy(conversationId = null, sender = null)
        is Trigger.PhoneState -> trigger.copy(number = null)
        is Trigger.Time,
        Trigger.Immediate,
        is Trigger.Connectivity,
        is Trigger.Sensor,
        -> trigger
    }

    private fun stripCondition(condition: Condition): Condition = when (condition) {
        is Condition.LocationIn ->
            condition.copy(lat = REMOVED_LATITUDE, lng = REMOVED_LONGITUDE)
        is Condition.And -> condition.copy(all = condition.all.map(::stripCondition))
        is Condition.Or -> condition.copy(any = condition.any.map(::stripCondition))
        is Condition.Not -> condition.copy(cond = stripCondition(condition.cond))
        is Condition.TimeWindow,
        is Condition.StateEquals,
        is Condition.StateCompare,
        is Condition.AppInForeground,
        is Condition.BooleanLiteral,
        is Condition.VarCompare,
        -> condition
    }

    private fun stripAction(action: Action): Action = when (action) {
        is Action.If -> action.copy(
            condition = stripCondition(action.condition),
            then = action.then.map(::stripAction),
            orElse = action.orElse.map(::stripAction),
        )
        is Action.While -> action.copy(
            condition = stripCondition(action.condition),
            body = action.body.map(::stripAction),
        )
        is Action.SetWifi,
        is Action.SetBluetooth,
        is Action.SetMobileData,
        is Action.SetDnd,
        is Action.SetRinger,
        is Action.LaunchApp,
        is Action.OpenUrl,
        is Action.ShowNotification,
        is Action.Tap,
        is Action.InputText,
        is Action.WhatsAppReply,
        is Action.RunShell,
        is Action.CopyToClipboard,
        is Action.CopyText,
        is Action.SetAlarm,
        is Action.SetTimer,
        is Action.SetVolume,
        is Action.SetFlashlight,
        is Action.SetDarkMode,
        is Action.OpenSettingsScreen,
        is Action.Vibrate,
        is Action.WriteSetting,
        is Action.Wait,
        is Action.InvokeLlm,
        is Action.InvokeLlmV2,
        -> action
    }
}
