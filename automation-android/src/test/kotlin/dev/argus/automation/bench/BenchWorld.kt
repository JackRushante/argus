package dev.argus.automation.bench

import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AndroidCapabilityState
import dev.argus.automation.AndroidCapabilityStateSource
import dev.argus.automation.AndroidSensorCapability
import dev.argus.automation.AndroidSensorCapabilitySource
import dev.argus.automation.GenerativeReadiness
import dev.argus.automation.GenerativeRuntimeReadiness
import dev.argus.automation.SensorReportingMode
import dev.argus.automation.vm.composeInstalledAppCandidatesPrompt
import dev.argus.automation.apps.InstalledAppCandidate
import dev.argus.automation.apps.matchInstalledApps
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.SensorKind
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.safety.DraftValidator
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneId

/**
 * Il telefono di prova del banco. I manifest NON sono scritti a mano: escono dal vero
 * [AndroidCapabilityProbe] (stesso codice dell'app) alimentato da uno stato Android fittizio, quindi
 * tool, trigger, capability e motivi di indisponibilita' sono quelli che l'app pubblica davvero.
 *
 * - `full`: Shizuku autorizzato, tutti i permessi, accesso notifiche, DND e impostazioni di sistema, posizione accesa; l'unico
 *   sensore armabile e' il movimento significativo, come sul telefono (e' l'unico con un backend).
 * - `no_shizuku`: stesso telefono senza Shizuku installato; restano le azioni del tier base.
 */
