package dev.argus.device

import android.content.Context
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.WriteSettingPolicy
import dev.argus.engine.runtime.ExecutionId
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class RingerMode(internal val shellValue: String) {
    NORMAL("NORMAL"),
    VIBRATE("VIBRATE"),
    SILENT("SILENT"),
    ;

    companion object {
        fun fromEngineValue(value: String): RingerMode? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}

class DeviceToolException(
    val code: String,
    cause: Throwable? = null,
) : IllegalStateException(code, cause)

/** Contratto ristretto usato dall'executor: nessuna shell arbitraria e nessun app.install. */
interface DeviceController {
    suspend fun setWifi(on: Boolean, executionId: ExecutionId, priority: Int = 0)
    suspend fun setBluetooth(on: Boolean, executionId: ExecutionId, priority: Int = 0)
    suspend fun setMobileData(on: Boolean, executionId: ExecutionId, priority: Int = 0)
    suspend fun setDnd(mode: DndMode, executionId: ExecutionId, priority: Int = 0)
    suspend fun setRinger(mode: RingerMode, executionId: ExecutionId, priority: Int = 0)
    suspend fun launchApp(packageName: String, executionId: ExecutionId, priority: Int = 0)
    suspend fun openUrl(url: String, executionId: ExecutionId, priority: Int = 0)

    /**
     * Sveglia/timer/schermate impostazioni reali via `am start` privilegiato. A differenza del
     * percorso BASE ([dev.argus.automation.base.BaseActionSurface]) queste girano con identità
     * shell (uid 2000) ed **esistono da background** (esenti dal blocco Background Activity Launch
     * di Android 14+/OEM). Sono il fix del caveat BAL: da automazione la sola via affidabile.
     */
    suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
        skipUi: Boolean,
        executionId: ExecutionId,
        priority: Int = 0,
    )

    suspend fun setTimer(
        seconds: Int,
        label: String?,
        skipUi: Boolean,
        executionId: ExecutionId,
        priority: Int = 0,
    )

    suspend fun openSettingsScreen(
        screen: SettingsScreen,
        pkg: String?,
        executionId: ExecutionId,
        priority: Int = 0,
    )

    suspend fun tap(x: Int, y: Int, executionId: ExecutionId, priority: Int = 0)
    suspend fun inputText(text: String, executionId: ExecutionId, priority: Int = 0)
    suspend fun writeSetting(
        namespace: SettingNamespace,
        key: String,
        value: String,
        executionId: ExecutionId,
        priority: Int = 0,
    )
}

