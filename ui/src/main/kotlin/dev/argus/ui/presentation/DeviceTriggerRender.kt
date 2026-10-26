package dev.argus.ui.presentation

import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.Trigger

/**
 * Testo di approvazione dei trigger di sistema senza Shizuku (0.4.1): schermo, batteria, uscita
 * audio, cambio di modalità, riproduzione. Generato dai tipi (invariante 4), EN/IT, e ogni campo
 * che cambia l'esecuzione compare per intero (soglie, tipo di uscita, valore atteso).
 */
internal object DeviceTriggerRender {
    fun iconKey(t: Trigger): String = when (t) {
        is Trigger.Screen -> "screen"
        is Trigger.Battery -> "battery"
        is Trigger.AudioOutput -> "audio_output"
        is Trigger.ModeChanged -> "mode_changed"
        is Trigger.Media -> "media"
        else -> "sensor"
    }

    fun line(t: Trigger, l: RenderLanguage): String {
        val prefix = RuleRenderMapper.whenPrefix(l)
        return prefix + when (t) {
            is Trigger.Screen -> screen(t, l)
            is Trigger.Battery -> battery(t, l)
            is Trigger.AudioOutput -> audioOutput(t, l)
            is Trigger.ModeChanged -> modeChanged(t, l)
            is Trigger.Media -> media(t, l)
            else -> ""
        }
    }

    private fun screen(t: Trigger.Screen, l: RenderLanguage): String = when (t.event) {
        ScreenEvent.ON -> l.pick("the screen turns on", "lo schermo si accende")
        ScreenEvent.OFF -> l.pick("the screen turns off", "lo schermo si spegne")
        ScreenEvent.UNLOCKED -> l.pick("the phone is unlocked", "il telefono viene sbloccato")
    }

    private fun battery(t: Trigger.Battery, l: RenderLanguage): String {
        val below = t.below
        val above = t.above
        return when {
            below != null && above == null -> l.pick(
                "the battery drops below $below% (once each time it crosses)",
                "la batteria scende sotto il $below% (una volta a ogni attraversamento)",
            )
            above != null && below == null -> l.pick(
                "the battery rises above $above% (once each time it crosses)",
                "la batteria sale sopra il $above% (una volta a ogni attraversamento)",
            )
            // Malformato (il validatore lo rifiuta): mostra comunque ogni soglia presente.
            else -> l.pick(
                "battery threshold (below ${below ?: "-"}%, above ${above ?: "-"}%) is not valid",
                "soglia batteria (sotto ${below ?: "-"}%, sopra ${above ?: "-"}%) non valida",
            )
        }
    }

    private fun audioOutput(t: Trigger.AudioOutput, l: RenderLanguage): String {
        val kind = when (t.kind) {
            AudioOutputKind.WIRED -> l.pick("wired audio output", "uscita audio a filo")
            AudioOutputKind.BLUETOOTH -> l.pick("Bluetooth audio output", "uscita audio Bluetooth")
            AudioOutputKind.ANY -> l.pick(
                "any personal audio output (wired or Bluetooth)",
                "qualsiasi uscita audio personale (a filo o Bluetooth)",
            )
        }
        val state = if (t.state == ConnState.CONNECTED) {
            l.pick("connected", "collegata")
        } else {
            l.pick("disconnected", "scollegata")
        }
        return "$kind $state"
    }

    private fun modeChanged(t: Trigger.ModeChanged, l: RenderLanguage): String {
        val what = when (t.what) {
            ModeKind.DND -> l.pick("Do Not Disturb", "Non disturbare")
            ModeKind.RINGER -> l.pick("the ringer mode", "la suoneria")
        }
        val target = t.to ?: return l.pick("$what changes", "$what cambia")
        val value = when (t.what) {
            ModeKind.DND -> when (target) {
                "off" -> l.pick("off", "disattivato")
                "priority" -> l.pick("priority only", "solo priorità")
                "total" -> l.pick("total silence", "silenzio totale")
                else -> target
            }
            ModeKind.RINGER -> when (target) {
                "normal" -> l.pick("normal", "normale")
                "vibrate" -> l.pick("vibrate", "vibrazione")
                "silent" -> l.pick("silent", "silenzioso")
                else -> target
            }
        }
        return l.pick("$what changes to $value", "$what passa a $value")
    }

    private fun media(t: Trigger.Media, l: RenderLanguage): String = when (t.state) {
        MediaPlayState.PLAYING -> l.pick("media starts playing", "inizia una riproduzione multimediale")
        MediaPlayState.STOPPED -> l.pick(
            "media stops playing",
            "finisce la riproduzione multimediale",
        )
    }
}
