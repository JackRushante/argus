package dev.argus.engine.model

/**
 * Interruttori del vocabolario 0.8: UNA riga per ogni trigger, condizione, azione o campo nuovo.
 *
 * Il tipo esiste già nel modello, nel fingerprint, nel backup e nella resa di approvazione, ma finché il
 * suo interruttore è `enabled = false`:
 *  - il [dev.argus.engine.safety.DraftValidator] rifiuta la regola con l'ERROR [FeatureGates.NOT_AVAILABLE_YET]
 *    (dettaglio = [wireName]), quindi non si arma mai;
 *  - il `TaintAwareInterpolator` blocca l'azione con lo stesso codice e l'esecutore Android fallisce chiuso
 *    con lo stesso codice;
 *  - il probe la dà non disponibile (`BlockCause.NOT_AVAILABLE_YET`), il manifest e il prompt di compile non
 *    la nominano.
 *
 * Accendere una feature: implementarla (validator, interpolatore, esecutore, probe, manifest, prompt) e poi
 * cambiare in `true` il SOLO `enabled` della propria riga. Le righe sono separate da un commento perché due
 * engineer che accendono due feature vicine non tocchino righe adiacenti.
 */
enum class FeatureGate(
    /** Nome mostrato nel dettaglio dell'errore e nei log: il wire name del tipo o `tipo.campo`. */
    val wireName: String,
    val enabled: Boolean,
) {
    // --- Regola ----------------------------------------------------------------------------------

    /** `confirm`: chiedi all'utente prima di eseguire. */
    CONFIRM("confirm", enabled = true),

    /** `exit`: la regola-intervallo finisce col suo trigger d'uscita. */
    EXIT("exit", enabled = false),

    // --- Variabili -------------------------------------------------------------------------------

    /** Variabile `stored`: valore conservato fra le esecuzioni. */
    VAR_STORED("stored", enabled = true),

    // --- Condizioni ------------------------------------------------------------------------------

    /** Condizione `weekday`. */
    CONDITION_WEEKDAY("weekday", enabled = true),

    /** Condizione `wifi_connected`. */
    CONDITION_WIFI_CONNECTED("wifi_connected", enabled = true),

    /** Condizione `bluetooth_connected`. */
    CONDITION_BLUETOOTH_CONNECTED("bluetooth_connected", enabled = true),

    /** Condizione `headset_connected`. */
    CONDITION_HEADSET_CONNECTED("headset_connected", enabled = true),

    /** Condizione `calendar_busy`. */
    CONDITION_CALENDAR_BUSY("calendar_busy", enabled = true),

    /** Condizione `stored_compare`. */
    CONDITION_STORED_COMPARE("stored_compare", enabled = true),

    /** Condizione `user_mode_is`. */
    CONDITION_USER_MODE_IS("user_mode_is", enabled = true),

    // --- Trigger ---------------------------------------------------------------------------------

    /** Trigger `calendar_event`. */
    TRIGGER_CALENDAR_EVENT("calendar_event", enabled = true),

    /** Trigger `nfc_tag`. */
    TRIGGER_NFC_TAG("nfc_tag", enabled = false),

    /** Trigger `next_alarm`. */
    TRIGGER_NEXT_ALARM("next_alarm", enabled = false),

    /** Trigger `app_usage`. */
    TRIGGER_APP_USAGE("app_usage", enabled = false),

    /** Trigger `user_mode_changed`. */
    TRIGGER_USER_MODE_CHANGED("user_mode_changed", enabled = true),

    /** Trigger `sensor` con `kind` = `shake`. */
    TRIGGER_SENSOR_SHAKE("sensor.shake", enabled = false),

    /** Trigger `sensor` con `kind` = `flip_face_down`. */
    TRIGGER_SENSOR_FLIP_FACE_DOWN("sensor.flip_face_down", enabled = false),

    // --- Azioni ----------------------------------------------------------------------------------

    /** Azione `ask`. */
    ACTION_ASK("ask", enabled = true),

    /** Azione `set_stored`. */
    ACTION_SET_STORED("set_stored", enabled = true),

    /** Azione `set_user_mode`. */
    ACTION_SET_USER_MODE("set_user_mode", enabled = true),

    /** Azione `notification_reply`. */
    ACTION_NOTIFICATION_REPLY("notification_reply", enabled = true),

    /** Azione `notification_action`. */
    ACTION_NOTIFICATION_ACTION("notification_action", enabled = true),

    /** Azione `send_sms`. */
    ACTION_SEND_SMS("send_sms", enabled = true),

    /** Azione `call_number`. */
    ACTION_CALL_NUMBER("call_number", enabled = true),

    /** Azione `send_intent`. */
    ACTION_SEND_INTENT("send_intent", enabled = true),

    /** Azione `screen_call`. */
    ACTION_SCREEN_CALL("screen_call", enabled = true),

    // --- Campi nuovi di azioni esistenti ---------------------------------------------------------

    /** `http_request.captureAs` ed `http_request.extract`: cattura della risposta. */
    HTTP_CAPTURE("http_request.captureAs", enabled = true),

    /** `http_request.secretHeaders` e `http_request.secretQuery`: valori dalla cassaforte. */
    HTTP_SECRETS("http_request.secretHeaders", enabled = true),

    /** `invoke_llm.output`: uscita del modello vincolata. */
    LLM_OUTPUT("invoke_llm.output", enabled = true),
}

