package dev.argus.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 0.8 ([FeatureGate.EXIT]): un valore del telefono che un'azione d'inizio di una regola-intervallo sta per
 * cambiare e che la fine rimette com'era. Uno per [key] e per intervallo: la prima azione che lo tocca ne
 * registra il valore di prima, le successive (anche di uno scatto ripetuto) non lo registrano più.
 */
@Serializable
sealed interface SpanSlot {
    /** Identità stabile del valore: mai un dato dell'utente oltre alla chiave approvata di un'impostazione. */
    val key: String

    @Serializable @SerialName("volume")
    data class Volume(val stream: VolumeStream) : SpanSlot {
        override val key: String get() = "volume.${stream.name.lowercase()}"
    }

    @Serializable @SerialName("ringer")
    data object Ringer : SpanSlot {
        override val key: String get() = "ringer"
    }

    @Serializable @SerialName("dnd")
    data object Dnd : SpanSlot {
        override val key: String get() = "dnd"
    }

    /** Livello e modalità automatica insieme: impostare un livello spegne anche l'automatica. */
    @Serializable @SerialName("brightness")
    data object Brightness : SpanSlot {
        override val key: String get() = "brightness"
    }

    @Serializable @SerialName("rotation")
    data object Rotation : SpanSlot {
        override val key: String get() = "rotation"
    }

    @Serializable @SerialName("wifi")
    data object Wifi : SpanSlot {
        override val key: String get() = "wifi"
    }

    @Serializable @SerialName("bluetooth")
    data object Bluetooth : SpanSlot {
        override val key: String get() = "bluetooth"
    }

    @Serializable @SerialName("mobile_data")
    data object MobileData : SpanSlot {
        override val key: String get() = "mobile_data"
    }

    @Serializable @SerialName("airplane")
    data object Airplane : SpanSlot {
        override val key: String get() = "airplane"
    }

    @Serializable @SerialName("location")
    data object Location : SpanSlot {
        override val key: String get() = "location"
    }

    @Serializable @SerialName("nfc")
    data object Nfc : SpanSlot {
        override val key: String get() = "nfc"
    }

    @Serializable @SerialName("battery_saver")
    data object BatterySaver : SpanSlot {
        override val key: String get() = "battery_saver"
    }

    @Serializable @SerialName("dark_mode")
    data object DarkMode : SpanSlot {
        override val key: String get() = "dark_mode"
    }

    @Serializable @SerialName("flashlight")
    data object Flashlight : SpanSlot {
        override val key: String get() = "flashlight"
    }

    /** Un'impostazione scritta da `write_setting`: si rilegge col lettore di stato prima di scriverla. */
    @Serializable @SerialName("setting")
    data class Setting(val namespace: SettingNamespace, val name: String) : SpanSlot {
        override val key: String get() = "setting.${namespace.name.lowercase()}.$name"
    }

    @Serializable @SerialName("user_mode")
    data object UserMode : SpanSlot {
        override val key: String get() = "user_mode"
    }
}

/** Il valore letto prima del cambio. Mai inventato: un valore che non si legge non ha un [SpanValue]. */
@Serializable
sealed interface SpanValue {
    @Serializable @SerialName("flag")
    data class Flag(val on: Boolean) : SpanValue

    /** Volume in percentuale (0..100), nella stessa scala di [Action.SetVolume]. */
    @Serializable @SerialName("percent")
    data class Percent(val level: Int) : SpanValue

    /** Luminosità in percentuale (0..100, come [Action.SetBrightness]) e modalità automatica. */
    @Serializable @SerialName("brightness")
    data class Brightness(val level: Int, val auto: Boolean) : SpanValue

    @Serializable @SerialName("ringer")
    data class Ringer(val mode: String) : SpanValue

    @Serializable @SerialName("dnd")
    data class Dnd(val mode: DndMode) : SpanValue

    @Serializable @SerialName("night")
    data class Night(val mode: NightMode) : SpanValue

    /** Il testo di un'impostazione. Un'impostazione assente non si legge come testo vuoto: non ha valore. */
    @Serializable @SerialName("text")
    data class Text(val value: String) : SpanValue {
        override fun toString(): String = "Text(value=<redacted>)"
    }

