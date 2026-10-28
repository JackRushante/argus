package dev.argus.engine.model

import java.util.IdentityHashMap

/**
 * Package d'app nominati da una regola (0.7.0): servono all'avviso «app non installata» e al tasto
 * «Cambia app» dell'approvazione. Logica pura sul modello, nessun accesso al telefono.
 *
 * I package SOSTITUIBILI sono quelli letterali e ben formati ([AppPackagePolicy]) di: trigger `app_event`,
 * `launch_app`, `app_control`, `app_permission` e `ui_steps.pkg`. Ognuno ha uno [Ref.slot], il suo numero
 * d'ordine in una visita deterministica: prima il trigger, poi le azioni in profondità (`then` prima di
 * `orElse`, poi il corpo di `while`). Un package che non è un nome valido (per esempio un segnaposto
 * `${var}`) non ha slot: la UI non può cambiarlo e il controllo di installazione lo ignora.
 *
 * Il package del trigger `notification` non si cambia (è legato alla conversazione), ma l'app deve esistere:
 * entra solo in [packagesToCheck].
 */
object AppPackages {
    enum class Kind { APP_EVENT, LAUNCH_APP, APP_CONTROL, APP_PERMISSION, UI_STEPS }

    data class Ref(val slot: Int, val kind: Kind, val pkg: String)

    /** Oltre questa profondità l'albero non viene visitato: il validatore lo rifiuta comunque. */
    private const val MAX_DEPTH = 32

    private class Entry(val ref: Ref, val action: Action?)

    /** Package sostituibili della regola, nell'ordine degli slot (0, 1, 2, ...). */
    fun refs(trigger: Trigger, actions: List<Action>): List<Ref> = entries(trigger, actions).map { it.ref }

    /**
     * Slot di ogni AZIONE che nomina un package, per IDENTITÀ dell'istanza (due azioni uguali hanno slot
     * diversi). Il trigger, se nomina un package, ha lo slot 0 e non compare qui.
     */
    fun slotsByAction(trigger: Trigger, actions: List<Action>): Map<Action, Int> {
        val map = IdentityHashMap<Action, Int>()
        entries(trigger, actions).forEach { entry -> entry.action?.let { map[it] = entry.ref.slot } }
        return map
    }

    /** Slot del trigger `app_event` se nomina un package valido, altrimenti null. */
    fun triggerSlot(trigger: Trigger): Int? =
        if (trigger is Trigger.AppEvent && AppPackagePolicy.valid(trigger.pkg)) 0 else null

    /**
     * Tutti i package che devono esistere sul telefono perché la regola funzioni, senza doppioni e
     * nell'ordine di comparsa: i sostituibili più quello del trigger `notification`.
     */
    fun packagesToCheck(trigger: Trigger, actions: List<Action>): List<String> {
        val all = ArrayList<String>()
        if (trigger is Trigger.Notification && AppPackagePolicy.valid(trigger.pkg)) all += trigger.pkg
        refs(trigger, actions).forEach { all += it.pkg }
        return all.distinct()
    }

    /** Trigger e azioni dopo la sostituzione. */
    data class Replaced(val trigger: Trigger, val actions: List<Action>)

    /**
     * Sostituisce il package dello [slot] con [newPackage]. Null se lo slot non esiste o se il nuovo nome
     * non è un package valido. Tutto il resto della regola resta identico.
     */
    fun replace(trigger: Trigger, actions: List<Action>, slot: Int, newPackage: String): Replaced? {
        if (!AppPackagePolicy.valid(newPackage)) return null
        if (refs(trigger, actions).none { it.slot == slot }) return null
        var counter = 0
        fun next(): Int = counter++
        val newTrigger = if (trigger is Trigger.AppEvent && AppPackagePolicy.valid(trigger.pkg)) {
            if (next() == slot) trigger.copy(pkg = newPackage) else trigger
        } else {
            trigger
        }

        fun rebuild(list: List<Action>, depth: Int): List<Action> {
            if (depth > MAX_DEPTH) return list
            return list.map { action ->
                when (action) {
                    is Action.LaunchApp ->
                        if (AppPackagePolicy.valid(action.pkg) && next() == slot) action.copy(pkg = newPackage) else action
                    is Action.AppControl ->
                        if (AppPackagePolicy.valid(action.pkg) && next() == slot) action.copy(pkg = newPackage) else action
                    is Action.AppPermission ->
                        if (AppPackagePolicy.valid(action.pkg) && next() == slot) action.copy(pkg = newPackage) else action
                    is Action.UiSteps -> {
                        val pkg = action.pkg
                        if (pkg != null && AppPackagePolicy.valid(pkg) && next() == slot) {
                            action.copy(pkg = newPackage)
                        } else {
                            action
                        }
                    }
                    is Action.If -> {
                        val then = rebuild(action.then, depth + 1)
                        val orElse = rebuild(action.orElse, depth + 1)
                        action.copy(then = then, orElse = orElse)
                    }
                    is Action.While -> action.copy(body = rebuild(action.body, depth + 1))
                    else -> action
                }
            }
        }

        return Replaced(newTrigger, rebuild(actions, 0))
    }

    private fun entries(trigger: Trigger, actions: List<Action>): List<Entry> {
        val out = ArrayList<Entry>()
        fun add(kind: Kind, pkg: String?, action: Action?) {
            if (pkg != null && AppPackagePolicy.valid(pkg)) out += Entry(Ref(out.size, kind, pkg), action)
        }
        if (trigger is Trigger.AppEvent) add(Kind.APP_EVENT, trigger.pkg, null)

        fun walk(list: List<Action>, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (action in list) {
                when (action) {
                    is Action.LaunchApp -> add(Kind.LAUNCH_APP, action.pkg, action)
                    is Action.AppControl -> add(Kind.APP_CONTROL, action.pkg, action)
                    is Action.AppPermission -> add(Kind.APP_PERMISSION, action.pkg, action)
                    is Action.UiSteps -> add(Kind.UI_STEPS, action.pkg, action)
                    is Action.If -> {
                        walk(action.then, depth + 1)
                        walk(action.orElse, depth + 1)
                    }
                    is Action.While -> walk(action.body, depth + 1)
                    else -> Unit
                }
            }
        }
        walk(actions, 0)
        return out
    }
}
