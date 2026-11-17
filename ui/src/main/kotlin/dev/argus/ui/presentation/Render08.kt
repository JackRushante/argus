package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.ConfirmSpec
import dev.argus.engine.model.Day
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.SpanPolicy
import dev.argus.engine.model.SpanSlot
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VolumeStream

/**
 * Resa di approvazione dei trigger, delle condizioni e dei campi di regola del vocabolario 0.8 (ancora
 * spenti, ma già mostrati per intero). Generata dai tipi (invariante 4), EN/IT; ogni filtro che restringe
 * lo scatto resta visibile, i letterali tra virgolette su una riga sola.
 */
internal object Trigger08Render {

    fun calendarEvent(t: Trigger.CalendarEvent, l: RenderLanguage): String {
        val minutes = kotlin.math.abs(t.offsetMinutes.toLong())
        val span = RuleRenderMapper.humanizeDuration(minutes * 60_000, l)
        val moment = when (t.phase) {
            CalendarPhase.START -> when {
                t.offsetMinutes < 0 -> l.pick(
                    "$span before a calendar event starts",
                    "$span prima che inizi un evento del calendario",
                )
                t.offsetMinutes > 0 -> l.pick(
                    "$span after a calendar event starts",
                    "$span dopo l'inizio di un evento del calendario",
                )
                else -> l.pick("a calendar event starts", "inizia un evento del calendario")
            }
            CalendarPhase.END -> when {
                t.offsetMinutes < 0 -> l.pick(
                    "$span before a calendar event ends",
                    "$span prima che finisca un evento del calendario",
                )
                t.offsetMinutes > 0 -> l.pick(
                    "$span after a calendar event ends",
                    "$span dopo la fine di un evento del calendario",
                )
                else -> l.pick("a calendar event ends", "finisce un evento del calendario")
            }
        }
        return RuleRenderMapper.whenPrefix(l) + moment + calendarFilters(t.titleMatch, t.calendar, l)
    }

    fun nfcTag(t: Trigger.NfcTag, l: RenderLanguage): String {
        val line = when {
            t.resolveTag && t.tagId.isEmpty() -> l.pick(
                "you hold the phone near the NFC tag that is read when you turn on the rule",
                "avvicini il telefono al tag NFC che viene letto quando attivi la regola",
            )
            t.resolveTag -> l.pick(
                "you hold the phone near the NFC tag ${quoted(t.tagId)} (read again when you turn on the rule)",
                "avvicini il telefono al tag NFC ${quoted(t.tagId)} (riletto quando attivi la regola)",
            )
            t.tagId.isEmpty() -> l.pick(
                "you hold the phone near an NFC tag that is not set yet",
                "avvicini il telefono a un tag NFC non ancora impostato",
            )
            else -> l.pick(
                "you hold the phone near the NFC tag ${quoted(t.tagId)}",
                "avvicini il telefono al tag NFC ${quoted(t.tagId)}",
            )
        }
        return RuleRenderMapper.whenPrefix(l) + line
    }

    fun nextAlarm(t: Trigger.NextAlarm, l: RenderLanguage): String {
        val span = RuleRenderMapper.humanizeDuration(kotlin.math.abs(t.offsetMinutes.toLong()) * 60_000, l)
        val moment = when {
            t.offsetMinutes > 0 -> l.pick(
                "$span before your next alarm",
                "$span prima della prossima sveglia",
            )
            t.offsetMinutes < 0 -> l.pick(
                "$span after your next alarm",
                "$span dopo la prossima sveglia",
            )
            else -> l.pick("your next alarm goes off", "suona la prossima sveglia")
        }
        return RuleRenderMapper.whenPrefix(l) + moment
    }

    fun appUsage(t: Trigger.AppUsage, l: RenderLanguage): String {
        val app = AppLabels.labelOf(t.pkg)?.let { "$it (${t.pkg})" } ?: t.pkg
        val span = RuleRenderMapper.humanizeDuration(t.minutes.toLong() * 60_000, l)
        return RuleRenderMapper.whenPrefix(l) + l.pick(
            "you have used the app $app for $span today (at most once a day)",
            "hai usato l'app $app per $span oggi (al massimo una volta al giorno)",
        )
    }

    fun userModeChanged(t: Trigger.UserModeChanged, l: RenderLanguage): String =
        RuleRenderMapper.whenPrefix(l) + (
            t.to?.let { l.pick("the user mode becomes ${quoted(it)}", "la modalità utente diventa ${quoted(it)}") }
                ?: l.pick("the user mode changes", "cambia la modalità utente")
            )

