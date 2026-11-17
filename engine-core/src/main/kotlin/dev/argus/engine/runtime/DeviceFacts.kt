package dev.argus.engine.runtime

import dev.argus.engine.model.AudioOutputKind

/**
 * 0.8: letture del device delle condizioni di contesto (`wifi_connected`, `bluetooth_connected`,
 * `headset_connected`, `calendar_busy`). Si leggono solo se una regola le chiede ([StateReadRequest.facts]);
 * un fatto non letto o non leggibile resta `null` e la condizione è UNKNOWN (invariante 8).
 */
enum class DeviceFact {
    /** Collegato o no a una rete Wi-Fi. Nessun permesso. */
    WIFI,

    /** Il nome della rete Wi-Fi: chiede la posizione, come l'identità Wi-Fi del trigger connectivity. */
    WIFI_SSID,

    /** I dispositivi Bluetooth collegati. */
    BLUETOOTH,

    /** I tipi di cuffie o auricolari collegati. */
    AUDIO_OUTPUTS,

    /** Le istanze di calendario in corso adesso. */
    CALENDAR_NOW,
}

/**
 * Collegamento Wi-Fi. [ssid] è null quando non è stato chiesto o non si è potuto leggere: una condizione
 * con un nome di rete resta allora UNKNOWN. Il nome non entra mai nei log.
 */
data class WifiLink(val connected: Boolean, val ssid: String? = null) {
    override fun toString(): String = "WifiLink(connected=$connected, ssid=${if (ssid == null) "null" else "<redacted>"})"
}

/** Un dispositivo Bluetooth collegato: indirizzo e nome, ciascuno null se non leggibile. Mai nei log. */
data class BluetoothLink(val address: String?, val name: String?) {
    override fun toString(): String =
        "BluetoothLink(address=${address?.let { "<redacted>" }}, name=${name?.let { "<redacted>" }})"
}

/**
 * I dispositivi Bluetooth collegati. [complete] = l'elenco è completo (adattatore spento, o tutte le
 * interrogazioni riuscite): solo allora l'assenza di un dispositivo vale «non collegato».
 */
data class BluetoothLinks(val devices: List<BluetoothLink>, val complete: Boolean)

/** Le letture di [DeviceFact]: null = non chiesta o non leggibile. */
data class DeviceFacts(
    val wifi: WifiLink? = null,
    val bluetooth: BluetoothLinks? = null,
    val audioOutputs: Set<AudioOutputKind>? = null,
    val calendarNow: List<CalendarInstance>? = null,
) {
    /** Solo ciò che [facts] chiede: il nome della rete esce se non c'è [DeviceFact.WIFI_SSID]. */
    fun project(facts: Set<DeviceFact>): DeviceFacts = DeviceFacts(
        wifi = wifi
            ?.takeIf { DeviceFact.WIFI in facts || DeviceFact.WIFI_SSID in facts }
            ?.let { if (DeviceFact.WIFI_SSID in facts) it else it.copy(ssid = null) },
        bluetooth = bluetooth.takeIf { DeviceFact.BLUETOOTH in facts },
        audioOutputs = audioOutputs.takeIf { DeviceFact.AUDIO_OUTPUTS in facts },
        calendarNow = calendarNow.takeIf { DeviceFact.CALENDAR_NOW in facts },
    )

    /** La lettura più recente vince, fatto per fatto. */
    fun merge(other: DeviceFacts): DeviceFacts = DeviceFacts(
        wifi = other.wifi ?: wifi,
        bluetooth = other.bluetooth ?: bluetooth,
        audioOutputs = other.audioOutputs ?: audioOutputs,
        calendarNow = other.calendarNow ?: calendarNow,
    )
}

/**
 * Un'istanza di un evento del calendario del telefono (una ricorrenza = un'istanza). Titolo e nome del
 * calendario sono dati di altri (un invito): restano in RAM e non entrano mai nei log.
 */
data class CalendarInstance(
    val eventId: Long,
    val beginMillis: Long,
    val endMillis: Long,
    val title: String?,
    val calendarName: String?,
    val allDay: Boolean,
    /** L'utente ha rifiutato l'invito. */
    val declined: Boolean,
    /** L'evento è segnato «libero» (disponibilità). */
    val free: Boolean,
    /** L'evento è annullato. */
    val canceled: Boolean = false,
) {
    override fun toString(): String =
        "CalendarInstance(eventId=$eventId, begin=$beginMillis, end=$endMillis, allDay=$allDay, " +
            "declined=$declined, free=$free, canceled=$canceled)"
}

/** Regole comuni a `calendar_busy` e al trigger `calendar_event`. */
object CalendarMatching {
    /** Contano solo gli impegni veri: non quelli di tutto il giorno, rifiutati, «liberi» o annullati. */
    fun counts(instance: CalendarInstance): Boolean =
        !instance.allDay && !instance.declined && !instance.free && !instance.canceled

    /** [titleMatch] = il titolo lo contiene (senza maiuscole); [calendar] = nome esatto (senza maiuscole). */
    fun matches(instance: CalendarInstance, titleMatch: String?, calendar: String?): Boolean =
        counts(instance) &&
            (titleMatch == null || instance.title?.contains(titleMatch, ignoreCase = true) == true) &&
            (calendar == null || instance.calendarName?.equals(calendar, ignoreCase = true) == true)

    /** Un'istanza che combacia è in corso a [nowMillis] (inizio compreso, fine esclusa). */
    fun busy(instances: List<CalendarInstance>, nowMillis: Long, titleMatch: String?, calendar: String?): Boolean =
        instances.any { instance ->
            matches(instance, titleMatch, calendar) &&
                instance.beginMillis <= nowMillis && nowMillis < instance.endMillis
        }
}

/** Valutazione delle condizioni di connessione su letture complete o parziali. null = UNKNOWN. */
internal object ConnectionFacts {
    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    fun wifi(link: WifiLink?, ssid: String?): Boolean? {
        link ?: return null
        if (!link.connected) return false
        if (ssid == null) return true
        return link.ssid?.let { it == ssid }
    }

    fun bluetooth(links: BluetoothLinks?, device: String?): Boolean? {
        links ?: return null
        val byMac = device != null && MAC.matches(device)
        var undecided = !links.complete
        for (link in links.devices) {
            if (device == null) return true
            val value = if (byMac) link.address else link.name
            when {
                value == null -> undecided = true
                value.equals(device, ignoreCase = true) -> return true
            }
        }
        return if (undecided) null else false
    }

    fun headset(outputs: Set<AudioOutputKind>?, kind: AudioOutputKind): Boolean? {
        outputs ?: return null
        return if (kind == AudioOutputKind.ANY) outputs.any { it != AudioOutputKind.ANY } else kind in outputs
    }
}
