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
import dev.argus.automation.AndroidTimeAlarmBackend
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.ArmedAutomationRegistrar
import dev.argus.automation.ArgusRuntimeController
import dev.argus.automation.AutomationNotifier
import dev.argus.automation.ConfiguredBridgeBrain
import dev.argus.automation.CoordinatorTimeAlarmRuntime
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.EngineNotificationEventDispatcher
import dev.argus.automation.EngineTimeEventDispatcher
import dev.argus.automation.FrameworkCurrentLocationProvider
import dev.argus.automation.GenerativeLane
import dev.argus.automation.LazyDeviceStateProvider
import dev.argus.automation.NotificationEventDispatcher
import dev.argus.automation.RoomTimeAlarmStateStore
import dev.argus.automation.ShizukuActionExecutor
import dev.argus.automation.TimeAlarmArmedAutomationRegistrar
import dev.argus.automation.TimeAlarmBackend
import dev.argus.automation.TimeAlarmCoordinator
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.TimeAlarmStateStore
import dev.argus.automation.TimeEventDispatcher
import dev.argus.automation.UnavailableGenerativeLane
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.automation.notification.AndroidNotificationReplyGateway
import dev.argus.automation.notification.AndroidNotificationSnapshotFactory
import dev.argus.automation.notification.NotificationIngress
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyHandleFactory
import dev.argus.brain.AndroidBridgeConfigurationStore
import dev.argus.brain.BridgeConfigurationStore
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAuditSink
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.RoomExecutionJournal
import dev.argus.data.RoomJournalMaintenance
import dev.argus.data.RoomObservedConversationStore
import dev.argus.device.DeviceController
import dev.argus.device.DeviceTools
import dev.argus.device.StateReader
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityProbe
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.notification.NotificationEventParser
import dev.argus.engine.notification.ObservedConversationStore
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.RevalidatingFirePolicy
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
    fun lazyDeviceState(reader: StateReader, gateway: ShizukuGateway): LazyDeviceStateProvider =
        LazyDeviceStateProvider(reader, gateway)

    @Provides
    fun deviceStateSnapshotProvider(
        provider: LazyDeviceStateProvider,
    ): DeviceStateSnapshotProvider = provider

    @Provides
    @Singleton
    fun capabilityProbe(
        @ApplicationContext context: Context,
        gateway: ShizukuGateway,
        whitelist: ContactWhitelistStore,
    ): AndroidCapabilityProbe = AndroidCapabilityProbe(context, gateway, whitelist)

    @Provides
    fun capabilityProbeBoundary(probe: AndroidCapabilityProbe): CapabilityProbe = probe

    @Provides
    fun firePolicySnapshotProvider(probe: AndroidCapabilityProbe): FirePolicySnapshotProvider = probe

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
    fun generativeLane(): GenerativeLane = UnavailableGenerativeLane

    @Provides
    @Singleton
    fun actionExecutor(
        tools: DeviceController,
        notifier: AutomationNotifier,
        generativeLane: GenerativeLane,
    ): ShizukuActionExecutor = ShizukuActionExecutor(tools, notifier, generativeLane)

    @Provides
    fun actionExecutorBoundary(executor: ShizukuActionExecutor): ActionExecutor = executor

    @Provides
    @Singleton
    fun engine(
        store: AutomationStore,
        executor: ActionExecutor,
        snapshots: FirePolicySnapshotProvider,
        audit: AuditSink,
        journal: ExecutionJournal,
    ): Engine = Engine(
        store = store,
        executor = executor,
        evaluator = ConditionEvaluator(Clock.systemDefaultZone()),
        matcher = TriggerMatcher(),
        firePolicy = RevalidatingFirePolicy(snapshots),
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
    ): NotificationIngress = NotificationIngress(
        snapshots,
        parser,
        handles,
        registry,
        conversations,
        dispatcher,
    )

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
    ): ArmedAutomationRegistrar = TimeAlarmArmedAutomationRegistrar(coordinator, store)

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
    ): ApprovalFlow = ApprovalFlow(
        drafts,
        approvals,
        automations,
        capabilities,
        location,
        registrar,
    )

    @Provides
    @Singleton
    fun runtimeController(
        @ApplicationScope scope: CoroutineScope,
        scheduler: TimeAlarmRuntime,
        capabilities: dev.argus.automation.CapabilityReconciler,
        maintenance: RoomJournalMaintenance,
        shizuku: ShizukuGateway,
    ): ArgusRuntimeController = ArgusRuntimeController(
        scope,
        scheduler,
        capabilities,
        maintenance,
        shizuku,
    )
}
