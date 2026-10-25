package dev.argus.device

import dev.argus.engine.model.StateKeys
import dev.argus.engine.runtime.DeviceState
import dev.argus.shizuku.PrivilegedShell
import java.util.concurrent.CancellationException

/** Lettura lazy e fail-closed: una chiave non leggibile resta assente, mai inventata. */
class StateReader(private val shell: PrivilegedShell) {
    suspend fun read(
        keys: Set<String>,
        includeForegroundApp: Boolean = false,
    ): DeviceState = readInternal(
        keys = keys,
        includeForegroundApp = includeForegroundApp,
        timeoutMillis = PrivilegedShell.DEFAULT_TIMEOUT_MILLIS,
        scalarOutputBytes = 64 * 1024,
        foregroundOutputBytes = PrivilegedShell.DEFAULT_TEXT_OUTPUT_BYTES,
    )

    /** Variante usata dai reader parametrici: stesso parser, limiti espliciti per ogni processo. */
    internal suspend fun readBounded(
        keys: Set<String>,
        includeForegroundApp: Boolean = false,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): DeviceState = readInternal(
        keys,
        includeForegroundApp,
        timeoutMillis,
        maxOutputBytes,
        maxOutputBytes,
    )

    private suspend fun readInternal(
        keys: Set<String>,
        includeForegroundApp: Boolean,
        timeoutMillis: Long,
        scalarOutputBytes: Int,
        foregroundOutputBytes: Int,
    ): DeviceState {
        require(keys.all(StateKeys.ALL::containsKey)) { "Chiave di stato fuori registry" }
        val values = linkedMapOf<String, String>()

        if (StateKeys.RINGER in keys) {
            readSetting("global", "mode_ringer", timeoutMillis, scalarOutputBytes)
                ?.let(::parseRinger)?.let {
                values[StateKeys.RINGER] = it
            }
        }
        if (StateKeys.WIFI in keys) {
            readSetting("global", "wifi_on", timeoutMillis, scalarOutputBytes)
                ?.let(::parseWifi)?.let {
                values[StateKeys.WIFI] = it
            }
        }
        if (StateKeys.BLUETOOTH in keys) {
            readSetting("global", "bluetooth_on", timeoutMillis, scalarOutputBytes)
                ?.let(::parseBluetooth)?.let {
                values[StateKeys.BLUETOOTH] = it
            }
        }
        if (StateKeys.DND in keys) {
            readSetting("global", "zen_mode", timeoutMillis, scalarOutputBytes)
                ?.let(::parseDnd)?.let {
                values[StateKeys.DND] = it
            }
        }
        if (StateKeys.AIRPLANE in keys) {
            readSetting("global", "airplane_mode_on", timeoutMillis, scalarOutputBytes)
                ?.let(::parseBooleanSetting)?.let {
                values[StateKeys.AIRPLANE] = it
            }
        }
        if (StateKeys.BATTERY in keys || StateKeys.CHARGING in keys) {
            commandOutput(
                listOf(DUMPSYS, "battery"),
                timeoutMillis,
                scalarOutputBytes,
                allowTruncated = true,
            )?.let(::parseBattery)?.let { battery ->
                if (StateKeys.BATTERY in keys && battery.level != null) {
                    values[StateKeys.BATTERY] = battery.level.toString()
                }
                if (StateKeys.CHARGING in keys && battery.charging != null) {
                    values[StateKeys.CHARGING] = battery.charging.toString()
                }
            }
        }
        if (StateKeys.SCREEN in keys) {
            commandOutput(
                listOf(DUMPSYS, "power"),
                timeoutMillis,
                scalarOutputBytes,
                allowTruncated = true,
            )?.let(::parseScreen)?.let {
                values[StateKeys.SCREEN] = it
            }
        }

        val foregroundApp = if (includeForegroundApp) {
            commandOutput(
                command = listOf(DUMPSYS, "activity", "activities"),
                timeoutMillis = timeoutMillis,
                maxOutputBytes = foregroundOutputBytes,
                allowTruncated = true,
            )?.let(::parseForegroundPackage)
        } else {
            null
        }
        return DeviceState(values = values, foregroundApp = foregroundApp)
    }

