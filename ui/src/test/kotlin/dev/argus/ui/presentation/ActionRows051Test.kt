package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.AppControlOp
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.PrivilegedActionPolicy
import dev.argus.engine.model.Trigger
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.iconFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Approvazione e dettaglio delle azioni privilegiate 0.5.1: testo chiaro dai tipi, package sempre visibile. */
class ActionRows051Test {

    private fun rowOf(action: Action, language: RenderLanguage): ActionRow {
        val automation = Automation(
            id = AutomationId("a"),
            name = "a",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Immediate,
            actions = listOf(action),
        )
        return RuleRenderMapper.map(automation, language = language).actions.single()
    }

    private val youtube = "com.google.android.youtube"

    @Test
    fun `force stop reads like the sentence a user would say and shows the real package`() {
        val action = Action.AppControl(youtube, AppControlOp.FORCE_STOP)
        assertEquals("Force stop YouTube", rowOf(action, RenderLanguage.EN).label)
        assertEquals("Forza l'arresto di YouTube", rowOf(action, RenderLanguage.IT).label)
        RenderLanguage.entries.forEach { assertEquals(youtube, rowOf(action, it).detail) }
    }

    @Test
    fun `freeze and unfreeze say what happens to the app`() {
        val freeze = Action.AppControl(youtube, AppControlOp.FREEZE)
        val unfreeze = Action.AppControl(youtube, AppControlOp.UNFREEZE)
        assertEquals("Freeze (disable) YouTube", rowOf(freeze, RenderLanguage.EN).label)
        assertEquals("Congela (disattiva) YouTube", rowOf(freeze, RenderLanguage.IT).label)
        assertEquals("Unfreeze (re-enable) YouTube", rowOf(unfreeze, RenderLanguage.EN).label)
        assertEquals("Scongela (riattiva) YouTube", rowOf(unfreeze, RenderLanguage.IT).label)
    }

    @Test
    fun `an app that is not in the short list is shown by its package`() {
        val action = Action.AppControl("com.example.unknown", AppControlOp.FORCE_STOP)
        assertEquals("Force stop com.example.unknown", rowOf(action, RenderLanguage.EN).label)
        assertEquals("com.example.unknown", rowOf(action, RenderLanguage.EN).detail)
    }

    @Test
    fun `a revoked permission names the thing the user loses and the full permission`() {
        val revoke = Action.AppPermission(youtube, "android.permission.RECORD_AUDIO", grant = false)
        assertEquals("Revoke the microphone permission from YouTube", rowOf(revoke, RenderLanguage.EN).label)
        assertEquals("Revoca il microfono a YouTube", rowOf(revoke, RenderLanguage.IT).label)
        RenderLanguage.entries.forEach {
            assertEquals("android.permission.RECORD_AUDIO · $youtube", rowOf(revoke, it).detail)
        }
        val grant = Action.AppPermission(youtube, "android.permission.CAMERA", grant = true)
        assertEquals("Grant the camera permission to YouTube", rowOf(grant, RenderLanguage.EN).label)
        assertEquals("Concedi la fotocamera a YouTube", rowOf(grant, RenderLanguage.IT).label)
    }

    @Test
    fun `every permission of the closed list has a readable group in both languages`() {
        PrivilegedActionPolicy.RUNTIME_PERMISSIONS.forEach { permission ->
            val revoke = Action.AppPermission(youtube, permission, grant = false)
            RenderLanguage.entries.forEach { language ->
                val label = rowOf(revoke, language).label
                assertFalse("android.permission" in label, "$permission: $label")
                assertTrue(label.endsWith("YouTube"), label)
            }
        }
    }

    @Test
    fun `a permission outside the list is rendered with its full name, never softened`() {
        val action = Action.AppPermission(youtube, "android.permission.WRITE_SECURE_SETTINGS", grant = true)
        assertTrue("android.permission.WRITE_SECURE_SETTINGS" in rowOf(action, RenderLanguage.EN).label)
    }

    @Test
    fun `the switches read as plain sentences in both languages`() {
        fun labels(action: Action) = RenderLanguage.entries.map { rowOf(action, it).label }
        assertEquals(listOf("Turn on airplane mode", "Attiva la modalità aereo"), labels(Action.SetAirplane(true)))
        assertEquals(listOf("Turn off airplane mode", "Disattiva la modalità aereo"), labels(Action.SetAirplane(false)))
        assertEquals(listOf("Turn on location", "Attiva la posizione"), labels(Action.SetLocation(true)))
        assertEquals(listOf("Turn off NFC", "Disattiva l'NFC"), labels(Action.SetNfc(false)))
        assertEquals(
            listOf("Turn on battery saver", "Attiva il risparmio energetico"),
            labels(Action.SetBatterySaver(true)),
        )
        assertEquals(listOf("Lock the screen", "Blocca lo schermo"), labels(Action.LockScreen))
        assertEquals(listOf("Take a screenshot", "Cattura una schermata"), labels(Action.Screenshot))
    }

    @Test
    fun `none of the new rows is a shell command or needs the live confirmation`() {
        listOf(
            Action.AppControl(youtube, AppControlOp.FREEZE), Action.SetAirplane(true), Action.SetLocation(true),
            Action.SetNfc(true), Action.SetBatterySaver(true), Action.LockScreen, Action.Screenshot,
            Action.AppPermission(youtube, "android.permission.CAMERA", true),
        ).forEach { action ->
            val row = rowOf(action, RenderLanguage.EN)
            assertFalse(row.isShell, action.toString())
            assertNull(row.shellCommand, action.toString())
            assertFalse(row.isGenerative, action.toString())
            assertFalse(row.requiresLiveConfirm, action.toString())
        }
    }

    @Test
    fun `each new row has its own icon key`() {
        val keys = listOf(
            Action.AppControl(youtube, AppControlOp.FREEZE), Action.SetAirplane(true), Action.SetLocation(true),
            Action.SetNfc(true), Action.SetBatterySaver(true), Action.LockScreen, Action.Screenshot,
            Action.AppPermission(youtube, "android.permission.CAMERA", true),
        ).map { rowOf(it, RenderLanguage.EN).iconKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.none { it == "settings" || it == "shell" })
        // Nessuna riga ricade sull'icona generica.
        keys.forEach { assertNotEquals(iconFor("unknown_key"), iconFor(it), it) }
    }
}