    fun shake(l: RenderLanguage): String = l.pick("you shake the phone", "scuoti il telefono")

    fun flipFaceDown(l: RenderLanguage): String =
        l.pick("you turn the phone face down", "giri il telefono a faccia in giù")

    internal fun calendarFilters(titleMatch: String?, calendar: String?, l: RenderLanguage): String =
        (titleMatch?.let { l.pick(" · title contains ${quoted(it)}", " · il titolo contiene ${quoted(it)}") } ?: "") +
            (calendar?.let { l.pick(" · calendar ${quoted(it)}", " · calendario ${quoted(it)}") } ?: "")

    internal fun quoted(text: String): String = "\"${VisibleText.singleLine(text)}\""
}

/** Condizioni 0.8: il testo dopo «Solo se»/«Finché» (il prefisso e il rientro li mette il mapper). */
internal object Condition08Render {

    fun weekday(days: Set<Day>, tz: String, l: RenderLanguage): String {
        val names = days.joinToString(", ") { dayName(it, l) }
        return l.pick("today is $names ($tz)", "oggi è $names ($tz)")
    }

    fun wifiConnected(ssid: String?, l: RenderLanguage): String = ssid?.let {
        l.pick(
            "the phone is connected to the Wi-Fi network ${Trigger08Render.quoted(it)}",
            "il telefono è connesso alla rete Wi-Fi ${Trigger08Render.quoted(it)}",
        )
    } ?: l.pick("the phone is connected to any Wi-Fi network", "il telefono è connesso a una rete Wi-Fi qualsiasi")

    fun bluetoothConnected(device: String?, l: RenderLanguage): String = device?.let {
        l.pick(
            "the Bluetooth device ${Trigger08Render.quoted(it)} is connected",
            "il dispositivo Bluetooth ${Trigger08Render.quoted(it)} è connesso",
        )
    } ?: l.pick("any Bluetooth device is connected", "è connesso un dispositivo Bluetooth qualsiasi")

    fun headsetConnected(kind: AudioOutputKind, l: RenderLanguage): String = when (kind) {
        AudioOutputKind.WIRED -> l.pick("wired headphones are connected", "sono collegate cuffie con il filo")
        AudioOutputKind.BLUETOOTH -> l.pick("Bluetooth headphones are connected", "sono collegate cuffie Bluetooth")
        AudioOutputKind.ANY -> l.pick(
            "headphones are connected (wired or Bluetooth)",
            "sono collegate cuffie (con il filo o Bluetooth)",
        )
    }

    fun calendarBusy(titleMatch: String?, calendar: String?, l: RenderLanguage): String =
        l.pick("a calendar event is in progress", "è in corso un evento del calendario") +
            Trigger08Render.calendarFilters(titleMatch, calendar, l)

    fun storedCompare(
        key: String,
        type: String,
        op: String,
        expected: String,
        l: RenderLanguage,
    ): String = l.pick(
        "the stored value ${Trigger08Render.quoted(key)} [$type] $op ${Trigger08Render.quoted(expected)}",
        "il valore conservato ${Trigger08Render.quoted(key)} [$type] $op ${Trigger08Render.quoted(expected)}",
    )

    fun userModeIs(mode: String, l: RenderLanguage): String = l.pick(
        "the user mode is ${Trigger08Render.quoted(mode)}",
        "la modalità utente è ${Trigger08Render.quoted(mode)}",
    )

    private fun dayName(day: Day, l: RenderLanguage): String = when (day) {
        Day.MON -> l.pick("Monday", "lunedì")
        Day.TUE -> l.pick("Tuesday", "martedì")
        Day.WED -> l.pick("Wednesday", "mercoledì")
        Day.THU -> l.pick("Thursday", "giovedì")
        Day.FRI -> l.pick("Friday", "venerdì")
        Day.SAT -> l.pick("Saturday", "sabato")
        Day.SUN -> l.pick("Sunday", "domenica")
    }
}

/** Campi di regola 0.8: conferma prima di eseguire e regola-intervallo. */
internal object RuleSpanRender {

    fun confirmLine(confirm: ConfirmSpec, l: RenderLanguage): String {
        val timeout = RuleRenderMapper.humanizeDuration(confirm.timeoutMs, l)
        // 0.8: il testo approvato è questo (Run/Esegui o Annulla in una notifica; senza risposta non esegue).
        return l.pick(
            "Asks for confirmation before running (within $timeout)",
            "Chiede conferma prima di eseguire (entro $timeout)",
        )
    }

