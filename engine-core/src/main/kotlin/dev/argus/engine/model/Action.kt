@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.argus.engine.model
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class DndMode { OFF, PRIORITY, TOTAL }
enum class ActionTier { DETERMINISTIC, GENERATIVE }

/**
 * Sink di consegna del testo generato da [Action.InvokeLlm] (#59). Enum CHIUSO e SENZA @SerialName:
 * serializza col nome UPPERCASE, coerente con [ArgusJson] case-sensitive.
 * - [WHATSAPP_REPLY]: il testo è inviato come reply WhatsApp al mittente del trigger (profilo P1).
 * - [LOCAL_NOTIFICATION]: il testo diventa una notifica locale, da un trigger qualsiasi.
 */
@Serializable
enum class GenerativeDeliverMode { WHATSAPP_REPLY, LOCAL_NOTIFICATION }

/** Stream audio target di [Action.SetVolume]. Enum chiuso: mappato 1:1 su AudioManager.STREAM_*. */
enum class VolumeStream { MEDIA, RING, ALARM, NOTIFICATION }

/**
 * Schermata Impostazioni target di [Action.OpenSettingsScreen]. Enum CHIUSO (mai una action-string
 * arbitraria): evita il routing-sink di `startActivity` verso Intent Settings.ACTION_* liberi.
 * `APP_DETAILS` è l'unico che consuma un `pkg`.
 */
enum class SettingsScreen { WIFI, BLUETOOTH, DISPLAY, SOUND, LOCATION, BATTERY, DATE, APP_DETAILS, SETTINGS }

/** Marker chiuso per impedire che la lane accetti azioni deterministiche per errore. */
sealed interface GenerativeAction

/** Discriminatori wire stabili condivisi da JSON, manifest capability e journal. */
object ActionTypeIds {
    const val SET_WIFI = "set_wifi"
    const val SET_BLUETOOTH = "set_bluetooth"
    const val SET_DND = "set_dnd"
    const val SET_RINGER = "set_ringer"
    const val LAUNCH_APP = "launch_app"
    const val OPEN_URL = "open_url"
    const val SHOW_NOTIFICATION = "show_notification"
    const val TAP = "tap"
    const val INPUT_TEXT = "input_text"
    const val WHATSAPP_REPLY = "whatsapp_reply"
    const val RUN_SHELL = "run_shell"
    const val COPY_TO_CLIPBOARD = "copy_to_clipboard"
    const val COPY_TEXT = "copy_text"
    const val SET_MOBILE_DATA = "set_mobile_data"
    const val SET_ALARM = "set_alarm"
    const val SET_TIMER = "set_timer"
    const val SET_VOLUME = "set_volume"
    const val SET_FLASHLIGHT = "set_flashlight"
    const val OPEN_SETTINGS_SCREEN = "open_settings_screen"
    const val VIBRATE = "vibrate"
    const val WRITE_SETTING = "write_setting"
    const val INVOKE_LLM = "invoke_llm"
    const val INVOKE_LLM_V2 = "invoke_llm_v2"
    const val WAIT = "wait"
    // Control-flow strutturato P4 §2.3: contenitori, non azioni foglia.
    const val IF = "if"
    const val WHILE = "while"
}

/**
 * Profilo P1 dell'azione generativa: l'unico contratto InvokeLlm che la lane esegue davvero.
 * Validator, derivazione capability e lane devono restare allineati a queste costanti.
 */
object GenerativeContract {
    const val CONTEXT_NOTIFICATION = "notification"
    const val CONTEXT_STATE = "state"
    /** Tool wire di reply: coincide con ActionTypeIds.WHATSAPP_REPLY. */
    const val TOOL_WHATSAPP_REPLY = ActionTypeIds.WHATSAPP_REPLY
    /** Tool raw richiesto a runtime quando il contesto include lo stato device. */
    const val TOOL_STATE_READ = "state.read"
    /** Tool web di sola lettura, server-side (wire-name col punto). */
    const val TOOL_WEB_SEARCH = "web.search"
    val CONTEXT_SOURCES: Set<String> = setOf(CONTEXT_NOTIFICATION, CONTEXT_STATE)
    val ALLOWED_TOOLS: List<String> = listOf(TOOL_WHATSAPP_REPLY)
    /** Tool di contesto opzionali di sola lettura ammessi accanto al reply obbligatorio. */
    val OPTIONAL_TOOLS: Set<String> = setOf(TOOL_WEB_SEARCH)

