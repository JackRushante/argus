package dev.argus.automation.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

data class InstalledAppCandidate(
    val label: String,
    val packageName: String,
)

fun interface InstalledAppResolver {
    suspend fun candidatesFor(query: String): List<InstalledAppCandidate>
}

object EmptyInstalledAppResolver : InstalledAppResolver {
    override suspend fun candidatesFor(query: String): List<InstalledAppCandidate> = emptyList()
}

/**
 * Espone al compiler soltanto le app launcher che corrispondono deterministicamente alla richiesta,
 * mai l'inventario completo del telefono. Nessun dato viene persistito.
 */
class AndroidInstalledAppResolver @Inject constructor(
    @ApplicationContext context: Context,
) : InstalledAppResolver {
    private val appContext = context.applicationContext

    override suspend fun candidatesFor(query: String): List<InstalledAppCandidate> =
        withContext(Dispatchers.Default) {
            val apps = runCatching {
                val packageManager = appContext.packageManager
                appContext.getSystemService(LauncherApps::class.java)
                    .getActivityList(null, Process.myUserHandle())
                    .map { activity ->
                        InstalledAppCandidate(
                            label = activity.applicationInfo.loadLabel(packageManager).toString(),
                            packageName = activity.applicationInfo.packageName,
                        )
                    }
                    .distinctBy(InstalledAppCandidate::packageName)
            }.getOrDefault(emptyList())
            matchInstalledApps(query, apps)
        }
}

internal fun matchInstalledApps(
    query: String,
    apps: List<InstalledAppCandidate>,
    limit: Int = 5,
): List<InstalledAppCandidate> {
    if (query.isBlank() || limit <= 0) return emptyList()
    val normalizedQuery = normalizeAppText(query)
    val queryTokens = appTokens(normalizedQuery)
    if (queryTokens.isEmpty()) return emptyList()

    return apps.asSequence()
        .mapNotNull { app ->
            val normalizedPackage = app.packageName.lowercase(Locale.ROOT)
            val exactPackage = normalizedPackage in normalizedQuery
            val labelTokens = appTokens(normalizeAppText(app.label))
            val packageTokens = appTokens(normalizedPackage.replace('.', ' '))
            val tokenScore = queryTokens.fold(0) { total, queryToken ->
                total + ((labelTokens + packageTokens).maxOfOrNull { appToken ->
                    when {
                        queryToken == appToken -> 20
                        commonPrefixLength(queryToken, appToken) >= MIN_PREFIX_LENGTH -> 12
                        else -> 0
                    }
                } ?: 0)
            }
            val labelBoost = if (queryTokens.any { query -> labelTokens.any { it == query } }) 5 else 0
            val score = if (exactPackage) EXACT_PACKAGE_SCORE else tokenScore + labelBoost
            if (score < MIN_MATCH_SCORE) null else score to app
        }
        .sortedWith(
            compareByDescending<Pair<Int, InstalledAppCandidate>> { it.first }
                .thenBy { it.second.label.lowercase(Locale.ROOT) }
                .thenBy { it.second.packageName },
        )
        .take(limit)
        .map { it.second }
        .toList()
}

private fun normalizeAppText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)

private fun appTokens(value: String): Set<String> = TOKEN_REGEX.findAll(value)
    .map(MatchResult::value)
    .filter { it.length >= MIN_TOKEN_LENGTH && it !in GENERIC_TOKENS }
    .toSet()

private fun commonPrefixLength(left: String, right: String): Int {
    val limit = minOf(left.length, right.length)
    var index = 0
    while (index < limit && left[index] == right[index]) index += 1
    return index
}

private const val EXACT_PACKAGE_SCORE = 10_000
private const val MIN_MATCH_SCORE = 12
private const val MIN_PREFIX_LENGTH = 6
private const val MIN_TOKEN_LENGTH = 4
private val TOKEN_REGEX = Regex("[a-z0-9]+")
private val GENERIC_TOKENS = setOf(
    "android",
    "app",
    "apps",
    "application",
    "applicazione",
    "com",
    "google",
    "package",
    "pacchetto",
)