    /**
     * La regola-intervallo resa come una regola sola: fino a quando dura (il trigger di fine, [triggerLine] già reso
     * dal mapper) e che cosa fa alla fine. I valori che rimette si elencano dai TIPI delle azioni d'inizio
     * ([startActions]), mai dalla prosa del modello (invariante 4), e si dice che un valore cambiato a mano nel
     * frattempo torna comunque com'era.
     */
    fun exitLine(exit: ExitSpec, triggerLine: String, startActions: List<Action>, l: RenderLanguage): String {
        val text = listOf(RuleRenderMapper.whenPrefix(RenderLanguage.EN), RuleRenderMapper.whenPrefix(RenderLanguage.IT))
            .firstOrNull { triggerLine.startsWith(it) }?.let { triggerLine.removePrefix(it) } ?: triggerLine
        val until = l.pick("Until this happens: ", "Finché non succede questo: ") + text
        val end = if (exit.restore) {
            val values = restoredValues(startActions, l)
            val listed = if (values.isEmpty()) "" else " (${values.joinToString(", ")})"
            l.pick(
                " · At the end: it puts back the values the start changed$listed. " +
                    "A value you change by hand in the meantime is still put back as it was",
                " · Alla fine: rimette com'era i valori cambiati dall'inizio$listed. " +
                    "Anche un valore cambiato a mano nel frattempo torna com'era",
            )
        } else {
            l.pick(" · At the end: it does not put back the previous values", " · Alla fine: non rimette i valori di prima")
        }
        val actions = if (exit.actions.isEmpty()) {
            ""
        } else {
            l.pick(" · then it runs the end actions below", " · poi esegue le azioni di fine qui sotto")
        }
        return until + end + actions
    }

    /** I valori, uno per tipo e nell'ordine delle azioni (anche annidate), che la fine rimette. */
    private fun restoredValues(actions: List<Action>, l: RenderLanguage): List<String> {
        val pending = ArrayDeque(actions)
        val slots = linkedMapOf<String, SpanSlot>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VISITED) {
            when (val action = pending.removeFirst()) {
                is Action.If -> {
                    pending.addAll(action.then)
                    pending.addAll(action.orElse)
                }
                is Action.While -> pending.addAll(action.body)
                else -> SpanPolicy.slotOf(action)?.let { slots.putIfAbsent(it.key, it) }
            }
        }
        return slots.values.map { slotLabel(it, l) }
    }

    private fun slotLabel(slot: SpanSlot, l: RenderLanguage): String = when (slot) {
        is SpanSlot.Volume -> when (slot.stream) {
            VolumeStream.MEDIA -> l.pick("media volume", "volume multimediale")
            VolumeStream.RING -> l.pick("ring volume", "volume della suoneria")
            VolumeStream.ALARM -> l.pick("alarm volume", "volume della sveglia")
            VolumeStream.NOTIFICATION -> l.pick("notification volume", "volume delle notifiche")
        }
        SpanSlot.Ringer -> l.pick("ringer mode", "modalità della suoneria")
        SpanSlot.Dnd -> l.pick("Do Not Disturb", "Non disturbare")
        SpanSlot.Brightness -> l.pick("brightness", "luminosità")
        SpanSlot.Rotation -> l.pick("auto-rotate", "rotazione automatica")
        SpanSlot.Wifi -> "Wi-Fi"
        SpanSlot.Bluetooth -> "Bluetooth"
        SpanSlot.MobileData -> l.pick("mobile data", "dati mobili")
        SpanSlot.Airplane -> l.pick("airplane mode", "modalità aereo")
        SpanSlot.Location -> l.pick("location", "localizzazione")
        SpanSlot.Nfc -> "NFC"
        SpanSlot.BatterySaver -> l.pick("battery saver", "risparmio energetico")
        SpanSlot.DarkMode -> l.pick("dark theme", "tema scuro")
        SpanSlot.Flashlight -> l.pick("flashlight", "torcia")
        is SpanSlot.Setting -> l.pick("the setting ", "l'impostazione ") + Trigger08Render.quoted(slot.name)
        SpanSlot.UserMode -> l.pick("the mode", "la modalità")
    }

    private const val MAX_VISITED = 4_096
}