/**
 * Quale [FeatureGate] usa ogni parte di una regola. I `when` sono esaustivi SENZA `else`: un tipo nuovo non
 * compila finché non si dichiara qui se è dietro un interruttore (null = tipo già disponibile).
 */
object FeatureGates {
    /** Codice unico di validator, interpolatore, esecutore e journal per un tipo spento. */
    const val NOT_AVAILABLE_YET = "not_available_yet"

    /** Nodi oltre i quali la visita si ferma: il validator rifiuta comunque alberi così grandi (64 + 64). */
    private const val MAX_VISITED_NODES = 4_096

    /** Gli interruttori SPENTI usati da [draft], nell'ordine in cui compaiono, senza doppioni. */
    fun disabledIn(draft: AutomationDraft): List<FeatureGate> = usedBy(draft).filterNot { it.enabled }

    /** Tutti gli interruttori usati da [draft], accesi o spenti. */
    fun usedBy(draft: AutomationDraft): Set<FeatureGate> {
        val used = linkedSetOf<FeatureGate>()
        draft.confirm?.let { used += FeatureGate.CONFIRM }
        draft.exit?.let { used += FeatureGate.EXIT }
        of(draft.trigger)?.let(used::add)
        draft.exit?.trigger?.let { exitTrigger -> of(exitTrigger)?.let(used::add) }
        draft.vars.forEach { binding -> of(binding)?.let(used::add) }
        draft.conditions?.let { visitConditions(it, used) }
        visitActions(draft.actions + draft.exit?.actions.orEmpty(), used)
        return used
    }

    /** true se l'azione foglia usa almeno un interruttore spento: interpolatore ed esecutore falliscono chiusi. */
    fun blocks(action: Action): Boolean = of(action).any { !it.enabled }

    fun of(trigger: Trigger): FeatureGate? = when (trigger) {
        is Trigger.Time -> null
        is Trigger.Immediate -> null
        is Trigger.Geofence -> null
        is Trigger.Notification -> null
        is Trigger.PhoneState -> null
        is Trigger.Connectivity -> null
        is Trigger.Sensor -> of(trigger.kind)
        is Trigger.Screen -> null
        is Trigger.Battery -> null
        is Trigger.AudioOutput -> null
        is Trigger.ModeChanged -> null
        is Trigger.Media -> null
        is Trigger.Manual -> null
        is Trigger.AppEvent -> null
        is Trigger.CalendarEvent -> FeatureGate.TRIGGER_CALENDAR_EVENT
        is Trigger.NfcTag -> FeatureGate.TRIGGER_NFC_TAG
        is Trigger.NextAlarm -> FeatureGate.TRIGGER_NEXT_ALARM
        is Trigger.AppUsage -> FeatureGate.TRIGGER_APP_USAGE
        is Trigger.UserModeChanged -> FeatureGate.TRIGGER_USER_MODE_CHANGED
    }

    fun of(kind: SensorKind): FeatureGate? = when (kind) {
        SensorKind.SIGNIFICANT_MOTION -> null
        SensorKind.STATIONARY_DETECT -> null
        SensorKind.MOTION_DETECT -> null
        SensorKind.STEP_DETECTOR -> null
        SensorKind.STEP_COUNTER -> null
        SensorKind.SHAKE -> FeatureGate.TRIGGER_SENSOR_SHAKE
        SensorKind.FLIP_FACE_DOWN -> FeatureGate.TRIGGER_SENSOR_FLIP_FACE_DOWN
    }

