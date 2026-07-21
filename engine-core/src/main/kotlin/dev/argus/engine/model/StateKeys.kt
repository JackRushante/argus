package dev.argus.engine.model

/** Vocabolario CHIUSO delle chiavi di DeviceState (spec §5 rev 3): il compile non può inventare
 *  chiavi (sono nel manifest) e il DraftValidator rifiuta StateEquals su chiavi fuori registry. */
object StateKeys {
    const val RINGER = "ringer"; const val WIFI = "wifi"; const val BLUETOOTH = "bluetooth"
    const val DND = "dnd"; const val BATTERY = "battery"; const val CHARGING = "charging"
    const val AIRPLANE = "airplane"; const val SCREEN = "screen"
    /** chiave -> valori ammessi (usato nel render del manifest e in doc) */
    val ALL: Map<String, String> = mapOf(
        RINGER to "normal|vibrate|silent", WIFI to "on|off", BLUETOOTH to "on|off",
        DND to "off|priority|total", BATTERY to "0-100", CHARGING to "true|false", AIRPLANE to "on|off",
        SCREEN to "on|off",
    )
}