class DeviceTools(
    private val shell: PrivilegedShell,
    private val cacheDirectory: File,
) : DeviceController {
    constructor(context: Context, shell: PrivilegedShell) : this(
        shell = shell,
        cacheDirectory = context.applicationContext.cacheDir,
    )

    override suspend fun setWifi(on: Boolean, executionId: ExecutionId, priority: Int) {
        runChecked(
            operation = "set_wifi",
            command = listOf(SVC, "wifi", if (on) "enable" else "disable"),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun setBluetooth(on: Boolean, executionId: ExecutionId, priority: Int) {
        runChecked(
            operation = "set_bluetooth",
            command = listOf(SVC, "bluetooth", if (on) "enable" else "disable"),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun setMobileData(on: Boolean, executionId: ExecutionId, priority: Int) {
        runChecked(
            operation = "set_mobile_data",
            command = listOf(SVC, "data", if (on) "enable" else "disable"),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun setDnd(mode: DndMode, executionId: ExecutionId, priority: Int) {
        val shellMode = when (mode) {
            DndMode.OFF -> "all"
            DndMode.PRIORITY -> "priority"
            DndMode.TOTAL -> "none"
        }
        runChecked(
            operation = "set_dnd",
            command = listOf(CMD, "notification", "set_dnd", shellMode),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun setRinger(mode: RingerMode, executionId: ExecutionId, priority: Int) {
        runChecked(
            operation = "set_ringer",
            command = listOf(CMD, "audio", "set-ringer-mode", mode.shellValue),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun launchApp(packageName: String, executionId: ExecutionId, priority: Int) {
        require(PACKAGE_NAME.matches(packageName)) { "Package non valido" }
        runChecked(
            operation = "launch_app",
            command = listOf(
                AM,
                "start",
                "--user",
                "current",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                "-p",
                packageName,
            ),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun openUrl(url: String, executionId: ExecutionId, priority: Int) {
        require(validHttpUrl(url)) { "URL non valido" }
        runChecked(
            operation = "open_url",
            command = listOf(
                AM,
                "start",
                "--user",
                "current",
                "-a",
                "android.intent.action.VIEW",
                "-d",
                url,
            ),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
        skipUi: Boolean,
        executionId: ExecutionId,
        priority: Int,
    ) {
        require(hour in 0..23 && minute in 0..59) { "Orario sveglia fuori intervallo" }
        val command = buildList {
            addAll(listOf(AM, "start", "--user", "current", "-a", "android.intent.action.SET_ALARM"))
            addAll(listOf("--ei", ALARM_EXTRA_HOUR, hour.toString()))
            addAll(listOf("--ei", ALARM_EXTRA_MINUTES, minute.toString()))
            addAll(listOf("--ez", ALARM_EXTRA_SKIP_UI, skipUi.toString()))
            appendLabel(label)
        }
        runChecked("set_alarm", command, executionId, priority)
    }

    override suspend fun setTimer(
        seconds: Int,
        label: String?,
        skipUi: Boolean,
        executionId: ExecutionId,
        priority: Int,
    ) {
        require(seconds in 1..MAX_TIMER_SECONDS) { "Durata timer fuori intervallo" }
        val command = buildList {
            addAll(listOf(AM, "start", "--user", "current", "-a", "android.intent.action.SET_TIMER"))
            addAll(listOf("--ei", ALARM_EXTRA_LENGTH, seconds.toString()))
            addAll(listOf("--ez", ALARM_EXTRA_SKIP_UI, skipUi.toString()))
            appendLabel(label)
        }
        runChecked("set_timer", command, executionId, priority)
    }

    override suspend fun openSettingsScreen(
        screen: SettingsScreen,
        pkg: String?,
        executionId: ExecutionId,
        priority: Int,
    ) {
        val command = buildList {
            addAll(listOf(AM, "start", "--user", "current", "-a", settingsAction(screen)))
            if (screen == SettingsScreen.APP_DETAILS) {
                require(PACKAGE_NAME.matches(pkg.orEmpty())) { "Package non valido" }
                addAll(listOf("-d", "package:$pkg"))
            }
        }
        runChecked("open_settings", command, executionId, priority)
    }

    /** L'etichetta è un token argv separato (nessuna shell injection), ma resta un dato: bounded e
     *  senza caratteri di controllo, come [inputText]. */
    private fun MutableList<String>.appendLabel(label: String?) {
        if (label.isNullOrBlank()) return
        require(label.length <= MAX_LABEL_CHARS) { "Etichetta troppo lunga" }
        require(label.none(Char::isISOControl)) { "L'etichetta contiene caratteri di controllo" }
        addAll(listOf("--es", ALARM_EXTRA_MESSAGE, label))
    }

    /** Enum CHIUSO → action-string Settings.ACTION_* letterale (niente action arbitraria: no routing
     *  sink privilegiato). Speculare a `settingsIntent` del BaseActionSurface. */
    private fun settingsAction(screen: SettingsScreen): String = when (screen) {
        SettingsScreen.WIFI -> "android.settings.WIFI_SETTINGS"
        SettingsScreen.BLUETOOTH -> "android.settings.BLUETOOTH_SETTINGS"
        SettingsScreen.DISPLAY -> "android.settings.DISPLAY_SETTINGS"
        SettingsScreen.SOUND -> "android.settings.SOUND_SETTINGS"
        SettingsScreen.LOCATION -> "android.settings.LOCATION_SOURCE_SETTINGS"
        SettingsScreen.BATTERY -> "android.intent.action.POWER_USAGE_SUMMARY"
        SettingsScreen.DATE -> "android.settings.DATE_SETTINGS"
        SettingsScreen.APP_DETAILS -> "android.settings.APPLICATION_DETAILS_SETTINGS"
        SettingsScreen.SETTINGS -> "android.settings.SETTINGS"
    }

    override suspend fun tap(x: Int, y: Int, executionId: ExecutionId, priority: Int) {
        require(x in COORDINATE_RANGE && y in COORDINATE_RANGE) {
            "Coordinate fuori intervallo"
        }
        runChecked(
            operation = "tap",
            command = listOf(INPUT, "tap", x.toString(), y.toString()),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun inputText(text: String, executionId: ExecutionId, priority: Int) {
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARS) { "Testo non valido" }
        require(text.none(Char::isISOControl)) { "Il testo contiene caratteri di controllo" }
        require("%s" !in text) { "Sequenza %s non rappresentabile da Android input" }
        runChecked(
            operation = "input_text",
            command = listOf(INPUT, "text", text),
            executionId = executionId,
            priority = priority,
        )
    }

    override suspend fun writeSetting(
        namespace: SettingNamespace,
        key: String,
        value: String,
        executionId: ExecutionId,
        priority: Int,
    ) {
        // Seconda barriera dopo DraftValidator/WriteSettingPolicy: argv separati, mai `sh -c`, e la
        // key/value non raggiungono mai il transport privilegiato se non passano la policy di forma.
        require(WriteSettingPolicy.validKey(key)) { "Chiave impostazione non valida" }
        require(WriteSettingPolicy.validValue(value)) { "Valore impostazione non valido" }
        runChecked(
            operation = "write_setting",
            command = listOf(SETTINGS, "put", namespace.name.lowercase(), key, value),
            executionId = executionId,
            priority = priority,
        )
    }

    suspend fun capture(): ByteArray {
        val bytes = runToBytes(
            operation = "screen_capture",
            command = listOf(SCREENCAP, "-p"),
            maxOutputBytes = PrivilegedShell.DEFAULT_FILE_OUTPUT_BYTES,
            suffix = ".png",
        )
        if (!bytes.startsWith(PNG_MAGIC)) throw DeviceToolException("screen_capture_invalid")
        return bytes
    }

    suspend fun dumpUi(): String {
        val remotePath = "$REMOTE_TEMP_PREFIX${UUID.randomUUID()}.xml"
        val raw = try {
            runChecked(
                operation = "dump_ui",
                command = listOf(UIAUTOMATOR, "dump", "--compressed", "--windows", remotePath),
            )
            runToBytes(
                operation = "dump_ui",
                command = listOf(CAT, remotePath),
                maxOutputBytes = MAX_UI_DUMP_BYTES,
                suffix = ".xml",
            ).toString(StandardCharsets.UTF_8)
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    shell.run(
                        command = listOf(RM, "-f", remotePath),
                        timeoutMillis = CLEANUP_TIMEOUT_MILLIS,
                        maxOutputBytes = CLEANUP_OUTPUT_BYTES,
                    )
                }
            }
        }
        return extractUiXmlPayload(raw) ?: throw DeviceToolException("dump_ui_invalid")
    }

    private suspend fun runChecked(
        operation: String,
        command: List<String>,
        executionId: ExecutionId? = null,
        priority: Int = 0,
    ): ShellResult {
        val result = shell.run(
            command = command,
            priority = priority,
            executionId = executionId?.value,
        )
        if (!result.successful || result.truncated) {
            throw DeviceToolException("${operation}_failed")
        }
        return result
    }

    private suspend fun runToBytes(
        operation: String,
        command: List<String>,
        maxOutputBytes: Int,
        suffix: String,
    ): ByteArray {
        prepareCacheDirectory()
        val destination = try {
            File.createTempFile("argus-", suffix, cacheDirectory)
        } catch (error: Exception) {
            throw DeviceToolException("temporary_file_failed", error)
        }
        try {
            val result = shell.runToFile(
                command = command,
                destination = destination,
                maxOutputBytes = maxOutputBytes,
            )
            if (!result.successful || result.truncated || destination.length() !in 1..maxOutputBytes.toLong()) {
                throw DeviceToolException("${operation}_failed")
            }
            return destination.readBytes()
        } finally {
            destination.delete()
        }
    }

    private fun prepareCacheDirectory() {
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw DeviceToolException("temporary_directory_failed")
        }
    }

    private fun validHttpUrl(raw: String): Boolean = runCatching {
        raw.length <= MAX_URL_CHARS && URI(raw).let { uri ->
            uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank()
        }
    }.getOrDefault(false)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        const val SVC = "/system/bin/svc"
        const val CMD = "/system/bin/cmd"
        const val SETTINGS = "/system/bin/settings"
        const val AM = "/system/bin/am"
        const val INPUT = "/system/bin/input"
        const val SCREENCAP = "/system/bin/screencap"
        const val UIAUTOMATOR = "/system/bin/uiautomator"
        const val CAT = "/system/bin/cat"
        const val RM = "/system/bin/rm"
        const val REMOTE_TEMP_PREFIX = "/data/local/tmp/argus-ui-"
        const val MAX_TEXT_CHARS = 4_000
        const val MAX_URL_CHARS = 8_192
        const val MAX_TIMER_SECONDS = 86_400
        const val MAX_LABEL_CHARS = 200
        const val ALARM_EXTRA_HOUR = "android.intent.extra.alarm.HOUR"
        const val ALARM_EXTRA_MINUTES = "android.intent.extra.alarm.MINUTES"
        const val ALARM_EXTRA_LENGTH = "android.intent.extra.alarm.LENGTH"
        const val ALARM_EXTRA_SKIP_UI = "android.intent.extra.alarm.SKIP_UI"
        const val ALARM_EXTRA_MESSAGE = "android.intent.extra.alarm.MESSAGE"
        const val MAX_UI_DUMP_BYTES = 4 * 1024 * 1024
        const val CLEANUP_TIMEOUT_MILLIS = 5_000L
        const val CLEANUP_OUTPUT_BYTES = 4 * 1024
        val COORDINATE_RANGE = 0..10_000
        val HTTP_SCHEMES = setOf("http", "https")
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    }
}

internal fun extractUiXmlPayload(raw: String): String? {
    val xmlStart = raw.indexOf("<?xml").takeIf { it >= 0 }
    val displaysStart = raw.indexOf("<displays").takeIf { it >= 0 }
    val hierarchyStart = raw.indexOf("<hierarchy").takeIf { it >= 0 }
    val start = xmlStart ?: displaysStart ?: hierarchyStart ?: return null
    val rootEnd = if (displaysStart != null && displaysStart >= start) {
        "</displays>"
    } else {
        "</hierarchy>"
    }
    val end = raw.indexOf(rootEnd, start)
    if (end < 0) return null
    return raw.substring(start, end + rootEnd.length).takeIf { "<hierarchy" in it }
}