    /** Solo il nodo: i figli di and/or/not li visita [usedBy]. */
    fun of(condition: Condition): FeatureGate? = when (condition) {
        is Condition.TimeWindow -> null
        is Condition.StateEquals -> null
        is Condition.StateCompare -> null
        is Condition.AppInForeground -> null
        is Condition.LocationIn -> null
        is Condition.And -> null
        is Condition.Or -> null
        is Condition.Not -> null
        is Condition.BooleanLiteral -> null
        is Condition.VarCompare -> null
        is Condition.Weekday -> FeatureGate.CONDITION_WEEKDAY
        is Condition.WifiConnected -> FeatureGate.CONDITION_WIFI_CONNECTED
        is Condition.BluetoothConnected -> FeatureGate.CONDITION_BLUETOOTH_CONNECTED
        is Condition.HeadsetConnected -> FeatureGate.CONDITION_HEADSET_CONNECTED
        is Condition.CalendarBusy -> FeatureGate.CONDITION_CALENDAR_BUSY
        is Condition.StoredCompare -> FeatureGate.CONDITION_STORED_COMPARE
        is Condition.UserModeIs -> FeatureGate.CONDITION_USER_MODE_IS
    }

    fun of(binding: VarBinding): FeatureGate? = when (binding) {
        is VarBinding.TriggerPayload -> null
        is VarBinding.State -> null
        is VarBinding.Literal -> null
        is VarBinding.RandomInt -> null
        is VarBinding.ClockTime -> null
        is VarBinding.Stored -> FeatureGate.VAR_STORED
    }

    /** Solo il nodo: le azioni annidate in if/while (e le loro condizioni) le visita [usedBy]. */
    fun of(action: Action): Set<FeatureGate> = when (action) {
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
        is Action.InvokeLlmV2,
        is Action.Wait,
        is Action.If,
        is Action.While,
        is Action.Speak,
        is Action.MediaControl,
        is Action.SetBrightness,
        is Action.SetRotation,
        is Action.AppControl,
        is Action.SetAirplane,
        is Action.SetLocation,
        is Action.SetNfc,
        is Action.SetBatterySaver,
        is Action.LockScreen,
        is Action.Screenshot,
        is Action.AppPermission,
        is Action.UiSteps,
        -> emptySet()
        is Action.HttpRequest -> buildSet {
            if (action.captureAs != null || action.extract != null) add(FeatureGate.HTTP_CAPTURE)
            if (action.secretHeaders != null || action.secretQuery != null) add(FeatureGate.HTTP_SECRETS)
        }
        is Action.InvokeLlm -> if (action.output != null) setOf(FeatureGate.LLM_OUTPUT) else emptySet()
        is Action.Ask -> setOf(FeatureGate.ACTION_ASK)
        is Action.SetStored -> setOf(FeatureGate.ACTION_SET_STORED)
        is Action.SetUserMode -> setOf(FeatureGate.ACTION_SET_USER_MODE)
        is Action.NotificationReply -> setOf(FeatureGate.ACTION_NOTIFICATION_REPLY)
        is Action.NotificationAction -> setOf(FeatureGate.ACTION_NOTIFICATION_ACTION)
        is Action.SendSms -> setOf(FeatureGate.ACTION_SEND_SMS)
        is Action.CallNumber -> setOf(FeatureGate.ACTION_CALL_NUMBER)
        is Action.SendIntent -> setOf(FeatureGate.ACTION_SEND_INTENT)
        is Action.ScreenCall -> setOf(FeatureGate.ACTION_SCREEN_CALL)
    }

    /** Visita iterativa e limitata: nessuna ricorsione su un albero non ancora validato. */
    private fun visitConditions(root: Condition, used: MutableSet<FeatureGate>) {
        val pending = ArrayDeque<Condition>()
        pending.addLast(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VISITED_NODES) {
            val condition = pending.removeFirst()
            of(condition)?.let(used::add)
            when (condition) {
                is Condition.And -> condition.all.forEach(pending::addLast)
                is Condition.Or -> condition.any.forEach(pending::addLast)
                is Condition.Not -> pending.addLast(condition.cond)
                else -> Unit
            }
        }
    }

    private fun visitActions(actions: List<Action>, used: MutableSet<FeatureGate>) {
        val pending = ArrayDeque<Action>()
        actions.forEach(pending::addLast)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VISITED_NODES) {
            val action = pending.removeFirst()
            used += of(action)
            when (action) {
                is Action.If -> {
                    visitConditions(action.condition, used)
                    action.then.forEach(pending::addLast)
                    action.orElse.forEach(pending::addLast)
                }
                is Action.While -> {
                    visitConditions(action.condition, used)
                    action.body.forEach(pending::addLast)
                }
                else -> Unit
            }
        }
    }
}
