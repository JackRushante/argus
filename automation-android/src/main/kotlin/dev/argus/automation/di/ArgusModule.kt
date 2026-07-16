package dev.argus.automation.di

import android.content.Context
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
import dev.argus.automation.ClipboardCopier
import dev.argus.automation.EngineNotificationEventDispatcher
import dev.argus.automation.EnginePhoneEventDispatcher
import dev.argus.automation.EngineConnectivityEventDispatcher
import dev.argus.automation.EngineGeofenceEventDispatcher
import dev.argus.automation.EngineTimeEventDispatcher
import dev.argus.automation.FrameworkCurrentLocationProvider
import dev.argus.automation.GenerativeLane
import dev.argus.automation.LazyDeviceStateProvider
import dev.argus.automation.NotificationEventDispatcher
import dev.argus.automation.EngineSensorEventDispatcher
import dev.argus.automation.RoomTimeAlarmStateStore
import dev.argus.automation.ShizukuActionExecutor
import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.automation.base.AndroidBaseActionSurface
import dev.argus.automation.base.BaseActionSurface
import dev.argus.automation.foreground.SharedForegroundSentinel
import dev.argus.automation.foreground.SentinelDemand
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
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyHandleFactory
import dev.argus.brain.AndroidBridgeConfigurationStore
import dev.argus.brain.BridgeConfigurationStore
import dev.argus.data.ArgusDatabase
import dev.argus.data.DeferredReplyStore
import dev.argus.data.RoomDeferredReplyStore
import dev.argus.data.RoomAuditSink
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.RoomExecutionJournal
import dev.argus.data.RoomJournalMaintenance
import dev.argus.data.RoomObservedConversationStore
import dev.argus.device.DeviceController
import dev.argus.device.DeviceTools
import dev.argus.device.ParametricStateReader
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
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuPrivilegedShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    fun journalMaintenance(database: ArgusDatabase): RoomJournalMaintenance =
        RoomJournalMaintenance(database)

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
    @Singleton
    fun privilegedShell(
        @ApplicationContext context: Context,
        gateway: ShizukuGateway,
        @ApplicationScope scope: CoroutineScope,
    ): ShizukuPrivilegedShell = ShizukuPrivilegedShell(context, gateway, scope)

    @Provides
    fun privilegedShellBoundary(shell: ShizukuPrivilegedShell): PrivilegedShell = shell

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

    @Provides
    @Singleton
    fun stateQueryProbe(
        gateway: ShizukuGateway,
        reader: ParametricStateReader,
    ): StateQueryProbe = AndroidStateQueryProbe(gateway, reader)

    @Provides
    @Singleton
    fun lazyDeviceState(
        reader: StateReader,
        parametricReader: ParametricStateReader,
        gateway: ShizukuGateway,
        location: CurrentLocationProvider,
    ): LazyDeviceStateProvider = LazyDeviceStateProvider(
        reader,
        gateway,
        location,
        parametricReader,
    )

    @Provides
    fun deviceStateSnapshotProvider(
        provider: LazyDeviceStateProvider,
    ): DeviceStateSnapshotProvider = provider

    @Provides
    @Singleton
    fun generativeRuntimeReadiness(
        configuration: BridgeConfigurationStore,
        preferences: AppPreferencesStore,
    ): GenerativeRuntimeReadiness = AndroidGenerativeRuntimeReadiness(configuration, preferences)

    @Provides
    @Singleton
    fun capabilityProbe(
        @ApplicationContext context: Context,
        gateway: ShizukuGateway,
        whitelist: ContactWhitelistStore,
        readiness: GenerativeRuntimeReadiness,
    ): AndroidCapabilityProbe = AndroidCapabilityProbe(
        context,
        gateway,
        whitelist,
        readiness,
        // Tier base attivo (P3-3): DND/Ringer via NotificationManager, LaunchApp/OpenUrl via Intent,
        // senza Shizuku. In futuro pilotato dal flavor (personal-full/play-core).
        baseTierActive = true,
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
    ) = dev.argus.automation.CapabilityReconciler(store, snapshots)

    @Provides
    @Singleton
    fun bridgeConfiguration(@ApplicationContext context: Context): BridgeConfigurationStore =
        AndroidBridgeConfigurationStore(context)

    @Provides
    @Singleton
    fun configuredBrain(
        configuration: BridgeConfigurationStore,
        preferences: AppPreferencesStore,
    ): ConfiguredBridgeBrain = ConfiguredBridgeBrain(
        configuration = configuration,
        privacyAccepted = { preferences.observe().value.privacyAccepted },
    )

    @Provides
    fun brain(brain: ConfiguredBridgeBrain): Brain = brain

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
    fun androidGenerativeLane(
        @ApplicationScope scope: CoroutineScope,
        journal: SubmittedActionJournal,
        automations: AutomationStore,
        policy: FirePolicy,
        brain: Brain,
        replies: NotificationReplyGateway,
        deferredReplies: DeferredReplySink,
    ): AndroidGenerativeLane = AndroidGenerativeLane(
        scope,
        journal,
        automations,
        policy,
        brain,
        replies,
        deferredReplies,
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

    @Provides
    @Singleton
    fun baseActionExecutor(surface: BaseActionSurface): AndroidBaseActionExecutor =
        AndroidBaseActionExecutor(surface)

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
            // Tier base (P3-3): DND/Ringer/LaunchApp/OpenUrl via API normali, non Shizuku.
            baseActions = baseActions,
        )

    @Provides
    fun actionExecutorBoundary(executor: ShizukuActionExecutor): ActionExecutor = executor

    @Provides
    @Singleton
    fun engine(
        store: AutomationStore,
        executor: ActionExecutor,
        firePolicy: FirePolicy,
        audit: AuditSink,
        journal: ExecutionJournal,
    ): Engine = Engine(
        store = store,
        executor = executor,
        evaluator = ConditionEvaluator(Clock.systemDefaultZone()),
        matcher = TriggerMatcher(),
        firePolicy = firePolicy,
        audit = audit,
        journal = journal,
        now = System::currentTimeMillis,
    )

    @Provides
    @Singleton
    fun timeEventDispatcher(engine: Engine, state: LazyDeviceStateProvider): EngineTimeEventDispatcher =
        EngineTimeEventDispatcher(engine, state)

    @Provides
    fun timeEventDispatcherBoundary(dispatcher: EngineTimeEventDispatcher): TimeEventDispatcher =
        dispatcher

    @Provides
    @Singleton
    fun notificationEventDispatcher(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
    ): EngineNotificationEventDispatcher = EngineNotificationEventDispatcher(engine, state)

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

    @Provides
    @Singleton
    fun phoneEventIngress(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
        @ApplicationContext context: Context,
    ): PhoneEventIngress = PhoneEventIngress(
        parser = PhoneEventParser(),
        callState = PrefsCallStateStore(context),
        dispatcher = EnginePhoneEventDispatcher(engine, state),
    )

    @Provides
    @Singleton
    fun connectivityEventDispatcher(
        engine: Engine,
        state: DeviceStateSnapshotProvider,
    ): EngineConnectivityEventDispatcher = EngineConnectivityEventDispatcher(engine, state)

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
    fun connectivitySentinelCoordinator(
        store: AutomationStore,
        sentinel: SharedForegroundSentinel,
    ): ConnectivitySentinelCoordinator =
        ConnectivitySentinelCoordinator(store, sentinel.demandBackend(SentinelDemand.Connectivity))

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
    ): EngineSensorEventDispatcher = EngineSensorEventDispatcher(engine, state)

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
    ): EngineGeofenceEventDispatcher = EngineGeofenceEventDispatcher(engine, state)

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
    ): TimeAlarmCoordinator = TimeAlarmCoordinator(store, state, backend, dispatcher, Instant::now)

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
    ): ArmedAutomationRegistrar = AndroidArmedAutomationRegistrar(
        coordinator,
        store,
        snapshots,
        connectivity,
        geofence,
        sensor,
    )

    @Provides
    @Singleton
    fun location(@ApplicationContext context: Context): CurrentLocationProvider =
        FrameworkCurrentLocationProvider(context)

    @Provides
    @Singleton
    fun approvalService(
        repository: DraftRepository,
        whitelist: ApprovalWhitelistProvider,
    ): ApprovalService = ApprovalService(
        repository,
        DraftValidator(AndroidCapabilityProbe.KNOWN_TOOLS),
        whitelist,
    )

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
    ): ApprovalFlow = ApprovalFlow(
        drafts,
        approvals,
        automations,
        capabilities,
        location,
        registrar,
        stateQueries,
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
    ): ArgusRuntimeController = ArgusRuntimeController(
        scope,
        scheduler,
        capabilities,
        maintenance,
        shizuku.observeStatus(),
        preferences,
        replyRegistry,
        connectivity,
        geofence,
        connectivityIngress,
        phoneIngress,
        sensor,
        sensorIngress,
    )
}
