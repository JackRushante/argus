package dev.argus.engine.runtime

import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.Action
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.StoredWrite
import dev.argus.engine.model.UserModePolicy
import dev.argus.engine.model.UserModeSnapshot
import kotlinx.coroutines.CancellationException

/** Valori conservati fra le esecuzioni (0.8). L'implementazione Android è Room. */
interface StoredValueStore {
    /** Solo le chiavi chieste che esistono. Un errore di lettura lancia: il chiamante lo tratta come UNKNOWN. */
    suspend fun read(keys: Set<String>): Map<String, StoredValue>

    /**
     * Applica [action] (già risolta) in modo ATOMICO: legge il valore corrente e il numero di chiavi, calcola
     * con [dev.argus.engine.model.StoredValueOps.plan] e scrive, tutto nella stessa transazione. Restituisce
     * l'esito calcolato; con [StoredWrite.Rejected] non scrive niente.
     */
    suspend fun apply(
        action: Action.SetStored,
        integrity: IntegrityLabel,
        confidentiality: ConfidentialityLabel,
        author: ChangeAuthor,
        atMillis: Long,
    ): StoredWrite
}

/** Elenco delle modalità e modalità attiva (0.8). L'implementazione Android è Room. */
interface UserModeStore {
    suspend fun snapshot(): UserModeSnapshot

    /**
     * Attiva [mode] (null = nessuna) in modo atomico. Il nome si cerca senza maiuscole e si salva come è
     * scritto nell'elenco. [ModeSwitch.UnknownMode] se non esiste, [ModeSwitch.Unchanged] se è già attiva.
     */
    suspend fun activate(mode: String?, author: ChangeAuthor, atMillis: Long): ModeSwitch
}

/** Consegna al motore un cambio di modalità (in produzione: fuori dal chiamante, nella cornice di dispatch). */
fun interface UserModeEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

/**
 * Unico punto da cui cambia la modalità: schermata, riquadro, widget e azione `set_user_mode`.
 *
 * Un cambio reale verso una modalità diventa UN evento `user_mode_changed` con la modalità nuova. Limite ai
 * loop: se il cambio lo fa una regola scattata da un cambio di modalità ([causedByModeChange]) l'evento è
 * «concatenato» e non si consegna; anche se arrivasse al motore il matcher lo rifiuta. Così una catena di
 * regole sulle modalità ha al più un passo: il cambio originale (utente o altra regola) accende le regole
 * `user_mode_changed`, e i cambi che queste fanno non accendono più niente.
 */
class UserModeSwitcher(
    private val store: UserModeStore,
    private val dispatcher: UserModeEventDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
    private val parser: DeviceEventParser = DeviceEventParser(),
) {
    suspend fun snapshot(): UserModeSnapshot = store.snapshot()

    suspend fun switchTo(
        mode: String?,
        author: ChangeAuthor,
        causedByModeChange: Boolean = false,
    ): ModeSwitch {
        val at = now()
        val result = store.activate(mode, author, at)
        if (result is ModeSwitch.Changed && result.to != null && !causedByModeChange) {
            parser.userMode(result.to, result.from, at, chained = false)?.let { dispatcher.dispatch(it) }
        }
        return result
    }

    /** Il tocco del riquadro: passa alla modalità successiva dell'elenco. null se l'elenco è vuoto. */
    suspend fun cycle(author: ChangeAuthor = ChangeAuthor.User): ModeSwitch? {
        val current = store.snapshot()
        val next = UserModePolicy.next(current.modes, current.active) ?: return null
        return switchTo(next, author)
    }
}

/** Esecuzione di `set_stored` e `set_user_mode`, condivisa da tutti gli esecutori. */
class StoredModeActions(
    private val stored: StoredValueStore,
    private val modes: UserModeSwitcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** [resolved] porta le etichette del template riempito: sono quelle che il valore SET conserva. */
    suspend fun setStored(resolved: ResolvedProgramAction, context: FireContext): ActionResult {
        val action = resolved.action as? Action.SetStored ?: return ActionResult.Failure("action_invalid")
        val write = try {
            stored.apply(
                action,
                resolved.inputIntegrity,
                resolved.inputConfidentiality,
                ChangeAuthor.Rule(context.automationId),
                now(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActionResult.Failure(STORED_UNAVAILABLE)
        }
        return when (write) {
            is StoredWrite.Put, is StoredWrite.Remove, StoredWrite.Unchanged -> ActionResult.Success
            is StoredWrite.Rejected -> ActionResult.Failure(write.code)
        }
    }

    suspend fun setUserMode(action: Action.SetUserMode, context: FireContext): ActionResult {
        if (!UserModePolicy.validLiteral(action.mode)) return ActionResult.Failure(USER_MODE_UNKNOWN)
        val result = try {
            modes.switchTo(
                action.mode,
                ChangeAuthor.Rule(context.automationId),
                causedByModeChange = context.event is TriggerEvent.UserModeChanged,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActionResult.Failure(USER_MODE_UNAVAILABLE)
        }
        return when (result) {
            is ModeSwitch.Changed, ModeSwitch.Unchanged -> ActionResult.Success
            ModeSwitch.UnknownMode -> ActionResult.Failure(USER_MODE_UNKNOWN)
        }
    }

    companion object {
        const val STORED_UNAVAILABLE = "stored_unavailable"
        const val USER_MODE_UNKNOWN = "user_mode_unknown"
        const val USER_MODE_UNAVAILABLE = "user_mode_unavailable"
    }
}
