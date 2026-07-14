package dev.argus.engine.notification

import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.flow.Flow
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Snapshot Android-neutral: nessun Parcelable o handle RemoteInput entra nel parser di dominio. */
data class NotificationSnapshot(
    val packageName: String,
    val notificationKey: String,
    val shortcutId: String?,
    /** URI candidate ordinate: sender dell'ultimo messaggio, poi people metadata. */
    val personUris: List<String>,
    val sender: String?,
    val title: String?,
    val messageText: String?,
    val fallbackText: String?,
    /** null significa che EXTRA_IS_GROUP_CONVERSATION non era presente. */
    val isGroup: Boolean?,
    val isGroupSummary: Boolean,
    val postedAtMillis: Long,
)

enum class ConversationIdentitySource(val wireName: String) {
    SHORTCUT("shortcut"),
    PERSON_URI("person"),
}

/** Metadati bounded per il picker locale. Il testo della notifica non è rappresentabile qui. */
data class ObservedConversation(
    val id: String,
    val packageName: String,
    val displayName: String,
    val isGroup: Boolean?,
    val lastSeenAtMillis: Long,
) {
    init {
        require(id.isNotBlank() && id.length <= 512 && id.none(Char::isISOControl)) {
            "Conversation ID osservato non valido"
        }
        require(PACKAGE_NAME.matches(packageName)) { "Package conversazione non valido" }
        require(displayName.isNotBlank() && displayName.length <= 120 && displayName.none(Char::isISOControl)) {
            "Display name conversazione non valido"
        }
        require(lastSeenAtMillis >= 0) { "lastSeenAtMillis non può essere negativo" }
    }

    private companion object {
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
    }
}

interface ObservedConversationStore {
    suspend fun recent(limit: Int = 200): List<ObservedConversation>
    fun observeRecent(limit: Int = 200): Flow<List<ObservedConversation>>
    suspend fun record(conversation: ObservedConversation)
    /** Cancellazione integrale dei metadati osservati: usata dalla revoca del consenso. */
    suspend fun clear()
}

data class ParsedNotification(
    val envelope: TriggerEnvelope,
    val observedConversation: ObservedConversation?,
    val identitySource: ConversationIdentitySource?,
)

/**
 * Trasforma metadati platform in un evento fail-closed. Shortcut e Person URI vengono hashati e
 * namespaced col package: restano stabili per il matching senza persistere l'identificatore raw.
 */
class NotificationEventParser(
    private val deniedPackages: Set<String> = setOf(ARGUS_PACKAGE),
) {
    fun parse(snapshot: NotificationSnapshot): ParsedNotification? {
        val packageName = snapshot.packageName.trim()
        if (!PACKAGE_NAME.matches(packageName) || packageName in deniedPackages || snapshot.isGroupSummary) {
            return null
        }
        // La key è un identificatore opaco DI SISTEMA e può contenere caratteri di controllo:
        // il tag WhatsApp reale è Base64 con newline finale (caratterizzazione P1-7). Va
        // conservata intatta per il matching di registry/gateway; nell'event ID entra solo
        // come digest e non tocca mai log o persistenza in chiaro.
        val notificationKey = snapshot.notificationKey.takeIf {
            it.isNotBlank() && it.length <= MAX_NOTIFICATION_KEY_CHARS
        } ?: return null
        val text = snapshot.messageText.safeText(MAX_TEXT_CHARS)
            ?: snapshot.fallbackText.safeText(MAX_TEXT_CHARS)
            ?: return null
        val sender = snapshot.sender.safeLabel(MAX_LABEL_CHARS)
        val title = snapshot.title.safeLabel(MAX_TITLE_CHARS)
        val identity = stableIdentity(packageName, snapshot)
        val conversationId = identity?.first
        val event = TriggerEvent.NotificationPosted(
            pkg = packageName,
            conversationId = conversationId,
            sender = sender,
            title = title,
            text = text,
            isGroup = snapshot.isGroup,
            notificationKey = notificationKey,
        )
        val eventId = TriggerEventId(
            "notification:" + digest(
                packageName,
                notificationKey,
                snapshot.postedAtMillis.coerceAtLeast(0).toString(),
                conversationId.orEmpty(),
                sender.orEmpty(),
                title.orEmpty(),
                text,
                snapshot.isGroup?.toString().orEmpty(),
            ),
        )
        val observed = conversationId?.let {
            ObservedConversation(
                id = it,
                packageName = packageName,
                displayName = conversationDisplayName(snapshot.isGroup, sender, title),
                isGroup = snapshot.isGroup,
                lastSeenAtMillis = snapshot.postedAtMillis.coerceAtLeast(0),
            )
        }
        return ParsedNotification(
            envelope = TriggerEnvelope(eventId, event),
            observedConversation = observed,
            identitySource = identity?.second,
        )
    }

    private fun stableIdentity(
        packageName: String,
        snapshot: NotificationSnapshot,
    ): Pair<String, ConversationIdentitySource>? {
        val shortcut = snapshot.shortcutId.safeIdentityCandidate()
        if (shortcut != null) {
            return stableConversationId(ConversationIdentitySource.SHORTCUT, packageName, shortcut) to
                ConversationIdentitySource.SHORTCUT
        }
        val personUri = snapshot.personUris.firstNotNullOfOrNull { it.safeIdentityCandidate() }
            ?: return null
        return stableConversationId(ConversationIdentitySource.PERSON_URI, packageName, personUri) to
            ConversationIdentitySource.PERSON_URI
    }

    private fun stableConversationId(
        source: ConversationIdentitySource,
        packageName: String,
        raw: String,
    ): String = "${source.wireName}:$packageName:${digest(raw)}"

    private fun conversationDisplayName(isGroup: Boolean?, sender: String?, title: String?): String =
        when (isGroup) {
            true -> title ?: sender
            false -> sender ?: title
            null -> title ?: sender
        } ?: "Conversazione"

    private fun String?.safeIdentityCandidate(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_IDENTITY_SOURCE_CHARS && it.none(Char::isISOControl) }

    private fun String?.safeText(maximum: Int): String? = this
        ?.filter { !it.isISOControl() || it == '\n' || it == '\t' }
        ?.trim()
        ?.take(maximum)
        ?.takeIf(String::isNotEmpty)

    private fun String?.safeLabel(maximum: Int): String? = this
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(maximum)
        ?.takeIf(String::isNotEmpty)

    private fun digest(vararg values: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            messageDigest.update(value.toByteArray(StandardCharsets.UTF_8))
            messageDigest.update(0)
        }
        return messageDigest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ARGUS_PACKAGE = "dev.argus"
        const val MAX_NOTIFICATION_KEY_CHARS = 1_024
        const val MAX_IDENTITY_SOURCE_CHARS = 2_048
        const val MAX_TEXT_CHARS = 4_096
        const val MAX_LABEL_CHARS = 120
        const val MAX_TITLE_CHARS = 512
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
    }
}