    /** allowedTools valido per un'azione generativa: contiene il reply (sink obbligatorio), nessun
     *  duplicato, e per il resto SOLO tool di contesto opzionali di sola lettura (web). shell/automation.*
     *  restano vietati da DraftValidator. */
    fun isAllowedToolset(tools: List<String>): Boolean =
        TOOL_WHATSAPP_REPLY in tools &&
            tools.size == tools.toSet().size &&
            tools.all { it == TOOL_WHATSAPP_REPLY || it in OPTIONAL_TOOLS }

    /** allowedTools valido per il sink NOTIFICA (#59): nessun duplicato, SOLO [TOOL_WEB_SEARCH]
     *  opzionale, MAI whatsapp_reply (la notifica È il sink, non un tool di uscita). Lista vuota
     *  valida (il testo si genera dal solo goal). */
    fun isNotificationToolset(tools: List<String>): Boolean =
        tools.size == tools.toSet().size && tools.all { it == TOOL_WEB_SEARCH }
}

@Serializable
sealed interface Action {
    val tier: ActionTier
        get() = when (this) {
            is InvokeLlm,
            is InvokeLlmV2,
            -> ActionTier.GENERATIVE
            // Control-flow: generativo se e solo se contiene (a qualsiasi profondità) un'azione
            // generativa. Così il gate cooldown generativo del validator scatta anche su generativi
            // annidati in if/while (fail-closed sul tier aggregato).
            is If -> if (containsGenerative(then + orElse)) {
                ActionTier.GENERATIVE
            } else {
                ActionTier.DETERMINISTIC
            }
            is While -> if (containsGenerative(body)) {
                ActionTier.GENERATIVE
            } else {
                ActionTier.DETERMINISTIC
            }
            is SetWifi,
            is SetBluetooth,
            is SetMobileData,
            is SetDnd,
            is SetRinger,
            is LaunchApp,
            is OpenUrl,
            is ShowNotification,
            is Tap,
            is InputText,
            is WhatsAppReply,
            is RunShell,
            is CopyToClipboard,
            is CopyText,
            is SetAlarm,
            is SetTimer,
            is SetVolume,
            is SetFlashlight,
            is OpenSettingsScreen,
            is Vibrate,
            is WriteSetting,
            is Wait,
            -> ActionTier.DETERMINISTIC
        }

    @Serializable @SerialName(ActionTypeIds.SET_WIFI) data class SetWifi(val on: Boolean) : Action
    @Serializable @SerialName(ActionTypeIds.SET_BLUETOOTH) data class SetBluetooth(val on: Boolean) : Action

    /** Toggle dei DATI MOBILI via `svc data enable|disable`. Clone privilegiato di [SetWifi]:
     *  PRIVILEGED (Shizuku), nessun percorso app-normale. */
    @Serializable @SerialName(ActionTypeIds.SET_MOBILE_DATA) data class SetMobileData(val on: Boolean) : Action
    @Serializable @SerialName(ActionTypeIds.SET_DND) data class SetDnd(val mode: DndMode) : Action
    @Serializable @SerialName(ActionTypeIds.SET_RINGER) data class SetRinger(val mode: String) : Action
    @Serializable @SerialName(ActionTypeIds.LAUNCH_APP) data class LaunchApp(val pkg: String) : Action
    @Serializable @SerialName(ActionTypeIds.OPEN_URL) data class OpenUrl(val url: String) : Action
    @Serializable @SerialName(ActionTypeIds.SHOW_NOTIFICATION) data class ShowNotification(val title: String, val text: String) : Action
    @Serializable @SerialName(ActionTypeIds.TAP) data class Tap(val x: Int, val y: Int) : Action
    @Serializable @SerialName(ActionTypeIds.INPUT_TEXT) data class InputText(val text: String) : Action
    @Serializable @SerialName(ActionTypeIds.WHATSAPP_REPLY) data class WhatsAppReply(val text: String) : Action
    /** [captureAs] (P4 §2.2): se presente, lo stdout del comando è catturato nella variabile
     *  omonima con taint TAINTED. @EncodeDefault(NEVER): assente ⇒ byte v1 stabili. */
    @Serializable @SerialName(ActionTypeIds.RUN_SHELL)
    data class RunShell(
        val cmd: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val captureAs: String? = null,
    ) : Action

