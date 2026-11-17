package dev.argus.nav

import dev.argus.automation.health.HealthDeepLinks
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.7.2: il tocco di un avviso di salute apre il Dettaglio o Sistema SOLO passando dal trampolino non
 * esportato. `MainActivity` è esportata: un intent con gli stessi extra mandato a lei non apre nulla.
 */
class AppDeepLinkTest {
    private val open = HealthDeepLinks.ACTION_OPEN
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val mainActivity = File("src/main/kotlin/dev/argus/MainActivity.kt").readText()

    @Test
    fun `the trampoline action with a rule id opens the rule detail`() {
        assertEquals(AppDeepLink.Rule("rule-1"), AppDeepLink.parse(open, "rule-1", openSystem = false))
        assertEquals(AppDeepLink.Rule("rule-1"), AppDeepLink.parse(open, "rule-1", openSystem = true))
    }

    @Test
    fun `the trampoline action with the system flag opens System`() {
        assertEquals(AppDeepLink.System, AppDeepLink.parse(open, null, openSystem = true))
        assertEquals(AppDeepLink.System, AppDeepLink.parse(open, " ", openSystem = true))
    }

    @Test
    fun `nothing, an oversized id or another action opens nothing`() {
        assertNull(AppDeepLink.parse(open, null, openSystem = false))
        assertNull(AppDeepLink.parse(open, "x".repeat(129), openSystem = false))
        // Un avvio dal launcher (MAIN) o un'azione inventata, anche con gli extra giusti.
        assertNull(AppDeepLink.parse("android.intent.action.MAIN", "rule-1", openSystem = true))
        assertNull(AppDeepLink.parse(null, "rule-1", openSystem = true))
    }

    @Test
    fun `an intent with the alert extras sent to MainActivity navigates nowhere`() {
        // MainActivity non legge il suo intent né onNewIntent: gli extra arrivano ma nessuno li guarda.
        listOf("getStringExtra", "getBooleanExtra", "getExtras", ".extras", "onNewIntent", "AppDeepLink", "HealthDeepLinks", "intent")
            .forEach { assertFalse(it in mainActivity, "MainActivity non deve leggere «$it»") }
        // L'unica destinazione che il NavHost segue la scrive soltanto il trampolino.
        val writers = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "HealthAlertNavigation.post(" in it.readText() }
            .map { it.name }
            .toList()
        assertEquals(listOf("HealthAlertOpenActivity.kt"), writers)
    }

    @Test
    fun `the trampoline is not exported, listens to no action and is the class the notifier targets`() {
        val open = Regex("""<activity\s+[^>]*android:name="\.nav\.HealthAlertOpenActivity"[^>]*/>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value ?: error("trampolino non dichiarato o con intent-filter")
        assertTrue("""android:exported="false"""" in open)
        assertEquals("dev.argus.nav.HealthAlertOpenActivity", HealthDeepLinks.OPEN_ACTIVITY_CLASS)
        val source = File("src/main/kotlin/dev/argus/nav/HealthAlertOpenActivity.kt").readText()
        assertTrue("package dev.argus.nav" in source && "class HealthAlertOpenActivity" in source)
    }
}
