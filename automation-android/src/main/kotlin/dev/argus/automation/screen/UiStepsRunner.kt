package dev.argus.automation.screen

import dev.argus.engine.model.Action
import dev.argus.engine.model.ScrollDirection
import dev.argus.engine.model.UiStep
import dev.argus.engine.model.UiStepsPolicy
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * Trasparenza della macro (0.5.1): finché gira, una notifica in corso dice «Argus sta usando lo
 * schermo» con «Ferma». [stopRequested] diventa vero quando l'utente la tocca.
 */
interface UiMacroSession {
    /** Mostra la notifica. Falso se non si può mostrare: allora la macro non parte (nessun controllo manuale). */
    fun begin(): Boolean

    /** Nasconde la notifica. Sempre chiamato, anche se la macro fallisce o viene annullata. */
    fun end()

    val stopRequested: Boolean
}

/**
 * Esecutore delle macro `ui_steps` (0.5.1, F17). Niente AI: un passo dopo l'altro, ognuno col suo
 * tempo massimo, e la macro intera entro [UiStepsPolicy.MAX_MACRO_MS]. Un passo che non trova ciò che
 * cerca ferma la macro con un codice: mai un clic «a caso».
 *
 * Confini che l'esecutore fa rispettare a ogni passo, oltre al validatore:
 *  - l'app in primo piano non è mai Argus, le Impostazioni, l'installatore o i dialoghi dei permessi
 *    ([UiStepsPolicy.isForbiddenPackage]): se compare, la macro si ferma prima di toccare;
 *  - una sola macro per volta (`ui_busy`);
 *  - la notifica «Ferma» è visibile finché la macro gira.
 *
 * Nessun contenuto dello schermo esce da qui: gli esiti sono codici chiusi.
 *
 * [now] e [pause] sono iniettati: i test fanno scorrere un tempo finto.
 */
