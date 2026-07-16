package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Classificazione del tier di esecuzione per action type (decision record §7.3, piano P3-3 §1).
 * È il router esaustivo che separa ciò che gira con API Android normali (BASE) da ciò che
 * richiede lo shell privilegiato Shizuku (PRIVILEGED). Deve fallire pulito, mai bloccare le base.
 */
class ActionPrivilegeTest {

    @Test
    fun `privileged actions require the elevated shell`() {
        val privileged = listOf<Action>(
            Action.SetWifi(true),
            Action.SetBluetooth(true),
            Action.RunShell("id"),
            Action.Tap(1, 2),
            Action.InputText("x"),
        )
        privileged.forEach { action ->
            assertEquals(
                ActionPrivilege.PRIVILEGED,
                ActionPrivileges.of(action),
                "atteso PRIVILEGED per $action",
            )
        }
    }

    @Test
    fun `base actions run without shizuku`() {
        val base = listOf<Action>(
            Action.SetDnd(DndMode.OFF),
            Action.SetRinger("normal"),
            Action.LaunchApp("com.example"),
            Action.OpenUrl("https://example.org"),
            Action.ShowNotification("Argus", "ciao"),
            Action.WhatsAppReply("ok"),
            Action.CopyToClipboard(),
            Action.InvokeLlm(
                goal = "rispondi",
                contextSources = listOf("notification"),
                allowedTools = listOf("whatsapp_reply"),
                replyTargetSender = true,
            ),
            Action.InvokeLlmV2(
                goal = "rispondi",
                stateContext = emptyList(),
                allowedTools = listOf("whatsapp_reply"),
                replyTargetSender = true,
                timeoutMs = 60_000,
            ),
        )
        base.forEach { action ->
            assertEquals(
                ActionPrivilege.BASE,
                ActionPrivileges.of(action),
                "atteso BASE per $action",
            )
        }
    }

    @Test
    fun `requiresShizuku is true only for privileged actions`() {
        assertEquals(true, ActionPrivileges.requiresShizuku(Action.RunShell("id")))
        assertEquals(true, ActionPrivileges.requiresShizuku(Action.SetWifi(false)))
        assertEquals(false, ActionPrivileges.requiresShizuku(Action.LaunchApp("com.example")))
        assertEquals(false, ActionPrivileges.requiresShizuku(Action.SetDnd(DndMode.OFF)))
    }
}
