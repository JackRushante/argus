package dev.argus.automation.di

import android.content.Context
import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.AndroidAppPreferencesStore
import dev.argus.automation.AndroidAutomationNotifier
import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.automation.AndroidGenerativeLane
import dev.argus.automation.AndroidArmedAutomationRegistrar
import dev.argus.automation.AndroidGenerativeRuntimeReadiness
import dev.argus.automation.AndroidStateQueryProbe
import dev.argus.automation.GenerativeRuntimeReadiness
import dev.argus.automation.AndroidTimeAlarmBackend
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.ArmedAutomationRegistrar
import dev.argus.automation.DispatchFrame
import dev.argus.automation.ImmediateReArm
import dev.argus.automation.power.AndroidPowerLock
import dev.argus.automation.power.AndroidWakeAlarmBackend
import dev.argus.automation.power.ExecutionWakeLock
import dev.argus.automation.power.WaitWakeSignals
import dev.argus.automation.power.WakeAlarmBackend
import dev.argus.automation.power.WakeAwarePause
import dev.argus.automation.RecoveryLauncher
import dev.argus.automation.ArgusRuntimeController
import dev.argus.automation.AutomationNotifier
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.automation.CoordinatorTimeAlarmRuntime
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.DeferredReplyCipher
import dev.argus.automation.DeferredReplyManager
import dev.argus.automation.DeferredReplySink
import dev.argus.automation.PersistentDeferredReplySink
import dev.argus.automation.PrivacyRevocationCoordinator
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.AndroidClipboardCopier
import dev.argus.automation.AppInstallInfo
import dev.argus.automation.ClipboardCopier
import dev.argus.automation.EngineNotificationEventDispatcher
import dev.argus.automation.EnginePhoneEventDispatcher
import dev.argus.automation.EngineConnectivityEventDispatcher
import dev.argus.automation.EngineGeofenceEventDispatcher
import dev.argus.automation.EngineImmediateEventDispatcher
import dev.argus.automation.EngineTimeEventDispatcher
import dev.argus.automation.ImmediateEventDispatcher
import dev.argus.automation.FrameworkCurrentLocationProvider
import dev.argus.automation.GenerativeLane
import dev.argus.automation.LazyDeviceStateProvider
import dev.argus.automation.NotificationEventDispatcher
import dev.argus.automation.OneShotConsumptionRegistry
import dev.argus.automation.ProcessOneShotConsumptionRegistry
import dev.argus.automation.EngineSensorEventDispatcher
import dev.argus.automation.LogRetentionStore
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.RoomTimeAlarmStateStore
import dev.argus.automation.ShizukuActionExecutor
import dev.argus.automation.apps.AndroidInstalledAppResolver
import dev.argus.automation.apps.InstalledAppResolver
import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.automation.base.AndroidBaseActionSurface
import dev.argus.automation.base.AndroidDeferredOpenNotifier
import dev.argus.automation.base.AndroidMediaKeySurface
import dev.argus.automation.base.AndroidSpeechEngine
import dev.argus.automation.base.AndroidSystemActionExecutor
import dev.argus.automation.base.AndroidSystemSettingsSurface
import dev.argus.automation.base.DeferredOpenNotifier
import dev.argus.automation.base.MediaKeySurface
import dev.argus.automation.base.SpeechEngine
import dev.argus.automation.base.SystemSettingsSurface
import dev.argus.automation.http.HttpRequestExecutor
import dev.argus.automation.privileged.AndroidPrivilegedActionEnvironment
import dev.argus.automation.privileged.MediaStoreScreenshotStore
import dev.argus.automation.privileged.PrivilegedActionEnvironment
import dev.argus.automation.privileged.PrivilegedActionRunner
import dev.argus.automation.privileged.ScreenshotStore
import dev.argus.automation.base.BaseActionSurface
import dev.argus.automation.foreground.AndroidForegroundDeniedNotice
import dev.argus.automation.foreground.SharedForegroundSentinel
import dev.argus.automation.connectivity.ForegroundDeniedNotice
import dev.argus.automation.foreground.ReceiverWorkLauncher
import dev.argus.automation.foreground.SentinelDemand
import dev.argus.automation.manual.AndroidManualShortcutPinner
import dev.argus.automation.manual.AutomationPauseStore
import dev.argus.automation.manual.EngineManualEventDispatcher
import dev.argus.automation.manual.ManualEntryService
import dev.argus.automation.manual.ManualEventDispatcher
import dev.argus.automation.manual.ManualRuleCatalog
import dev.argus.automation.manual.ManualRuleRunner
import dev.argus.automation.manual.ManualShortcutPinner
import dev.argus.automation.manual.ManualTileSelection
import dev.argus.automation.manual.ManualTokenStore
import dev.argus.automation.manual.ManualWorkLauncher
import dev.argus.automation.manual.PrefsAutomationPauseStore
import dev.argus.automation.manual.PrefsManualTileSelection
import dev.argus.automation.manual.PrefsManualTokenStore
import dev.argus.automation.manual.QuickTileController
import dev.argus.automation.manual.ReceiverManualWorkLauncher
import dev.argus.automation.phone.SimAuditingPhoneEventDispatcher
import dev.argus.automation.triggers.DeviceEventDemand
import dev.argus.automation.triggers.DeviceEventDispatcher
import dev.argus.automation.triggers.DeviceEventIngress
import dev.argus.automation.triggers.EngineDeviceEventDispatcher
import dev.argus.automation.triggers.PrefsBatteryLevelStore
import dev.argus.engine.device.DeviceEventParser
import dev.argus.automation.sensor.AndroidSignificantMotionBackend
import dev.argus.automation.sensor.EligibleSensorRule
import dev.argus.automation.sensor.PrefsSensorDetectionStore
import dev.argus.automation.sensor.SensorDetectionStore
import dev.argus.automation.sensor.SensorEventDispatcher
import dev.argus.automation.sensor.SensorEventIngress
import dev.argus.automation.sensor.SensorTriggerBackend
import dev.argus.automation.sensor.SensorTriggerCoordinator
import dev.argus.automation.sensor.SensorTriggerRuntime
import dev.argus.engine.model.Trigger
import dev.argus.automation.ShizukuStaticShellRunner
import dev.argus.automation.StateQueryProbe
import dev.argus.automation.TimeAlarmBackend
import dev.argus.automation.TimeAlarmCoordinator
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.TimeAlarmStateStore
import dev.argus.automation.TimeEventDispatcher
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.automation.notification.AndroidNotificationReplyGateway
import dev.argus.automation.notification.AndroidNotificationSnapshotFactory
import dev.argus.automation.notification.NotificationIngress
import dev.argus.automation.notification.NotificationTriggerDemand
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyHandleFactory
import dev.argus.automation.budget.AndroidBudgetAlerts
import dev.argus.automation.budget.AndroidBudgetSettingsStore
import dev.argus.automation.budget.BudgetAlerts
import dev.argus.automation.budget.BudgetPolicy
import dev.argus.automation.budget.BudgetSettingsStore
import dev.argus.automation.budget.DraftErrorSource
import dev.argus.automation.budget.MeteredBrain
import dev.argus.brain.AndroidProviderConfigStore
import dev.argus.brain.BridgeConfigurationStore
import dev.argus.brain.DefaultTransportFactory
import dev.argus.brain.ProviderConfigStore
import dev.argus.brain.TransportFactory
import dev.argus.data.ArgusDatabase
import dev.argus.data.dao.UsageDao
import dev.argus.data.DeferredReplyStore
import dev.argus.data.RoomDeferredReplyStore
import dev.argus.data.RoomAuditSink
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.backup.ContentResolverDocumentIo
import dev.argus.data.backup.RulesBackup
import dev.argus.data.backup.RulesBackupService
import dev.argus.data.RoomExecutionJournal
import dev.argus.data.RoomJournalMaintenance
import dev.argus.data.RoomObservedConversationStore
import dev.argus.automation.privileged.PrefsRootPreference
import dev.argus.automation.privileged.PrivilegedAccessCenter
import dev.argus.device.AndroidDirectSettings
import dev.argus.device.AndroidPublicStateSource
import dev.argus.device.DirectSettings
import dev.argus.device.ForegroundAppSource
import dev.argus.device.PermanentGrantsController
import dev.argus.device.UsageStatsForegroundSource
import dev.argus.device.DeviceController
import dev.argus.device.DeviceTools
import dev.argus.device.PrivilegedActionController
import dev.argus.device.PrivilegedActionTools
import dev.argus.device.ParametricStateReader
import dev.argus.device.PublicStateReader
import dev.argus.device.StateReader
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.automation.phone.PhoneEventIngress
import dev.argus.automation.phone.PrefsCallStateStore
import dev.argus.automation.connectivity.ConnectivityEventDispatcher
import dev.argus.automation.connectivity.ConnectivityEventIngress
import dev.argus.automation.connectivity.AndroidConnectivitySentinelBackend
import dev.argus.automation.connectivity.ConnectivitySentinelBackend
import dev.argus.automation.connectivity.ConnectivitySentinelCoordinator
import dev.argus.automation.connectivity.ConnectivitySentinelStatus
import dev.argus.automation.connectivity.ConnectivityTriggerRuntime
import dev.argus.automation.connectivity.PrefsConnectivityStateStore
import dev.argus.automation.geofence.AndroidGeofenceBackend
import dev.argus.automation.geofence.GeofenceBackend
import dev.argus.automation.geofence.GeofenceCoordinator
import dev.argus.automation.geofence.GeofenceEventDispatcher
import dev.argus.automation.geofence.GeofenceEventIngress
import dev.argus.automation.geofence.GeofenceStateStore
import dev.argus.automation.geofence.LocationBackedTransitionVerifier
import dev.argus.automation.geofence.GeofenceTriggerRuntime
import dev.argus.automation.geofence.PrefsGeofenceStateStore
import dev.argus.engine.connectivity.ConnectivityEventParser
import dev.argus.engine.notification.NotificationEventParser
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.notification.ObservedConversationStore
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.ResolvedActionExecutor
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.RevalidatingFirePolicy
import dev.argus.engine.runtime.SubmittedActionJournal
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.ApprovalWhitelistProvider
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftValidator
import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.RootAccess
import dev.argus.shizuku.RootPreference
import dev.argus.shizuku.RootPrivilegedShell
import dev.argus.shizuku.RootState
import dev.argus.shizuku.RoutedPrivilegedShell
import dev.argus.shizuku.ShizukuAccess
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuIdentityProbe
import dev.argus.shizuku.ShizukuPrivilegedShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object ArgusModule {
    @Provides
    @Singleton
    fun renderLanguageSource(): RenderLanguageSource = RenderLanguageSource()

    @Provides
    @Singleton
    fun appInstallInfo(@ApplicationContext context: Context): AppInstallInfo = AppInstallInfo.read(context)

    @Provides
    @Singleton
    fun installedAppResolver(
        @ApplicationContext context: Context,
    ): InstalledAppResolver = AndroidInstalledAppResolver(context)

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ArgusDatabase = ArgusDatabase.build(context)

    @Provides
    @Singleton
    fun automationStore(database: ArgusDatabase): RoomAutomationStore =
        RoomAutomationStore(database.automationDao())

    @Provides
    fun automationStoreBoundary(store: RoomAutomationStore): AutomationStore = store

    @Provides
    @Singleton
    fun draftRepository(database: ArgusDatabase): RoomDraftRepository = RoomDraftRepository(database)

    @Provides
    fun draftRepositoryBoundary(repository: RoomDraftRepository): DraftRepository = repository

    @Provides
    @Singleton
    fun rulesBackup(
        @ApplicationContext context: Context,
        store: AutomationStore,
        repository: RoomDraftRepository,
        installInfo: AppInstallInfo,
    ): RulesBackup = RulesBackupService(
        automations = store,
        drafts = repository,
        documents = ContentResolverDocumentIo(context),
        appVersion = { installInfo.versionName },
    )

    @Provides
    @Singleton
    fun auditSink(database: ArgusDatabase): RoomAuditSink = RoomAuditSink(database.auditDao())

    @Provides
    fun auditSinkBoundary(sink: RoomAuditSink): AuditSink = sink

    @Provides
    @Singleton
    fun executionJournal(database: ArgusDatabase): RoomExecutionJournal =
        RoomExecutionJournal(database.executionJournalDao())

    @Provides
    fun executionJournalBoundary(journal: RoomExecutionJournal): ExecutionJournal = journal

    @Provides
    fun submittedActionJournalBoundary(journal: RoomExecutionJournal): SubmittedActionJournal = journal

    @Provides
    @Singleton
    fun journalMaintenance(
        database: ArgusDatabase,
        logRetention: LogRetentionStore,
    ): RoomJournalMaintenance =
        RoomJournalMaintenance(database, logRetentionDays = logRetention::currentDays)

    @Provides
    @Singleton
    fun logRetentionStore(@ApplicationContext context: Context): LogRetentionStore =
        LogRetentionStore(context)

    @Provides
    @Singleton
    fun contactWhitelist(database: ArgusDatabase): RoomContactWhitelistStore =
        RoomContactWhitelistStore(database.contactWhitelistDao())

    @Provides
    fun contactWhitelistBoundary(store: RoomContactWhitelistStore): ContactWhitelistStore = store

    @Provides
    fun approvalWhitelistBoundary(store: RoomContactWhitelistStore): ApprovalWhitelistProvider = store

    @Provides
    @Singleton
    fun observedConversationStore(database: ArgusDatabase): RoomObservedConversationStore =
        RoomObservedConversationStore(database.observedConversationDao())

    @Provides
    fun observedConversationStoreBoundary(
        store: RoomObservedConversationStore,
    ): ObservedConversationStore = store

    @Provides
    @Singleton
    fun notificationEventParser(): NotificationEventParser = NotificationEventParser()

    @Provides
    @Singleton
    fun notificationSnapshotFactory(): AndroidNotificationSnapshotFactory =
        AndroidNotificationSnapshotFactory()

    @Provides
    @Singleton
    fun notificationReplyHandleFactory(): NotificationReplyHandleFactory =
        NotificationReplyHandleFactory()

    @Provides
    @Singleton
    fun notificationReplyRegistry(): ActiveNotificationReplyRegistry =
        ActiveNotificationReplyRegistry()

    @Provides
    @Singleton
    fun notificationReplyGateway(
        @ApplicationContext context: Context,
        registry: ActiveNotificationReplyRegistry,
    ): AndroidNotificationReplyGateway = AndroidNotificationReplyGateway(context, registry)

    @Provides
    fun notificationReplyGatewayBoundary(
        gateway: AndroidNotificationReplyGateway,
    ): NotificationReplyGateway = gateway

    @Provides
    @Singleton
    fun shizukuGateway(@ApplicationContext context: Context): ShizukuGateway = ShizukuGateway(context)

    @Provides
    fun shizukuAccessBoundary(gateway: ShizukuGateway): ShizukuAccess = gateway

    @Provides
    @Singleton
    fun privilegedShell(
        @ApplicationContext context: Context,
        gateway: ShizukuGateway,
        @ApplicationScope scope: CoroutineScope,
    ): ShizukuPrivilegedShell = ShizukuPrivilegedShell(context, gateway, scope)

    @Provides
    fun shizukuIdentityProbe(shell: ShizukuPrivilegedShell): ShizukuIdentityProbe = shell

    /** 0.5.0: interruttore «Usa root (su)», spento di default. */
    @Provides
    @Singleton
    fun rootPreference(@ApplicationContext context: Context): RootPreference = PrefsRootPreference(context)

    /**
     * 0.5.0: backend root. Costruirlo NON chiama `su` se l'interruttore è spento; acceso, verifica
     * `su -c id` una volta in background.
     */
    @Provides
    @Singleton
    fun rootShell(
        preference: RootPreference,
        @ApplicationScope scope: CoroutineScope,
    ): RootPrivilegedShell = RootPrivilegedShell(preference, scope)

    @Provides
    fun rootAccess(shell: RootPrivilegedShell): RootAccess = shell

    /** Shizuku se autorizzato (Sui compreso), altrimenti `su` se attivo e verificato. */
    @Provides
    @Singleton
    fun privilegedShellBoundary(
        shizukuShell: ShizukuPrivilegedShell,
        rootShell: RootPrivilegedShell,
        gateway: ShizukuGateway,
    ): PrivilegedShell = RoutedPrivilegedShell(
        shizuku = shizukuShell,
        root = rootShell,
        shizukuAvailable = { gateway.status() == dev.argus.shizuku.ShizukuGatewayStatus.AUTHORIZED },
        rootActive = rootShell::isActive,
    )

    // --- 0.5.0 (privilegi): permessi permanenti, scritture dirette, app in primo piano senza Shizuku ---

    @Provides
    @Singleton
    fun permanentGrantsController(
        @ApplicationContext context: Context,
        shell: PrivilegedShell,
    ): PermanentGrantsController = PermanentGrantsController(shell, context.packageName)

    @Provides
    @Singleton
    fun directSettings(@ApplicationContext context: Context): DirectSettings = AndroidDirectSettings(context)

    @Provides
    @Singleton
    fun usageForegroundSource(@ApplicationContext context: Context): ForegroundAppSource =
        UsageStatsForegroundSource(context)

    @Provides
    @Singleton
    fun privilegedAccessCenter(
        @ApplicationContext context: Context,
        shizuku: ShizukuAccess,
        root: RootAccess,
        grants: PermanentGrantsController,
    ): PrivilegedAccessCenter = PrivilegedAccessCenter(
        shizuku,
        root,
        grants,
        PrivilegedAccessCenter.statusReader(context),
    )

    @Provides
    @Singleton
    fun deviceTools(@ApplicationContext context: Context, shell: PrivilegedShell): DeviceTools =
        DeviceTools(context, shell)

    @Provides
    fun deviceController(tools: DeviceTools): DeviceController = tools

    @Provides
    @Singleton
    fun stateReader(shell: PrivilegedShell): StateReader = StateReader(shell)

    @Provides
    @Singleton
    fun parametricStateReader(shell: PrivilegedShell): ParametricStateReader =
        ParametricStateReader(shell)

    /** Lettura delle chiavi di stato con le API pubbliche di Android, senza Shizuku (0.4.0). */
    @Provides
    @Singleton
    fun publicStateReader(@ApplicationContext context: Context): PublicStateReader =
        PublicStateReader(AndroidPublicStateSource(context))

    @Provides
    @Singleton
    fun stateQueryProbe(
        gateway: ShizukuGateway,
        reader: ParametricStateReader,
        root: RootAccess,
    ): StateQueryProbe = AndroidStateQueryProbe(gateway, reader, root::isActive)

    @Provides
    @Singleton
    fun lazyDeviceState(
        reader: StateReader,
        parametricReader: ParametricStateReader,
        publicReader: PublicStateReader,
        gateway: ShizukuGateway,
        location: CurrentLocationProvider,
        root: RootAccess,
        usageForeground: ForegroundAppSource,
    ): LazyDeviceStateProvider = LazyDeviceStateProvider(
        reader,
        gateway,
        location,
        parametricReader,
        publicReader,
        // 0.5.0: shell privilegiata = Shizuku autorizzato o root verificato.
        privilegedReady = {
            gateway.status() == dev.argus.shizuku.ShizukuGatewayStatus.AUTHORIZED || root.isActive()
        },
        usageForeground = usageForeground,
    )

    @Provides
    fun deviceStateSnapshotProvider(
        provider: LazyDeviceStateProvider,
    ): DeviceStateSnapshotProvider = provider

    @Provides
    @Singleton
    fun generativeRuntimeReadiness(
        configuration: ProviderConfigStore,
        preferences: AppPreferencesStore,
    ): GenerativeRuntimeReadiness = AndroidGenerativeRuntimeReadiness(configuration, preferences)

    @Provides
    @Singleton
    fun capabilityProbe(
        @ApplicationContext context: Context,
        gateway: ShizukuGateway,
        whitelist: ContactWhitelistStore,
        readiness: GenerativeRuntimeReadiness,
        root: RootAccess,
    ): AndroidCapabilityProbe = AndroidCapabilityProbe(
        context,
        gateway,
        whitelist,
        readiness,
        // Tier base attivo (P3-3): DND/Ringer via NotificationManager, LaunchApp/OpenUrl via Intent,
        // senza Shizuku. In futuro pilotato dal flavor (personal-full/play-core).
        baseTierActive = true,
        // 0.5.0: root `su` acceso e verificato (letto dalla cache, il probe non chiama mai `su`).
        rootActive = root::isActive,
    )

    @Provides
    fun capabilityProbeBoundary(probe: AndroidCapabilityProbe): CapabilityProbe = probe

    @Provides
    fun firePolicySnapshotProvider(probe: AndroidCapabilityProbe): FirePolicySnapshotProvider = probe

    @Provides
    @Singleton
    fun revalidatingFirePolicy(
        snapshots: FirePolicySnapshotProvider,
    ): RevalidatingFirePolicy = RevalidatingFirePolicy(snapshots)

    @Provides
    fun firePolicyBoundary(policy: RevalidatingFirePolicy): FirePolicy = policy

    @Provides
    @Singleton
    fun capabilityReconciler(
        store: AutomationStore,
        snapshots: FirePolicySnapshotProvider,
        audit: AuditSink,
    ) = dev.argus.automation.CapabilityReconciler(store, snapshots, audit)

    @Provides
    @Singleton
    fun providerConfigStore(@ApplicationContext context: Context): ProviderConfigStore =
        AndroidProviderConfigStore(context)

    /** Ponte legacy: Readiness/probe/entry point restano invariati sul contratto storico. */
    @Provides
    fun bridgeConfiguration(store: ProviderConfigStore): BridgeConfigurationStore = store

    @Provides
    @Singleton
    fun transportFactory(store: ProviderConfigStore): TransportFactory = DefaultTransportFactory(store)

    @Provides
    @Singleton
    fun configuredBrain(
        configuration: ProviderConfigStore,
        factory: TransportFactory,
        preferences: AppPreferencesStore,
    ): ConfiguredBridgeBrain = ConfiguredBridgeBrain(
        configuration = configuration,
        privacyAccepted = { preferences.observe().value.privacyAccepted },
        factory = factory,
    )

    @Provides
    fun usageDao(database: ArgusDatabase): UsageDao = database.usageDao()

    @Provides
    @Singleton
    fun budgetSettings(@ApplicationContext context: Context): BudgetSettingsStore =
        AndroidBudgetSettingsStore(context)

    @Provides
    @Singleton
    fun budgetPolicy(usage: UsageDao, settings: BudgetSettingsStore): BudgetPolicy =
        BudgetPolicy(usage, settings)

    @Provides
    @Singleton
    fun budgetAlerts(@ApplicationContext context: Context): BudgetAlerts = AndroidBudgetAlerts(context)

    // @Singleton perché il dedupe soft-warning è in-memory nel decorator.
    @Provides
    @Singleton
    fun brain(
        brain: ConfiguredBridgeBrain,
        policy: BudgetPolicy,
        usage: UsageDao,
        store: ProviderConfigStore,
        audit: AuditSink,
        alerts: BudgetAlerts,
        budgetSettings: BudgetSettingsStore,
        validator: DraftValidator,
        approvalWhitelist: ApprovalWhitelistProvider,
    ): Brain = MeteredBrain(
        brain, policy, usage, store::selectedProviderConfig, audit, alerts, settings = budgetSettings,
        // 0.5.0: giro di riparazione sugli errori ERROR del validatore, lo stesso (e la stessa whitelist)
        // che decide `canArm` in approvazione.
        draftErrors = DraftErrorSource { draft ->
            validator.validate(draft, approvalWhitelist.currentConversationIds())
        },
    )

    @Provides
    @Singleton
    fun appPreferences(@ApplicationContext context: Context): AppPreferencesStore =
        AndroidAppPreferencesStore(context)

    @Provides
    @Singleton
    fun notifier(@ApplicationContext context: Context): AndroidAutomationNotifier =
        AndroidAutomationNotifier(context)

    @Provides
    fun notifierBoundary(notifier: AndroidAutomationNotifier): AutomationNotifier = notifier

    @Provides
    @Singleton
    fun deferredReplyStore(database: ArgusDatabase): DeferredReplyStore =
        RoomDeferredReplyStore(database.deferredReplyDao())

    @Provides
    @Singleton
    fun deferredReplyCipher(): DeferredReplyCipher = DeferredReplyCipher.withKeystoreKey()

    @Provides
    @Singleton
    fun deferredReplySink(
        store: DeferredReplyStore,
        cipher: DeferredReplyCipher,
    ): DeferredReplySink = PersistentDeferredReplySink(store, cipher)

    @Provides
    @Singleton
    fun deferredReplyManager(
        store: DeferredReplyStore,
        cipher: DeferredReplyCipher,
    ): DeferredReplyManager = DeferredReplyManager(store, cipher)

    @Provides
    @Singleton
    fun privacyRevocationCoordinator(
        preferences: AppPreferencesStore,
        replyRegistry: ActiveNotificationReplyRegistry,
        conversations: ObservedConversationStore,
        deferredReplies: DeferredReplyStore,
    ): PrivacyRevocationCoordinator = PrivacyRevocationCoordinator(
        preferences,
        replyRegistry,
        conversations,
        deferredReplies,
    )

    @Provides
    @Singleton
    fun oneShotConsumptionRegistry(): OneShotConsumptionRegistry =
        ProcessOneShotConsumptionRegistry()

    @Provides
    @Singleton
    fun androidGenerativeLane(
        @ApplicationScope scope: CoroutineScope,
        journal: SubmittedActionJournal,
        automations: AutomationStore,
        policy: FirePolicy,
        brain: Brain,
        replies: NotificationReplyGateway,
        deferredReplies: DeferredReplySink,
        notifier: AutomationNotifier,
        oneShotConsumptions: OneShotConsumptionRegistry,
        replyRegistry: ActiveNotificationReplyRegistry,
        wakeLock: ExecutionWakeLock,
        sentinel: SharedForegroundSentinel,
    ): AndroidGenerativeLane = AndroidGenerativeLane(
        scope,
        journal,
        automations,
        policy,
        brain,
        replies,
        deferredReplies,
        notifier,
        oneShotConsumptions,
        replyBursts = replyRegistry,
        wakeLock = wakeLock,
        sentinel = sentinel,
    )

    @Provides
    fun generativeLane(lane: AndroidGenerativeLane): GenerativeLane = lane

    @Provides
    @Singleton
    fun clipboardCopier(@ApplicationContext context: Context): ClipboardCopier =
        AndroidClipboardCopier(context)

    @Provides
    @Singleton
    fun staticShellRunner(shell: PrivilegedShell): ShizukuStaticShellRunner =
        ShizukuStaticShellRunner(shell)

    @Provides
    @Singleton
    fun baseActionSurface(@ApplicationContext context: Context): BaseActionSurface =
        AndroidBaseActionSurface(context)

    /** 0.4.1 (B4): notifica «Tocca per aprire …» quando Android blocca un avvio dal background. */
    @Provides
    @Singleton
    fun deferredOpenNotifier(@ApplicationContext context: Context): DeferredOpenNotifier =
        AndroidDeferredOpenNotifier(context)

    @Provides
    @Singleton
    fun baseActionExecutor(
        surface: BaseActionSurface,
        deferredOpen: DeferredOpenNotifier,
    ): AndroidBaseActionExecutor = AndroidBaseActionExecutor(surface, deferredOpen)

    /** 0.4.1: lettura ad alta voce nella lingua dell'app (inglese o italiano). */
    @Provides
    @Singleton
    fun speechEngine(
        @ApplicationContext context: Context,
        language: RenderLanguageSource,
    ): SpeechEngine = AndroidSpeechEngine(context) {
        if (language.current == dev.argus.ui.presentation.RenderLanguage.IT) "it" else "en"
    }

    @Provides
    @Singleton
    fun mediaKeySurface(@ApplicationContext context: Context): MediaKeySurface =
        AndroidMediaKeySurface(context)

    @Provides
    @Singleton
    fun systemSettingsSurface(@ApplicationContext context: Context): SystemSettingsSurface =
        AndroidSystemSettingsSurface(context)

    @Provides
    @Singleton
    fun systemActionExecutor(
        speech: SpeechEngine,
        media: MediaKeySurface,
        settings: SystemSettingsSurface,
    ): AndroidSystemActionExecutor = AndroidSystemActionExecutor(speech, media, settings)

    @Provides
    @Singleton
    fun httpRequestExecutor(): HttpRequestExecutor = HttpRequestExecutor()

    // --- 0.5.1: azioni privilegiate tipizzate (controllo app, aereo, posizione, NFC, ...) ---

    @Provides
    @Singleton
    fun privilegedActionController(
        @ApplicationContext context: Context,
        shell: PrivilegedShell,
    ): PrivilegedActionController = PrivilegedActionTools(shell, context.applicationContext.cacheDir)

    @Provides
    @Singleton
    fun screenshotStore(@ApplicationContext context: Context): ScreenshotStore =
        MediaStoreScreenshotStore(context)

    @Provides
    @Singleton
    fun privilegedActionEnvironment(@ApplicationContext context: Context): PrivilegedActionEnvironment =
        AndroidPrivilegedActionEnvironment(context)

    @Provides
    @Singleton
    fun privilegedActionRunner(
        tools: PrivilegedActionController,
        screenshots: ScreenshotStore,
        environment: PrivilegedActionEnvironment,
    ): PrivilegedActionRunner = PrivilegedActionRunner(tools, screenshots, environment)

    @Provides
    @Singleton
    fun actionExecutor(
        tools: DeviceController,
        staticShell: ShizukuStaticShellRunner,
        notifier: AutomationNotifier,
        generativeLane: GenerativeLane,
        replies: NotificationReplyGateway,
        clipboard: ClipboardCopier,
        whitelist: ContactWhitelistStore,
        baseActions: AndroidBaseActionExecutor,
        gateway: ShizukuGateway,
        systemActions: AndroidSystemActionExecutor,
        httpRequests: HttpRequestExecutor,
        root: RootAccess,
        directSettings: DirectSettings,
        privilegedActions: PrivilegedActionRunner,
    ): ShizukuActionExecutor =
        ShizukuActionExecutor(
            tools,
            notifier,
            generativeLane,
            replies,
            clipboard,
            staticShell,
            // Riletta a ogni scatto, non memoizzata: togliere un contatto dalla whitelist deve
            // revocargli la shell subito, senza attendere un riavvio del processo.
            whitelistedIds = { whitelist.all().mapTo(mutableSetOf()) { it.id } },
            // Tier base (P3-3): DND/Ringer via API normali, non Shizuku.
            baseActions = baseActions,
            // Caveat BAL: le activity-launch (sveglia/timer/settings/app/URL) da automazione (background)
            // sono affidabili solo via `am start` privilegiato. Riletto a ogni scatto: se l'utente revoca
            // Shizuku si ricade sul percorso BASE senza riavvio.
            // 0.5.0: «Shizuku o root»: il root `su` verificato conta come un backend privilegiato pronto.
            shizukuReady = {
                gateway.status() == dev.argus.shizuku.ShizukuGatewayStatus.AUTHORIZED || root.isActive()
            },
            // 0.4.1: parlato, tasti multimediali, luminosità/rotazione e chiamate HTTP, senza Shizuku.
            systemActions = systemActions,
            httpRequests = httpRequests,
            // 0.5.0: write_setting diretto coi permessi permanenti, solo se nessun backend è pronto.
            directSettings = directSettings,
            // 0.5.1: azioni privilegiate tipizzate; il runner ripete i controlli sul bersaglio.
            privilegedActions = privilegedActions,
        )

    @Provides
    fun actionExecutorBoundary(executor: ShizukuActionExecutor): ActionExecutor = executor

    @Provides
    fun resolvedActionExecutorBoundary(executor: ShizukuActionExecutor): ResolvedActionExecutor = executor

    /** Wakelock parziale «argus:execution» con contatore (B19). */
    @Provides
    @Singleton
    fun executionWakeLock(@ApplicationContext context: Context): ExecutionWakeLock =
        ExecutionWakeLock(AndroidPowerLock(context, AndroidPowerLock.EXECUTION_TAG))

    /** Registro delle attese lunghe + wakelock breve «argus:wait-wakeup» del receiver di ripresa. */
    @Provides
    @Singleton
    fun waitWakeSignals(@ApplicationContext context: Context): WaitWakeSignals =
        WaitWakeSignals(AndroidPowerLock(context, AndroidPowerLock.WAIT_WAKEUP_TAG))

    @Provides
    @Singleton
    fun wakeAlarmBackend(@ApplicationContext context: Context): WakeAlarmBackend =
        AndroidWakeAlarmBackend(context)

    /** Attese che sopravvivono al deep sleep, iniettate come `Engine(pause = …)`. */
    @Provides
    @Singleton
    fun wakeAwarePause(
        wakeLock: ExecutionWakeLock,
        alarms: WakeAlarmBackend,
        signals: WaitWakeSignals,
    ): WakeAwarePause = WakeAwarePause(wakeLock, alarms, signals, SystemClock::elapsedRealtime)

    /**
     * Cornice di ogni dispatch verso l'Engine: wakelock, conteggio dei dispatch in corso e pulizia
     * periodica a fine dispatch. Il controller si risolve in modo pigro (Provider) perché dipende
     * a sua volta dagli ingress, che dipendono dai dispatcher.
     */
    @Provides
    @Singleton
    fun dispatchFrame(
        wakeLock: ExecutionWakeLock,
        controller: Provider<ArgusRuntimeController>,
    ): DispatchFrame = DispatchFrame(wakeLock) { controller.get().maintenanceIfDue() }

    @Provides
    @Singleton
    fun engine(
        store: AutomationStore,
        executor: ActionExecutor,
        firePolicy: FirePolicy,
        audit: AuditSink,
        journal: ExecutionJournal,
        resolvedExecutor: ResolvedActionExecutor,
        wakeAwarePause: WakeAwarePause,
        automationPause: AutomationPauseStore,
    ): Engine = Engine(
        store = store,
        executor = executor,
        evaluator = ConditionEvaluator(Clock.systemDefaultZone()),
        matcher = TriggerMatcher(),
        firePolicy = firePolicy,
        audit = audit,
        journal = journal,
        resolvedExecutor = resolvedExecutor,
        pause = { millis -> wakeAwarePause.pause(millis) },
        // 0.4.1 (F21): «Pausa automazioni», letta dal motore a ogni scatto.
        firingPause = automationPause,
        now = System::currentTimeMillis,
    )

    @Provides
    @Singleton
    fun timeEventDispatcher(engine: Engine, state: LazyDeviceStateProvider, frame: DispatchFrame): EngineTimeEventDispatcher =
        EngineTimeEventDispatcher(engine, state, frame)

    @Provides
    fun timeEventDispatcherBoundary(dispatcher: EngineTimeEventDispatcher): TimeEventDispatcher =
        dispatcher

    @Provides
    @Singleton
    fun immediateEventDispatcher(
        engine: Engine,
        state: LazyDeviceStateProvider,
        frame: DispatchFrame,
    ): EngineImmediateEventDispatcher = EngineImmediateEventDispatcher(engine, state, frame)

    @Provides
    fun immediateEventDispatcherBoundary(
        dispatcher: EngineImmediateEventDispatcher,
    ): ImmediateEventDispatcher = dispatcher

    @Provides
    @Singleton
    fun notificationEventDispatcher(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
        frame: DispatchFrame,
    ): EngineNotificationEventDispatcher = EngineNotificationEventDispatcher(engine, state, frame)

    @Provides
    fun notificationEventDispatcherBoundary(
        dispatcher: EngineNotificationEventDispatcher,
    ): NotificationEventDispatcher = dispatcher

    @Provides
    @Singleton
    fun notificationIngress(
        snapshots: AndroidNotificationSnapshotFactory,
        parser: NotificationEventParser,
        handles: NotificationReplyHandleFactory,
        registry: ActiveNotificationReplyRegistry,
        conversations: ObservedConversationStore,
        dispatcher: NotificationEventDispatcher,
        preferences: AppPreferencesStore,
    ): NotificationIngress = NotificationIngress(
        snapshots,
        parser,
        handles,
        registry,
        conversations,
        dispatcher,
        privacyAccepted = { preferences.observe().value.privacyAccepted },
    )

    /** Quali package interessano alle regole armate con trigger notifica (solo lettura delle regole). */
    @Provides
    @Singleton
    fun notificationTriggerDemand(
        store: AutomationStore,
        @ApplicationScope scope: CoroutineScope,
    ): NotificationTriggerDemand = NotificationTriggerDemand(store.observeAll(), scope)

    @Provides
    @Singleton
    fun phoneEventIngress(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
        @ApplicationContext context: Context,
        frame: DispatchFrame,
        store: AutomationStore,
        audit: AuditSink,
    ): PhoneEventIngress = PhoneEventIngress(
        parser = PhoneEventParser(),
        callState = PrefsCallStateStore(context),
        // 0.4.1: se lo slot SIM non è determinabile, le regole col filtro `sim` non scattano e il
        // Log dice perché (`sim_unknown`).
        dispatcher = SimAuditingPhoneEventDispatcher(
            EnginePhoneEventDispatcher(engine, state, frame),
            store,
            audit,
        ),
    )

    @Provides
    @Singleton
    fun deviceEventDemand(): DeviceEventDemand = DeviceEventDemand()

    @Provides
    @Singleton
    fun deviceEventDispatcher(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
        frame: DispatchFrame,
    ): DeviceEventDispatcher = EngineDeviceEventDispatcher(engine, state, frame)

    @Provides
    @Singleton
    fun deviceEventIngress(
        @ApplicationContext context: Context,
        dispatcher: DeviceEventDispatcher,
    ): DeviceEventIngress = DeviceEventIngress(
        parser = DeviceEventParser(),
        battery = PrefsBatteryLevelStore(context),
        dispatcher = dispatcher,
    )

    @Provides
    @Singleton
    fun connectivityEventDispatcher(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
        frame: DispatchFrame,
    ): EngineConnectivityEventDispatcher = EngineConnectivityEventDispatcher(engine, state, frame)

    @Provides
    fun connectivityEventDispatcherBoundary(
        dispatcher: EngineConnectivityEventDispatcher,
    ): ConnectivityEventDispatcher = dispatcher

    @Provides
    @Singleton
    fun connectivityEventIngress(
        @ApplicationContext context: Context,
        dispatcher: ConnectivityEventDispatcher,
    ): ConnectivityEventIngress = ConnectivityEventIngress(
        parser = ConnectivityEventParser(),
        state = PrefsConnectivityStateStore(context),
        dispatcher = dispatcher,
    )

    @Provides
    @Singleton
    fun connectivitySentinelStatus(): ConnectivitySentinelStatus = ConnectivitySentinelStatus()

    @Provides
    @Singleton
    fun connectivitySentinelBackend(
        @ApplicationContext context: Context,
        status: ConnectivitySentinelStatus,
    ): AndroidConnectivitySentinelBackend = AndroidConnectivitySentinelBackend(context, status)

    @Provides
    fun connectivitySentinelBackendBoundary(
        backend: AndroidConnectivitySentinelBackend,
    ): ConnectivitySentinelBackend = backend

    /** Unico FGS condiviso (decision record P3 §6.2): possiede il backend e unisce i demand. */
    @Provides
    @Singleton
    fun sharedForegroundSentinel(
        backend: ConnectivitySentinelBackend,
    ): SharedForegroundSentinel = SharedForegroundSentinel(backend)

    @Provides
    @Singleton
    fun receiverWorkLauncher(
        @ApplicationScope scope: CoroutineScope,
        sentinel: SharedForegroundSentinel,
        audit: AuditSink,
    ): ReceiverWorkLauncher = ReceiverWorkLauncher(scope, sentinel, audit)

    @Provides
    @Singleton
    fun connectivitySentinelCoordinator(
        store: AutomationStore,
        sentinel: SharedForegroundSentinel,
        audit: AuditSink,
        notice: ForegroundDeniedNotice,
        deviceEvents: DeviceEventDemand,
    ): ConnectivitySentinelCoordinator =
        ConnectivitySentinelCoordinator(
            store,
            sentinel.demandBackend(SentinelDemand.Connectivity),
            audit,
            notice,
            deviceEvents = deviceEvents,
        )

    @Provides
    @Singleton
    fun foregroundDeniedNotice(@ApplicationContext context: Context): ForegroundDeniedNotice =
        AndroidForegroundDeniedNotice(context)

    @Provides
    fun connectivityTriggerRuntime(
        coordinator: ConnectivitySentinelCoordinator,
    ): ConnectivityTriggerRuntime = coordinator

    @Provides
    @Singleton
    fun sensorDetectionStore(
        @ApplicationContext context: Context,
    ): PrefsSensorDetectionStore = PrefsSensorDetectionStore(context)

    @Provides
    fun sensorDetectionStoreBoundary(store: PrefsSensorDetectionStore): SensorDetectionStore = store

    @Provides
    @Singleton
    fun sensorTriggerBackend(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        // Provider spezza il ciclo backend → ingress → coordinator → backend: l'ingress viene
        // risolto solo al primo callback, non a build-time del grafo.
        ingress: Provider<SensorEventIngress>,
    ): AndroidSignificantMotionBackend =
        AndroidSignificantMotionBackend(context, scope) { kind -> ingress.get().onSensorTriggered(kind) }

    @Provides
    fun sensorTriggerBackendBoundary(backend: AndroidSignificantMotionBackend): SensorTriggerBackend = backend

    @Provides
    @Singleton
    fun sensorTriggerCoordinator(
        store: AutomationStore,
        backend: SensorTriggerBackend,
        sentinel: SharedForegroundSentinel,
    ): SensorTriggerCoordinator = SensorTriggerCoordinator(
        store,
        backend,
        AndroidCapabilityProbe.IMPLEMENTED_SENSOR_KINDS,
        sentinel.demandBackend(SentinelDemand.Sensor),
    )

    @Provides
    fun sensorTriggerRuntime(coordinator: SensorTriggerCoordinator): SensorTriggerRuntime = coordinator

    @Provides
    @Singleton
    fun sensorEventDispatcher(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
        frame: DispatchFrame,
    ): EngineSensorEventDispatcher = EngineSensorEventDispatcher(engine, state, frame)

    @Provides
    fun sensorEventDispatcherBoundary(
        dispatcher: EngineSensorEventDispatcher,
    ): SensorEventDispatcher = dispatcher

    @Provides
    @Singleton
    fun sensorEventIngress(
        detections: SensorDetectionStore,
        dispatcher: SensorEventDispatcher,
        coordinator: SensorTriggerCoordinator,
        store: AutomationStore,
    ): SensorEventIngress = SensorEventIngress(
        store = detections,
        dispatcher = dispatcher,
        rearmer = coordinator,
        eligibleRules = { kind ->
            store.armed().mapNotNull { automation ->
                val trigger = automation.trigger as? Trigger.Sensor ?: return@mapNotNull null
                if (trigger.kind != kind) return@mapNotNull null
                val fingerprint = automation.approvalFingerprint ?: return@mapNotNull null
                EligibleSensorRule(automation.id, fingerprint, trigger.kind)
            }
        },
    )

    @Provides
    @Singleton
    fun geofenceState(@ApplicationContext context: Context): PrefsGeofenceStateStore =
        PrefsGeofenceStateStore(context)

    @Provides
    fun geofenceStateBoundary(state: PrefsGeofenceStateStore): GeofenceStateStore = state

    @Provides
    @Singleton
    fun geofenceBackend(@ApplicationContext context: Context): AndroidGeofenceBackend =
        AndroidGeofenceBackend(context)

    @Provides
    fun geofenceBackendBoundary(backend: AndroidGeofenceBackend): GeofenceBackend = backend

    @Provides
    @Singleton
    fun geofenceEventDispatcher(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
        frame: DispatchFrame,
    ): EngineGeofenceEventDispatcher = EngineGeofenceEventDispatcher(engine, state, frame)

    @Provides
    fun geofenceEventDispatcherBoundary(
        dispatcher: EngineGeofenceEventDispatcher,
    ): GeofenceEventDispatcher = dispatcher

    @Provides
    @Singleton
    fun geofenceEventIngress(
        state: GeofenceStateStore,
        dispatcher: GeofenceEventDispatcher,
        store: AutomationStore,
        location: CurrentLocationProvider,
    ): GeofenceEventIngress = GeofenceEventIngress(
        state,
        dispatcher,
        LocationBackedTransitionVerifier({ store.get(it)?.trigger }, location),
    )

    @Provides
    @Singleton
    fun geofenceCoordinator(
        store: AutomationStore,
        state: GeofenceStateStore,
        backend: GeofenceBackend,
        location: CurrentLocationProvider,
        ingress: GeofenceEventIngress,
    ): GeofenceCoordinator = GeofenceCoordinator(store, state, backend, location, ingress)

    @Provides
    fun geofenceTriggerRuntime(coordinator: GeofenceCoordinator): GeofenceTriggerRuntime =
        coordinator

    @Provides
    @Singleton
    fun timeAlarmState(database: ArgusDatabase): RoomTimeAlarmStateStore =
        RoomTimeAlarmStateStore(database)

    @Provides
    fun timeAlarmStateBoundary(state: RoomTimeAlarmStateStore): TimeAlarmStateStore = state

    @Provides
    @Singleton
    fun timeAlarmBackend(@ApplicationContext context: Context): AndroidTimeAlarmBackend =
        AndroidTimeAlarmBackend(context)

    @Provides
    fun timeAlarmBackendBoundary(backend: AndroidTimeAlarmBackend): TimeAlarmBackend = backend

    @Provides
    @Singleton
    fun timeAlarmCoordinator(
        store: AutomationStore,
        state: TimeAlarmStateStore,
        backend: TimeAlarmBackend,
        dispatcher: TimeEventDispatcher,
        audit: AuditSink,
        oneShotConsumptions: OneShotConsumptionRegistry,
        launcher: ReceiverWorkLauncher,
    ): TimeAlarmCoordinator =
        TimeAlarmCoordinator(
            store,
            state,
            backend,
            dispatcher,
            Instant::now,
            audit,
            oneShotConsumptions,
            // I recuperi dovuti partono in background e tengono la lease del servizio in primo
            // piano per tutta la loro durata: il reconcile non aspetta un programma lungo (B3).
            recoveryLauncher = RecoveryLauncher { block -> launcher.launch("time_recovery", block) },
        )

    @Provides
    @Singleton
    fun timeAlarmRuntime(coordinator: TimeAlarmCoordinator): TimeAlarmRuntime =
        CoordinatorTimeAlarmRuntime(coordinator)

    @Provides
    @Singleton
    fun registrar(
        coordinator: TimeAlarmCoordinator,
        store: AutomationStore,
        snapshots: FirePolicySnapshotProvider,
        connectivity: ConnectivityTriggerRuntime,
        geofence: GeofenceTriggerRuntime,
        sensor: SensorTriggerRuntime,
        immediateDispatcher: ImmediateEventDispatcher,
        audit: AuditSink,
        oneShotConsumptions: OneShotConsumptionRegistry,
        foregroundLauncher: ReceiverWorkLauncher,
    ): ArmedAutomationRegistrar = AndroidArmedAutomationRegistrar(
        coordinator,
        store,
        snapshots,
        connectivity,
        geofence,
        sensor,
        immediateDispatcher,
        Instant::now,
        audit,
        oneShotConsumptions,
        foregroundLauncher,
    )

    /**
     * Ri-arm immediato al ri-enable, servito dal percorso `registerImmediate` del registrar.
     * Interfaccia stretta apposta: il coordinator non dipende dall'intero registrar (evita il ciclo
     * Hilt) e il registrar non dipende dal coordinator, quindi il grafo resta aciclico.
     */
    @Provides
    @Singleton
    fun immediateReArm(registrar: ArmedAutomationRegistrar): ImmediateReArm =
        ImmediateReArm { automation -> registrar.register(automation) }

    @Provides
    @Singleton
    fun location(@ApplicationContext context: Context): CurrentLocationProvider =
        FrameworkCurrentLocationProvider(context)

    @Provides
    @Singleton
    fun approvalService(
        repository: DraftRepository,
        validator: DraftValidator,
        whitelist: ApprovalWhitelistProvider,
    ): ApprovalService = ApprovalService(
        repository,
        validator,
        whitelist,
    )

    /** Un solo validatore per l'approvazione e per il giro di riparazione del compile (0.5.0). */
    @Provides
    @Singleton
    fun draftValidator(): DraftValidator = DraftValidator(AndroidCapabilityProbe.KNOWN_TOOLS)

    @Provides
    @Singleton
    fun approvalFlow(
        drafts: DraftRepository,
        approvals: ApprovalService,
        automations: AutomationStore,
        capabilities: FirePolicySnapshotProvider,
        location: CurrentLocationProvider,
        registrar: ArmedAutomationRegistrar,
        stateQueries: StateQueryProbe,
        audit: AuditSink,
    ): ApprovalFlow = ApprovalFlow(
        drafts,
        approvals,
        automations,
        capabilities,
        location,
        registrar,
        stateQueries,
        audit,
    )

    @Provides
    @Singleton
    fun runtimeController(
        @ApplicationScope scope: CoroutineScope,
        scheduler: TimeAlarmRuntime,
        capabilities: dev.argus.automation.CapabilityReconciler,
        maintenance: RoomJournalMaintenance,
        shizuku: ShizukuGateway,
        preferences: AppPreferencesStore,
        replyRegistry: ActiveNotificationReplyRegistry,
        connectivity: ConnectivityTriggerRuntime,
        geofence: GeofenceTriggerRuntime,
        connectivityIngress: ConnectivityEventIngress,
        phoneIngress: PhoneEventIngress,
        sensor: SensorTriggerRuntime,
        sensorIngress: SensorEventIngress,
        store: AutomationStore,
        audit: AuditSink,
        frame: DispatchFrame,
        root: RootAccess,
    ): ArgusRuntimeController = ArgusRuntimeController(
        scope,
        scheduler,
        capabilities,
        maintenance,
        // 0.5.0: il flusso emette anche quando il root diventa attivo o smette di esserlo (stesso stato
        // Shizuku): la riconciliazione delle capacità riparte come per un cambio di Shizuku.
        combine(
            shizuku.observeStatus(),
            root.observeState().map { it == RootState.ACTIVE }.distinctUntilChanged(),
        ) { status, _ -> status },
        preferences,
        replyRegistry,
        connectivity,
        geofence,
        connectivityIngress,
        phoneIngress,
        sensor,
        sensorIngress,
        store = store,
        audit = audit,
        dispatchesIdle = { frame.idle },
    )

    // --- 0.4.1 (manuale): avvio manuale delle regole (F8) e «Pausa automazioni» (F21) ---

    @Provides
    @Singleton
    fun automationPauseStore(@ApplicationContext context: Context): AutomationPauseStore =
        PrefsAutomationPauseStore(context)

    @Provides
    @Singleton
    fun manualTokenStore(@ApplicationContext context: Context): ManualTokenStore =
        PrefsManualTokenStore(context)

    @Provides
    @Singleton
    fun manualTileSelection(@ApplicationContext context: Context): ManualTileSelection =
        PrefsManualTileSelection(context)

    @Provides
    @Singleton
    fun manualShortcutPinner(@ApplicationContext context: Context): ManualShortcutPinner =
        AndroidManualShortcutPinner(context)

    @Provides
    @Singleton
    fun manualEventDispatcher(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
        frame: DispatchFrame,
    ): ManualEventDispatcher = EngineManualEventDispatcher(engine, state, frame)

    @Provides
    @Singleton
    fun manualWorkLauncher(launcher: ReceiverWorkLauncher): ManualWorkLauncher =
        ReceiverManualWorkLauncher(launcher)

    @Provides
    @Singleton
    fun manualRuleCatalog(store: AutomationStore, tokens: ManualTokenStore): ManualRuleCatalog =
        ManualRuleCatalog(store, tokens)

    @Provides
    @Singleton
    fun manualRuleRunner(
        store: AutomationStore,
        tokens: ManualTokenStore,
        pause: AutomationPauseStore,
        dispatcher: ManualEventDispatcher,
        launcher: ManualWorkLauncher,
    ): ManualRuleRunner = ManualRuleRunner(store, tokens, pause, dispatcher, launcher)

    @Provides
    @Singleton
    fun manualEntryService(
        catalog: ManualRuleCatalog,
        tokens: ManualTokenStore,
        runner: ManualRuleRunner,
        pinner: ManualShortcutPinner,
        tile: ManualTileSelection,
        pause: AutomationPauseStore,
    ): ManualEntryService = ManualEntryService(catalog, tokens, runner, pinner, tile, pause)

    @Provides
    @Singleton
    fun quickTileController(
        tile: ManualTileSelection,
        catalog: ManualRuleCatalog,
        runner: ManualRuleRunner,
        pause: AutomationPauseStore,
    ): QuickTileController = QuickTileController(tile, catalog, runner, pause)
}
