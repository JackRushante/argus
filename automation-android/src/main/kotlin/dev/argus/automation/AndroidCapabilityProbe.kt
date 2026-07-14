package dev.argus.automation

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.StateKeys
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
        foregroundLocationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        backgroundLocationGranted = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        exactAlarmsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        batteryOptimizationExempt = appContext.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(appContext.packageName),
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
)

class AndroidCapabilityProbe internal constructor(
    private val source: AndroidCapabilityStateSource,
    private val whitelist: ContactWhitelistStore,
    private val readiness: GenerativeRuntimeReadiness,
) : CapabilityProbe, FirePolicySnapshotProvider {
    constructor(
        context: Context,
        shizuku: ShizukuGateway,
        whitelist: ContactWhitelistStore,
        readiness: GenerativeRuntimeReadiness,
    ) : this(SystemAndroidCapabilityStateSource(context, shizuku), whitelist, readiness)

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
        )
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
        val shizukuAvailable = state.shizukuStatus == ShizukuGatewayStatus.AUTHORIZED
        val shizukuTransient = state.shizukuStatus == ShizukuGatewayStatus.INSTALLED_NOT_RUNNING &&
            state.shizukuPermissionGranted
        val listenerGranted = state.notificationListenerGranted
        // Requisiti runtime della lane generativa oltre al canale notification/reply.
        val generativeReady = generative.bridgeConfigured && generative.privacyAccepted &&
            state.batteryOptimizationExempt

        val available = buildSet {
            add(CapabilityIds.TRIGGER_TIME)
            if (state.notificationsGranted) add(ActionCapabilities.SHOW_NOTIFICATION)
            // CapabilityRequirements persiste anche i raw tool approvati: il set del fire-time
            // deve contenere gli stessi nomi wire, senza alias con le capability typed.
            if (shizukuAvailable) {
                addAll(SHIZUKU_CAPABILITIES)
                addAll(SHIZUKU_TOOLS)
            }
            if (listenerGranted) {
                add(CapabilityIds.TRIGGER_NOTIFICATION)
                add(GenerativeContract.TOOL_WHATSAPP_REPLY)
            }
            if (generativeReady) add(CapabilityIds.ACTION_INVOKE_LLM)
            // CapabilityIds.ACTION_WHATSAPP_REPLY resta assente finché l'executor statico
            // non esiste: nessuna capability advertised senza implementation path.
        }
        val transient = if (shizukuTransient) SHIZUKU_CAPABILITIES + SHIZUKU_TOOLS else emptySet()

        val availableTools = buildList {
            if (shizukuAvailable) {
                addAll(SHIZUKU_ACTION_TYPES)
                addAll(SHIZUKU_TOOLS)
            }
            if (state.notificationsGranted) {
                add(ActionTypeIds.SHOW_NOTIFICATION)
                add(TOOL_NOTIFY_SHOW)
            }
            if (listenerGranted) add(GenerativeContract.TOOL_WHATSAPP_REPLY)
        }.sorted()
        val unavailableTools = linkedMapOf<String, String>()
        if (!shizukuAvailable) {
            val reason = shizukuReason(state.shizukuStatus)
            SHIZUKU_ACTION_TYPES.forEach { unavailableTools[it] = reason }
            SHIZUKU_TOOLS.forEach { unavailableTools[it] = reason }
        }
        if (!state.notificationsGranted) {
            unavailableTools[ActionTypeIds.SHOW_NOTIFICATION] = "permesso notifiche mancante"
            unavailableTools[TOOL_NOTIFY_SHOW] = "permesso notifiche mancante"
        }
        if (!listenerGranted) {
            unavailableTools[GenerativeContract.TOOL_WHATSAPP_REPLY] = REASON_NOTIFICATION_LISTENER
        }
        PHASE_UNAVAILABLE_TOOLS.forEach { (tool, reason) -> unavailableTools[tool] = reason }

        return ResolvedCapabilities(
            state = state,
            contacts = contacts,
            available = available,
            transientlyUnavailable = transient,
            availableTools = availableTools,
            unavailableTools = unavailableTools.toSortedMap(),
        )
    }

    private fun grantedPermissionNames(state: AndroidCapabilityState): List<String> = buildList {
        if (state.notificationsGranted) add("notifications")
        if (state.notificationListenerGranted) add("notification_listener")
        if (state.foregroundLocationGranted) add("location_foreground")
        if (state.backgroundLocationGranted) add("location_background")
        if (state.exactAlarmsGranted) add("exact_alarms")
        if (state.batteryOptimizationExempt) add("battery_optimization_exempt")
    }

    private fun shizukuReason(status: ShizukuGatewayStatus): String = when (status) {
        ShizukuGatewayStatus.NOT_INSTALLED -> "Shizuku non installato"
        ShizukuGatewayStatus.INSTALLED_NOT_RUNNING -> "Shizuku non in esecuzione"
        ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED -> "autorizzazione Shizuku mancante"
        ShizukuGatewayStatus.UNSUPPORTED -> "versione Shizuku non supportata"
        ShizukuGatewayStatus.AUTHORIZED -> "Shizuku non disponibile"
    }

    internal companion object {
        const val REASON_NOTIFICATION_LISTENER = "accesso alle notifiche non concesso"
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
        val SHIZUKU_ACTION_TYPES = setOf(
            ActionTypeIds.SET_WIFI,
            ActionTypeIds.SET_BLUETOOTH,
            ActionTypeIds.SET_DND,
            ActionTypeIds.SET_RINGER,
            ActionTypeIds.LAUNCH_APP,
            ActionTypeIds.OPEN_URL,
        )
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
        val SHIZUKU_CAPABILITIES: Set<String> = buildSet {
            add(ActionCapabilities.SET_WIFI)
            add(ActionCapabilities.SET_BLUETOOTH)
            add(ActionCapabilities.SET_DND)
            add(ActionCapabilities.SET_RINGER)
            add(ActionCapabilities.LAUNCH_APP)
            add(ActionCapabilities.OPEN_URL)
            add(CapabilityIds.STATE_FOREGROUND_APP)
            StateKeys.ALL.keys.forEach { add(CapabilityIds.state(it)) }
        }
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