internal class BenchWorld private constructor(
    private val fullManifest: CapabilityManifest,
    private val fullCapabilities: Set<String>,
    private val noShizukuManifest: CapabilityManifest,
    private val noShizukuCapabilities: Set<String>,
) {
    /** Fuso del telefono: i casi orari sono scritti in ora locale di Roma. */
    val zone: ZoneId = ZoneId.of(ZONE_ID)

    val contacts: List<WhitelistedContact> = CONTACTS

    /** Gli id reali sono quelli che la validazione locale conosce (gli alias `contact_n` sono del modello). */
    val whitelistedIds: Set<String> = CONTACTS.mapTo(LinkedHashSet()) { it.id }

    /**
     * App del telefono di prova, con le etichette nella lingua del telefono: come sull'app, la lingua
     * dell'interfaccia e quella delle etichette dei lanciatori coincidono (`it` o `en`).
     */
    fun installedApps(lang: String): List<InstalledAppCandidate> = when (lang) {
        "it" -> INSTALLED_APPS_IT
        "en" -> INSTALLED_APPS_EN
        else -> throw BenchException("lingua '$lang' non ammessa")
    }

    /**
     * La richiesta come la manda ChatViewModel al compile: il testo piu' i candidati app che
     * corrispondono (`installed_app_candidates`), cercati con le funzioni vere dell'app. Mai l'elenco intero.
     */
    fun compilePrompt(text: String, lang: String): String =
        composeInstalledAppCandidatesPrompt(text, matchInstalledApps(text, installedApps(lang)))

    /** Il manifest del caso, con la lingua dei testi per l'utente impostata come fa l'app (lingua dell'interfaccia). */
    fun manifest(kind: ManifestKind, lang: String): CapabilityManifest {
        if (lang !in REPLY_LANGUAGES) throw BenchException("lingua '$lang' non ammessa")
        return manifest(kind).copy(replyLanguage = lang)
    }

    val validator = DraftValidator(AndroidCapabilityProbe.KNOWN_TOOLS)

    /** Stato neutro del telefono, come lo vede il compile. */
    val deviceState: DeviceState = DeviceState(values = baseValues("80"))

    /** Stato dei casi `act` con contesto `state`: batteria riconoscibile nel testo generato. */
    val actState: DeviceState = DeviceState(values = baseValues("73"))

    fun manifest(kind: ManifestKind): CapabilityManifest = when (kind) {
        ManifestKind.FULL -> fullManifest
        ManifestKind.NO_SHIZUKU -> noShizukuManifest
    }

    /** Capability che il fire-time e la revisione considerano disponibili ORA per [kind]. */
    fun availableCapabilities(kind: ManifestKind): Set<String> = when (kind) {
        ManifestKind.FULL -> fullCapabilities
        ManifestKind.NO_SHIZUKU -> noShizukuCapabilities
    }

    fun contactId(displayName: String): String? = CONTACTS.firstOrNull { it.displayName == displayName }?.id

    companion object {
        const val ZONE_ID = "Europe/Rome"

        val CONTACTS: List<WhitelistedContact> = listOf(
            WhitelistedContact("Mamma", "bench-jid-mamma"),
            WhitelistedContact("Marco Bianchi", "bench-jid-marco"),
            WhitelistedContact("Giulia", "bench-jid-giulia"),
        )

        val CONTACT_NAMES: Set<String> = CONTACTS.mapTo(LinkedHashSet()) { it.displayName }

        private val REPLY_LANGUAGES = setOf("it", "en")

        /** App comuni con pacchetto vero: (pacchetto, etichetta inglese, etichetta italiana). */
        private val APP_TABLE: List<Triple<String, String, String>> = listOf(
            Triple("com.google.android.apps.maps", "Maps", "Maps"),
            Triple("com.whatsapp", "WhatsApp", "WhatsApp"),
            Triple("org.telegram.messenger", "Telegram", "Telegram"),
            Triple("com.spotify.music", "Spotify", "Spotify"),
            Triple("com.google.android.youtube", "YouTube", "YouTube"),
            Triple("com.android.chrome", "Chrome", "Chrome"),
            Triple("com.google.android.gm", "Gmail", "Gmail"),
            Triple("com.google.android.calendar", "Calendar", "Calendario"),
            Triple("com.google.android.GoogleCamera", "Camera", "Fotocamera"),
            Triple("com.android.settings", "Settings", "Impostazioni"),
            Triple("com.instagram.android", "Instagram", "Instagram"),
            Triple("com.facebook.katana", "Facebook", "Facebook"),
            Triple("com.netflix.mediaclient", "Netflix", "Netflix"),
            Triple("com.amazon.mShop.android.shopping", "Amazon", "Amazon"),
            Triple("com.google.android.apps.photos", "Photos", "Foto"),
            Triple("com.google.android.apps.docs", "Drive", "Drive"),
            Triple("com.google.android.deskclock", "Clock", "Orologio"),
            Triple("com.google.android.apps.messaging", "Messages", "Messaggi"),
            Triple("com.zhiliaoapp.musically", "TikTok", "TikTok"),
            Triple("com.android.vending", "Play Store", "Play Store"),
            Triple("com.google.android.calculator", "Calculator", "Calcolatrice"),
        )

        /**
         * App del telefono di prova, elenco fisso. Come nell'app, solo quelle che la richiesta nomina
         * arrivano al modello (come `installed_app_candidates`), mai l'elenco intero.
         */
        val INSTALLED_APPS_EN: List<InstalledAppCandidate> =
            APP_TABLE.map { (pkg, en, _) -> InstalledAppCandidate(en, pkg) }

        val INSTALLED_APPS_IT: List<InstalledAppCandidate> =
            APP_TABLE.map { (pkg, _, italian) -> InstalledAppCandidate(italian, pkg) }

        private val FULL_STATE = AndroidCapabilityState(
            deviceModel = "CPH2747",
            androidVersion = 16,
            androidApi = 36,
            shizukuStatus = ShizukuGatewayStatus.AUTHORIZED,
            shizukuPermissionGranted = true,
            notificationsGranted = true,
            notificationListenerGranted = true,
            foregroundLocationGranted = true,
            backgroundLocationGranted = true,
            exactAlarmsGranted = true,
            batteryOptimizationExempt = true,
            receiveSmsGranted = true,
            readPhoneStateGranted = true,
            readCallLogGranted = true,
            bluetoothConnectGranted = true,
            activityRecognitionGranted = true,
            dndPolicyGranted = true,
            locationEnabled = true,
            writeSettingsGranted = true,
        )

        private val NO_SHIZUKU_STATE = FULL_STATE.copy(
            shizukuStatus = ShizukuGatewayStatus.NOT_INSTALLED,
            shizukuPermissionGranted = false,
        )

        private val SIGNIFICANT_MOTION = AndroidSensorCapability(
            kind = SensorKind.SIGNIFICANT_MOTION,
            reportingMode = SensorReportingMode.ONE_SHOT,
            wakeUp = true,
            fifoMaxEventCount = 0,
            minDelayUs = 0,
            maxDelayUs = 0,
            permissionGranted = true,
        )

        private fun baseValues(battery: String): Map<String, String> = mapOf(
            "ringer" to "normal",
            "wifi" to "on",
            "bluetooth" to "off",
            "dnd" to "off",
            "battery" to battery,
            "charging" to "false",
            "airplane" to "off",
            "screen" to "on",
        )

        suspend fun create(): BenchWorld {
            val deviceState = DeviceState(values = baseValues("80"))
            val full = snapshot(FULL_STATE, deviceState)
            val noShizuku = snapshot(NO_SHIZUKU_STATE, deviceState)
            return BenchWorld(full.first, full.second, noShizuku.first, noShizuku.second)
        }

        private suspend fun snapshot(
            state: AndroidCapabilityState,
            deviceState: DeviceState,
        ): Pair<CapabilityManifest, Set<String>> {
            val probe = AndroidCapabilityProbe(
                source = AndroidCapabilityStateSource { state },
                whitelist = StaticWhitelist(CONTACTS),
                readiness = GenerativeRuntimeReadiness {
                    GenerativeReadiness(bridgeConfigured = true, privacyAccepted = true)
                },
                sensors = AndroidSensorCapabilitySource { _ -> listOf(SIGNIFICANT_MOTION) },
                implementedSensorKinds = AndroidCapabilityProbe.IMPLEMENTED_SENSOR_KINDS,
                // Come ArgusModule: DND/Ringer e LaunchApp/OpenUrl senza Shizuku.
                baseTierActive = true,
            )
            return probe.probe(deviceState) to probe.current().availableCapabilities
        }
    }
}

private class StaticWhitelist(initial: List<WhitelistedContact>) : ContactWhitelistStore {
    private val values = MutableStateFlow(initial)
    override suspend fun all(): List<WhitelistedContact> = values.value
    override fun observeAll(): Flow<List<WhitelistedContact>> = values
    override suspend fun upsert(contact: WhitelistedContact) {
        values.value = values.value.filterNot { it.id == contact.id } + contact
    }
    override suspend fun remove(conversationId: String) {
        values.value = values.value.filterNot { it.id == conversationId }
    }
}
