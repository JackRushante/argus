package dev.argus.ui.model

import dev.argus.ui.components.defaultGrantSelection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sistema · Privilegi (0.5.0): contratti, selezione di default del dialogo, stringhe in due lingue. */
class PrivilegesUiTest {
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()

    private fun strings(xml: String): Map<String, String> =
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private val englishOwn = strings(english).filterKeys { it.startsWith("priv50_") }
    private val italianOwn = strings(italian).filterKeys { it.startsWith("priv50_") }

    private fun rows(vararg granted: Boolean) =
        PermanentGrantUi.entries.mapIndexed { index, grant -> PermanentGrantRow(grant, granted[index]) }

    @Test
    fun `the dialog pre-ticks only what is still to grant`() {
        assertEquals(
            setOf(PermanentGrantUi.WRITE_SECURE_SETTINGS, PermanentGrantUi.DND_ACCESS),
            defaultGrantSelection(rows(false, true, true, false)),
        )
        assertTrue(defaultGrantSelection(rows(true, true, true, true)).isEmpty())
        assertEquals(PermanentGrantUi.entries.toSet(), defaultGrantSelection(rows(false, false, false, false)))
    }

    @Test
    fun `granting is offered only with a backend and something missing`() {
        val missing = rows(false, true, true, true)
        val complete = rows(true, true, true, true)
        assertFalse(PrivilegedUi(PrivilegedBackendUi.NONE, grants = missing).canGrant)
        PrivilegedBackendUi.entries.filter { it != PrivilegedBackendUi.NONE }.forEach { backend ->
            assertTrue(PrivilegedUi(backend, grants = missing).canGrant, backend.name)
            assertFalse(PrivilegedUi(backend, grants = complete).canGrant, backend.name)
        }
        assertFalse(PrivilegedUi(PrivilegedBackendUi.SUI).canGrant, "rows not read yet")
    }

    @Test
    fun `the root switch reads as on for every state but off`() {
        assertFalse(PrivilegedUi(rootState = RootStateUi.OFF).rootEnabled)
        RootStateUi.entries.filter { it != RootStateUi.OFF }.forEach {
            assertTrue(PrivilegedUi(rootState = it).rootEnabled, it.name)
        }
    }

    @Test
    fun `the new state and callbacks default to nothing for existing screens`() {
        val callbacks: SettingsCallbacks = NoopSettingsCallbacksStub()
        callbacks.onSetRootEnabled(true)
        callbacks.onGrantPermanent(setOf(PermanentGrantUi.USAGE_STATS))
        assertEquals(PrivilegedUi(), PrivilegedUi())
    }

    @Test
    fun `no notification access entry among the permanent permissions`() {
        assertEquals(4, PermanentGrantUi.entries.size)
        assertTrue(PermanentGrantUi.entries.none { it.id.contains("listener") || it.id.contains("notification") })
        // Il dialogo lo dice con parole sue, in entrambe le lingue.
        assertTrue(englishOwn.getValue("priv50_dialog_listener_note").contains("Notification access"))
        assertTrue(italianOwn.getValue("priv50_dialog_listener_note").contains("accesso alle notifiche"))
    }

    @Test
    fun `sui is shown as Sui (root) in both languages`() {
        assertTrue(englishOwn.getValue("priv50_backend_sui").startsWith("Sui (root)"))
        assertTrue(italianOwn.getValue("priv50_backend_sui").startsWith("Sui (root)"))
    }

    @Test
    fun `privilege strings exist in both languages with the same placeholders and escaped apostrophes`() {
        assertTrue(englishOwn.isNotEmpty())
        assertEquals(englishOwn.keys, italianOwn.keys)
        englishOwn.forEach { (key, text) ->
            assertEquals(
                PLACEHOLDER.findAll(text).map { it.value }.toList(),
                PLACEHOLDER.findAll(italianOwn.getValue(key)).map { it.value }.toList(),
                key,
            )
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "apostrofo non escapato in $key")
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(italianOwn.getValue(key)), "apostrofo non escapato in it:$key")
        }
    }

    @Test
    fun `every grant and every state has its own strings`() {
        PermanentGrantUi.entries.forEach { grant ->
            val stem = when (grant) {
                PermanentGrantUi.WRITE_SECURE_SETTINGS -> "secure"
                PermanentGrantUi.WRITE_SETTINGS -> "write"
                PermanentGrantUi.USAGE_STATS -> "usage"
                PermanentGrantUi.DND_ACCESS -> "dnd"
            }
            assertTrue("priv50_grant_${stem}_title" in englishOwn, grant.name)
            assertTrue("priv50_grant_${stem}_body" in englishOwn, grant.name)
        }
        listOf("off", "checking", "active", "denied", "no_su").forEach {
            assertTrue("priv50_root_$it" in englishOwn, it)
        }
        listOf("none", "shizuku", "shell", "shizuku_root", "sui", "root").forEach {
            assertTrue("priv50_backend_$it" in englishOwn, it)
        }
    }

    /** Un'implementazione vuota: i metodi senza default non servono qui, basta chiamare quelli nuovi. */
    private class NoopSettingsCallbacksStub : SettingsCallbacks {
        override fun onEditBridgeUrl(url: String) = Unit
        override fun onTestConnection() = Unit
        override fun onOpenShizukuFix() = Unit
        override fun onOpenBatteryFix() = Unit
        override fun onOpenNotificationAccessFix() = Unit
        override fun onOpenLocationFix() = Unit
        override fun onRemoveContact(conversationId: String) = Unit
        override fun onAddContact() = Unit
        override fun onBudgetChange(maxPerHour: Int) = Unit
        override fun onRevokePrivacy() = Unit
        override fun onRerunOnboarding() = Unit
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>([^<]*)</string>""")
        val PLACEHOLDER = Regex("""%(\d+\$)?[sd]""")
    }
}