    private suspend fun readSetting(
        namespace: String,
        key: String,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): String? = commandOutput(
        listOf(SETTINGS, "get", namespace, key),
        timeoutMillis,
        maxOutputBytes,
    )?.trim()?.takeUnless {
        it.isEmpty() || it == "null"
    }

    /**
     * Output del comando, `null` se è fallito o scaduto. Un output troncato dal limite di byte si
     * scarta (un valore scalare tagliato non è affidabile), salvo [allowTruncated]: per i dumpsys,
     * che sono lunghi per natura, si tiene la parte letta senza l'ultima riga, che può essere
     * tagliata a metà. Se il valore cercato sta oltre il limite il parser non lo trova e la
     * condizione resta sconosciuta; se sta prima, si usa.
     */
    private suspend fun commandOutput(
        command: List<String>,
        timeoutMillis: Long = PrivilegedShell.DEFAULT_TIMEOUT_MILLIS,
        maxOutputBytes: Int = 64 * 1024,
        allowTruncated: Boolean = false,
    ): String? = try {
        val result = shell.run(
            command,
            timeoutMillis = timeoutMillis,
            maxOutputBytes = maxOutputBytes,
        )
        when {
            !result.successful -> null
            !result.truncated -> result.stdoutText
            allowTruncated -> completeLines(result.stdoutText)
            else -> null
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    internal data class BatterySnapshot(val level: Int?, val charging: Boolean?)

    internal companion object {
        const val SETTINGS = "/system/bin/settings"
        const val DUMPSYS = "/system/bin/dumpsys"
        private val COMPONENT = Regex(
            "([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)/[A-Za-z0-9_.$]+",
        )
        private val RESUMED_ACTIVITY_FIELD = Regex(
            "\\b(?:topResumedActivity|mResumedActivity|ResumedActivity)\\b",
        )
        private val DISPLAY_HEADER = Regex("^\\s*Display #(\\d+)\\b")
        private const val DEFAULT_DISPLAY_ID = 0
        private val FOCUS_SECTION = Regex("Resumed activities in task display areas")
        private val FOCUSED_ACTIVITY = Regex("^\\s*Resumed:\\s")
        private val WAKEFULNESS = Regex("mWakefulness=([A-Za-z]+)")
        private val DISPLAY_POWER_STATE = Regex("Display Power:\\s*state=([A-Za-z]+)")

        fun parseRinger(raw: String): String? = when (raw.trim()) {
            "0" -> "silent"
            "1" -> "vibrate"
            "2" -> "normal"
            else -> null
        }

        fun parseWifi(raw: String): String? = when (raw.trim()) {
            "0", "3" -> "off"
            "1", "2" -> "on"
            else -> null
        }

        fun parseBluetooth(raw: String): String? = when (raw.trim()) {
            "0" -> "off"
            "1", "2" -> "on"
            else -> null
        }

        fun parseDnd(raw: String): String? = when (raw.trim()) {
            "0" -> "off"
            "1" -> "priority"
            "2" -> "total"
            else -> null
        }

        fun parseBooleanSetting(raw: String): String? = when (raw.trim()) {
            "0" -> "off"
            "1" -> "on"
            else -> null
        }

        fun parseBattery(raw: String): BatterySnapshot {
            val fields = raw.lineSequence().mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator < 0) null else {
                    line.substring(0, separator).trim().lowercase() to
                        line.substring(separator + 1).trim()
                }
            }.toMap()
            val level = fields["level"]?.toIntOrNull()?.takeIf { it in 0..100 }
            val charging = when (fields["status"]?.toIntOrNull()) {
                2, 5 -> true
                3, 4 -> false
                else -> null
            }
            return BatterySnapshot(level, charging)
        }

        /**
         * Stato schermo da `dumpsys power`. Fonte primaria `mWakefulness=` (Awake -> on;
         * Asleep/Dozing -> off); fallback robusto su `Display Power: state=ON|OFF` quando il campo
         * wakefulness manca. Ritorna esattamente "on"/"off" (i valori dichiarati in StateKeys.ALL),
         * null se nessun marcatore riconoscibile (fail-closed: chiave resta assente).
         */
        fun parseScreen(raw: String): String? {
            WAKEFULNESS.find(raw)?.groupValues?.get(1)?.let { wakefulness ->
                return when (wakefulness.lowercase()) {
                    "awake" -> "on"
                    "asleep", "dozing" -> "off"
                    else -> null
                }
            }
            DISPLAY_POWER_STATE.find(raw)?.groupValues?.get(1)?.let { state ->
                return when (state.uppercase()) {
                    "ON" -> "on"
                    "OFF" -> "off"
                    else -> null
                }
            }
            return null
        }

        /**
         * Parte dell'output fino all'ultimo a capo: scarta una riga finale tagliata a metà dal
         * limite di byte, che darebbe un valore sbagliato (un livello «8» al posto di «85»).
         */
        fun completeLines(raw: String): String = raw.substring(0, raw.lastIndexOf('\n') + 1)

        /**
         * App in primo piano da `dumpsys activity activities`. Il dump ha una riga
         * `topResumedActivity=` per ogni task foglia: in schermo diviso ce ne sono due e la prima
         * elencata non è necessariamente quella con il focus. La regola, in ordine:
         * 1. l'attività con il focus: una riga `Resumed:` della sezione «Resumed activities in task
         *    display areas» (l'attività focalizzata di ogni task display area);
         *    ma solo se è anche resumed nel display di default (#0), così un display esterno non
         *    ruba il primo piano;
         * 2. altrimenti la prima attività resumed elencata nel display di default;
         * 3. se l'output non ha intestazioni `Display #n` (altri formati) la prima resumed nel testo.
         * Con intestazioni ma nessuna resumed nel display di default il valore è sconosciuto
         * (`null`), mai quello di un altro display.
         */
        fun parseForegroundPackage(raw: String): String? {
            var display: Int? = null
            var inFocusSection = false
            var sawDisplayHeader = false
            val anywhere = mutableListOf<String>()
            val onDefaultDisplay = mutableListOf<String>()
            val focused = mutableListOf<String>()
            for (line in raw.lineSequence()) {
                // Una riga non indentata chiude il blocco del display corrente (altra sezione).
                if (line.isNotEmpty() && !line[0].isWhitespace()) {
                    display = null
                    inFocusSection = false
                }
                DISPLAY_HEADER.find(line)?.let { header ->
                    display = header.groupValues[1].toIntOrNull()
                    inFocusSection = false
                    sawDisplayHeader = true
                }
                if (FOCUS_SECTION.containsMatchIn(line)) {
                    display = null
                    inFocusSection = true
                    continue
                }
                if (inFocusSection) {
                    if (FOCUSED_ACTIVITY.containsMatchIn(line)) {
                        COMPONENT.find(line)?.groupValues?.get(1)?.let { focused += it }
                    }
                    continue
                }
                if (RESUMED_ACTIVITY_FIELD.containsMatchIn(line)) {
                    COMPONENT.find(line)?.groupValues?.get(1)?.let { pkg ->
                        anywhere += pkg
                        if (display == DEFAULT_DISPLAY_ID) onDefaultDisplay += pkg
                    }
                }
            }
            val candidates = if (sawDisplayHeader) onDefaultDisplay else anywhere
            return focused.firstOrNull { it in candidates } ?: candidates.firstOrNull()
        }
    }
}