    /** Copia negli appunti il payload testuale del trigger (SMS o notifica), opzionalmente
     *  ridotto al primo capture group di una regex RE2 lineare (P2-3, OTP). Estrazione
     *  DETERMINISTICA: il testo non lascia mai il telefono. */
    @Serializable @SerialName(ActionTypeIds.COPY_TO_CLIPBOARD)
    data class CopyToClipboard(val extractionRegex: String? = null) : Action

    /** Copia negli appunti una stringa LETTERALE approvata. A differenza di [CopyToClipboard] non
     *  dipende dal trigger (nessun payload testuale richiesto): il campo [text] è il testo da
     *  copiare, interpolabile con `${'$'}{var}`. BASE: nessuno Shizuku, scrittura clipboard locale. */
    @Serializable @SerialName(ActionTypeIds.COPY_TEXT)
    data class CopyText(val text: String) : Action

    /** Imposta la SVEGLIA reale dell'app orologio via Intent `AlarmClock.ACTION_SET_ALARM`
     *  (NON una notifica). BASE: solo il permesso manifest normal `SET_ALARM`, nessuno Shizuku.
     *  Range validato: hour 0..23, minute 0..59. `skipUi` è un hint per non aprire l'app orologio. */
    @Serializable @SerialName(ActionTypeIds.SET_ALARM)
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String? = null,
        val skipUi: Boolean = true,
    ) : Action

    /** Avvia un TIMER reale via Intent `AlarmClock.ACTION_SET_TIMER`. BASE come [SetAlarm].
     *  Range validato: seconds 1..86400. */
    @Serializable @SerialName(ActionTypeIds.SET_TIMER)
    data class SetTimer(
        val seconds: Int,
        val label: String? = null,
        val skipUi: Boolean = true,
    ) : Action

    /** Volume per stream via `AudioManager.setStreamVolume`. BASE, nessun permesso: la gate DND
     *  ([BaseActionSurface.isDndPolicyGranted]) scatta solo se porta RING/NOTIFICATION a 0
     *  (silenziamento). `level` è ora una **percentuale 0..100** (non più un indice assoluto dello
     *  stream): l'executor la mappa sul massimo reale dello stream a runtime
     *  (`getStreamMaxVolume(stream)`), con 100 = massimo e ogni percentuale > 0 che non silenzia mai
     *  (minimo 1). Validato in 0..100. */
    @Serializable @SerialName(ActionTypeIds.SET_VOLUME)
    data class SetVolume(
        val stream: VolumeStream,
        val level: Int,
    ) : Action

    /** Torcia on/off via `CameraManager.setTorchMode` sulla camera con flash. BASE, nessun permesso
     *  (API 23+). Fallisce `torch_unavailable` se nessuna camera con flash o CameraAccessException. */
    @Serializable @SerialName(ActionTypeIds.SET_FLASHLIGHT)
    data class SetFlashlight(val on: Boolean) : Action

    /** Apre una schermata Impostazioni via Intent `Settings.ACTION_*` mappato da un enum CHIUSO
     *  ([SettingsScreen]): nessuna action-string arbitraria (evita il routing-sink). BASE,
     *  `startActivity` NEW_TASK. `pkg` serve solo per [SettingsScreen.APP_DETAILS]. */
    @Serializable @SerialName(ActionTypeIds.OPEN_SETTINGS_SCREEN)
    data class OpenSettingsScreen(
        val screen: SettingsScreen,
        val pkg: String? = null,
    ) : Action

    /** Vibrazione one-shot via `Vibrator.vibrate(VibrationEffect.createOneShot)`. BASE, permesso
     *  normal `VIBRATE`. `durationMs` validato in 1..10000. */
    @Serializable @SerialName(ActionTypeIds.VIBRATE)
    data class Vibrate(val durationMs: Int) : Action

    /**
     * Pausa cooperativa dentro un programma P4. È interpretata dal [ProgramInterpreter], non da
     * Android/Shizuku: serve a rendere esprimibili sequenze temporali reali (per esempio torcia
     * on → wait 500 ms → off) senza attribuire una semantica sorprendente al delay fra i loop.
     */
    @Serializable @SerialName(ActionTypeIds.WAIT)
    data class Wait(val durationMs: Long) : Action

    /**
     * Scrittura PARAMETRICA di un'impostazione Android (`system|secure|global`) per chiave —
     * contraltare WRITE di [StateQuery.Setting]. È il pezzo di "libertà di automazione assoluta"
     * (direttiva D0 "limiti solo etici"): scrive QUALSIASI chiave, non un'allowlist curata.
     *
     * Sempre PRIVILEGED (Shizuku): `settings put` su secure/global non ha percorso app-normale.
     *
     * L'unico invariante NON negoziabile è D2 (decision-record §4.2, "un dato non fidato non può
     * creare autorità"): `namespace`/`key`/`value` sono LETTERALI CLEAN nel fingerprint approvato,
     * MAI interpolati dal contenuto del trigger (SMS/notifiche) — identico regime di [RunShell].
     * Guardrail = validazione ([WriteSettingPolicy]: regex key, value bounded, control char/NUL/
     * newline rifiutati; argv separati quindi niente shell injection) + review umana pre-arm
     * (l'utente vede namespace/key/value letterali). Nessun altro limite.
     */
    @Serializable @SerialName(ActionTypeIds.WRITE_SETTING)
    data class WriteSetting(
        val namespace: SettingNamespace,
        val key: String,
        val value: String,
    ) : Action

    @Serializable @SerialName(ActionTypeIds.INVOKE_LLM)
    data class InvokeLlm(
        val goal: String,
        val contextSources: List<String>,
        val allowedTools: List<String>,   // MAI shell.run / automation.* (DraftValidator, spec §7)
        val replyTargetSender: Boolean,   // spec §10.4: destinatario vincolato al trigger.sender
        val timeoutMs: Long = 60_000,
        // Sink di consegna (#59). @EncodeDefault(NEVER): il ramo reply (default) NON serializza
        // questi campi, così i fingerprint v1 già approvati restano byte-for-byte stabili e le
        // regole reply esistenti non richiedono ri-approvazione. Solo LOCAL_NOTIFICATION li emette.
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val deliver: GenerativeDeliverMode = GenerativeDeliverMode.WHATSAPP_REPLY,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val notificationTitle: String? = null,
        // [captureAs] (P4 §2.2): output del modello catturato nella variabile omonima (taint
        // TAINTED). @EncodeDefault(NEVER): assente ⇒ i fingerprint v1 già approvati restano stabili.
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val captureAs: String? = null,
    ) : Action, GenerativeAction

    /**
     * Profilo P3 con stato minimo: a differenza di [InvokeLlm] non autorizza uno snapshot
     * generico. Ogni reader, tipo e classificazione entra nel fingerprint approvato.
     *
     * Tutti i campi wire sono obbligatori: una regola v1 non viene mai migrata aggiungendo
     * default o riscrivendone il fingerprint in silenzio.
     */
    @Serializable @SerialName(ActionTypeIds.INVOKE_LLM_V2)
    data class InvokeLlmV2(
        val goal: String,
        val stateContext: List<ApprovedStateContext>,
        val allowedTools: List<String>,
        val replyTargetSender: Boolean,
        val timeoutMs: Long,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val captureAs: String? = null,
    ) : Action, GenerativeAction

    /**
     * Control-flow strutturato P4 §2.3 (mai goto, mai eval): esegue [then] se [condition] è vera,
     * altrimenti [orElse]. NON implementa [GenerativeAction] (è un contenitore, non un'azione
     * generativa foglia). L'interprete deterministico (P4-B) cammina l'albero; qui è solo modello.
     */
    @Serializable @SerialName(ActionTypeIds.IF)
    data class If(
        val condition: FlowCondition,
        val then: List<Action>,
        val orElse: List<Action> = emptyList(),
    ) : Action

    /**
     * Ripetizione BOUNDED P4 §2.3: ri-valuta [condition] a ogni iterazione con stato aggiornato,
     * con [delayBetweenMs] fra i giri (0..3_600_000). Contatore + deadline dura impediscono i loop
     * infiniti.
     *
     * Il conteggio massimo è espresso da ESATTAMENTE UNO tra:
     * - [maxIterations]: intero letterale 1..1000 (invariante storico, wire byte-identico);
     * - [maxIterationsVar]: nome di una variabile NUMBER (es. un `random_int`) letta a runtime e
     *   clampata in 1..1000. Sblocca "gira ${'$'}{n} volte" con n dinamico.
     *
     * L'invariante XOR è imposto dal DraftValidator (statico) e dal ProgramInterpreter (fail-closed a
     * runtime). Entrambi @EncodeDefault(NEVER): un while con solo maxIterations serializza byte-identico
     * a P4-A (fingerprint v1 stabile), e maxIterationsVar è additivo, mai emesso quando assente.
     */
    @Serializable @SerialName(ActionTypeIds.WHILE)
    data class While(
        val condition: FlowCondition,
        val body: List<Action>,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maxIterations: Int? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val maxIterationsVar: String? = null,
        val delayBetweenMs: Long = 0,
    ) : Action
}

