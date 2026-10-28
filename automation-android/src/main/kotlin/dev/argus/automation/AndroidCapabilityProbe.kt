package dev.argus.automation

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.StateReaderManifest
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQueryFamily
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.NoopAuditSink
import kotlinx.coroutines.CancellationException
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import dev.argus.device.PermanentGrantStatus
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus

internal data class AndroidCapabilityState(
    val deviceModel: String,
    val androidVersion: Int,
    val androidApi: Int,
    val shizukuStatus: ShizukuGatewayStatus,
    val shizukuPermissionGranted: Boolean,
    val notificationsGranted: Boolean,
    val notificationListenerGranted: Boolean,
    val foregroundLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val exactAlarmsGranted: Boolean,
    val batteryOptimizationExempt: Boolean,
    val receiveSmsGranted: Boolean = false,
    val readPhoneStateGranted: Boolean = false,
    val readCallLogGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = false,
    val activityRecognitionGranted: Boolean = false,
    /** Accesso «Non disturbare» (`ACCESS_NOTIFICATION_POLICY`): abilita DND/Ringer senza Shizuku. */
    val dndPolicyGranted: Boolean = false,
    /**
     * Servizi di localizzazione accesi (`LocationManagerCompat.isLocationEnabled`). Con la posizione
     * spenta dalle impostazioni rapide geofence e identita' Wi-Fi (SSID) non funzionano anche con
     * tutti i permessi concessi.
     */
    val locationEnabled: Boolean = true,
    /**
     * Il telefono ha più di uno slot SIM / modem (0.4.1): solo allora il filtro SIM di `phone_state`
     * ha senso e viene pubblicato.
     */
    val multiSim: Boolean = false,
    /**
     * 0.4.1: permesso speciale «Modifica impostazioni di sistema» (`Settings.System.canWrite`). Abilita
     * luminosità e rotazione dello schermo senza Shizuku.
     */
    val writeSettingsGranted: Boolean = false,
    /**
     * 0.5.0: il backend root (`su`) è acceso E verificato (uid 0). Vale come Shizuku per i comandi
     * privilegiati: le capacità «solo Shizuku» diventano «Shizuku o root».
     */
    val rootActive: Boolean = false,
    /** 0.5.0: `WRITE_SECURE_SETTINGS` concesso (permessi permanenti): `write_setting` su SECURE e GLOBAL senza Shizuku. */
    val secureSettingsGranted: Boolean = false,
    /** 0.5.0: accesso ai dati di utilizzo concesso (`GET_USAGE_STATS`): `app_in_foreground` senza Shizuku. */
    val usageStatsGranted: Boolean = false,
    /** 0.5.1: il telefono ha l'NFC (`FEATURE_NFC`): solo allora `set_nfc` si pubblica. */
    val nfcPresent: Boolean = false,
) {
    /** Uno shell privilegiato è disponibile ORA: Shizuku autorizzato (Sui compreso) o root verificato. */
    val privilegedBackendAvailable: Boolean
        get() = shizukuStatus == ShizukuGatewayStatus.AUTHORIZED || rootActive

    /** Permessi di posizione precisi, in primo piano e in background. */
    val locationGranted: Boolean get() = foregroundLocationGranted && backgroundLocationGranted

    /** Geofence e SSID funzionano: permessi concessi e servizi di localizzazione accesi. */
    val locationUsable: Boolean get() = locationGranted && locationEnabled

    /**
     * Il Bluetooth si legge con le API pubbliche solo da Android 12 col permesso `BLUETOOTH_CONNECT`
     * concesso (prima servirebbe il permesso legacy `BLUETOOTH`, che l'app non dichiara). Senza,
     * la chiave `bluetooth` resta a Shizuku.
     */
    val publicBluetoothStateReadable: Boolean
        get() = androidApi >= Build.VERSION_CODES.S && bluetoothConnectGranted
}

internal fun interface AndroidCapabilityStateSource {
    fun read(): AndroidCapabilityState
}

internal class SystemAndroidCapabilityStateSource(
    context: Context,
    private val shizuku: ShizukuGateway,
    /** 0.5.0: root `su` attivo e verificato. Letto dalla cache, non chiama mai `su`. */
    private val rootActive: () -> Boolean = { false },
) : AndroidCapabilityStateSource {
    private val appContext = context.applicationContext

    override fun read(): AndroidCapabilityState = AndroidCapabilityState(
        deviceModel = Build.MODEL.ifBlank { "Android" },
        androidVersion = Build.VERSION.RELEASE.substringBefore('.').toIntOrNull()
            ?: Build.VERSION.SDK_INT,
        androidApi = Build.VERSION.SDK_INT,
        shizukuStatus = runCatching { shizuku.status() }
            .getOrDefault(ShizukuGatewayStatus.UNSUPPORTED),
        shizukuPermissionGranted = granted(SHIZUKU_PERMISSION),
        notificationsGranted = (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS)
            ) && runCatching {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }.getOrDefault(false),
        notificationListenerGranted = runCatching {
            appContext.packageName in
                NotificationManagerCompat.getEnabledListenerPackages(appContext)
        }.getOrDefault(false),
        // Geofence e identità Wi-Fi richiedono accesso preciso: il solo COARSE non va
        // pubblicato a Hermes come capacità realmente armabile.
        foregroundLocationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        backgroundLocationGranted = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        exactAlarmsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        batteryOptimizationExempt = appContext.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(appContext.packageName),
        receiveSmsGranted = granted(Manifest.permission.RECEIVE_SMS),
        readPhoneStateGranted = granted(Manifest.permission.READ_PHONE_STATE),
        readCallLogGranted = granted(Manifest.permission.READ_CALL_LOG),
        bluetoothConnectGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(Manifest.permission.BLUETOOTH_CONNECT),
        activityRecognitionGranted = granted(Manifest.permission.ACTIVITY_RECOGNITION),
        dndPolicyGranted = runCatching {
            appContext.getSystemService(NotificationManager::class.java)
                .isNotificationPolicyAccessGranted
        }.getOrDefault(false),
        // Se non si riesce a leggere non si inventa un blocco: resta «accesa» come prima.
        locationEnabled = runCatching {
            appContext.getSystemService(LocationManager::class.java)
                ?.let(LocationManagerCompat::isLocationEnabled)
        }.getOrNull() ?: true,
        multiSim = runCatching {
            appContext.getSystemService(TelephonyManager::class.java)
                ?.let { it.supportedModemCount >= 2 || it.activeModemCount >= 2 }
        }.getOrNull() ?: false,
        writeSettingsGranted = runCatching { Settings.System.canWrite(appContext) }.getOrDefault(false),
        rootActive = runCatching(rootActive).getOrDefault(false),
        secureSettingsGranted = PermanentGrantStatus.secureSettings(appContext),
        usageStatsGranted = PermanentGrantStatus.usageStats(appContext),
        nfcPresent = runCatching {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
        }.getOrDefault(false),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    }
}

