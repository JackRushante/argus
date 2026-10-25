package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger

/**
 * Alias dei contatti in whitelist (D4). Al modello (cloud o bridge) non viaggia mai l'id reale della
 * conversazione (JID, hash di notifica): solo `contact_<n>`, con `n` posizione 1-based nella
 * whitelist del manifest. Dopo il compile, [restore] rimette l'id reale nella bozza PRIMA della
 * validazione locale. Un alias sconosciuto resta com'è: non è in whitelist e il validatore lo
 * rifiuta (`conversationId` fuori whitelist), quindi un modello che inventa un alias non ottiene
 * nulla.
 */
object ContactAliasMapper {
    private const val PREFIX = "contact_"

    fun aliasFor(index: Int): String = "$PREFIX${index + 1}"

    /** alias -> id reale, nell'ordine della whitelist del manifest. */
    fun aliasMap(contacts: List<WhitelistedContact>): Map<String, String> =
        contacts.mapIndexed { index, contact -> aliasFor(index) to contact.id }.toMap()

    fun restore(draft: AutomationDraft, contacts: List<WhitelistedContact>): AutomationDraft {
        val trigger = draft.trigger as? Trigger.Notification ?: return draft
        val alias = trigger.conversationId ?: return draft
        val realId = aliasMap(contacts)[alias] ?: return draft
        return draft.copy(trigger = trigger.copy(conversationId = realId))
    }

    fun restore(result: CompileResult, manifest: CapabilityManifest): CompileResult {
        val draft = result.draft ?: return result
        return result.copy(draft = restore(draft, manifest.whitelistedContacts))
    }
}
