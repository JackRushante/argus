package dev.argus.manual

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.manual.AutomationPauseStore
import dev.argus.automation.manual.ManualRuleCatalog
import dev.argus.automation.manual.ManualRuleRunner
import dev.argus.automation.manual.ManualTileSelection
import dev.argus.automation.manual.QuickTileController

/**
 * Accesso Hilt per i componenti che il sistema istanzia da sé (riquadri, widget, attività
 * trampolino). La logica sta in `automation-android` (classi testabili su JVM); questi componenti
 * sono solo il bordo Android.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ManualEntryPoint {
    fun runner(): ManualRuleRunner
    fun catalog(): ManualRuleCatalog
    fun tiles(): QuickTileController
    fun pause(): AutomationPauseStore
    fun tileSelection(): ManualTileSelection
}

internal fun Context.manualEntryPoint(): ManualEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, ManualEntryPoint::class.java)
