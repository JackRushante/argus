package dev.argus.automation.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore

/**
 * Tipi di app per cui Android ha un intent standard (0.7.0): «la calcolatrice», «il calendario»… La
 * richiesta che li nomina (in italiano o in inglese) fa aggiungere ai candidati l'app PREDEFINITA
 * dell'utente per quel tipo, anche se il suo nome non c'entra con le parole della richiesta
 * («Calcolatrice» → l'app `com.oneplus.calculator` che si chiama «Calculator»).
 *
 * [keywords] sono parole intere, già in minuscolo e senza accenti. [category] è la categoria
 * `Intent.CATEGORY_APP_*`; la fotocamera non ne ha una e usa `MediaStore.ACTION_IMAGE_CAPTURE`.
 */
internal enum class AppKind(val keywords: Set<String>, val category: String?) {
    CALCULATOR(setOf("calcolatrice", "calcolatrici", "calculator", "calculators"), Intent.CATEGORY_APP_CALCULATOR),
    CALENDAR(setOf("calendario", "calendari", "agenda", "calendar", "calendars"), Intent.CATEGORY_APP_CALENDAR),
    CONTACTS(setOf("contatti", "rubrica", "contacts"), Intent.CATEGORY_APP_CONTACTS),

    // «e-mail» si spezza in «e» e «mail»: basta «mail».
    EMAIL(setOf("email", "emails", "mail", "posta"), Intent.CATEGORY_APP_EMAIL),
    GALLERY(setOf("galleria", "gallery"), Intent.CATEGORY_APP_GALLERY),
    MAPS(setOf("mappe", "mappa", "maps"), Intent.CATEGORY_APP_MAPS),
    MESSAGES(setOf("messaggi", "messages", "messaging", "sms"), Intent.CATEGORY_APP_MESSAGING),
    MUSIC(setOf("musica", "music"), Intent.CATEGORY_APP_MUSIC),
    BROWSER(setOf("browser", "internet"), Intent.CATEGORY_APP_BROWSER),
    CAMERA(setOf("fotocamera", "fotocamere", "camera"), null),
    ;

    /**
     * L'intent con cui Android sceglie l'app per questo tipo. Senza il selettore del launcher: serve solo a
     * risolvere, non ad avviare, e così il confronto con i filtri delle app è quello diretto.
     */
    fun intent(): Intent = category?.let { Intent(Intent.ACTION_MAIN).addCategory(it) }
        ?: Intent(MediaStore.ACTION_IMAGE_CAPTURE)
}

/** Al massimo questi tipi per richiesta: una richiesta non deve esporre mezzo telefono. */
private const val MAX_KINDS_PER_REQUEST = 3

/** I tipi di app che la richiesta nomina, nell'ordine dell'enum. Parole intere, IT ed EN. */
internal fun appKindsIn(query: String): Set<AppKind> {
    if (query.isBlank()) return emptySet()
    val words = Regex("[a-z0-9]+").findAll(normalizeAppText(query)).map(MatchResult::value).toSet()
    return AppKind.entries.filter { kind -> kind.keywords.any(words::contains) }
        .take(MAX_KINDS_PER_REQUEST)
        .toCollection(LinkedHashSet())
}

/** L'app predefinita dell'utente per un tipo di app, o null se non ce n'è una sola scelta. */
internal fun interface DefaultAppLookup {
    fun defaultFor(kind: AppKind): InstalledAppCandidate?
}

/**
 * Chiede ad Android quale attività risponde all'intent del tipo. Vale come predefinita quella che il
 * sistema sceglie da sola: l'unica installata o quella che l'utente ha fissato. Se l'utente ha più app e
 * nessuna fissata, Android risponde col selettore di sistema (`android`): allora non c'è un predefinito
 * e i candidati restano quelli della richiesta (il modello chiede quale). Serve il `<queries>` del manifest.
 */
internal class PackageManagerDefaultApps(private val packageManager: PackageManager) : DefaultAppLookup {
    override fun defaultFor(kind: AppKind): InstalledAppCandidate? = runCatching {
        val intent = kind.intent()
        // Prima i soli filtri «predefiniti», poi tutti: alcune app dichiarano la categoria senza DEFAULT.
        listOf(PackageManager.MATCH_DEFAULT_ONLY, 0).firstNotNullOfOrNull { flags ->
            val found = packageManager.resolveActivity(intent, flags)
            val packageName = found?.activityInfo?.packageName
            if (found == null || packageName.isNullOrBlank() || packageName == SYSTEM_RESOLVER) {
                null
            } else {
                InstalledAppCandidate(
                    label = found.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName,
                    packageName = packageName,
                    isDefault = true,
                )
            }
        }
    }.getOrNull()

    private companion object {
        const val SYSTEM_RESOLVER = "android"
    }
}

/**
 * Aggiunge ai candidati già trovati l'app predefinita di ogni tipo che la richiesta nomina, segnata
 * [InstalledAppCandidate.isDefault], in testa. Una app già tra i candidati non si duplica: prende il
 * segno e tiene la sua etichetta. Senza tipi nominati o senza predefiniti i candidati restano com'erano.
 * Si manda al modello solo nome e package, mai l'elenco delle app.
 */
internal fun withDefaultApps(
    query: String,
    matched: List<InstalledAppCandidate>,
    defaults: DefaultAppLookup,
): List<InstalledAppCandidate> {
    val found = appKindsIn(query).mapNotNull { kind -> runCatching { defaults.defaultFor(kind) }.getOrNull() }
        .distinctBy(InstalledAppCandidate::packageName)
        .map { default ->
            val known = matched.firstOrNull { it.packageName == default.packageName }
            (known ?: default).copy(isDefault = true)
        }
    if (found.isEmpty()) return matched
    val defaultPackages = found.mapTo(HashSet(), InstalledAppCandidate::packageName)
    return found + matched.filterNot { it.packageName in defaultPackages }
}
