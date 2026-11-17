package dev.argus.automation.modes

import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.DispatchFrame
import dev.argus.automation.manual.ManualTokenStore
import dev.argus.automation.manual.TileUiState
import dev.argus.data.ModeEditResult
import dev.argus.data.UserModeList
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.UserModePolicy
import dev.argus.engine.model.UserModeSnapshot
import dev.argus.engine.model.toDraft
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.UserModeEventDispatcher
import dev.argus.engine.runtime.UserModeSwitcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Esegue [block] fuori dal chiamante, tenendo vivo il processo (in produzione la lease del servizio in primo piano). */
fun interface ModeWorkLauncher {
    fun launch(block: suspend () -> Unit): Job
}

/**
 * Consegna al motore il cambio di modalità con la stessa cornice di ogni scatto, FUORI dal chiamante: un
 * `set_user_mode` non aspetta (né annida) le regole che il suo cambio accende. [engine] è pigro perché il
 * motore dipende a sua volta dall'esecutore che usa questo dispatcher.
 */
class EngineUserModeEventDispatcher(
    private val engine: () -> Engine,
    private val state: DeviceStateSnapshotProvider,
    private val launcher: ModeWorkLauncher,
    private val frame: DispatchFrame = DispatchFrame.NONE,
) : UserModeEventDispatcher {
    override suspend fun dispatch(envelope: TriggerEnvelope) {
        launcher.launch {
            frame.run { engine().onTrigger(envelope) { request -> state.current(request) } }
        }
    }
}

/**
 * Modifiche all'elenco delle modalità dalla schermata Modalità. Rinominare o eliminare una modalità che una regola
 * nomina (trigger, condizione o `set_user_mode`) manda quella regola in «da rivedere»: la regola approvata non si
 * riscrive mai da sola, e una regola armata non resta legata a un nome che non esiste più.
 */
class UserModeEditor(
    private val modes: UserModeList,
    private val automations: AutomationStore,
    private val audit: AuditSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun add(name: String): ModeEditResult = modes.add(name)

    suspend fun rename(from: String, to: String): ModeEditResult {
        val users = rulesUsing(from)
        val result = modes.rename(from, to)
        // Solo maiuscole cambiate: il nome si confronta senza maiuscole, le regole valgono ancora.
        if (result is ModeEditResult.Done && !UserModePolicy.same(from, result.name)) {
            quarantine(users, REASON_RENAMED)
        }
        return result
    }

    suspend fun delete(name: String): ModeEditResult {
        val users = rulesUsing(name)
        val result = modes.delete(name)
        if (result is ModeEditResult.Done) quarantine(users, REASON_DELETED)
        return result
    }

    private suspend fun rulesUsing(mode: String): List<AutomationId> = automations.all()
        .filter { it.status == AutomationStatus.ARMED || it.status == AutomationStatus.DISABLED }
        .filter { UserModePolicy.uses(it.toDraft(), mode) }
        .map { it.id }

    private suspend fun quarantine(ids: List<AutomationId>, reason: String) {
        ids.forEach { id ->
            automations.markNeedsReview(id)
            try {
                audit.record(AuditEvent(id, AuditKind.RULE_NEEDS_REVIEW, nowMillis(), detail = reason))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Il Log non cambia l'esito.
            }
        }
    }

    companion object {
        const val REASON_RENAMED = "user_mode_renamed"
        const val REASON_DELETED = "user_mode_deleted"
    }
}

/** Riquadro «Argus: modalità»: etichetta = modalità attiva; senza modalità definite è non disponibile. */
data class ModeTileSpec(val activeMode: String?, val state: TileUiState)

/** Una riga del widget: il nome, se è attiva e il token che il pulsante porta nell'intent. */
data class ModeWidgetEntry(val name: String, val active: Boolean, val token: String)

enum class ModeIntentResult { SWITCHED, UNCHANGED, BAD_TOKEN, UNKNOWN_MODE, UNAVAILABLE }

/**
 * Ingressi della modalità fuori dall'app: riquadro delle impostazioni rapide e widget. Come per l'avvio manuale,
 * il widget passa da un intent verso il trampolino non esportato e porta un token: un intent costruito da un'altra
 * app, che il nome della modalità può indovinarlo ma il token no, non cambia niente. Il token vive nello stesso
 * store dei token delle regole manuali, sotto un id che nessuna regola può avere.
 */
class ModeEntryPoints(
    private val switcher: UserModeSwitcher,
    private val tokens: ManualTokenStore,
) {
    suspend fun snapshot(): UserModeSnapshot = switcher.snapshot()

    fun tileSpec(snapshot: UserModeSnapshot): ModeTileSpec = when {
        snapshot.modes.isEmpty() -> ModeTileSpec(null, TileUiState.UNAVAILABLE)
        snapshot.active == null -> ModeTileSpec(null, TileUiState.INACTIVE)
        else -> ModeTileSpec(snapshot.active, TileUiState.ACTIVE)
    }

    /** Il tocco del riquadro: la modalità successiva. null = nessuna modalità definita. */
    suspend fun onTileClick(): ModeSwitch? = switcher.cycle(ChangeAuthor.User)

    suspend fun widgetEntries(): List<ModeWidgetEntry> {
        val snapshot = switcher.snapshot()
        val token = tokens.tokenFor(TOKEN_ID)
        return snapshot.modes.map { ModeWidgetEntry(it, UserModePolicy.same(it, snapshot.active ?: ""), token) }
    }

    /** Il pulsante del widget: prima il token, poi il nome. */
    suspend fun switchFromIntent(mode: String?, token: String?): ModeIntentResult {
        if (!tokens.isValid(TOKEN_ID, token)) return ModeIntentResult.BAD_TOKEN
        if (mode.isNullOrBlank() || !UserModePolicy.validLiteral(mode)) return ModeIntentResult.UNKNOWN_MODE
        return try {
            when (switcher.switchTo(mode, ChangeAuthor.User)) {
                is ModeSwitch.Changed -> ModeIntentResult.SWITCHED
                ModeSwitch.Unchanged -> ModeIntentResult.UNCHANGED
                ModeSwitch.UnknownMode -> ModeIntentResult.UNKNOWN_MODE
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ModeIntentResult.UNAVAILABLE
        }
    }

    companion object {
        /** Id del token dei pulsanti modalità: gli id delle regole sono UUID, questo non lo è mai. */
        val TOKEN_ID = AutomationId("argus.user_mode")
    }
}
