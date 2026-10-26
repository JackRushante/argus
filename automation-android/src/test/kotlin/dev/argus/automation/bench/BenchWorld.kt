package dev.argus.automation.bench

import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AndroidCapabilityState
import dev.argus.automation.AndroidCapabilityStateSource
import dev.argus.automation.AndroidSensorCapability
import dev.argus.automation.AndroidSensorCapabilitySource
import dev.argus.automation.GenerativeReadiness
import dev.argus.automation.GenerativeRuntimeReadiness
import dev.argus.automation.SensorReportingMode
import dev.argus.automation.apps.InstalledAppCandidate
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
 * - `full`: Shizuku autorizzato, tutti i permessi, accesso notifiche e DND, posizione accesa; l'unico
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

    val installedApps: List<InstalledAppCandidate> = INSTALLED_APPS

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

        /**
         * App del telefono di prova. Come nell'app, solo quelle che la richiesta nomina arrivano al
         * modello (come `installed_app_candidates`), mai l'elenco intero.
         */
        val INSTALLED_APPS: List<InstalledAppCandidate> = listOf(
            InstalledAppCandidate("Spotify", "com.spotify.music"),
            InstalledAppCandidate("YouTube", "com.google.android.youtube"),
            InstalledAppCandidate("Chrome", "com.android.chrome"),
            InstalledAppCandidate("Maps", "com.google.android.apps.maps"),
            InstalledAppCandidate("WhatsApp", "com.whatsapp"),
            InstalledAppCandidate("Telegram", "org.telegram.messenger"),
            InstalledAppCandidate("Gmail", "com.google.android.gm"),
            InstalledAppCandidate("Calcolatrice", "com.google.android.calculator"),
            InstalledAppCandidate("Fotocamera", "com.android.camera"),
        )

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
