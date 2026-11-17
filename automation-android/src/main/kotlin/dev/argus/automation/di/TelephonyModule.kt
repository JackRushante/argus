package dev.argus.automation.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.base.DeferredOpenNotifier
import dev.argus.automation.labels.LabelAwarePause
import dev.argus.automation.telephony.AndroidContactLookup
import dev.argus.automation.telephony.AndroidTelephonyClock
import dev.argus.automation.telephony.AndroidTelephonySurface
import dev.argus.automation.telephony.CallScreeningCoordinator
import dev.argus.automation.telephony.CallerScopeLog
import dev.argus.automation.telephony.ContactLookup
import dev.argus.automation.telephony.PrefsTelephonyLedgerStore
import dev.argus.automation.telephony.ScreenCallDecisionLog
import dev.argus.automation.telephony.ShellPrivilegedAm
import dev.argus.automation.telephony.TelephonyActionRunner
import dev.argus.automation.telephony.TelephonyRateLimiter
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.CallScreeningDecider
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.shizuku.PrivilegedShell
import java.time.Clock
import javax.inject.Singleton

/** 0.8: SMS, chiamate, Intent e filtro chiamate. */
@Module
@InstallIn(SingletonComponent::class)
object TelephonyModule {
    @Provides
    @Singleton
    fun telephonyRateLimiter(@ApplicationContext context: Context): TelephonyRateLimiter =
        AndroidTelephonyClock(context).let { clock ->
            TelephonyRateLimiter(PrefsTelephonyLedgerStore(context, clock), clock)
        }

    @Provides
    @Singleton
    fun telephonyActionRunner(
        @ApplicationContext context: Context,
        limiter: TelephonyRateLimiter,
        audit: AuditSink,
        shell: PrivilegedShell,
        deferredOpen: DeferredOpenNotifier,
    ): TelephonyActionRunner = TelephonyActionRunner(
        surface = AndroidTelephonySurface(context),
        limiter = limiter,
        audit = audit,
        privileged = ShellPrivilegedAm(shell),
        deferredOpen = deferredOpen,
    )

    @Provides
    @Singleton
    fun screenCallDecisionLog(): ScreenCallDecisionLog = ScreenCallDecisionLog()

    @Provides
    @Singleton
    fun callerScopeLog(): CallerScopeLog = CallerScopeLog()

    @Provides
    @Singleton
    fun contactLookup(@ApplicationContext context: Context): ContactLookup = AndroidContactLookup(context)

    @Provides
    @Singleton
    fun callScreeningCoordinator(
        store: AutomationStore,
        snapshots: FirePolicySnapshotProvider,
        pause: LabelAwarePause,
        contacts: ContactLookup,
        decisions: ScreenCallDecisionLog,
        audit: AuditSink,
        scopes: CallerScopeLog,
    ): CallScreeningCoordinator = CallScreeningCoordinator(
        armed = store::armed,
        snapshots = snapshots,
        pause = pause,
        contacts = contacts,
        decider = CallScreeningDecider(Clock.systemDefaultZone()),
        decisions = decisions,
        audit = audit,
        scopes = scopes,
    )
}
