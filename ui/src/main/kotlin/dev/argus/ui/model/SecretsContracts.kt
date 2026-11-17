package dev.argus.ui.model

/**
 * 0.8 · Sistema › Segreti. A saved secret as the screen shows it: its name, how many rules use it and when a
 * rule last read it. Never the value: the vault is write-only for the UI.
 */
data class SecretRowUi(
    val name: String,
    val usedBy: Int,
    /** Epoch millis of the last fire-time read; null = never used. */
    val lastUsedAtMillis: Long? = null,
)

data class SecretsState(
    val secrets: List<SecretRowUi> = emptyList(),
    val loading: Boolean = true,
    /** A save or a delete is running: the actions wait for it. */
    val busy: Boolean = false,
)

interface SecretsCallbacks {
    fun onBack()

    /**
     * Stores or replaces [value] under [name]. The value goes straight to the vault: it is never kept in a
     * state, a saved instance state or a log.
     */
    fun onSave(name: String, value: String)

    /** Deletes [name]: the rules that use it move to «needs review». */
    fun onDelete(name: String)
}