/**
 * Perche' una capability richiesta da una regola non e' utilizzabile ORA (diagnosi «Perche' non e'
 * scattata?» del Dettaglio). Una causa per capability, derivata dagli stessi flag di stato che usa
 * il probe per pubblicarla.
 */
enum class BlockCause {
    SHIZUKU_NOT_INSTALLED,
    SHIZUKU_NOT_RUNNING,
    SHIZUKU_NOT_AUTHORIZED,
    SHIZUKU_TOO_OLD,
    LOCATION_OFF,
    LOCATION_PERMISSION,
    NOTIFICATION_ACCESS,
    NOTIFICATIONS_PERMISSION,
    SMS_PERMISSION,
    CALL_PERMISSION,
    BLUETOOTH_PERMISSION,
    DND_ACCESS,
    AI_NOT_READY,
    SENSOR_UNAVAILABLE,

    /** 0.4.1: manca «Modifica impostazioni di sistema» (luminosità, rotazione). */
    WRITE_SETTINGS_ACCESS,
    OTHER,
}

private data class ResolvedCapabilities(
    val state: AndroidCapabilityState,
    val contacts: List<WhitelistedContact>,
    val available: Set<String>,
    val transientlyUnavailable: Set<String>,
    val availableTools: List<String>,
    val unavailableTools: Map<String, String>,
    val armableSensorKinds: List<SensorKind>,
    val blockDetails: Map<String, String> = emptyMap(),
)

