package dev.argus.automation

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
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
)

internal fun interface AndroidCapabilityStateSource {
    fun read(): AndroidCapabilityState
}

internal class SystemAndroidCapabilityStateSource(
    context: Context,
    private val shizuku: ShizukuGateway,
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
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    }
}

private data class ResolvedCapabilities(
    val state: AndroidCapabilityState,
    val contacts: List<WhitelistedContact>,
    val available: Set<String>,
    val transientlyUnavailable: Set<String>,
    val availableTools: List<String>,
    val unavailableTools: Map<String, String>,
    val armableSensorKinds: List<SensorKind>,
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
    ) : this(
        SystemAndroidCapabilityStateSource(context, shizuku),
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
            shizukuAvailable = resolved.state.shizukuStatus == ShizukuGatewayStatus.AUTHORIZED,
            grantedPermissions = grantedPermissionNames(resolved.state),
            availableTools = resolved.availableTools,
            unavailableTools = resolved.unavailableTools,
            whitelistedContacts = resolved.contacts,
            stateKeys = StateKeys.ALL,
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
        if (state.notificationListenerGranted) add("notification")
        if (state.foregroundLocationGranted && state.backgroundLocationGranted) add("geofence")
        if (state.receiveSmsGranted) add("phone_state.sms")
        if (state.readPhoneStateGranted && state.readCallLogGranted) add("phone_state.call")
        add("connectivity.wifi")
        if (state.foregroundLocationGranted && state.backgroundLocationGranted) {
            add("connectivity.wifi.identity")
        }
        if (state.bluetoothConnectGranted) add("connectivity.bt")
        add("connectivity.power")
        armableSensorKinds.forEach { add("sensor.${it.wireName}") }
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
        val shizukuAvailable = state.shizukuStatus == ShizukuGatewayStatus.AUTHORIZED
        val shizukuTransient = state.shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING &&
            state.shizukuPermissionGranted
        // Tier base: LaunchApp/OpenUrl senza grant; DND/Ringer col policy `ACCESS_NOTIFICATION_POLICY`.
        val launchBase = baseTierActive
        val dndBase = baseTierActive && state.dndPolicyGranted
        val listenerGranted = state.notificationListenerGranted
        // Requisiti runtime della lane generativa oltre al canale notification/reply.
        val generativeReady = generative.bridgeConfigured && generative.privacyAccepted &&
            state.batteryOptimizationExempt

        val available = buildSet {
            add(CapabilityIds.TRIGGER_TIME)
            if (state.foregroundLocationGranted && state.backgroundLocationGranted) {
                add(CapabilityIds.TRIGGER_GEOFENCE)
            }
            add(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI)
            add(CapabilityIds.TRIGGER_CONNECTIVITY_POWER)
            if (state.bluetoothConnectGranted) add(CapabilityIds.TRIGGER_CONNECTIVITY_BT)
            if (state.foregroundLocationGranted && state.backgroundLocationGranted) {
                add(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY)
                // Fire-time location conditions use framework LocationManager, not Shizuku.
                add(CapabilityIds.STATE_LOCATION)
            }
            // Clipboard locale: nessun permesso OS richiesto (scrittura verificata su device).
            add(ActionCapabilities.COPY_TO_CLIPBOARD)
            // Sveglia/timer: Intent AlarmClock col permesso normal SET_ALARM (auto-concesso), quindi
            // sempre armabili senza Shizuku né grant runtime, come la clipboard.
            addAll(BASE_ALARM_CAPABILITIES)
            if (state.notificationsGranted) add(ActionCapabilities.SHOW_NOTIFICATION)
            // CapabilityRequirements persiste anche i raw tool approvati: il set del fire-time
            // deve contenere gli stessi nomi wire, senza alias con le capability typed.
            if (shizukuAvailable) {
                addAll(PRIVILEGED_CAPABILITIES)
                addAll(SHIZUKU_TOOLS)
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
            if (generativeReady) add(CapabilityIds.ACTION_INVOKE_LLM)
            armableSensorKinds.forEach { add(CapabilityIds.triggerSensor(it)) }
        }
        val transient = if (shizukuTransient) SHIZUKU_CAPABILITIES + SHIZUKU_TOOLS else emptySet()

        val availableTools = buildList {
            add(ActionTypeIds.COPY_TO_CLIPBOARD)
            addAll(BASE_ALARM_ACTION_TYPES)
            if (shizukuAvailable) {
                addAll(PRIVILEGED_ACTION_TYPES)
                addAll(SHIZUKU_TOOLS)
            }
            if (shizukuAvailable || dndBase) addAll(BASE_DND_ACTION_TYPES)
            if (shizukuAvailable || launchBase) addAll(BASE_LAUNCH_ACTION_TYPES)
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
            }
        }.sorted()
        val unavailableTools = linkedMapOf<String, String>()
        if (!shizukuAvailable) {
            val reason = shizukuReason(state.shizukuStatus)
            PRIVILEGED_ACTION_TYPES.forEach { unavailableTools[it] = reason }
            SHIZUKU_TOOLS.forEach { unavailableTools[it] = reason }
            if (!launchBase) BASE_LAUNCH_ACTION_TYPES.forEach { unavailableTools[it] = reason }
            if (!dndBase) BASE_DND_ACTION_TYPES.forEach {
                unavailableTools[it] = if (baseTierActive) REASON_DND_POLICY else reason
            }
        }
        if (!state.notificationsGranted) {
            unavailableTools[ActionTypeIds.SHOW_NOTIFICATION] = "permesso notifiche mancante"
            unavailableTools[TOOL_NOTIFY_SHOW] = "permesso notifiche mancante"
        }
        if (!listenerGranted) {
            unavailableTools[GenerativeContract.TOOL_WHATSAPP_REPLY] = REASON_NOTIFICATION_LISTENER
        }
        if (!generativeReady) {
            unavailableTools[ActionTypeIds.INVOKE_LLM] = REASON_GENERATIVE_RUNTIME
            unavailableTools[ActionTypeIds.INVOKE_LLM_V2] = REASON_GENERATIVE_RUNTIME
        }
        PHASE_UNAVAILABLE_TOOLS.forEach { (tool, reason) -> unavailableTools[tool] = reason }

        return ResolvedCapabilities(
            state = state,
            contacts = contacts,
            available = available,
            transientlyUnavailable = transient,
            availableTools = availableTools,
            unavailableTools = unavailableTools.toSortedMap(),
            armableSensorKinds = armableSensorKinds,
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
    }

    private fun shizukuReason(status: ShizukuGatewayStatus): String = when (status) {
        ShizukuGatewayStatus.NOT_INSTALLED -> "Shizuku non installato"
        ShizukuGatewayStatus.INSTALLED_NOT_RUNNING -> "Shizuku non in esecuzione"
        ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED -> "autorizzazione Shizuku mancante"
        ShizukuGatewayStatus.UNSUPPORTED -> "versione Shizuku non supportata"
        ShizukuGatewayStatus.AUTHORIZED -> "Shizuku non disponibile"
    }

    internal companion object {
        /**
         * P3-2B collega il backend reale ([dev.argus.automation.sensor.AndroidSignificantMotionBackend]),
         * quindi il probe può pubblicare SIGNIFICANT_MOTION quando hardware e mode combaciano. Gli
         * altri kind restano fuori finché non hanno un backend proprio (P3-2C).
         */
        val IMPLEMENTED_SENSOR_KINDS: Set<SensorKind> = setOf(SensorKind.SIGNIFICANT_MOTION)
        const val REASON_NOTIFICATION_LISTENER = "accesso alle notifiche non concesso"
        const val REASON_GENERATIVE_RUNTIME =
            "runtime generativo non pronto (bearer, privacy o esenzione batteria mancanti)"
        const val TOOL_STATE_READ = "state.read"
        const val TOOL_SCREEN_CAPTURE = "screen.capture"
        const val TOOL_SCREEN_DUMP_UI = "screen.dump_ui"
        const val TOOL_TOGGLE_SET = "toggle.set"
        const val TOOL_APP_LAUNCH = "app.launch"
        const val TOOL_NOTIFY_SHOW = "notify.show"

        val SHIZUKU_TOOLS = setOf(
            TOOL_STATE_READ,
            TOOL_SCREEN_CAPTURE,
            TOOL_SCREEN_DUMP_UI,
            TOOL_TOGGLE_SET,
            TOOL_APP_LAUNCH,
        )
        const val REASON_DND_POLICY = "accesso «Non disturbare» non concesso"

        /** Azioni che richiedono davvero lo shell Shizuku: toggle radio, shell e write_setting. */
        val PRIVILEGED_ACTION_TYPES = setOf(
            ActionTypeIds.SET_WIFI,
            ActionTypeIds.SET_BLUETOOTH,
            ActionTypeIds.RUN_SHELL,
            // Scrittura impostazioni parametrica: `settings put` non ha percorso app-normale.
            ActionTypeIds.WRITE_SETTING,
        )
        /** Base con grant `ACCESS_NOTIFICATION_POLICY` (NotificationManager/AudioManager). */
        val BASE_DND_ACTION_TYPES = setOf(ActionTypeIds.SET_DND, ActionTypeIds.SET_RINGER)
        /** Base senza alcun grant: Intent verso launcher/URL. */
        val BASE_LAUNCH_ACTION_TYPES = setOf(ActionTypeIds.LAUNCH_APP, ActionTypeIds.OPEN_URL)
        /** Base sempre disponibili (permesso normal SET_ALARM): sveglia/timer via Intent AlarmClock. */
        val BASE_ALARM_ACTION_TYPES = setOf(ActionTypeIds.SET_ALARM, ActionTypeIds.SET_TIMER)
        // Unione legacy: usata per la ragione di indisponibilità quando il tier base è inattivo.
        val SHIZUKU_ACTION_TYPES =
            PRIVILEGED_ACTION_TYPES + BASE_DND_ACTION_TYPES + BASE_LAUNCH_ACTION_TYPES
        val PHASE_UNAVAILABLE_TOOLS = linkedMapOf(
            "screen.tap" to "azione UI non disponibile in questa fase",
            "screen.swipe" to "azione UI non disponibile in questa fase",
            "screen.type" to "azione UI non disponibile in questa fase",
            "app.install" to "installazione app non implementata",
            "shell.run" to "conferma live non implementata",
            "web.search" to "provider non configurato",
            "vision.analyze" to "provider multimodale non configurato",
        )
        val KNOWN_TOOLS: Set<String> = SHIZUKU_TOOLS + TOOL_NOTIFY_SHOW +
            GenerativeContract.TOOL_WHATSAPP_REPLY + PHASE_UNAVAILABLE_TOOLS.keys
        val BASE_DND_CAPABILITIES = setOf(ActionCapabilities.SET_DND, ActionCapabilities.SET_RINGER)
        val BASE_LAUNCH_CAPABILITIES =
            setOf(ActionCapabilities.LAUNCH_APP, ActionCapabilities.OPEN_URL)
        /** Capability sempre disponibili delle azioni sveglia/timer (permesso normal SET_ALARM). */
        val BASE_ALARM_CAPABILITIES =
            setOf(ActionCapabilities.SET_ALARM, ActionCapabilities.SET_TIMER)
        /** Capability che richiedono lo shell Shizuku: toggle, shell e lettori privilegiati. */
        val PRIVILEGED_CAPABILITIES: Set<String> = buildSet {
            add(ActionCapabilities.SET_WIFI)
            add(ActionCapabilities.SET_BLUETOOTH)
            add(ActionCapabilities.RUN_SHELL)
            // Gate famiglia della scrittura parametrica: forAction(WriteSetting) richiede questa,
            // pubblicata solo con Shizuku (e transiente se Shizuku è fermo ma autorizzato).
            add(ActionCapabilities.WRITE_SETTING)
            add(CapabilityIds.STATE_FOREGROUND_APP)
            add(CapabilityIds.STATE_READER_BUILTIN)
            add(CapabilityIds.STATE_READER_SETTING)
            add(CapabilityIds.STATE_READER_SYSTEM_PROPERTY)
            add(CapabilityIds.STATE_READER_SYSFS)
            add(CapabilityIds.STATE_READER_DUMPSYS_FIELD)
            StateKeys.ALL.keys.forEach { add(CapabilityIds.state(it)) }
        }
        // Unione legacy: usata per il set transiente (Shizuku fermo ma autorizzato).
        val SHIZUKU_CAPABILITIES: Set<String> =
            PRIVILEGED_CAPABILITIES + BASE_DND_CAPABILITIES + BASE_LAUNCH_CAPABILITIES
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
) {
    suspend fun reconcile(): CapabilityReconcileReport {
        val snapshot = snapshots.current()
        val needsReview = mutableListOf<AutomationId>()
        val temporarilyBlocked = mutableListOf<AutomationId>()
        store.armed().forEach { automation ->
            val missing = automation.requiredCapabilities - snapshot.availableCapabilities
            val structuralMissing = missing - snapshot.transientlyUnavailableCapabilities
            val validationFailed = validationFailed(automation, snapshot)
            val requirementsChanged = automation.requiredCapabilities != CapabilityRequirements.derive(
                automation.trigger,
                automation.actions,
                automation.conditions,
            )
            if (structuralMissing.isNotEmpty() || validationFailed || requirementsChanged) {
                val fingerprint = automation.approvalFingerprint ?: return@forEach
                if (store.markNeedsReviewIfApproved(automation.id, fingerprint)) {
                    needsReview += automation.id
                }
            } else if (missing.isNotEmpty()) {
                temporarilyBlocked += automation.id
            }
        }
        return CapabilityReconcileReport(needsReview, temporarilyBlocked)
    }

    private fun validationFailed(
        automation: Automation,
        snapshot: FirePolicySnapshot,
    ): Boolean = DraftValidator(snapshot.knownTools).validate(
        AutomationDraft(
            name = automation.name,
            trigger = automation.trigger,
            actions = automation.actions,
            conditions = automation.conditions,
            cooldownMs = automation.cooldownMs,
        ),
        snapshot.whitelistedConversationIds,
    ).any { it.severity == Severity.ERROR }
}