class UiStepsRunner(
    private val screen: ScreenSource,
    private val session: UiMacroSession,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val running = Mutex()

    /** Ferma la macro con un codice chiuso. */
    private class MacroStop(val code: String) : RuntimeException(null, null, false, false)

    /**
     * Esegue la macro. [openApp] apre `pkg` come farebbe l'azione `launch_app` (stesso percorso:
     * Shizuku, intent o notifica «Tocca per aprire»); il suo esito, se non è un successo, ferma la macro.
     */
    suspend fun run(action: Action.UiSteps, openApp: suspend (String) -> ActionResult): ActionResult {
        // Difesa in profondità: il validatore e l'interpolatore l'hanno già garantito.
        if (!UiStepsPolicy.isResolvedShapeValid(action)) return ActionResult.Failure("resolved_action_invalid")
        if (!screen.available) return ActionResult.Failure(ScreenCodes.SERVICE_UNAVAILABLE)
        if (!running.tryLock()) return ActionResult.Failure(ScreenCodes.BUSY)
        var began = false
        try {
            if (!session.begin()) return ActionResult.Failure(ScreenCodes.NOTIFICATION_UNAVAILABLE)
            began = true
            val deadline = now() + UiStepsPolicy.MAX_MACRO_MS
            return try {
                val pkg = action.pkg
                if (pkg != null) openAndWait(pkg, deadline, openApp)
                for (step in action.steps) execute(step, deadline)
                ActionResult.Success
            } catch (stop: MacroStop) {
                ActionResult.Failure(stop.code)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Un nodo scaduto o un errore del framework: codice chiuso, mai il testo dell'errore.
                ActionResult.Failure(ScreenCodes.ACTION_FAILED)
            }
        } finally {
            if (began) session.end()
            running.unlock()
        }
    }

    /**
     * Apre l'app e aspetta che sia in primo piano. Finché non lo è, la schermata di partenza (anche
     * Argus o le Impostazioni, se la regola parte da lì) non ferma la macro: da quando l'app indicata è
     * davanti, ogni passo ricontrolla.
     */
    private suspend fun openAndWait(pkg: String, deadline: Long, openApp: suspend (String) -> ActionResult) {
        checkpoint(deadline, enforceScreen = false)
        when (val opened = openApp(pkg)) {
            is ActionResult.Failure -> throw MacroStop(opened.reason)
            else -> Unit
        }
        val ready = waitUntil(deadline, UiStepsPolicy.OPEN_TIMEOUT_MS, enforceScreen = false) {
            screen.activePackage() == pkg
        }
        if (!ready) throw MacroStop(ScreenCodes.OPEN_FAILED)
        pause(UiStepsPolicy.SETTLE_MS)
    }

    private suspend fun execute(step: UiStep, deadline: Long) {
        checkpoint(deadline)
        when (step) {
            is UiStep.WaitFor -> {
                val query = ScreenQuery(text = step.text, id = step.id, textAlsoDescription = true)
                val appeared = waitUntil(deadline, step.timeoutMs.toLong()) { firstMatch(query) != null }
                if (!appeared) throw MacroStop(ScreenCodes.TIMEOUT)
            }
            is UiStep.Click -> click(step, deadline)
            is UiStep.Type -> type(step, deadline)
            is UiStep.Scroll -> scroll(step.dir, deadline)
            UiStep.Back -> {
                if (!screen.back()) throw MacroStop(ScreenCodes.ACTION_FAILED)
                pause(UiStepsPolicy.SETTLE_MS)
            }
            UiStep.Home -> {
                if (!screen.home()) throw MacroStop(ScreenCodes.ACTION_FAILED)
                pause(UiStepsPolicy.SETTLE_MS)
            }
            is UiStep.Check -> check(step, deadline)
        }
    }

    private suspend fun click(step: UiStep.Click, deadline: Long) {
        val query = ScreenQuery(text = step.text, id = step.id, desc = step.desc)
        val index = step.index ?: 0
        var target: ScreenNode? = null
        val found = waitUntil(deadline, UiStepsPolicy.FIND_TIMEOUT_MS) {
            target = screen.root()?.let { ScreenMatcher.find(it, query).getOrNull(index) }
            target != null
        }
        if (!found) throw MacroStop(ScreenCodes.ELEMENT_NOT_FOUND)
        val clickable = ScreenMatcher.clickableFor(requireNotNull(target)) ?: throw MacroStop(ScreenCodes.CLICK_FAILED)
        // Ultimo controllo prima del tocco: la schermata può essere cambiata durante la ricerca.
        checkpoint(deadline)
        if (!clickable.click()) throw MacroStop(ScreenCodes.CLICK_FAILED)
        pause(UiStepsPolicy.SETTLE_MS)
    }

    private suspend fun type(step: UiStep.Type, deadline: Long) {
        var field: ScreenNode? = null
        val found = waitUntil(deadline, UiStepsPolicy.FIND_TIMEOUT_MS) {
            field = screen.focusedInput()?.takeIf { it.isEditable }
            field != null
        }
        if (!found) throw MacroStop(ScreenCodes.INPUT_FAILED)
        checkpoint(deadline)
        if (!requireNotNull(field).setText(step.text)) throw MacroStop(ScreenCodes.INPUT_FAILED)
        pause(UiStepsPolicy.SETTLE_MS)
    }

    private suspend fun scroll(dir: ScrollDirection, deadline: Long) {
        val container = screen.root()?.let(ScreenMatcher::firstScrollable)
            ?: throw MacroStop(ScreenCodes.SCROLL_FAILED)
        checkpoint(deadline)
        if (!container.scroll(forward = dir == ScrollDirection.DOWN)) throw MacroStop(ScreenCodes.SCROLL_FAILED)
        pause(UiStepsPolicy.SETTLE_MS)
    }

    private suspend fun check(step: UiStep.Check, deadline: Long) {
        val query = ScreenQuery(text = step.text, textAlsoDescription = true)
        // Il controllo riesce appena vale: la schermata può ancora stare cambiando.
        val holds = waitUntil(deadline, UiStepsPolicy.FIND_TIMEOUT_MS) {
            (firstMatch(query) != null) == step.present
        }
        if (!holds) throw MacroStop(ScreenCodes.CHECK_FAILED)
    }

    private fun firstMatch(query: ScreenQuery): ScreenNode? =
        screen.root()?.let { ScreenMatcher.find(it, query).firstOrNull() }

    /**
     * Ripete [condition] ogni [POLL_MS] fino a [timeoutMs] (e comunque fino alla scadenza della macro).
     * Ad ogni giro controlla stop, scadenza e schermata vietata. Vero se la condizione è valsa.
     */
    private suspend fun waitUntil(
        deadline: Long,
        timeoutMs: Long,
        enforceScreen: Boolean = true,
        condition: () -> Boolean,
    ): Boolean {
        val start = now()
        while (true) {
            checkpoint(deadline, enforceScreen)
            if (condition()) return true
            if (now() - start >= timeoutMs) return false
            pause(POLL_MS)
        }
    }

    private fun checkpoint(deadline: Long, enforceScreen: Boolean = true) {
        if (session.stopRequested) throw MacroStop(ScreenCodes.STOPPED)
        if (now() >= deadline) throw MacroStop(ScreenCodes.MACRO_TIMEOUT)
        if (!screen.available) throw MacroStop(ScreenCodes.SERVICE_UNAVAILABLE)
        if (!enforceScreen) return
        val active = screen.activePackage()
        if (active != null && UiStepsPolicy.isForbiddenPackage(active)) throw MacroStop(ScreenCodes.FORBIDDEN_SCREEN)
    }

    private companion object {
        const val POLL_MS = 250L
    }
}
