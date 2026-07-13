package dev.argus.device

import android.content.Context
import dev.argus.engine.model.DndMode
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
    suspend fun setWifi(on: Boolean)
    suspend fun setBluetooth(on: Boolean)
    suspend fun setDnd(mode: DndMode)
    suspend fun setRinger(mode: RingerMode)
    suspend fun launchApp(packageName: String)
    suspend fun openUrl(url: String)
    suspend fun tap(x: Int, y: Int)
    suspend fun inputText(text: String)
}

class DeviceTools(
    private val shell: PrivilegedShell,
    private val cacheDirectory: File,
) : DeviceController {
    constructor(context: Context, shell: PrivilegedShell) : this(
        shell = shell,
        cacheDirectory = context.applicationContext.cacheDir,
    )

    override suspend fun setWifi(on: Boolean) {
        runChecked(
            operation = "set_wifi",
            command = listOf(SVC, "wifi", if (on) "enable" else "disable"),
        )
    }

    override suspend fun setBluetooth(on: Boolean) {
        runChecked(
            operation = "set_bluetooth",
            command = listOf(SVC, "bluetooth", if (on) "enable" else "disable"),
        )
    }

    override suspend fun setDnd(mode: DndMode) {
        val shellMode = when (mode) {
            DndMode.OFF -> "all"
            DndMode.PRIORITY -> "priority"
            DndMode.TOTAL -> "none"
        }
        runChecked(
            operation = "set_dnd",
            command = listOf(CMD, "notification", "set_dnd", shellMode),
        )
    }

    override suspend fun setRinger(mode: RingerMode) {
        runChecked(
            operation = "set_ringer",
            command = listOf(CMD, "audio", "set-ringer-mode", mode.shellValue),
        )
    }

    override suspend fun launchApp(packageName: String) {
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
        )
    }

    override suspend fun openUrl(url: String) {
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
        )
    }

    override suspend fun tap(x: Int, y: Int) {
        require(x in COORDINATE_RANGE && y in COORDINATE_RANGE) {
            "Coordinate fuori intervallo"
        }
        runChecked(
            operation = "tap",
            command = listOf(INPUT, "tap", x.toString(), y.toString()),
        )
    }

    override suspend fun inputText(text: String) {
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARS) { "Testo non valido" }
        require(text.none(Char::isISOControl)) { "Il testo contiene caratteri di controllo" }
        require("%s" !in text) { "Sequenza %s non rappresentabile da Android input" }
        runChecked(
            operation = "input_text",
            command = listOf(INPUT, "text", text),
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

    private suspend fun runChecked(operation: String, command: List<String>): ShellResult {
        val result = shell.run(command)
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
        const val AM = "/system/bin/am"
        const val INPUT = "/system/bin/input"
        const val SCREENCAP = "/system/bin/screencap"
        const val UIAUTOMATOR = "/system/bin/uiautomator"
        const val CAT = "/system/bin/cat"
        const val RM = "/system/bin/rm"
        const val REMOTE_TEMP_PREFIX = "/data/local/tmp/argus-ui-"
        const val MAX_TEXT_CHARS = 4_000
        const val MAX_URL_CHARS = 8_192
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
