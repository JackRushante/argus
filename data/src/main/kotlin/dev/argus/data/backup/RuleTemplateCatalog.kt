package dev.argus.data.backup

import android.content.res.AssetManager
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId

/** Gruppo di un modello nella galleria (0.7.2). */
enum class TemplateCategory(val wireName: String) {
    HOME("home"),
    WORK("work"),
    BATTERY("battery"),
    MESSAGES("messages"),
    ;

    companion object {
        fun fromWire(value: String): TemplateCategory? = entries.firstOrNull { it.wireName == value }
    }
}

/** Testo di un modello nelle due lingue dell'app. */
data class TemplateText(val en: String, val it: String) {
    fun pick(italian: Boolean): String = if (italian) it else en
}

/** Una voce della galleria dei modelli: titolo e descrizione; la regola sta nel suo file. */
data class RuleTemplate(
    val id: String,
    val category: TemplateCategory,
    val title: TemplateText,
    val description: TemplateText,
)

/** Accesso in sola lettura ai file dei modelli; in produzione gli asset dell'app. */
fun interface TemplateAssets {
    @Throws(IOException::class)
    fun open(path: String): InputStream
}

class AndroidTemplateAssets(private val assets: AssetManager) : TemplateAssets {
    override fun open(path: String): InputStream = assets.open(path)
}

/**
 * Modelli di regola spediti con l'app (0.7.2), negli asset `templates/`:
 *  - `index.json`: `{"format":"argus-templates","version":1,"templates":[{"id","category","title":{"en","it"},"description":{"en","it"}}]}`;
 *  - `<id>.en.json` e `<id>.it.json`: la regola, nel formato del file di backup ([RulesBackupFormat]) con una
 *    sola voce, nella lingua dell'utente.
 *
 * «Usa questo modello» passa i byte di [ruleBytes] a [RulesBackup.importBytes]: lo stesso percorso di un file
 * importato (limite, decodifica stretta, bozza `IMPORT` da approvare). Dove la regola chiede dati dell'utente
 * (una chat, una rete, un luogo) il modello porta i valori «da completare» di [PersonalDataStripper].
 */
class RuleTemplateCatalog(private val assets: TemplateAssets) {

    /** I modelli dell'indice, nell'ordine del file. Un indice illeggibile o malformato dà un elenco vuoto. */
    fun templates(): List<RuleTemplate> {
        val text = readText(INDEX_PATH) ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        if (root.string("format") != INDEX_FORMAT || root.int("version") != INDEX_VERSION) return emptyList()
        val entries = root["templates"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.string("id")?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            val category = entry.string("category")?.let(TemplateCategory::fromWire) ?: return@mapNotNull null
            val title = (entry["title"] as? JsonObject)?.text() ?: return@mapNotNull null
            val description = (entry["description"] as? JsonObject)?.text() ?: return@mapNotNull null
            RuleTemplate(id, category, title, description)
        }.distinctBy { it.id }
    }

    /** La regola del modello, decodificata come all'import; null se il file manca o non è valido. */
    fun draft(id: String, italian: Boolean): AutomationDraft? {
        val text = ruleText(id, italian) ?: return null
        val entries = (RulesBackupCodec.decode(text) as? DecodedBackup.Entries)?.entries ?: return null
        val rule = entries.singleOrNull() as? DecodedEntry.Rule ?: return null
        return rule.rule.draft
    }

    /**
     * I byte da importare per «Usa questo modello»: il file del modello con l'orario nel fuso [zone] del
     * telefono (i modelli lo scrivono in un fuso qualunque). null se il modello non c'è o non è valido.
     */
    fun ruleBytes(id: String, italian: Boolean, zone: ZoneId): ByteArray? {
        val draft = draft(id, italian) ?: return null
        val localized = draft.copy(trigger = localizedTrigger(draft.trigger, zone))
        val text = RulesBackupCodec.encode(
            rules = listOf(BackupRule(enabled = false, draft = localized)),
            exportedAt = TEMPLATE_STAMP,
            appVersion = TEMPLATE_STAMP,
        )
        return RulesBackupFormat.utf8WithinCap(text)
    }

    private fun localizedTrigger(trigger: Trigger, zone: ZoneId): Trigger =
        if (trigger is Trigger.Time) trigger.copy(tz = zone.id) else trigger

    private fun ruleText(id: String, italian: Boolean): String? {
        if (!ID.matches(id)) return null
        return readText("$DIRECTORY/$id.${if (italian) "it" else "en"}.json")
    }

    private fun readText(path: String): String? = try {
        assets.open(path).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            var tooLarge = false
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > RulesBackupFormat.MAX_FILE_BYTES) {
                    tooLarge = true
                    break
                }
                out.write(buffer, 0, read)
            }
            if (tooLarge) null else out.toByteArray().toString(Charsets.UTF_8)
        }
    } catch (_: IOException) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()

    private fun JsonObject.text(): TemplateText? {
        val en = string("en")?.takeIf(String::isNotBlank) ?: return null
        val it = string("it")?.takeIf(String::isNotBlank) ?: return null
        return TemplateText(en, it)
    }

    companion object {
        const val DIRECTORY = "templates"
        const val INDEX_PATH = "$DIRECTORY/index.json"
        const val INDEX_FORMAT = "argus-templates"
        const val INDEX_VERSION = 1
        private const val TEMPLATE_STAMP = "template"
        private val ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    }
}
