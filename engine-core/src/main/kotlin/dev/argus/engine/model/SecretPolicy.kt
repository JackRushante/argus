package dev.argus.engine.model

/**
 * The phone's secrets vault (0.8, [FeatureGate.HTTP_SECRETS]): tokens and passwords that a rule uses BY
 * NAME. A rule, a backup, an export and the AI only ever see the name; the value lives encrypted on the
 * phone and is read by the HTTP executor at fire time, straight into the header or query parameter.
 *
 * Pure rules shared by the vault, the validator, the executor and the UI, so the layers cannot drift.
 */
object SecretPolicy {
    /** A secret's name: the same shape as a variable name, so it reads naturally in chat and in review. */
    val NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,31}$")

    const val MAX_VALUE_CHARS = 4_096

    /** Nodes after which the visit stops: the validator rejects trees this large anyway. */
    private const val MAX_VISITED_NODES = 4_096

    fun isNameValid(name: String): Boolean = NAME_REGEX.matches(name)

    /**
     * 1..4096 characters, not blank, no control characters (a CR/LF would split a header). Characters
     * outside ASCII are accepted: a query parameter is percent-encoded; a header refuses them at fire time.
     */
    fun isValueValid(value: String): Boolean =
        value.length in 1..MAX_VALUE_CHARS && value.isNotBlank() && value.none { it.isISOControl() }

    /** The vault references of one request, headers first. */
    fun refsOf(action: Action.HttpRequest): List<SecretRef> =
        action.secretHeaders.orEmpty() + action.secretQuery.orEmpty()

    /** true if [action] reads at least one value from the vault. */
    fun carriesSecrets(action: Action.HttpRequest): Boolean =
        !action.secretHeaders.isNullOrEmpty() || !action.secretQuery.isNullOrEmpty()

    /** Names of the secrets [draft] uses (actions, nested ones and exit actions), in order, no duplicates. */
    fun namesUsedBy(draft: AutomationDraft): Set<String> =
        namesUsedBy(draft.actions + draft.exit?.actions.orEmpty())

    fun namesUsedBy(automation: Automation): Set<String> =
        namesUsedBy(automation.actions + automation.exit?.actions.orEmpty())

    /** Iterative and bounded visit: no recursion on a tree that may not be validated yet. */
    fun namesUsedBy(actions: List<Action>): Set<String> {
        val names = linkedSetOf<String>()
        val pending = ArrayDeque<Action>()
        actions.forEach(pending::addLast)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VISITED_NODES) {
            when (val action = pending.removeFirst()) {
                is Action.HttpRequest -> refsOf(action).forEach { names += it.secret }
                is Action.If -> {
                    action.then.forEach(pending::addLast)
                    action.orElse.forEach(pending::addLast)
                }
                is Action.While -> action.body.forEach(pending::addLast)
                else -> Unit
            }
        }
        return names
    }
}
