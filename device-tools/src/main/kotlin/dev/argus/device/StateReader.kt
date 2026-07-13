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
    ): DeviceState {
        require(keys.all(StateKeys.ALL::containsKey)) { "Chiave di stato fuori registry" }
        val values = linkedMapOf<String, String>()

        if (StateKeys.RINGER in keys) {
            readSetting("global", "mode_ringer")?.let(::parseRinger)?.let {
                values[StateKeys.RINGER] = it
            }
        }
        if (StateKeys.WIFI in keys) {
            readSetting("global", "wifi_on")?.let(::parseWifi)?.let {
                values[StateKeys.WIFI] = it
            }
        }
        if (StateKeys.BLUETOOTH in keys) {
            readSetting("global", "bluetooth_on")?.let(::parseBluetooth)?.let {
                values[StateKeys.BLUETOOTH] = it
            }
        }
        if (StateKeys.DND in keys) {
            readSetting("global", "zen_mode")?.let(::parseDnd)?.let {
                values[StateKeys.DND] = it
            }
        }
        if (StateKeys.AIRPLANE in keys) {
            readSetting("global", "airplane_mode_on")?.let(::parseBooleanSetting)?.let {
                values[StateKeys.AIRPLANE] = it
            }
        }
        if (StateKeys.BATTERY in keys || StateKeys.CHARGING in keys) {
            commandOutput(listOf(DUMPSYS, "battery"))?.let(::parseBattery)?.let { battery ->
                if (StateKeys.BATTERY in keys && battery.level != null) {
                    values[StateKeys.BATTERY] = battery.level.toString()
                }
                if (StateKeys.CHARGING in keys && battery.charging != null) {
                    values[StateKeys.CHARGING] = battery.charging.toString()
                }
            }
        }

        val foregroundApp = if (includeForegroundApp) {
            commandOutput(
                command = listOf(DUMPSYS, "activity", "activities"),
                maxOutputBytes = PrivilegedShell.DEFAULT_TEXT_OUTPUT_BYTES,
            )?.let(::parseForegroundPackage)
        } else {
            null
        }
        return DeviceState(values = values, foregroundApp = foregroundApp)
    }

    private suspend fun readSetting(namespace: String, key: String): String? =
        commandOutput(listOf(SETTINGS, "get", namespace, key))?.trim()?.takeUnless {
            it.isEmpty() || it == "null"
        }

    private suspend fun commandOutput(
        command: List<String>,
        maxOutputBytes: Int = 64 * 1024,
    ): String? = try {
        shell.run(command, maxOutputBytes = maxOutputBytes).takeIf {
            it.successful && !it.truncated
        }?.stdoutText
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

        fun parseForegroundPackage(raw: String): String? = raw.lineSequence()
            .filter { "mResumedActivity" in it || "topResumedActivity" in it }
            .mapNotNull { COMPONENT.find(it)?.groupValues?.get(1) }
            .firstOrNull()
    }
}