class AndroidCapabilityProbe internal constructor(
    private val source: AndroidCapabilityStateSource,
    private val whitelist: ContactWhitelistStore,
    private val readiness: GenerativeRuntimeReadiness,
    private val sensors: AndroidSensorCapabilitySource = EmptyAndroidSensorCapabilitySource,
    private val implementedSensorKinds: Set<SensorKind> = emptySet(),
    /**
     * Tier base attivo (decision record §7.3, piano P3-3): quando `true`, le azioni BASE
     * (LaunchApp/OpenUrl sempre; DND/Ringer col grant `ACCESS_NOTIFICATION_POLICY`) si pubblicano
     * anche senza Shizuku. Default `false` = ogni azione resta gated su Shizuku (legacy), così il
     * flip è deliberato e allineato all'executor che esegue davvero le base senza privilegi (B4).
     */
    private val baseTierActive: Boolean = false,
) : CapabilityProbe, FirePolicySnapshotProvider {
    constructor(
        context: Context,
        shizuku: ShizukuGateway,
        whitelist: ContactWhitelistStore,
        readiness: GenerativeRuntimeReadiness,
        baseTierActive: Boolean = false,
        rootActive: () -> Boolean = { false },
    ) : this(
        SystemAndroidCapabilityStateSource(context, shizuku, rootActive),
        whitelist,
        readiness,
        SystemAndroidSensorCapabilitySource(context),
        IMPLEMENTED_SENSOR_KINDS,
        baseTierActive,
    )

    override suspend fun probe(currentState: DeviceState): CapabilityManifest {
        val resolved = resolve()
        return CapabilityManifest(
            deviceModel = resolved.state.deviceModel,
            androidVersion = resolved.state.androidVersion,
            androidApi = resolved.state.androidApi,
            shizukuAvailable = resolved.state.privilegedBackendAvailable,
            grantedPermissions = grantedPermissionNames(resolved.state),
            availableTools = resolved.availableTools,
            unavailableTools = resolved.unavailableTools,
            whitelistedContacts = resolved.contacts,
            // Solo le chiavi con una capacita' `state.<chiave>` armabile ORA (API pubbliche, o Shizuku
            // autorizzato per il Bluetooth senza permesso): altrimenti il modello propone `state_equals`
            // che l'app non puo' armare. Il manifest serve solo al compile: le regole gia' armate non
            // cambiano.
            stateKeys = StateKeys.ALL.filterKeys { CapabilityIds.state(it) in resolved.available },
            availableTriggers = availableTriggers(resolved.state, resolved.armableSensorKinds),
            stateReaders = StateReaderManifest(
                families = StateQueryFamily.entries.filter { family ->
                    family.capabilityId in resolved.available
                },
            ),
        )
    }

    /** Wire name dei trigger armabili ORA: Hermes non deve mai proporre un trigger morto. */
    private fun availableTriggers(
        state: AndroidCapabilityState,
        armableSensorKinds: List<SensorKind>,
    ): List<String> = buildList {
        add("time")
        // Sempre armabile: fira on-arm, nessuna dipendenza OS o grant.
        add("immediate")
        // 0.4.1: avvio manuale, sempre armabile (nessuna dipendenza OS o grant).
        add("manual")
        if (state.notificationListenerGranted) add("notification")
        if (state.locationUsable) add("geofence")
        if (state.receiveSmsGranted) add("phone_state.sms")
        if (state.readPhoneStateGranted && state.readCallLogGranted) add("phone_state.call")
        add("connectivity.wifi")
        if (state.locationUsable) add("connectivity.wifi.identity")
        if (state.bluetoothConnectGranted) add("connectivity.bt")
        add("connectivity.power")
        armableSensorKinds.forEach { add("sensor.${it.wireName}") }
        // 0.4.1: eventi di sistema senza Shizuku, sempre armabili (receiver dinamici, nessun grant).
        add("screen")
        add("battery")
        add("audio_output")
        add("mode_changed")
        add("media")
        // Il filtro SIM vale solo su un telefono con più slot e dove SMS o chiamate sono armabili.
        if (state.multiSim && (state.receiveSmsGranted || (state.readPhoneStateGranted && state.readCallLogGranted))) {
            add("phone_state.sim")
        }
    }

    /**
     * Dettaglio stabile del PERCHE' una capability e' bloccata quando la causa non e' un permesso
     * mancante: capability → codice (oggi solo `location_disabled`: permessi di posizione concessi
     * ma servizi di localizzazione spenti). Vuota se nulla e' bloccato per questi motivi. Il
     * blocco al fire-time resta `capability_unavailable`; questo e' il dettaglio da auditare.
     */
    suspend fun capabilityBlockDetails(): Map<String, String> = resolve().blockDetails

    /**
     * Per ogni capability di [required] che oggi NON e' in `available` (cioe' che il fire-time
     * bloccherebbe con `capability_unavailable`), la causa del blocco. Mappa vuota se e' tutto
     * disponibile. Le capability che il tier base rende disponibili anche con Shizuku fermo (DND e
     * avvio app) stanno sia in `available` sia nel set transitorio ma funzionano: non sono blocchi.
     * Non cambia `resolve()` ne' cio' che il probe pubblica: legge lo stesso stato.
     */
    suspend fun blockCauses(required: Set<String>): Map<String, BlockCause> {
        val resolved = resolve()
        val causes = linkedMapOf<String, BlockCause>()
        required.forEach { capability ->
            if (capability !in resolved.available) {
                causes[capability] = causeOf(capability, resolved.state)
            }
        }
        return causes
    }

    private fun causeOf(capability: String, state: AndroidCapabilityState): BlockCause = when {
        // Il Bluetooth e' l'unica chiave di stato pubblica che puo' mancare senza Shizuku: dipende dal
        // permesso (Android 12+). Le altre sette sono sempre leggibili e non risultano mai bloccate.
        capability == CapabilityIds.state(StateKeys.BLUETOOTH) ->
            if (state.androidApi >= Build.VERSION_CODES.S) BlockCause.BLUETOOTH_PERMISSION
            else shizukuCause(state.shizukuStatus)
        capability in PRIVILEGED_CAPABILITIES || capability == TOOL_STATE_READ ->
            shizukuCause(state.shizukuStatus)
        // 0.5.1: set_nfc senza NFC non ha una causa che l'utente possa rimuovere.
        capability in NFC_CAPABILITIES ->
            if (state.nfcPresent) shizukuCause(state.shizukuStatus) else BlockCause.OTHER
        capability in BASE_DND_CAPABILITIES ->
            if (baseTierActive && !state.dndPolicyGranted) BlockCause.DND_ACCESS
            else shizukuCause(state.shizukuStatus)
        capability in BASE_LAUNCH_CAPABILITIES -> shizukuCause(state.shizukuStatus)
        capability in WRITE_SETTINGS_CAPABILITIES -> BlockCause.WRITE_SETTINGS_ACCESS
        capability == CapabilityIds.TRIGGER_GEOFENCE ||
            capability == CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY ->
            if (state.locationGranted && !state.locationEnabled) BlockCause.LOCATION_OFF
            else BlockCause.LOCATION_PERMISSION
        capability == CapabilityIds.STATE_LOCATION -> BlockCause.LOCATION_PERMISSION
        capability == CapabilityIds.TRIGGER_NOTIFICATION ||
            capability == GenerativeContract.TOOL_WHATSAPP_REPLY ||
            capability == ActionCapabilities.WHATSAPP_REPLY -> BlockCause.NOTIFICATION_ACCESS
        capability == ActionCapabilities.SHOW_NOTIFICATION -> BlockCause.NOTIFICATIONS_PERMISSION
        capability == CapabilityIds.TRIGGER_PHONE_SMS -> BlockCause.SMS_PERMISSION
        capability == CapabilityIds.TRIGGER_PHONE_CALL -> BlockCause.CALL_PERMISSION
        capability == CapabilityIds.TRIGGER_CONNECTIVITY_BT -> BlockCause.BLUETOOTH_PERMISSION
        capability == CapabilityIds.ACTION_INVOKE_LLM ||
            capability == GenerativeContract.TOOL_WEB_SEARCH -> BlockCause.AI_NOT_READY
        capability.startsWith(SENSOR_TRIGGER_PREFIX) -> BlockCause.SENSOR_UNAVAILABLE
        else -> BlockCause.OTHER
    }

    private fun shizukuCause(status: ShizukuGatewayStatus): BlockCause = when (status) {
        ShizukuGatewayStatus.NOT_INSTALLED -> BlockCause.SHIZUKU_NOT_INSTALLED
        ShizukuGatewayStatus.INSTALLED_NOT_RUNNING -> BlockCause.SHIZUKU_NOT_RUNNING
        ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED -> BlockCause.SHIZUKU_NOT_AUTHORIZED
        ShizukuGatewayStatus.UNSUPPORTED -> BlockCause.SHIZUKU_TOO_OLD
        // Con Shizuku autorizzato le privilegiate sono tutte disponibili: non si arriva qui.
        ShizukuGatewayStatus.AUTHORIZED -> BlockCause.OTHER
    }

    override suspend fun current(): FirePolicySnapshot {
        val resolved = resolve()
        return FirePolicySnapshot(
            knownTools = KNOWN_TOOLS,
            availableCapabilities = resolved.available,
            whitelistedConversationIds = resolved.contacts.mapTo(linkedSetOf()) { it.id },
            transientlyUnavailableCapabilities = resolved.transientlyUnavailable,
        )
    }

    private suspend fun resolve(): ResolvedCapabilities {
        val state = source.read()
        val contacts = whitelist.all()
        val generative = readiness.current()
        val armableSensorKinds = SensorCapabilityPolicy.armableKinds(
            sensors.read(state.activityRecognitionGranted),
            implementedSensorKinds,
        )
        // 0.5.0: «Shizuku o root». Il nome resta per non toccare le decine di punti che lo usano.
        val shizukuAvailable = state.privilegedBackendAvailable
        val shizukuTransient = state.shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING &&
            state.shizukuPermissionGranted
        // Tier base: LaunchApp/OpenUrl senza grant; DND/Ringer col policy `ACCESS_NOTIFICATION_POLICY`.
        val launchBase = baseTierActive
        val dndBase = baseTierActive && state.dndPolicyGranted
        val listenerGranted = state.notificationListenerGranted
        // Requisiti runtime della lane generativa oltre al canale notification/reply: chiave del
        // provider e consenso privacy. L'esenzione batteria NON è un requisito (D7).
        val generativeReady = generative.bridgeConfigured && generative.privacyAccepted

        val available = buildSet {
            add(CapabilityIds.TRIGGER_TIME)
            // Trigger immediato: nessuna dipendenza OS, fira on-arm ⇒ sempre disponibile.
            add(CapabilityIds.TRIGGER_IMMEDIATE)
            // 0.4.1: avvio manuale, stesso regime dell'immediato: nessun grant, parte da un gesto.
            add(CapabilityIds.TRIGGER_MANUAL)
            // Geofence e SSID richiedono permessi E servizi di localizzazione accesi: con la
            // posizione spenta dalle impostazioni rapide non funzionano (blocco temporaneo, D6).
            if (state.locationUsable) add(CapabilityIds.TRIGGER_GEOFENCE)
            add(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI)
            add(CapabilityIds.TRIGGER_CONNECTIVITY_POWER)
            // 0.4.1: sorgenti di sistema della sentinella; nessun permesso, sempre pubblicate.
            addAll(DEVICE_EVENT_TRIGGER_CAPABILITIES)
            if (state.multiSim) add(CapabilityIds.TRIGGER_PHONE_SIM)
            if (state.bluetoothConnectGranted) add(CapabilityIds.TRIGGER_CONNECTIVITY_BT)
            if (state.locationUsable) add(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY)
            // Fire-time location conditions use framework LocationManager, not Shizuku.
            if (state.locationGranted) add(CapabilityIds.STATE_LOCATION)
            // Le chiavi di stato (batteria, carica, schermo, suoneria, DND, aereo, Wi-Fi, Bluetooth) si
            // leggono con le API pubbliche di Android: armabili anche senza Shizuku, fermo o non
            // autorizzato. Il Bluetooth solo col permesso; con Shizuku autorizzato resta leggibile
            // comunque (lo shell e' il ripiego), come prima.
            addAll(publicStateCapabilities(state))
            // Clipboard locale: nessun permesso OS richiesto (scrittura verificata su device).
            add(ActionCapabilities.COPY_TO_CLIPBOARD)
            // copy_text (clipboard letterale): stesso regime BASE, sempre disponibile.
            add(ActionCapabilities.COPY_TEXT)
            // Sveglia/timer: Intent AlarmClock col permesso normal SET_ALARM (auto-concesso), quindi
            // sempre armabili senza Shizuku né grant runtime, come la clipboard.
            addAll(BASE_ALARM_CAPABILITIES)
            // Manager pack S4 (volume/torcia/schermata impostazioni/vibrazione): AudioManager/
            // CameraManager/Vibrator/Intent Settings, solo permesso normal VIBRATE, nessun grant
            // runtime pubblicabile. Sempre disponibili come la clipboard (la gate DND del volume a 0
            // è un fallimento tipizzato a runtime, non un gate di pubblicazione).
            addAll(BASE_MANAGER_CAPABILITIES)
            // 0.4.1: lettura ad alta voce, tasti multimediali e chiamata HTTP: nessun permesso da
            // concedere (TextToSpeech, AudioManager, rete). Luminosità e rotazione solo col permesso
            // speciale «Modifica impostazioni di sistema».
            addAll(BASE_ACTIONS_041_CAPABILITIES)
            if (state.writeSettingsGranted) addAll(WRITE_SETTINGS_CAPABILITIES)
            // 0.5.0, permessi permanenti: con `WRITE_SECURE_SETTINGS` la scrittura parametrica passa dal
            // ContentResolver (SECURE/GLOBAL; SYSTEM resta legato a «Modifica impostazioni di sistema»),
            // con l'accesso ai dati di utilizzo l'app in primo piano si legge da UsageStatsManager.
            if (state.secureSettingsGranted) add(ActionCapabilities.WRITE_SETTING)
            if (state.usageStatsGranted) add(CapabilityIds.STATE_FOREGROUND_APP)
            if (state.notificationsGranted) add(ActionCapabilities.SHOW_NOTIFICATION)
            // CapabilityRequirements persiste anche i raw tool approvati: il set del fire-time
            // deve contenere gli stessi nomi wire, senza alias con le capability typed.
            // 0.5.1: set_nfc solo con un backend privilegiato E l'NFC sul telefono.
            if (shizukuAvailable && state.nfcPresent) addAll(NFC_CAPABILITIES)
            if (shizukuAvailable) {
                addAll(PRIVILEGED_CAPABILITIES)
                // Capability runtime usata da contextSources=["state"]; non è un Action wire e
                // quindi non va pubblicata in manifest.available_tools.
                add(TOOL_STATE_READ)
            }
            if (shizukuAvailable || dndBase) addAll(BASE_DND_CAPABILITIES)
            if (shizukuAvailable || launchBase) addAll(BASE_LAUNCH_CAPABILITIES)
            if (listenerGranted) {
                add(CapabilityIds.TRIGGER_NOTIFICATION)
                add(GenerativeContract.TOOL_WHATSAPP_REPLY)
                // La reply statica è eseguita da ShizukuActionExecutor via NotificationReplyGateway:
                // stesso canale del tool raw, quindi stesso grant.
                add(ActionCapabilities.WHATSAPP_REPLY)
            }
            // Telefonia (P2-2): grant runtime distinti per evento.
            if (state.receiveSmsGranted) add(CapabilityIds.TRIGGER_PHONE_SMS)
            if (state.readPhoneStateGranted && state.readCallLogGranted) {
                add(CapabilityIds.TRIGGER_PHONE_CALL)
            }
            if (generativeReady) {
                add(CapabilityIds.ACTION_INVOKE_LLM)
                // web.search e' una capability RICHIESTA all'arm (CapabilityRequirements.InvokeLlm fa
                // addAll(allowedTools)): deve stare nel set `available`, non solo in availableTools, o
                // una regola reply+web compila ma non si arma ("capacita non disponibile: web.search").
                add(GenerativeContract.TOOL_WEB_SEARCH)
            }
            armableSensorKinds.forEach { add(CapabilityIds.triggerSensor(it)) }
        }
        val transient = if (shizukuTransient) {
            // Anche le chiavi di stato: se il Bluetooth non e' leggibile in pubblico, con Shizuku
            // fermo ma autorizzato e' un outage recuperabile, come prima della lettura pubblica.
            PRIVILEGED_CAPABILITIES + BASE_DND_CAPABILITIES + BASE_LAUNCH_CAPABILITIES + TOOL_STATE_READ +
                (if (state.nfcPresent) NFC_CAPABILITIES else emptySet())
        } else {
            emptySet()
        }

        val availableTools = buildList {
            // Control-flow P4 (wait/if/while): contenitori STRUTTURALI del programma, non azioni gated
            // dal SO. Vanno pubblicati SEMPRE (nessuna dipendenza da Shizuku/permessi/hardware): il
            // compilatore usa solo manifest.available_tools, quindi senza questi crederebbe che `wait`
            // non esista e rifiuterebbe una pausa con unsupported_capability (bug device-found).
            add(ActionTypeIds.WAIT)
            add(ActionTypeIds.IF)
            add(ActionTypeIds.WHILE)
            add(ActionTypeIds.COPY_TO_CLIPBOARD)
            add(ActionTypeIds.COPY_TEXT)
            addAll(BASE_ALARM_ACTION_TYPES)
            addAll(BASE_MANAGER_ACTION_TYPES)
            addAll(BASE_ACTIONS_041_TYPES)
            if (state.writeSettingsGranted) addAll(WRITE_SETTINGS_ACTION_TYPES)
            if (shizukuAvailable) {
                addAll(PRIVILEGED_ACTION_TYPES)
                if (state.nfcPresent) addAll(NFC_ACTION_TYPES)
            }
            if (shizukuAvailable || dndBase) addAll(BASE_DND_ACTION_TYPES)
            if (shizukuAvailable || launchBase) addAll(BASE_LAUNCH_ACTION_TYPES)
            if (state.secureSettingsGranted && ActionTypeIds.WRITE_SETTING !in this) {
                add(ActionTypeIds.WRITE_SETTING)
            }
            if (state.notificationsGranted) {
                add(ActionTypeIds.SHOW_NOTIFICATION)
                add(TOOL_NOTIFY_SHOW)
            }
            if (listenerGranted) add(GenerativeContract.TOOL_WHATSAPP_REPLY)
            // Il compilatore usa SOLO manifest.available_tools: invoke_llm deve comparire qui,
            // altrimenti Hermes ripiega su una reply statica anche quando il runtime è pronto.
            if (generativeReady) {
                add(ActionTypeIds.INVOKE_LLM)
                add(ActionTypeIds.INVOKE_LLM_V2)
                // web.search è disponibile ⇔ invoke_llm lo è: il provider di default (Hermes) fa il
                // web server-side; F3 aggiunge i provider diretti.
                add(GenerativeContract.TOOL_WEB_SEARCH)
            }
        }.sorted()
        val unavailableTools = linkedMapOf<String, String>()
        if (!shizukuAvailable) {
            val reason = shizukuReason(state.shizukuStatus)
            PRIVILEGED_ACTION_TYPES.forEach {
                if (it == ActionTypeIds.WRITE_SETTING && state.secureSettingsGranted) return@forEach
                unavailableTools[it] = reason
            }
            if (!launchBase) BASE_LAUNCH_ACTION_TYPES.forEach { unavailableTools[it] = reason }
            if (!dndBase) BASE_DND_ACTION_TYPES.forEach {
                unavailableTools[it] = if (baseTierActive) REASON_DND_POLICY else reason
            }
        }
        // 0.5.1: set_nfc dipende anche dall'hardware; senza NFC il motivo è questo, non Shizuku.
        if (!state.nfcPresent) {
            NFC_ACTION_TYPES.forEach { unavailableTools[it] = REASON_NO_NFC }
        } else if (!shizukuAvailable) {
            NFC_ACTION_TYPES.forEach { unavailableTools[it] = shizukuReason(state.shizukuStatus) }
        }
        if (!state.writeSettingsGranted) {
            WRITE_SETTINGS_ACTION_TYPES.forEach { unavailableTools[it] = REASON_WRITE_SETTINGS }
        }
        if (!state.notificationsGranted) {
            unavailableTools[ActionTypeIds.SHOW_NOTIFICATION] = "notification permission missing"
            unavailableTools[TOOL_NOTIFY_SHOW] = "notification permission missing"
        }
        if (!listenerGranted) {
            unavailableTools[GenerativeContract.TOOL_WHATSAPP_REPLY] = REASON_NOTIFICATION_LISTENER
        }
        if (!generativeReady) {
            unavailableTools[ActionTypeIds.INVOKE_LLM] = REASON_GENERATIVE_RUNTIME
            unavailableTools[ActionTypeIds.INVOKE_LLM_V2] = REASON_GENERATIVE_RUNTIME
            unavailableTools[GenerativeContract.TOOL_WEB_SEARCH] = REASON_GENERATIVE_RUNTIME
        }
        PHASE_UNAVAILABLE_TOOLS.forEach { (tool, reason) -> unavailableTools[tool] = reason }
        // Permessi di posizione concessi ma servizi spenti: la causa del blocco e' questa, e il
        // modello deve saperlo (con i permessi mancanti il trigger e' gia' assente dal manifest).
        val blockDetails = linkedMapOf<String, String>()
        if (state.locationGranted && !state.locationEnabled) {
            LOCATION_DEPENDENT_CAPABILITIES.forEach { capability ->
                unavailableTools[capability] = REASON_LOCATION_OFF
                blockDetails[capability] = DETAIL_LOCATION_DISABLED
            }
        }

        return ResolvedCapabilities(
            state = state,
            contacts = contacts,
            available = available,
            transientlyUnavailable = transient,
            availableTools = availableTools,
            unavailableTools = unavailableTools.toSortedMap(),
            armableSensorKinds = armableSensorKinds,
            blockDetails = blockDetails,
        )
    }

    private fun grantedPermissionNames(state: AndroidCapabilityState): List<String> = buildList {
        if (state.notificationsGranted) add("notifications")
        if (state.notificationListenerGranted) add("notification_listener")
        if (state.foregroundLocationGranted) add("location_foreground")
        if (state.backgroundLocationGranted) add("location_background")
        if (state.exactAlarmsGranted) add("exact_alarms")
        if (state.batteryOptimizationExempt) add("battery_optimization_exempt")
        if (state.receiveSmsGranted) add("receive_sms")
        if (state.readPhoneStateGranted) add("read_phone_state")
        if (state.readCallLogGranted) add("read_call_log")
        if (state.bluetoothConnectGranted) add("bluetooth_connect")
        if (state.activityRecognitionGranted) add("activity_recognition")
        if (state.writeSettingsGranted) add("write_settings")
        if (state.secureSettingsGranted) add("write_secure_settings")
        if (state.usageStatsGranted) add("usage_stats")
    }

    /** Capability `state.<chiave>` leggibili ORA senza Shizuku (tutte, tranne il Bluetooth senza permesso). */
    private fun publicStateCapabilities(state: AndroidCapabilityState): Set<String> =
        StateKeys.ALL.keys
            .filter { key -> key != StateKeys.BLUETOOTH || state.publicBluetoothStateReadable }
            .mapTo(linkedSetOf(), CapabilityIds::state)

    private fun shizukuReason(status: ShizukuGatewayStatus): String = when (status) {
        ShizukuGatewayStatus.NOT_INSTALLED -> "Shizuku not installed"
        ShizukuGatewayStatus.INSTALLED_NOT_RUNNING -> "Shizuku not running"
        ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED -> "Shizuku permission not granted"
        ShizukuGatewayStatus.UNSUPPORTED -> "Shizuku version not supported"
        ShizukuGatewayStatus.AUTHORIZED -> "Shizuku not available"
    }

    internal companion object {
        /**
         * P3-2B collega il backend reale ([dev.argus.automation.sensor.AndroidSignificantMotionBackend]),
         * quindi il probe può pubblicare SIGNIFICANT_MOTION quando hardware e mode combaciano. Gli
         * altri kind restano fuori finché non hanno un backend proprio (P3-2C).
         */
        val IMPLEMENTED_SENSOR_KINDS: Set<SensorKind> = setOf(SensorKind.SIGNIFICANT_MOTION)
        const val REASON_NOTIFICATION_LISTENER = "notification access not granted"

        /** Trigger di sistema senza Shizuku (0.4.1): schermo, batteria, uscita audio, modalità, media. */
        val DEVICE_EVENT_TRIGGER_CAPABILITIES: Set<String> = setOf(
            CapabilityIds.TRIGGER_SCREEN,
            CapabilityIds.TRIGGER_BATTERY,
            CapabilityIds.TRIGGER_AUDIO_OUTPUT,
            CapabilityIds.TRIGGER_MODE_CHANGED,
            CapabilityIds.TRIGGER_MEDIA,
        )

        /** Prefisso delle capability dei trigger sensore (`trigger.sensor.<kind>`). */
        private const val SENSOR_TRIGGER_PREFIX = "trigger.sensor."

        /** Motivo (in inglese, va al modello) per geofence e SSID con la posizione spenta. */
        const val REASON_LOCATION_OFF = "location services are off"

        /** Dettaglio stabile per il Log/audit dello stesso blocco. */
        const val DETAIL_LOCATION_DISABLED = "location_disabled"

        /** Capability che non funzionano con i servizi di localizzazione spenti. */
        val LOCATION_DEPENDENT_CAPABILITIES = listOf(
            CapabilityIds.TRIGGER_GEOFENCE,
            CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY,
        )
        const val REASON_GENERATIVE_RUNTIME =
            "generative runtime not ready (provider key or privacy consent missing)"
        const val TOOL_STATE_READ = "state.read"
        const val TOOL_SCREEN_CAPTURE = "screen.capture"
        const val TOOL_SCREEN_DUMP_UI = "screen.dump_ui"
        const val TOOL_TOGGLE_SET = "toggle.set"
        const val TOOL_APP_LAUNCH = "app.launch"
        const val TOOL_NOTIFY_SHOW = "notify.show"

        /**
         * Vecchi nomi raw mai collegati a un Action wire eseguibile. Pubblicarli in
         * `available_tools` induceva il compilatore a produrre draft che il validator rifiutava.
         */
        val UNIMPLEMENTED_RAW_TOOLS = setOf(
            TOOL_SCREEN_CAPTURE,
            TOOL_SCREEN_DUMP_UI,
            TOOL_TOGGLE_SET,
            TOOL_APP_LAUNCH,
        )
        const val REASON_DND_POLICY = "Do Not Disturb access not granted"

        /** Motivo (in inglese, va al modello) per luminosità e rotazione senza il permesso speciale. */
        const val REASON_WRITE_SETTINGS = "modify system settings access not granted"

        /** 0.4.1, sempre disponibili: parlato, tasti multimediali, HTTP (nessun grant da pubblicare). */
        val BASE_ACTIONS_041_TYPES = setOf(
            ActionTypeIds.SPEAK,
            ActionTypeIds.MEDIA_CONTROL,
            ActionTypeIds.HTTP_REQUEST,
        )
        val BASE_ACTIONS_041_CAPABILITIES = setOf(
            ActionCapabilities.SPEAK,
            ActionCapabilities.MEDIA_CONTROL,
            ActionCapabilities.HTTP_REQUEST,
        )

        /** 0.4.1, solo col permesso «Modifica impostazioni di sistema» (`WRITE_SETTINGS`). */
        val WRITE_SETTINGS_ACTION_TYPES = setOf(ActionTypeIds.SET_BRIGHTNESS, ActionTypeIds.SET_ROTATION)
        val WRITE_SETTINGS_CAPABILITIES =
            setOf(ActionCapabilities.SET_BRIGHTNESS, ActionCapabilities.SET_ROTATION)

        /** Motivo (in inglese, va al modello) per `set_nfc` su un telefono senza NFC. */
        const val REASON_NO_NFC = "this phone has no NFC"

        /** 0.5.1: `set_nfc` richiede un backend privilegiato e l'NFC sul telefono. */
        val NFC_ACTION_TYPES = setOf(ActionTypeIds.SET_NFC)
        val NFC_CAPABILITIES = setOf(ActionCapabilities.SET_NFC)

        /** Azioni che richiedono davvero lo shell Shizuku: toggle radio, shell e write_setting. */
        val PRIVILEGED_ACTION_TYPES = setOf(
            ActionTypeIds.SET_WIFI,
            ActionTypeIds.SET_BLUETOOTH,
            // Dati mobili: `svc data enable|disable`, nessun percorso app-normale (come i toggle radio).
            ActionTypeIds.SET_MOBILE_DATA,
            // Tema scuro: `cmd uimode night ...`, nessun percorso app-normale (come i toggle radio).
            ActionTypeIds.SET_DARK_MODE,
            ActionTypeIds.RUN_SHELL,
            // Scrittura impostazioni parametrica: `settings put` non ha percorso app-normale.
            ActionTypeIds.WRITE_SETTING,
            // 0.5.1: azioni privilegiate tipizzate (set_nfc a parte, v. NFC_ACTION_TYPES).
            ActionTypeIds.APP_CONTROL,
            ActionTypeIds.SET_AIRPLANE,
            ActionTypeIds.SET_LOCATION,
            ActionTypeIds.SET_BATTERY_SAVER,
            ActionTypeIds.LOCK_SCREEN,
            ActionTypeIds.SCREENSHOT,
            ActionTypeIds.APP_PERMISSION,
        )
        /** Base con grant `ACCESS_NOTIFICATION_POLICY` (NotificationManager/AudioManager). */
        val BASE_DND_ACTION_TYPES = setOf(ActionTypeIds.SET_DND, ActionTypeIds.SET_RINGER)
        /** Base senza alcun grant: Intent verso launcher/URL. */
        val BASE_LAUNCH_ACTION_TYPES = setOf(ActionTypeIds.LAUNCH_APP, ActionTypeIds.OPEN_URL)
        /** Base sempre disponibili (permesso normal SET_ALARM): sveglia/timer via Intent AlarmClock. */
        val BASE_ALARM_ACTION_TYPES = setOf(ActionTypeIds.SET_ALARM, ActionTypeIds.SET_TIMER)
        /** Base sempre disponibili (S4): Manager/Intent, solo permesso normal VIBRATE, ungated. */
        val BASE_MANAGER_ACTION_TYPES = setOf(
            ActionTypeIds.SET_VOLUME,
            ActionTypeIds.SET_FLASHLIGHT,
            ActionTypeIds.OPEN_SETTINGS_SCREEN,
            ActionTypeIds.VIBRATE,
        )
        // Unione legacy: usata per la ragione di indisponibilità quando il tier base è inattivo.
        val SHIZUKU_ACTION_TYPES =
            PRIVILEGED_ACTION_TYPES + BASE_DND_ACTION_TYPES + BASE_LAUNCH_ACTION_TYPES
        val PHASE_UNAVAILABLE_TOOLS = linkedMapOf(
            TOOL_SCREEN_CAPTURE to "typed computer-use loop not implemented",
            TOOL_SCREEN_DUMP_UI to "typed computer-use loop not implemented",
            TOOL_TOGGLE_SET to "raw alias replaced by the typed toggle actions",
            TOOL_APP_LAUNCH to "raw alias replaced by launch_app",
            "screen.tap" to "UI action not available in this phase",
            "screen.swipe" to "UI action not available in this phase",
            "screen.type" to "UI action not available in this phase",
            "app.install" to "app installation not implemented",
            "shell.run" to "live confirmation not implemented",
            "vision.analyze" to "multimodal provider not configured",
        )
        val KNOWN_TOOLS: Set<String> = UNIMPLEMENTED_RAW_TOOLS + TOOL_STATE_READ + TOOL_NOTIFY_SHOW +
            GenerativeContract.TOOL_WHATSAPP_REPLY + GenerativeContract.TOOL_WEB_SEARCH +
            PHASE_UNAVAILABLE_TOOLS.keys
        val BASE_DND_CAPABILITIES = setOf(ActionCapabilities.SET_DND, ActionCapabilities.SET_RINGER)
        val BASE_LAUNCH_CAPABILITIES =
            setOf(ActionCapabilities.LAUNCH_APP, ActionCapabilities.OPEN_URL)
        /** Capability sempre disponibili delle azioni sveglia/timer (permesso normal SET_ALARM). */
        val BASE_ALARM_CAPABILITIES =
            setOf(ActionCapabilities.SET_ALARM, ActionCapabilities.SET_TIMER)
        /** Capability sempre disponibili del Manager pack S4 (ungated, solo permesso normal VIBRATE). */
        val BASE_MANAGER_CAPABILITIES = setOf(
            ActionCapabilities.SET_VOLUME,
            ActionCapabilities.SET_FLASHLIGHT,
            ActionCapabilities.OPEN_SETTINGS_SCREEN,
            ActionCapabilities.VIBRATE,
        )
        /** Capability che richiedono lo shell Shizuku: toggle, shell e lettori privilegiati. */
        val PRIVILEGED_CAPABILITIES: Set<String> = buildSet {
            add(ActionCapabilities.SET_WIFI)
            add(ActionCapabilities.SET_BLUETOOTH)
            add(ActionCapabilities.SET_MOBILE_DATA)
            add(ActionCapabilities.SET_DARK_MODE)
            add(ActionCapabilities.RUN_SHELL)
            // Gate famiglia della scrittura parametrica: forAction(WriteSetting) richiede questa,
            // pubblicata solo con Shizuku (e transiente se Shizuku è fermo ma autorizzato).
            add(ActionCapabilities.WRITE_SETTING)
            // 0.5.1: azioni privilegiate tipizzate (set_nfc a parte, v. NFC_CAPABILITIES).
            add(ActionCapabilities.APP_CONTROL)
            add(ActionCapabilities.SET_AIRPLANE)
            add(ActionCapabilities.SET_LOCATION)
            add(ActionCapabilities.SET_BATTERY_SAVER)
            add(ActionCapabilities.LOCK_SCREEN)
            add(ActionCapabilities.SCREENSHOT)
            add(ActionCapabilities.APP_PERMISSION)
            add(CapabilityIds.STATE_FOREGROUND_APP)
            add(CapabilityIds.STATE_READER_BUILTIN)
            add(CapabilityIds.STATE_READER_SETTING)
            add(CapabilityIds.STATE_READER_SYSTEM_PROPERTY)
            add(CapabilityIds.STATE_READER_SYSFS)
            add(CapabilityIds.STATE_READER_DUMPSYS_FIELD)
            StateKeys.ALL.keys.forEach { add(CapabilityIds.state(it)) }
        }
        /** Le otto `state.<chiave>`: si leggono con le API pubbliche, Shizuku e' solo il ripiego. */
        val PUBLIC_STATE_CAPABILITIES: Set<String> =
            StateKeys.ALL.keys.mapTo(linkedSetOf(), CapabilityIds::state)
        // Capability che dipendono da Shizuku (banner «serve Shizuku»): le chiavi di stato pubbliche non
        // ne fanno piu' parte, una regola che usa solo quelle funziona anche senza.
        val SHIZUKU_CAPABILITIES: Set<String> =
            (PRIVILEGED_CAPABILITIES - PUBLIC_STATE_CAPABILITIES) + BASE_DND_CAPABILITIES +
                BASE_LAUNCH_CAPABILITIES + NFC_CAPABILITIES
    }
}