/** Visita iterativa: il tier non può causare stack overflow su un albero non ancora validato. */
private fun containsGenerative(actions: List<Action>): Boolean {
    val pending = ArrayDeque<Action>()
    actions.forEach(pending::addLast)
    while (pending.isNotEmpty()) {
        when (val action = pending.removeFirst()) {
            is Action.InvokeLlm, is Action.InvokeLlmV2 -> return true
            is Action.If -> {
                action.then.forEach(pending::addLast)
                action.orElse.forEach(pending::addLast)
            }
            is Action.While -> action.body.forEach(pending::addLast)
            else -> Unit
        }
    }
    return false
}

/** Metadati approvati per un singolo valore locale inviato al Brain configurato. */
@Serializable
data class ApprovedStateContext(
    val query: StateQuery,
    val valueType: StateValueType,
    val policyVersion: Int,
    val integrity: IntegrityLabel,
    val confidentiality: ConfidentialityLabel,
)

/** Classificazione minima fail-closed: il compilatore può alzarla, mai abbassarla. */
object StateContextClassification {
    const val MAX_QUERIES = 16

    fun minimumConfidentiality(query: StateQuery): ConfidentialityLabel = when (query) {
        is StateQuery.Builtin -> ConfidentialityLabel.PRIVATE
        is StateQuery.Setting,
        is StateQuery.SystemProperty,
        is StateQuery.Sysfs,
        is StateQuery.DumpsysField,
        -> ConfidentialityLabel.SECRET
    }

    fun covers(actual: ConfidentialityLabel, minimum: ConfidentialityLabel): Boolean =
        actual.ordinal >= minimum.ordinal

    fun validValueType(query: StateQuery, valueType: StateValueType): Boolean =
        (query as? StateQuery.Builtin)?.let { builtin ->
            when (builtin.key) {
                StateKeys.BATTERY -> valueType == StateValueType.NUMBER
                StateKeys.CHARGING -> valueType == StateValueType.BOOLEAN
                else -> valueType == StateValueType.TEXT
            }
        } ?: true
}
