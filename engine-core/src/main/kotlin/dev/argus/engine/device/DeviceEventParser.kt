package dev.argus.engine.device

import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.AppPackagePolicy
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Normalizza gli eventi di sistema senza Shizuku (schermo, batteria, uscita audio, modalità,
 * riproduzione) in envelope con id stabile e opaco, come [dev.argus.engine.connectivity.ConnectivityEventParser].
 * Gli id sono digest: l'identità del dispositivo audio ([sourceIdentity]) entra solo come materiale
 * hash e non viene mai persistita né loggata.
 */
class DeviceEventParser {
    fun screen(event: ScreenEvent, atMillis: Long): TriggerEnvelope = envelope(
        TriggerEvent.ScreenChanged(event),
        "screen", event.name, instant(atMillis),
    )

    /** `null` se un livello esce da 0..100 (dato incoerente: fail-closed, nessun evento). */
    fun battery(previous: Int, current: Int, atMillis: Long): TriggerEnvelope? {
        if (previous !in LEVELS || current !in LEVELS || previous == current) return null
        return envelope(
            TriggerEvent.BatteryLevelChanged(previous, current),
            "battery", previous.toString(), current.toString(), instant(atMillis),
        )
    }

    /** `kind` ANY non è un'uscita reale: esiste solo come filtro del trigger. */
    fun audioOutput(
        kind: AudioOutputKind,
        state: ConnState,
        sourceIdentity: String,
        atMillis: Long,
    ): TriggerEnvelope? {
        if (kind == AudioOutputKind.ANY) return null
        val identity = sourceIdentity.takeIf {
            it.isNotBlank() && it.length <= MAX_SOURCE_IDENTITY_CHARS && it.none(Char::isISOControl)
        } ?: return null
        return envelope(
            TriggerEvent.AudioOutputChanged(kind, state),
            "audio_output", kind.name, state.name, identity, instant(atMillis),
        )
    }

    /** `null` se [to] non è un valore del vocabolario di [what] (mai un valore inventato). */
    fun mode(what: ModeKind, to: String, from: String?, atMillis: Long): TriggerEnvelope? {
        val allowed = what.allowedValues
        if (to !in allowed || (from != null && from !in allowed)) return null
        return envelope(
            TriggerEvent.DeviceModeChanged(what, to, from),
            "mode", what.name, from.orEmpty(), to, instant(atMillis),
        )
    }

    fun media(state: MediaPlayState, atMillis: Long): TriggerEnvelope = envelope(
        TriggerEvent.MediaPlaybackChanged(state),
        "media", state.name, instant(atMillis),
    )

    /**
     * App aperta o chiusa (0.5.1). L'id è un digest di package, direzione e istante dell'evento di
     * sistema: la stessa transizione riletta due volte dà lo stesso id e il motore non la esegue due
     * volte. `null` se il package non ha la forma di un package Android (mai un valore inventato).
     */
    fun appEvent(pkg: String, event: AppEventKind, atMillis: Long): TriggerEnvelope? {
        if (!AppPackagePolicy.valid(pkg)) return null
        return envelope(
            TriggerEvent.AppForegroundChanged(pkg, event),
            "app_event", pkg, event.name, instant(atMillis),
        )
    }

    private fun instant(atMillis: Long): String = atMillis.coerceAtLeast(0).toString()

    private fun envelope(event: TriggerEvent, vararg identity: String) =
        TriggerEnvelope(TriggerEventId("device:" + digest(*identity)), event)

    private fun digest(vararg values: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            messageDigest.update(value.toByteArray(StandardCharsets.UTF_8))
            messageDigest.update(0)
        }
        return messageDigest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_SOURCE_IDENTITY_CHARS = 512
        private val LEVELS = 0..100
    }
}