data class CapabilityReconcileReport(
    val needsReview: List<AutomationId>,
    val temporarilyBlocked: List<AutomationId>,
)

/** Riconcilia grant/revoche senza consumare una revisione approvata più recente. */
class CapabilityReconciler(
    private val store: AutomationStore,
    private val snapshots: FirePolicySnapshotProvider,
    private val audit: AuditSink = NoopAuditSink,
) {
    suspend fun reconcile(): CapabilityReconcileReport {
        val snapshot = snapshots.current()
        val needsReview = mutableListOf<AutomationId>()
        val temporarilyBlocked = mutableListOf<AutomationId>()
        store.armed().forEach { automation ->
            val missing = automation.requiredCapabilities - snapshot.availableCapabilities
            val validationFailed = validationFailed(automation, snapshot)
            val requirementsChanged = automation.requiredCapabilities != CapabilityRequirements.derive(
                automation.trigger,
                automation.actions,
                automation.conditions,
                automation.vars,
            )
            // D6: solo un draft non più valido o requisiti non più coerenti mandano la regola «da
            // rivedere». Una capability mancante (permesso revocato, Shizuku spento, runtime
            // generativo non pronto) è un blocco temporaneo: la regola resta com'è e al fire-time
            // FirePolicy blocca con `capability_unavailable`, auditato.
            if (validationFailed || requirementsChanged) {
                val fingerprint = automation.approvalFingerprint ?: return@forEach
                if (store.markNeedsReviewIfApproved(automation.id, fingerprint)) {
                    needsReview += automation.id
                    // Lifecycle (task #31-B): il PERCHÉ è noto solo qui, reason chiuso per causa.
                    recordNeedsReview(
                        automation.id,
                        reason = if (validationFailed) "validation_failed" else "requirements_changed",
                    )
                }
            } else if (missing.isNotEmpty()) {
                temporarilyBlocked += automation.id
            }
        }
        return CapabilityReconcileReport(needsReview, temporarilyBlocked)
    }

    private suspend fun recordNeedsReview(id: AutomationId, reason: String) {
        try {
            audit.record(
                AuditEvent(
                    id,
                    AuditKind.RULE_NEEDS_REVIEW,
                    System.currentTimeMillis(),
                    detail = reason,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il logging non deve cambiare l'esito del reconcile.
        }
    }

    private fun validationFailed(
        automation: Automation,
        snapshot: FirePolicySnapshot,
    ): Boolean = DraftValidator(snapshot.knownTools).validate(
        AutomationDraft(
            name = automation.name,
            trigger = automation.trigger,
            actions = automation.actions,
            vars = automation.vars,
            conditions = automation.conditions,
            cooldownMs = automation.cooldownMs,
        ),
        snapshot.whitelistedConversationIds,
    ).any { it.severity == Severity.ERROR }
}
