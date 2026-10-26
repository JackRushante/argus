package dev.argus.device

import dev.argus.engine.model.StateKeys

/** Lettura grezza della batteria: l'intent sticky `ACTION_BATTERY_CHANGED` senza interpretarlo. */
data class BatteryReading(val level: Int, val scale: Int, val status: Int)

/**
 * Valori grezzi dell'OS letti con le API pubbliche, senza privilegi. Ogni metodo ritorna `null`
 * quando il valore non è leggibile: il lettore non inventa mai nulla al posto di un valore assente.
 * Interfaccia sottile per poter sostituire le API di sistema con un fake nei test.
 */
interface PublicStateSource {
    fun battery(): BatteryReading?
    fun screenInteractive(): Boolean?
    fun ringerMode(): Int?
    fun interruptionFilter(): Int?
    fun airplaneMode(): Int?
    fun wifiEnabled(): Boolean?

    /** `true` se il permesso necessario a leggere il Bluetooth è concesso (Android 12+: BLUETOOTH_CONNECT). */
    fun canReadBluetooth(): Boolean
    fun bluetoothEnabled(): Boolean?
}

/**
 * Lettore delle 8 chiavi di [StateKeys] con API pubbliche (nessuno Shizuku, nessun processo).
 * Stessi valori e formati di [StateKeys.ALL] e del lettore Shizuku [StateReader]. Fail-closed: una
 * chiave non leggibile, fuori dal dominio o priva del permesso resta ASSENTE nel risultato, mai un
 * valore inventato e mai una stringa vuota (invariante 8).
 */
class PublicStateReader(private val source: PublicStateSource) {
    /** Legge solo le chiavi richieste. Le chiavi non leggibili non compaiono nel risultato. */
    fun read(keys: Set<String>): Map<String, String> {
        require(keys.all(StateKeys.ALL::containsKey)) { "Chiave di stato fuori registry" }
        val values = linkedMapOf<String, String>()

        if (StateKeys.RINGER in keys) {
            guarded { source.ringerMode() }?.let(::parseRinger)?.let { values[StateKeys.RINGER] = it }
        }
        if (StateKeys.WIFI in keys) {
            guarded { source.wifiEnabled() }?.let(::parseOnOff)?.let { values[StateKeys.WIFI] = it }
        }
        if (StateKeys.BLUETOOTH in keys && guarded { source.canReadBluetooth() } == true) {
            guarded { source.bluetoothEnabled() }?.let(::parseOnOff)?.let {
                values[StateKeys.BLUETOOTH] = it
            }
        }
        if (StateKeys.DND in keys) {
            guarded { source.interruptionFilter() }?.let(::parseDnd)?.let { values[StateKeys.DND] = it }
        }
        if (StateKeys.AIRPLANE in keys) {
            guarded { source.airplaneMode() }?.let(::parseAirplane)?.let {
                values[StateKeys.AIRPLANE] = it
            }
        }
        if (StateKeys.BATTERY in keys || StateKeys.CHARGING in keys) {
            guarded { source.battery() }?.let { reading ->
                if (StateKeys.BATTERY in keys) {
                    parseBatteryLevel(reading)?.let { values[StateKeys.BATTERY] = it.toString() }
                }
                if (StateKeys.CHARGING in keys) {
                    parseCharging(reading.status)?.let { values[StateKeys.CHARGING] = it.toString() }
                }
            }
        }
        if (StateKeys.SCREEN in keys) {
            guarded { source.screenInteractive() }?.let { values[StateKeys.SCREEN] = if (it) "on" else "off" }
        }
        return values
    }

    /** Un'API di sistema che lancia (SecurityException, servizio morto...) equivale a «non leggibile». */
    private inline fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    companion object {
        // Costanti Android replicate come letterali: restano valide nei test JVM e non legano il
        // dominio dei valori ai cambi di API.
        private const val RINGER_MODE_SILENT = 0
        private const val RINGER_MODE_VIBRATE = 1
        private const val RINGER_MODE_NORMAL = 2
        private const val INTERRUPTION_FILTER_ALL = 1
        private const val INTERRUPTION_FILTER_PRIORITY = 2
        private const val INTERRUPTION_FILTER_NONE = 3
        private const val BATTERY_STATUS_CHARGING = 2
        private const val BATTERY_STATUS_DISCHARGING = 3
        private const val BATTERY_STATUS_NOT_CHARGING = 4
        private const val BATTERY_STATUS_FULL = 5

        fun parseRinger(mode: Int): String? = when (mode) {
            RINGER_MODE_SILENT -> "silent"
            RINGER_MODE_VIBRATE -> "vibrate"
            RINGER_MODE_NORMAL -> "normal"
            else -> null
        }

        fun parseOnOff(enabled: Boolean): String = if (enabled) "on" else "off"

        /**
         * Filtro di interruzione -> `off|priority|total`. Come il lettore Shizuku (`zen_mode`): la
         * modalità «solo sveglie» e il valore sconosciuto NON hanno un valore nel dominio e restano
         * assenti.
         */
        fun parseDnd(filter: Int): String? = when (filter) {
            INTERRUPTION_FILTER_ALL -> "off"
            INTERRUPTION_FILTER_PRIORITY -> "priority"
            INTERRUPTION_FILTER_NONE -> "total"
            else -> null
        }

        fun parseAirplane(raw: Int): String? = when (raw) {
            0 -> "off"
            1 -> "on"
            else -> null
        }

        /** Percentuale 0-100 da livello/scala; `null` se i dati sono incoerenti. */
        fun parseBatteryLevel(reading: BatteryReading): Int? {
            if (reading.level < 0 || reading.scale <= 0 || reading.level > reading.scale) return null
            return (reading.level.toLong() * 100 / reading.scale).toInt().takeIf { it in 0..100 }
        }

        /** Stessa regola del lettore Shizuku: in carica o piena = `true`, scarica o non in carica = `false`. */
        fun parseCharging(status: Int): Boolean? = when (status) {
            BATTERY_STATUS_CHARGING, BATTERY_STATUS_FULL -> true
            BATTERY_STATUS_DISCHARGING, BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
    }
}
