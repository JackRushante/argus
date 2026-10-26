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
import dev.argus.manual.ManualWidgetRefresher
import javax.inject.Inject

@HiltAndroidApp
class ArgusApplication : Application(), DefaultLifecycleObserver {
    @Inject lateinit var alarmRuntime: TimeAlarmRuntime
    @Inject lateinit var runtimeController: ArgusRuntimeController
    @Inject lateinit var renderLanguage: RenderLanguageSource
    @Inject lateinit var manualWidgetRefresher: ManualWidgetRefresher

    override fun onCreate() {
        super<Application>.onCreate()
        TimeAlarmRuntimeRegistry.install(alarmRuntime)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        runtimeController.start()
        // 0.4.1 (F8): il widget delle regole manuali segue le regole armate e la pausa.
        manualWidgetRefresher.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Cambio lingua del telefono: i ViewModel rigenerano i testi senza riavvio (B34).
        renderLanguage.refresh()
    }

    override fun onStart(owner: LifecycleOwner) {
        runtimeController.onForeground()
    }
}
