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
 *  - [Trigger.Time] con `solar`: la posizione congelata all'arm. Chi importa la regola la arma con
 *    la posizione del proprio telefono, letta di nuovo all'arm.
 *  - [Action.HttpRequest]: i VALORI delle intestazioni (token, chiavi API, cookie): restano i nomi.
 *    Un valore vuoto non passa la validazione (`http_header_invalid`), quindi la regola importata non
 *    si arma finché non si rifà con le proprie credenziali. URL e body non si toccano: chi condivide
 *    deve rileggerli.
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

    /** Valore con cui si svuota un'intestazione HTTP: vuoto, quindi non valido finché non si rifà. */
    const val REMOVED_HEADER_VALUE = ""

    fun strip(draft: AutomationDraft): AutomationDraft = draft.copy(
        trigger = stripTrigger(draft.trigger),
        conditions = draft.conditions?.let(::stripCondition),
        actions = draft.actions.map(::stripAction),
    )

    private fun stripTrigger(trigger: Trigger): Trigger = when (trigger) {
        is Trigger.Geofence -> trigger.copy(lat = 0.0, lng = 0.0, resolveCurrentLocation = true)
        is Trigger.Notification -> trigger.copy(conversationId = null, sender = null)
        is Trigger.PhoneState -> trigger.copy(number = null)
        is Trigger.Time -> trigger.solar?.let { trigger.copy(solar = it.copy(lat = null, lng = null)) } ?: trigger
        Trigger.Immediate,
        is Trigger.Connectivity,
        is Trigger.Sensor,
        // 0.4.1: nessun dato personale (stato di sistema, soglie, slot SIM).
        is Trigger.Screen,
        is Trigger.Battery,
        is Trigger.AudioOutput,
        is Trigger.ModeChanged,
        is Trigger.Media,
        // 0.4.1: l'avvio manuale non ha campi.
        Trigger.Manual,
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
        is Action.HttpRequest -> action.copy(
            headers = action.headers?.mapValues { REMOVED_HEADER_VALUE },
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
        is Action.Speak,
        is Action.MediaControl,
        is Action.SetBrightness,
        is Action.SetRotation,
        // 0.5.1: package, permessi e booleani; nessun dato personale.
        is Action.AppControl,
        is Action.SetAirplane,
        is Action.SetLocation,
        is Action.SetNfc,
        is Action.SetBatterySaver,
        is Action.LockScreen,
        is Action.Screenshot,
        is Action.AppPermission,
        is Action.InvokeLlm,
        is Action.InvokeLlmV2,
        -> action
    }
}
