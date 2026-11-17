package dev.argus.automation.telephony

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import dev.argus.engine.model.Action
import dev.argus.engine.model.IntentKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Un broadcast che Argus invia raggiunge anche i SUOI receiver registrati a runtime, esportati o no, e il
 * PackageManager non li elenca: [AndroidTelephonySurface.OWN_RUNTIME_RECEIVER_ACTIONS] deve coprirli tutti.
 * Questo test legge i sorgenti di ogni modulo e fallisce se compare un `registerReceiver` nuovo o un'azione
 * che l'elenco non ha.
 */
class OwnRuntimeReceiversTest {
    private val sources: List<File> = File("..").listFiles().orEmpty()
        .filter { File(it, "src/main").isDirectory }
        .flatMap { module -> File(module, "src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }

    /** Il nome nel sorgente di ogni azione che oggi si registra a runtime, col suo valore. */
    private val knownActions = mapOf(
        "Intent.ACTION_SCREEN_ON" to Intent.ACTION_SCREEN_ON,
        "Intent.ACTION_SCREEN_OFF" to Intent.ACTION_SCREEN_OFF,
        "Intent.ACTION_USER_PRESENT" to Intent.ACTION_USER_PRESENT,
        "Intent.ACTION_BATTERY_CHANGED" to Intent.ACTION_BATTERY_CHANGED,
        "Intent.ACTION_POWER_CONNECTED" to Intent.ACTION_POWER_CONNECTED,
        "Intent.ACTION_POWER_DISCONNECTED" to Intent.ACTION_POWER_DISCONNECTED,
        "NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED" to NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
        "AudioManager.RINGER_MODE_CHANGED_ACTION" to AudioManager.RINGER_MODE_CHANGED_ACTION,
    )

    @Test
    fun `the sources are found`() {
        assertTrue(sources.size > 50, "sorgenti trovati: ${sources.size}")
    }

    @Test
    fun `every runtime receiver is in a known file`() {
        val withReceivers = sources.filter { RECEIVER.containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals(
            setOf("AndroidDeviceEventSources.kt", "ConnectivitySentinelService.kt"),
            withReceivers,
            "un registerReceiver nuovo: aggiungi le sue azioni a AndroidTelephonySurface.OWN_RUNTIME_RECEIVER_ACTIONS",
        )
    }

    @Test
    fun `every action registered at runtime is refused as a broadcast without package`() {
        val files = sources.filter { it.name in setOf("AndroidDeviceEventSources.kt", "ConnectivitySentinelService.kt") }
        val names = files.flatMap { file -> ACTION_ARG.findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(names.isNotEmpty())
        names.forEach { name ->
            val value = knownActions[name] ?: error("azione runtime sconosciuta $name: aggiungila qui e all'elenco")
            assertTrue(value in AndroidTelephonySurface.OWN_RUNTIME_RECEIVER_ACTIONS, name)
            assertTrue(
                AndroidTelephonySurface.reachesOwnRuntimeReceiver(Action.SendIntent(IntentKind.BROADCAST, action = value)),
                name,
            )
            assertTrue(
                AndroidTelephonySurface.reachesOwnRuntimeReceiver(
                    Action.SendIntent(IntentKind.BROADCAST, action = value.uppercase()),
                ),
                name,
            )
        }
    }

    @Test
    fun `a broadcast with a package or another kind does not reach the runtime receivers`() {
        val screenOn = Intent.ACTION_SCREEN_ON
        assertFalse(
            AndroidTelephonySurface.reachesOwnRuntimeReceiver(
                Action.SendIntent(IntentKind.BROADCAST, action = screenOn, pkg = "com.example.app"),
            ),
        )
        assertFalse(AndroidTelephonySurface.reachesOwnRuntimeReceiver(Action.SendIntent(IntentKind.ACTIVITY, action = screenOn)))
        assertFalse(
            AndroidTelephonySurface.reachesOwnRuntimeReceiver(Action.SendIntent(IntentKind.BROADCAST, action = "com.example.PING")),
        )
    }

    private companion object {
        /** `registerReceiver(` con un receiver vero (non la lettura del broadcast sticky con `null`). */
        val RECEIVER = Regex("""(?<![A-Za-z])registerReceiver\(\s*(?!null\b)""")

        /** Le azioni nei filtri: `addAction(X)` e `IntentFilter(X)`. */
        val ACTION_ARG = Regex("""(?:addAction|IntentFilter)\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)""")
    }
}
