package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.HttpRequestPolicy
import dev.argus.engine.model.MediaCommand
import dev.argus.ui.model.ActionRow
import java.util.Locale

/**
 * Resa dell'approvazione e del dettaglio per le azioni 0.4.1 (lettura ad alta voce, tasti
 * multimediali, luminosità, rotazione, chiamata HTTP). Come ogni resa di [RuleRenderMapper] parte dai
 * TIPI, mai dalla prosa del modello, ed è bilingue via [RenderLanguage].
 *
 * Per `http_request` la review mostra metodo e URL INTERI, il content type e il body per intero (con i
 * segnaposto `${…}` com'è nel modello approvato); le intestazioni il cui nome contiene
 * `authorization`, `token`, `key` o `secret` hanno il valore mascherato (`Bearer ••••abcd`), così
 * uno screenshot dell'approvazione non rivela il segreto.
 */
internal object ActionRows041 {

    fun speak(a: Action.Speak, l: RenderLanguage) = ActionRow(
        iconKey = "ringer",
        label = l.pick("Speak aloud", "Leggi ad alta voce"),
        detail = a.text,
        isShell = false,
        shellCommand = null,
        isGenerative = false,
        requiresLiveConfirm = false,
    )

    fun mediaControl(a: Action.MediaControl, l: RenderLanguage) = ActionRow(
        iconKey = "ringer",
        label = l.pick("Media key", "Tasto multimediale"),
        detail = mediaCommandLabel(a.command, l),
        isShell = false,
        shellCommand = null,
        isGenerative = false,
        requiresLiveConfirm = false,
    )

    fun mediaCommandLabel(command: MediaCommand, l: RenderLanguage): String = when (command) {
        MediaCommand.PLAY -> l.pick("play", "riproduci")
        MediaCommand.PAUSE -> l.pick("pause", "pausa")
        MediaCommand.PLAY_PAUSE -> l.pick("play / pause", "riproduci / pausa")
        MediaCommand.NEXT -> l.pick("next track", "traccia successiva")
        MediaCommand.PREVIOUS -> l.pick("previous track", "traccia precedente")
    }

    fun setBrightness(a: Action.SetBrightness, l: RenderLanguage) = ActionRow(
        iconKey = "settings",
        label = l.pick("Set screen brightness", "Imposta la luminosità dello schermo"),
        detail = when {
            a.level != null -> l.pick("${a.level}% (manual)", "${a.level}% (manuale)")
            a.auto == true -> l.pick("automatic", "automatica")
            else -> l.pick("manual (keeps the current level)", "manuale (mantiene il livello attuale)")
        },
        isShell = false,
        shellCommand = null,
        isGenerative = false,
        requiresLiveConfirm = false,
    )

    fun setRotation(a: Action.SetRotation, l: RenderLanguage) = ActionRow(
        iconKey = "settings",
        label = l.pick("Set auto-rotate", "Imposta la rotazione automatica"),
        detail = if (a.auto) l.pick("on", "attiva") else l.pick("off", "disattivata"),
        isShell = false,
        shellCommand = null,
        isGenerative = false,
        requiresLiveConfirm = false,
    )

    fun httpRequest(a: Action.HttpRequest, l: RenderLanguage): ActionRow {
        val lines = buildList {
            // Metodo e URL INTERI, mai abbreviati: è l'indirizzo a cui escono i dati.
            add("${a.method.name} ${a.url}")
            a.headers.orEmpty().forEach { (name, value) ->
                add("$name: ${HttpRequestPolicy.displayValue(name, value)}")
            }
            // Un content type dichiarato nel campo si mostra come intestazione, se non è già nell'elenco.
            val alreadyListed = a.headers.orEmpty().keys
                .any { it.lowercase(Locale.ROOT) == HttpRequestPolicy.CONTENT_TYPE_HEADER }
            if (a.contentType != null && !alreadyListed) add("Content-Type: ${a.contentType}")
            a.body?.let { add(l.pick("Body: $it", "Corpo: $it")) }
        }
        return ActionRow(
            iconKey = "cloud",
            label = l.pick("HTTP request", "Richiesta HTTP"),
            detail = lines.joinToString("\n"),
            isShell = false,
            shellCommand = null,
            isGenerative = false,
            requiresLiveConfirm = false,
        )
    }
}
