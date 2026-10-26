package dev.argus.automation.triggers

import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sorgenti di eventi di sistema (0.4.1) che la sentinella in primo piano registra SOLO se una
 * regola armata le usa. Il Wi-Fi e l'alimentazione restano gestiti a parte, come prima.
 */
enum class DeviceEventSource {
    SCREEN,
    BATTERY,
    AUDIO_OUTPUT,
    MODE_DND,
    MODE_RINGER,
    MEDIA,
}

/** La sorgente di cui ha bisogno il trigger, o `null` se il trigger non passa dalla sentinella. */
fun Trigger.deviceEventSource(): DeviceEventSource? = when (this) {
    is Trigger.Screen -> DeviceEventSource.SCREEN
    is Trigger.Battery -> DeviceEventSource.BATTERY
    is Trigger.AudioOutput -> DeviceEventSource.AUDIO_OUTPUT
    is Trigger.ModeChanged -> when (what) {
        ModeKind.DND -> DeviceEventSource.MODE_DND
        ModeKind.RINGER -> DeviceEventSource.MODE_RINGER
    }
    is Trigger.Media -> DeviceEventSource.MEDIA
    else -> null
}

/**
 * Insieme delle sorgenti richieste dalle regole armate. Lo scrive il coordinator della sentinella
 * a ogni reconcile; lo legge il servizio, che registra e rimuove i receiver di conseguenza (anche
 * a servizio già acceso per il Wi-Fi). Un solo valore, sempre l'insieme corrente.
 */
class DeviceEventDemand {
    private val required = MutableStateFlow<Set<DeviceEventSource>>(emptySet())

    val sources: StateFlow<Set<DeviceEventSource>> = required.asStateFlow()

    fun update(sources: Set<DeviceEventSource>) {
        required.value = sources.toSet()
    }
}
