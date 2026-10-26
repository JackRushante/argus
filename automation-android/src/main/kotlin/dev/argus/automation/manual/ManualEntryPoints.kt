package dev.argus.automation.manual

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Contratto degli intent dell'avvio manuale (0.4.1, F8). L'attività trampolino che li riceve sta
 * nell'app, NON è esportata (la avvia solo il sistema per conto di Argus: scorciatoia fissata o
 * `PendingIntent` del widget) e verifica comunque il token per regola.
 */
object ManualIntents {
    const val ACTION_RUN = "dev.argus.action.RUN_MANUAL_RULE"
    const val EXTRA_RULE_ID = "dev.argus.extra.MANUAL_RULE_ID"
    const val EXTRA_TOKEN = "dev.argus.extra.MANUAL_TOKEN"

    /** Classe dell'attività trampolino (modulo app): il manifest la dichiara non esportata. */
    const val RUN_ACTIVITY_CLASS = "dev.argus.manual.ManualRunActivity"

    /** Prefisso dell'id della scorciatoia fissata, così una sola scorciatoia per regola. */
    const val SHORTCUT_ID_PREFIX = "manual_"

    /** Intent esplicito verso il trampolino: stesso pacchetto, mai implicito. */
    fun runIntent(context: Context, ruleId: String, token: String): Intent =
        Intent(ACTION_RUN)
            .setClassName(context.packageName, RUN_ACTIVITY_CLASS)
            .putExtra(EXTRA_RULE_ID, ruleId)
            .putExtra(EXTRA_TOKEN, token)
}

/** Una regola manuale armata come la mostrano il widget e le scorciatoie. */
data class ManualEntry(val id: String, val name: String, val token: String)

/**
 * Elenco delle regole `manual` ARMATE (le sole che hanno senso su widget e riquadro), ognuna col
 * proprio token. Ordine stabile (nome, poi id) e tetto sul numero: la logica sta qui, fuori da
 * RemoteViews e TileService, così si prova su JVM.
 */
class ManualRuleCatalog(
    private val store: AutomationStore,
    private val tokens: ManualTokenStore,
) {
    suspend fun entries(): List<ManualEntry> = store.armed()
        .filter { isRunnableManual(it) }
        .sortedWith(compareBy<Automation>({ it.name.lowercase() }, { it.id.value }))
        .take(MAX_ENTRIES)
        .map { ManualEntry(it.id.value, it.name, tokens.tokenFor(it.id)) }

    /** La regola se è ancora una manuale armata, altrimenti null. */
    suspend fun runnable(id: String): Automation? =
        store.get(AutomationId(id))?.takeIf { isRunnableManual(it) }

    companion object {
        /** Tetto di righe del widget: oltre non ha senso una lista di pulsanti. */
        const val MAX_ENTRIES = 30

        fun isRunnableManual(automation: Automation): Boolean =
            automation.status == AutomationStatus.ARMED && automation.enabled &&
                automation.trigger is Trigger.Manual
    }
}

/** La regola scelta per il riquadro «Argus: esegui regola». Una sola: l'etichetta del riquadro è il suo nome. */
interface ManualTileSelection {
    val ruleId: StateFlow<String?>
    fun select(id: String?)
}

class PrefsManualTileSelection internal constructor(
    private val preferences: SharedPreferences,
) : ManualTileSelection {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val state = MutableStateFlow(preferences.getString(KEY_RULE, null)?.takeIf(String::isNotBlank))
    override val ruleId: StateFlow<String?> = state.asStateFlow()

    override fun select(id: String?) {
        val value = id?.takeIf(String::isNotBlank)
        if (value == state.value) return
        val editor = preferences.edit()
        if (value == null) editor.remove(KEY_RULE) else editor.putString(KEY_RULE, value)
        editor.apply()
        state.value = value
    }

    private companion object {
        const val PREFERENCES_NAME = "argus_manual_tile"
        const val KEY_RULE = "rule_id"
    }
}

/** Propone al launcher la scorciatoia fissata di una regola. */
interface ManualShortcutPinner {
    /** Il launcher accetta le scorciatoie fissate. */
    fun isSupported(): Boolean

    /** true se la richiesta è stata inviata al launcher (l'utente poi conferma lì). */
    fun requestPin(entry: ManualEntry): Boolean
}

class AndroidManualShortcutPinner(context: Context) : ManualShortcutPinner {
    private val appContext = context.applicationContext

    override fun isSupported(): Boolean = runCatching {
        ShortcutManagerCompat.isRequestPinShortcutSupported(appContext)
    }.getOrDefault(false)

    override fun requestPin(entry: ManualEntry): Boolean = runCatching {
        val label = entry.name.take(MAX_LABEL_CHARS)
        val shortcut = ShortcutInfoCompat.Builder(appContext, SHORTCUT_ID_PREFIX + entry.id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(appContext, appContext.applicationInfo.icon))
            .setIntent(ManualIntents.runIntent(appContext, entry.id, entry.token))
            .build()
        ShortcutManagerCompat.requestPinShortcut(appContext, shortcut, null)
    }.getOrDefault(false)

    private companion object {
        const val SHORTCUT_ID_PREFIX = ManualIntents.SHORTCUT_ID_PREFIX
        const val MAX_LABEL_CHARS = 40
    }
}

enum class AddToHomeResult { REQUESTED, UNSUPPORTED, UNAVAILABLE }

/**
 * Faccia unica dell'avvio manuale per le schermate: eseguire, fissare sulla Home, scegliere il
 * riquadro. Le schermate non toccano token né launcher.
 */
class ManualEntryService(
    private val catalog: ManualRuleCatalog,
    private val tokens: ManualTokenStore,
    private val runner: ManualRuleRunner,
    private val pinner: ManualShortcutPinner,
    private val tile: ManualTileSelection,
    private val pause: AutomationPauseStore,
) {
    val paused: StateFlow<Boolean> get() = pause.paused
    val tileRuleId: StateFlow<String?> get() = tile.ruleId

    fun canPinShortcut(): Boolean = pinner.isSupported()

    suspend fun run(ruleId: String): ManualRunResult = runner.runFromApp(ruleId)

    suspend fun addToHome(ruleId: String): AddToHomeResult {
        if (!pinner.isSupported()) return AddToHomeResult.UNSUPPORTED
        val rule = catalog.runnable(ruleId) ?: return AddToHomeResult.UNAVAILABLE
        val entry = ManualEntry(rule.id.value, rule.name, tokens.tokenFor(rule.id))
        return if (pinner.requestPin(entry)) AddToHomeResult.REQUESTED else AddToHomeResult.UNSUPPORTED
    }

    fun useInTile(ruleId: String) = tile.select(ruleId)

    /** Regola eliminata: il suo token non vale più e, se era quella del riquadro, il riquadro si svuota. */
    fun forget(ruleId: String) {
        tokens.forget(AutomationId(ruleId))
        if (tile.ruleId.value == ruleId) tile.select(null)
    }
}
