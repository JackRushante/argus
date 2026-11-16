package dev.argus.data.backup

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding

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
 *  - [Trigger.Notification]: `conversationId`, `sender` e `titleMatch` (su WhatsApp il titolo è il nome
 *    del contatto). Resta il package (e il filtro sul testo del messaggio).
 *  - [Trigger.PhoneState]: il `number`. Restano evento e filtro sul testo.
 *  - [Trigger.Connectivity]: `match`, il nome della rete Wi-Fi o del dispositivo Bluetooth.
 *  - Variabili [VarBinding.Literal] con riservatezza SECRET: il valore (token, password).
 *
 *  Un campo tolto NON diventa null: null vuol dire «chiunque» (qualunque mittente, qualunque rete) e la
 *  regola importata sarebbe più larga di quella condivisa. Diventa [REMOVED_TEXT] (vuoto), che il
 *  validatore rifiuta (`conversation_id_invalid`, `sender_invalid`, `title_match_invalid`,
 *  `number_invalid`, `match_invalid`), oppure [REMOVED_SECRET] per un letterale (`var_literal_invalid`):
 *  la regola non si arma finché chi la importa non la completa con i propri dati. Un campo che era già
 *  null resta null.
 *  - [Trigger.Time] con `solar`: la posizione congelata all'arm. Chi importa la regola la arma con
 *    la posizione del proprio telefono, letta di nuovo all'arm.
 *  - [Action.HttpRequest]: i VALORI delle intestazioni (token, chiavi API, cookie): restano i nomi.
 *    Un valore vuoto non passa la validazione (`http_header_invalid`), quindi la regola importata non
 *    si arma finché non si rifà con le proprie credenziali. URL e body non si toccano: chi condivide
 *    deve rileggerli.
 *
 *  - 0.8: condizioni `wifi_connected.ssid`, `bluetooth_connected.device`, `calendar_busy` e trigger
 *    `calendar_event` (`titleMatch`, `calendar`) diventano [REMOVED_TEXT] se c'erano; `nfc_tag` perde il
 *    `tagId` e passa a `resolveTag = true` (chi importa legge il proprio tag all'arm); `send_sms` e
 *    `call_number` perdono il numero; `send_intent` perde `data` e i valori degli extra. Le
 *    `secretHeaders`/`secretQuery` di `http_request` restano: portano nomi, non valori. Il trigger e le
 *    azioni di `exit` seguono le stesse regole. I validator di questi tipi devono rifiutare il vuoto.
 *
 * Tutte le azioni sono attraversate (if/while contengono condizioni e azioni annidate) e il `when` esaustivo fa fallire la compilazione
 * se un nuovo tipo viene aggiunto senza decidere cosa farne. I testi liberi (notifiche, comandi, URL)
 * non vengono toccati: chi condivide deve rileggerli.
 */
object PersonalDataStripper {
    /** Latitudine/longitudine fuori intervallo che marcano una `location_in` svuotata. */
    const val REMOVED_LATITUDE = 91.0
    const val REMOVED_LONGITUDE = 181.0

    /** Valore con cui si svuota un'intestazione HTTP: vuoto, quindi non valido finché non si rifà. */
    const val REMOVED_HEADER_VALUE = ""

    /** Valore di un campo di identità tolto (contatto, numero, rete): vuoto, quindi non valido. */
    const val REMOVED_TEXT = ""

    /**
     * Valore di un letterale SECRET tolto: un carattere di controllo, che il validatore rifiuta in ogni
     * letterale (un TEXT vuoto sarebbe valido e la regola partirebbe con un segreto vuoto).
     */
    const val REMOVED_SECRET = "\u0000"

    fun strip(draft: AutomationDraft): AutomationDraft = draft.copy(
        trigger = stripTrigger(draft.trigger),
        conditions = draft.conditions?.let(::stripCondition),
        actions = draft.actions.map(::stripAction),
        vars = draft.vars.map(::stripVar),
        // 0.8: il trigger e le azioni d'uscita di una regola-intervallo seguono le stesse regole.
        exit = draft.exit?.let { exit ->
            exit.copy(trigger = stripTrigger(exit.trigger), actions = exit.actions.map(::stripAction))
        },
    )

    private fun removed(value: String?): String? = value?.let { REMOVED_TEXT }

    private fun stripVar(binding: VarBinding): VarBinding = when (binding) {
        is VarBinding.Literal ->
            if (binding.confidentiality == ConfidentialityLabel.SECRET) binding.copy(value = REMOVED_SECRET) else binding
        // Nessun valore dentro: la query, il campo del trigger o il seme vengono dalla regola.
        is VarBinding.TriggerPayload,
        is VarBinding.State,
        is VarBinding.RandomInt,
        is VarBinding.ClockTime,
        // 0.8: la chiave del valore conservato è un nome scelto dalla regola, non un dato personale.
        is VarBinding.Stored,
        -> binding
    }

    private fun stripTrigger(trigger: Trigger): Trigger = when (trigger) {
        is Trigger.Geofence -> trigger.copy(lat = 0.0, lng = 0.0, resolveCurrentLocation = true)
        is Trigger.Notification -> trigger.copy(
            conversationId = removed(trigger.conversationId),
            sender = removed(trigger.sender),
            titleMatch = removed(trigger.titleMatch),
        )
        is Trigger.PhoneState -> trigger.copy(number = removed(trigger.number))
        is Trigger.Connectivity -> trigger.copy(match = removed(trigger.match))
        is Trigger.Time -> trigger.solar?.let { trigger.copy(solar = it.copy(lat = null, lng = null)) } ?: trigger
        Trigger.Immediate,
        is Trigger.Sensor,
        // 0.4.1: nessun dato personale (stato di sistema, soglie, slot SIM).
        is Trigger.Screen,
        is Trigger.Battery,
        is Trigger.AudioOutput,
        is Trigger.ModeChanged,
        is Trigger.Media,
        // 0.4.1: l'avvio manuale non ha campi.
        Trigger.Manual,
        // 0.5.1: il package dell'app e la direzione sono il criterio della regola, non un dato personale.
        is Trigger.AppEvent,
        -> trigger
        // 0.8: titolo e calendario dicono di chi sono gli impegni.
        is Trigger.CalendarEvent -> trigger.copy(
            titleMatch = removed(trigger.titleMatch),
            calendar = removed(trigger.calendar),
        )
        // 0.8: il tag è un oggetto di chi condivide. Come per il geofence, chi importa lo legge col
        // proprio telefono all'arm.
        is Trigger.NfcTag -> trigger.copy(tagId = REMOVED_TEXT, resolveTag = true)
        // 0.8: offset, package e nome della modalità sono il criterio della regola.
        is Trigger.NextAlarm,
        is Trigger.AppUsage,
        is Trigger.UserModeChanged,
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
        // 0.8: nome della rete, dispositivo, titolo e calendario. null resta null («qualunque»).
        is Condition.WifiConnected -> condition.copy(ssid = removed(condition.ssid))
        is Condition.BluetoothConnected -> condition.copy(device = removed(condition.device))
        is Condition.CalendarBusy -> condition.copy(
            titleMatch = removed(condition.titleMatch),
            calendar = removed(condition.calendar),
        )
        is Condition.Weekday,
        is Condition.HeadsetConnected,
        is Condition.StoredCompare,
        is Condition.UserModeIs,
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
        // secretHeaders/secretQuery (0.8) restano: portano i NOMI dei segreti della cassaforte, mai i valori.
        is Action.HttpRequest -> action.copy(
            headers = action.headers?.mapValues { REMOVED_HEADER_VALUE },
        )
        // 0.8: numeri di telefono e dati degli Intent (URI ed extra possono portare contatti o indirizzi).
        is Action.SendSms -> action.copy(number = REMOVED_TEXT)
        is Action.CallNumber -> action.copy(number = REMOVED_TEXT)
        is Action.SendIntent -> action.copy(
            data = removed(action.data),
            extras = action.extras.map { it.copy(value = REMOVED_TEXT) },
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
        // 0.5.1: la macro sullo schermo non porta dati personali (package e criteri di ricerca).
        is Action.UiSteps,
        // 0.8: testi approvati (domanda, risposta, valore conservato), modalità e operazioni chiuse.
        is Action.Ask,
        is Action.SetStored,
        is Action.SetUserMode,
        is Action.NotificationReply,
        is Action.NotificationAction,
        is Action.ScreenCall,
        -> action
    }
}
