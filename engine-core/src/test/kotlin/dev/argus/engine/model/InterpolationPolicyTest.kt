package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterpolationPolicyTest {

    @Test fun `parse extracts valid refs and flags malformed markers`() {
        val ok = InterpolationPolicy.parse("Ciao \${nome}, saldo \${saldo_1}")
        assertEquals(listOf("nome", "saldo_1"), ok.refs)
        assertFalse(ok.malformed)

        // Nome fuori regex (maiuscolo) → il ${ resta non-matchato ⇒ malformed.
        val badName = InterpolationPolicy.parse("valore \${Nome}")
        assertTrue(badName.refs.isEmpty())
        assertTrue(badName.malformed)

        // Parentesi non chiusa → malformed.
        val unclosed = InterpolationPolicy.parse("valore \${nome")
        assertTrue(unclosed.malformed)

        // Nessuna interpolazione.
        assertFalse(InterpolationPolicy.containsInterpolation("testo statico"))
        assertTrue(InterpolationPolicy.containsInterpolation("x \${y}"))
    }

    @Test fun `text fields are classified as local sinks or authority sinks`() {
        // show_notification.title/text e whatsapp_reply.text e invoke_llm.goal = SINK.
        val notif = InterpolationPolicy.textFields(Action.ShowNotification("t", "x"))
        assertTrue(notif.all { it.cls == InterpolationPolicy.FieldClass.SINK })
        assertEquals(setOf("title", "text"), notif.map { it.label }.toSet())

        assertEquals(
            InterpolationPolicy.FieldClass.SINK,
            InterpolationPolicy.textFields(Action.WhatsAppReply("y")).single().cls,
        )
        assertEquals(
            InterpolationPolicy.FieldClass.SINK,
            InterpolationPolicy.textFields(
                Action.InvokeLlm("g", listOf("notification"), listOf("whatsapp_reply"), true),
            ).single { it.label == "goal" }.cls,
        )

        // AUTHORITY: run_shell.cmd, open_url.url, launch_app.pkg, input_text.text, write_setting.*.
        assertEquals(
            InterpolationPolicy.FieldClass.AUTHORITY,
            InterpolationPolicy.textFields(Action.RunShell("id")).single().cls,
        )
        assertEquals(
            InterpolationPolicy.FieldClass.AUTHORITY,
            InterpolationPolicy.textFields(Action.OpenUrl("https://x")).single().cls,
        )
        assertTrue(
            InterpolationPolicy.textFields(
                Action.WriteSetting(SettingNamespace.SYSTEM, "k", "v"),
            ).all { it.cls == InterpolationPolicy.FieldClass.AUTHORITY },
        )
    }

    @Test fun `local notification and alarm labels are sinks`() {
        val action = Action.InvokeLlm(
            goal = "g",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
            notificationTitle = "Titolo",
        )
        val fields = InterpolationPolicy.textFields(action)
        assertEquals(InterpolationPolicy.FieldClass.SINK, fields.single { it.label == "goal" }.cls)
        assertEquals(InterpolationPolicy.FieldClass.SINK, fields.single { it.label == "notificationTitle" }.cls)
        assertEquals(
            InterpolationPolicy.FieldClass.SINK,
            InterpolationPolicy.textFields(Action.SetAlarm(8, 0, "Sveglia \${name}")).single().cls,
        )
        assertEquals(
            InterpolationPolicy.FieldClass.SINK,
            InterpolationPolicy.textFields(Action.SetTimer(60, "Timer \${name}")).single().cls,
        )
    }

    @Test fun `control flow containers expose no interpolatable fields`() {
        assertTrue(
            InterpolationPolicy.textFields(
                Action.While(Condition.VarCompare("x", CmpOp.EQ, "1"), listOf(Action.SetWifi(true)), 1),
            ).isEmpty(),
        )
    }
}
