package dev.argus

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.argus.automation.ArgusRuntimeController
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.TimeAlarmRuntime
import dev.argus.automation.TimeAlarmRuntimeRegistry
import dev.argus.automation.apps.AndroidAppLabelLookup
import dev.argus.automation.backup.AutoBackupCoordinator
import dev.argus.automation.calendar.CalendarTriggerLifecycle
import dev.argus.automation.directboot.BeforeUnlockCopyWriter
import dev.argus.automation.directboot.LockedProcess
import dev.argus.automation.prompt.UserPromptStartup
import dev.argus.manual.ManualWidgetRefresher
import dev.argus.ui.presentation.AppLabels
import javax.inject.Inject

@HiltAndroidApp
class ArgusApplication : Application(), DefaultLifecycleObserver {
    @Inject lateinit var alarmRuntime: TimeAlarmRuntime
    @Inject lateinit var runtimeController: ArgusRuntimeController
    @Inject lateinit var renderLanguage: RenderLanguageSource
    @Inject lateinit var manualWidgetRefresher: ManualWidgetRefresher
    @Inject lateinit var autoBackup: AutoBackupCoordinator
    @Inject lateinit var userPrompts: UserPromptStartup
    @Inject lateinit var calendarTrigger: CalendarTriggerLifecycle
    @Inject lateinit var beforeUnlockCopy: BeforeUnlockCopyWriter

    override fun onCreate() {
        // 0.8, rules before the first unlock: the `:locked` process runs only the two direct-boot-aware receivers,
        // possibly before the user has unlocked. Nothing of the start-up may run there: the Hilt injection (done by
        // the generated superclass in super.onCreate) would build the graph, which opens Room and the preferences in
        // credential-encrypted storage and crashes before unlock. Application.onCreate itself does nothing.
        if (LockedProcess.isCurrent(this)) return
        super<Application>.onCreate()
        TimeAlarmRuntimeRegistry.install(alarmRuntime)
        // 0.5.1: il testo di approvazione di «app aperta/chiusa» mostra il nome dell'app se il telefono lo conosce.
        AppLabels.install(AndroidAppLabelLookup(this)::labelOf)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        runtimeController.start()
        // 0.4.1 (F8): il widget delle regole manuali segue le regole armate e la pausa.
        manualWidgetRefresher.start()
        // 0.7.2: rimette la sveglia del backup automatico (un riavvio la cancella) e recupera un ritardo.
        autoBackup.start()
        // 0.8: rimostra le conferme ancora valide dopo un riavvio, chiude le scadute.
        userPrompts.start()
        // 0.8: programma le istanze del calendario delle regole armate e segue i cambi del calendario.
        calendarTrigger.start()
        // 0.8: the device-encrypted copy of the rules opted in to run before the first unlock follows the rules.
        beforeUnlockCopy.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 0.8: nothing was injected in the `:locked` process.
        if (!::renderLanguage.isInitialized) return
        // Cambio lingua del telefono: i ViewModel rigenerano i testi senza riavvio (B34).
        renderLanguage.refresh()
    }

    override fun onStart(owner: LifecycleOwner) {
        runtimeController.onForeground()
        autoBackup.onForeground()
        calendarTrigger.onForeground()
    }
}