    /** La modalità utente attiva; null = nessuna modalità (un valore letto, non un valore ignoto). */
    @Serializable @SerialName("mode")
    data class Mode(val name: String?) : SpanValue
}

/** Un valore registrato all'inizio dell'intervallo, da rimettere alla fine. */
@Serializable
data class SpanRecord(val slot: SpanSlot, val value: SpanValue)

/** Un passo di ripristino: un'azione tipata già approvata nella sua forma, o il ritorno a «nessuna modalità». */
sealed interface SpanRestoreStep {
    data class Run(val action: Action) : SpanRestoreStep

    /** `set_user_mode` non ha un valore per «nessuna modalità»: il motore lo chiede alla sua porta. */
    data object ClearUserMode : SpanRestoreStep
}

/**
 * Regole della regola-intervallo ([Automation.exit], [FeatureGate.EXIT]): quali azioni cambiano un valore da
 * rimettere, come si rimette, quali trigger possono aprire e chiudere un intervallo.
 */
object SpanPolicy {
    private val RINGER_MODES = setOf("normal", "vibrate", "silent")

    /**
     * Il valore che [action] sta per cambiare, o null se l'azione non cambia un valore che si rimette. Il `when`
     * è esaustivo senza `else`: un tipo nuovo non compila finché non si decide se si rimette.
     */
    fun slotOf(action: Action): SpanSlot? = when (action) {
        is Action.SetVolume -> SpanSlot.Volume(action.stream)
        is Action.SetRinger -> SpanSlot.Ringer
        is Action.SetDnd -> SpanSlot.Dnd
        is Action.SetBrightness -> SpanSlot.Brightness
        is Action.SetRotation -> SpanSlot.Rotation
        is Action.SetWifi -> SpanSlot.Wifi
        is Action.SetBluetooth -> SpanSlot.Bluetooth
        is Action.SetMobileData -> SpanSlot.MobileData
        is Action.SetAirplane -> SpanSlot.Airplane
        is Action.SetLocation -> SpanSlot.Location
        is Action.SetNfc -> SpanSlot.Nfc
        is Action.SetBatterySaver -> SpanSlot.BatterySaver
        is Action.SetDarkMode -> SpanSlot.DarkMode
        is Action.SetFlashlight -> SpanSlot.Flashlight
        // La chiave è letterale (il validator rifiuta una chiave interpolata): è quella approvata.
        is Action.WriteSetting -> if (WriteSettingPolicy.validKey(action.key)) {
            SpanSlot.Setting(action.namespace, action.key)
        } else {
            null
        }
        is Action.SetUserMode -> SpanSlot.UserMode
        // Azioni che non lasciano un valore da rimettere (eventi, messaggi, app, calcoli, contenitori).
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
        is Action.OpenSettingsScreen,
        is Action.Vibrate,
        is Action.InvokeLlm,
        is Action.InvokeLlmV2,
        is Action.Wait,
        is Action.If,
        is Action.While,
        is Action.Speak,
        is Action.MediaControl,
        is Action.HttpRequest,
        is Action.AppControl,
        is Action.LockScreen,
        is Action.Screenshot,
        is Action.AppPermission,
        is Action.UiSteps,
        is Action.Ask,
        is Action.SetStored,
        is Action.NotificationReply,
        is Action.NotificationAction,
        is Action.SendSms,
        is Action.CallNumber,
        is Action.SendIntent,
        is Action.ScreenCall,
        -> null
    }

