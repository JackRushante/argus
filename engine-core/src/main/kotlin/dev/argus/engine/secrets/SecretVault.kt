package dev.argus.engine.secrets

import kotlinx.coroutines.flow.Flow

/** What the UI may know about a saved secret: its name and when a rule last read it. Never the value. */
data class SecretEntry(
    val name: String,
    /** Epoch millis of the last read at fire time; null = never used. */
    val lastUsedAtMillis: Long? = null,
)

/**
 * Fire-time read of a secret's value, for the HTTP executor ONLY. The value goes straight into the
 * request: never into a variable, the journal, an exception or a log. null = no such secret (or it can no
 * longer be decrypted): the action fails closed.
 */
fun interface SecretValueSource {
    suspend fun valueFor(name: String): String?
}

/**
 * The phone's secrets vault. Write-only for the UI: [save] stores or replaces a value, [delete] removes it,
 * and nothing but [SecretValueSource.valueFor] ever returns one. Implementations keep values encrypted at
 * rest, out of backups, and record the last use when [valueFor] succeeds.
 */
interface SecretVault : SecretValueSource {
    /** Saved secrets, sorted by name. */
    fun observe(): Flow<List<SecretEntry>>

    /** Saved secrets, sorted by name. */
    suspend fun entries(): List<SecretEntry>

    /** Names of the saved secrets. */
    suspend fun names(): Set<String> = entries().mapTo(sortedSetOf()) { it.name }

    /** Stores or replaces [value] under [name]; false if either is invalid or the write fails. */
    suspend fun save(name: String, value: String): Boolean

    /** Removes [name]; false if it was not there or the write fails. */
    suspend fun delete(name: String): Boolean
}
