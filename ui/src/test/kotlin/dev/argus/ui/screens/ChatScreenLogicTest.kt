package dev.argus.ui.screens

import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.ChatError
import dev.argus.ui.model.ChatItem
import dev.argus.ui.model.ChatState
import dev.argus.ui.model.DraftCardStatus
import dev.argus.ui.model.NoticeKind
import dev.argus.ui.model.RuleRender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatScreenLogicTest {
    private fun card(id: String) = ChatItem.DraftCard(
        draftId = id,
        rule = RuleRender(
            triggerLine = "Quando: ora",
            triggerIconKey = "time",
            conditionLines = emptyList(),
            actions = listOf(
                ActionRow("generative", "Rispondi con l'AI", null, false, null, isGenerative = true, requiresLiveConfirm = false),
            ),
            isGenerative = false,
            privacyNote = null,
        ),
        issues = emptyList(),
        status = DraftCardStatus.PROPOSED,
    )

    @Test
    fun `draft cards are keyed by draft id and everything else by position`() {
        assertEquals("draft:a3", chatItemKey(7, card("a3")))
        assertEquals("draft:a3", chatItemKey(0, card("a3")), "la card mantiene la chiave se cambia posizione")
        assertEquals("item:2", chatItemKey(2, ChatItem.UserMessage("ciao", "10:00")))
        assertEquals("item:3", chatItemKey(3, ChatItem.AssistantMessage("ok", "10:00")))
        assertEquals("item:4", chatItemKey(4, ChatItem.SystemNotice("info", NoticeKind.INFO)))
    }

    @Test
    fun `keys of a conversation are unique`() {
        val items = listOf(
            ChatItem.UserMessage("a", "10:00"),
            card("d1"),
            ChatItem.AssistantMessage("b", "10:01"),
            card("d2"),
            ChatItem.SystemNotice("n", NoticeKind.ERROR),
        )
        val keys = items.mapIndexed { index, item -> chatItemKey(index, item) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the technical detail shows the code of every explained variant`() {
        assertEquals("timeout", chatErrorDetail(ChatError.Timeout))
        assertEquals("network", chatErrorDetail(ChatError.BridgeUnreachable))
        assertEquals("draft_missing", chatErrorDetail(ChatError.MalformedReply("draft_missing")))
        assertEquals("auth", chatErrorDetail(ChatError.Auth))
        assertEquals("budget", chatErrorDetail(ChatError.Budget))
        assertEquals("rate_limit", chatErrorDetail(ChatError.RateLimit))
        assertEquals("output_truncated", chatErrorDetail(ChatError.OutputTruncated))
        assertEquals("context_truncated", chatErrorDetail(ChatError.ContextTruncated))
        assertEquals("HTTP 422", chatErrorDetail(ChatError.ServerRejected(422)))
        // Il messaggio del provider sta nel testo del banner, mai nel dettaglio tecnico da copiare.
        assertEquals("HTTP 400", chatErrorDetail(ChatError.ServerRejected(400, "x is not a valid model ID")))
        assertEquals("HTTP 503", chatErrorDetail(ChatError.ServiceUnavailable(503)))
        assertEquals("not_configured", chatErrorDetail(ChatError.NotConfigured))
        assertEquals("illegal_state_exception", chatErrorDetail(ChatError.Internal("illegal_state_exception")))
        assertNull(chatErrorDetail(ChatError.TooLong(40_000, 32_768)), "il messaggio si spiega da solo")
        assertNotEquals<ChatError>(ChatError.ServerRejected(400), ChatError.ServiceUnavailable(400))
    }

    private fun state(items: List<ChatItem>) = ChatState(
        items = items,
        input = "",
        sending = false,
        sendingElapsedSec = null,
        brainReachable = true,
        error = null,
    )

    @Test
    fun `clearing the conversation asks for confirmation only when there are messages to lose`() {
        assertFalse(hasClearableContent(state(emptyList())))
        assertTrue(hasClearableContent(state(listOf(ChatItem.UserMessage("ciao", "10:00")))))
        assertTrue(hasClearableContent(state(listOf(ChatItem.SystemNotice("info", NoticeKind.INFO)))))
        assertTrue(hasClearableContent(state(listOf(card("d1"), ChatItem.AssistantMessage("ok", "10:01")))))
    }

    @Test
    fun `pending proposals alone are not lost by clearing the conversation`() {
        // Le proposte in attesa restano comunque (sono il canale di approvazione): niente da confermare.
        assertFalse(hasClearableContent(state(listOf(card("d1"), card("d2")))))
    }
}
