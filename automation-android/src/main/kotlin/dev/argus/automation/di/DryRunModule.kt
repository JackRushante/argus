package dev.argus.automation.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.CurrentLocationProvider
import dev.argus.automation.LazyDeviceStateProvider
import dev.argus.automation.PersistentStateReader
import dev.argus.automation.calendar.CalendarInstanceSource
import dev.argus.automation.context.AndroidDeviceFactsReader
import dev.argus.automation.dryrun.AndroidDryRunFacts
import dev.argus.automation.dryrun.ContactScopeGate
import dev.argus.automation.dryrun.DryRunRuleReader
import dev.argus.automation.labels.LabelAwarePause
import dev.argus.automation.telephony.ContactLookup
import dev.argus.device.ForegroundAppSource
import dev.argus.device.PublicStateReader
import dev.argus.device.StateReader
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.dryrun.DryRunSimulator
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.RevalidatingFirePolicy
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.secrets.SecretVault
import dev.argus.shizuku.RootAccess
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.flow.first

/**
 * 0.8 «Prova a secco». The simulator gets only things that read: the fire policy (it decides, it never acts),
 * the state readers WITHOUT the privileged shell, facts as values, the pause, a contact lookup. No executor,
 * store, journal, audit sink, span store, vault value, brain or rate ledger is reachable from here.
 */
@Module
@InstallIn(SingletonComponent::class)
object DryRunModule {
    @Provides
    fun dryRunSimulator(
        @ApplicationContext context: Context,
        policy: RevalidatingFirePolicy,
        reader: StateReader,
        publicReader: PublicStateReader,
        location: CurrentLocationProvider,
        usageForeground: ForegroundAppSource,
        calendarSource: CalendarInstanceSource,
        persistentState: PersistentStateReader,
        pause: LabelAwarePause,
        contacts: ContactLookup,
        whitelist: ContactWhitelistStore,
        secrets: SecretVault,
        gateway: ShizukuGateway,
        root: RootAccess,
    ): DryRunSimulator {
        // Public readers only: the privileged shell is never ready here, so no `settings get`, `dumpsys` or
        // parametric query runs (those states are UNKNOWN and fail closed, as when Shizuku is off).
        val state = LazyDeviceStateProvider(
            reader = reader,
            shizukuStatus = { ShizukuGatewayStatus.NOT_INSTALLED },
            location = location,
            parametricReader = null,
            publicReader = publicReader,
            privilegedReady = { false },
            usageForeground = usageForeground,
            factsReader = AndroidDeviceFactsReader(context, calendarSource),
            persistentState = persistentState,
        )
        return DryRunSimulator(
            firePolicy = policy,
            readState = { request -> state.current(request) },
            facts = AndroidDryRunFacts(
                context = context,
                whitelistedIds = { whitelist.all().mapTo(mutableSetOf()) { it.id } },
                // Names only: `names()` lists entries, it never decrypts a value.
                secretNames = { secrets.names() },
                // Cached status, as the executor reads it: no command is sent.
                privilegedReady = { gateway.status() == ShizukuGatewayStatus.AUTHORIZED || root.isActive() },
            ),
            pauses = pause,
            callerScope = ContactScopeGate(contacts),
        )
    }

    @Provides
    fun dryRunRuleReader(
        store: AutomationStore,
        drafts: DraftRepository,
        whitelist: ContactWhitelistStore,
    ): DryRunRuleReader = DryRunRuleReader(
        automation = { id -> store.get(id) },
        drafts = { drafts.observeAll().first() },
        conversationLabels = { whitelist.all().associate { it.id to it.displayName } },
    )
}