    /**
     * I passi che rimettono [record], nell'ordine in cui si eseguono; null se il valore non è della forma del
     * suo slot (un record corrotto o di un'altra versione non si rimette mai a caso).
     */
    fun restoreSteps(record: SpanRecord): List<SpanRestoreStep>? {
        val value = record.value
        fun run(action: Action) = listOf(SpanRestoreStep.Run(action))
        return when (val slot = record.slot) {
            is SpanSlot.Volume -> (value as? SpanValue.Percent)?.takeIf { it.level in 0..100 }
                ?.let { run(Action.SetVolume(slot.stream, it.level)) }
            SpanSlot.Ringer -> (value as? SpanValue.Ringer)?.takeIf { it.mode in RINGER_MODES }
                ?.let { run(Action.SetRinger(it.mode)) }
            SpanSlot.Dnd -> (value as? SpanValue.Dnd)?.let { run(Action.SetDnd(it.mode)) }
            // Prima il livello (che passa alla luminosità manuale), poi, se era automatica, di nuovo automatica.
            SpanSlot.Brightness -> (value as? SpanValue.Brightness)?.takeIf { it.level in 0..100 }?.let {
                buildList {
                    add(SpanRestoreStep.Run(Action.SetBrightness(level = it.level)))
                    if (it.auto) add(SpanRestoreStep.Run(Action.SetBrightness(auto = true)))
                }
            }
            SpanSlot.Rotation -> flag(value)?.let { run(Action.SetRotation(it)) }
            SpanSlot.Wifi -> flag(value)?.let { run(Action.SetWifi(it)) }
            SpanSlot.Bluetooth -> flag(value)?.let { run(Action.SetBluetooth(it)) }
            SpanSlot.MobileData -> flag(value)?.let { run(Action.SetMobileData(it)) }
            SpanSlot.Airplane -> flag(value)?.let { run(Action.SetAirplane(it)) }
            SpanSlot.Location -> flag(value)?.let { run(Action.SetLocation(it)) }
            SpanSlot.Nfc -> flag(value)?.let { run(Action.SetNfc(it)) }
            SpanSlot.BatterySaver -> flag(value)?.let { run(Action.SetBatterySaver(it)) }
            SpanSlot.Flashlight -> flag(value)?.let { run(Action.SetFlashlight(it)) }
            SpanSlot.DarkMode -> (value as? SpanValue.Night)?.let { run(Action.SetDarkMode(it.mode)) }
            is SpanSlot.Setting -> (value as? SpanValue.Text)?.let { text ->
                Action.WriteSetting(slot.namespace, slot.name, text.value)
                    .takeIf { WriteSettingPolicy.valid(it) && !InterpolationPolicy.containsInterpolation(text.value) }
                    ?.let(::run)
            }
            SpanSlot.UserMode -> (value as? SpanValue.Mode)?.let { mode ->
                when (val name = mode.name) {
                    null -> listOf(SpanRestoreStep.ClearUserMode)
                    else -> if (UserModePolicy.validLiteral(name)) run(Action.SetUserMode(name)) else null
                }
            }
        }
    }

    private fun flag(value: SpanValue): Boolean? = (value as? SpanValue.Flag)?.on

    /**
     * Un intervallo comincia molte volte: una regola che si consuma al primo scatto (immediata, ora singola,
     * «fra N minuti») sarebbe spenta subito, e lo spegnimento rimetterebbe i valori appena cambiati.
     */
    fun supportsStartTrigger(trigger: Trigger): Boolean = ConfirmPolicy.supportsTrigger(trigger)

    /**
     * La fine deve poter arrivare dopo l'inizio, una volta per intervallo: niente immediato (scatta all'arm),
     * niente orari singoli, niente avvio manuale (le scorciatoie avviano solo le regole manuali) e niente DWELL
     * (l'attesa nell'area vale per un ingresso, non per una fine).
     */
    fun supportsExitTrigger(trigger: Trigger): Boolean = when (trigger) {
        is Trigger.Immediate, is Trigger.Manual -> false
        is Trigger.Time -> !trigger.isOneShot()
        is Trigger.Geofence -> trigger.transition != Transition.DWELL
        else -> true
    }
}

/** I trigger da ascoltare per la regola: il suo e, per una regola-intervallo, quello di fine. */
val Automation.listenedTriggers: List<Trigger>
    get() = listOfNotNull(trigger, exit?.trigger)

/** Come [Automation.listenedTriggers], per una bozza (permessi e capacità chiesti all'approvazione). */
val AutomationDraft.listenedTriggers: List<Trigger>
    get() = listOfNotNull(trigger, exit?.trigger)

/** Tutte le azioni della regola: quelle d'inizio e quelle di fine. */
val Automation.allActions: List<Action>
    get() = actions + exit?.actions.orEmpty()

val AutomationDraft.allActions: List<Action>
    get() = actions + exit?.actions.orEmpty()

/** Il primo trigger della regola del tipo [T] (inizio, poi fine): per le sorgenti con una registrazione per regola. */
inline fun <reified T : Trigger> Automation.listenedTrigger(): T? = listenedTriggers.firstNotNullOfOrNull { it as? T }
