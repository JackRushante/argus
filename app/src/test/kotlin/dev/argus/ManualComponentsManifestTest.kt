package dev.argus

import dev.argus.automation.manual.ManualIntents
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Avvio manuale (F8) e pausa (F21): i componenti Android che il sistema istanzia sono dichiarati con
 * la superficie minima. Il trampolino che lancia le regole NON è esportato; i riquadri si legano solo
 * col permesso di sistema; il widget è esportato solo per gli aggiornamenti e non esegue nulla.
 */
class ManualComponentsManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    /** Il blocco XML di un componente, dal tag di apertura alla chiusura (anche autochiudente). */
    private fun component(tag: String, name: String): String {
        val open = Regex("""<$tag\s+[^>]*android:name="$name"[^>]*?(/>|>)""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest) ?: error("componente $name non dichiarato")
        if (open.groupValues[1] == "/>") return open.value
        val end = manifest.indexOf("</$tag>", open.range.last)
        return manifest.substring(open.range.first, end + "</$tag>".length)
    }

    @Test
    fun `the trampoline activity is not exported and listens to no external action`() {
        val activity = component("activity", """\.manual\.ManualRunActivity""")

        assertTrue("""android:exported="false"""" in activity)
        assertFalse("intent-filter" in activity)
        assertTrue("""android:excludeFromRecents="true"""" in activity)
    }

    @Test
    fun `the class name the shortcuts and widgets point at is the declared trampoline`() {
        assertEquals("dev.argus.manual.ManualRunActivity", ManualIntents.RUN_ACTIVITY_CLASS)
        val source = File("src/main/kotlin/dev/argus/manual/ManualRunActivity.kt").readText()
        assertTrue("package dev.argus.manual" in source)
        assertTrue("class ManualRunActivity" in source)
        // Il trampolino accetta solo la sua azione e passa dal runner che verifica il token.
        assertTrue("ManualIntents.ACTION_RUN" in source)
        assertTrue("runFromIntent(ruleId, token)" in source)
    }

    @Test
    fun `the quick settings tiles can be bound only by the system`() {
        listOf("""\.manual\.ManualRuleTileService""", """\.manual\.PauseTileService""").forEach { name ->
            val service = component("service", name)
            assertTrue(
                """android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"""" in service,
                "tile $name senza il permesso di sistema",
            )
            assertTrue("android.service.quicksettings.action.QS_TILE" in service)
        }
    }

    @Test
    fun `the widget list service is private and bound only by the system`() {
        val service = component("service", """\.manual\.ManualWidgetService""")

        assertTrue("""android:exported="false"""" in service)
        assertTrue("""android:permission="android.permission.BIND_REMOTEVIEWS"""" in service)
    }

    @Test
    fun `the widget receiver answers only to the system update broadcast`() {
        val receiver = component("receiver", """\.manual\.ManualWidgetProvider""")

        assertTrue("android.appwidget.action.APPWIDGET_UPDATE" in receiver)
        assertEquals(1, Regex("<action ").findAll(receiver).count(), "nessun'altra azione")
        assertTrue("@xml/manual_widget_info" in receiver)
    }

    @Test
    fun `no manual component declares a custom exported action`() {
        // Le azioni custom dell'avvio manuale viaggiano solo su intent espliciti verso il trampolino.
        assertFalse(
            Regex("""<action\s+android:name="${Regex.escape(ManualIntents.ACTION_RUN)}"""").containsMatchIn(manifest),
            "ACTION_RUN non deve comparire in nessun intent-filter",
        )
    }

    @Test
    fun `every string the manifest and the widget layouts use exists in both languages`() {
        val english = File("src/main/res/values/strings.xml").readText()
        val italian = File("src/main/res/values-it/strings.xml").readText()
        val files = listOf(
            "src/main/AndroidManifest.xml",
            "src/main/res/layout/manual_widget.xml",
            "src/main/res/layout/manual_widget_item.xml",
            "src/main/res/xml/manual_widget_info.xml",
        )
        val used = files.flatMap { path ->
            Regex("""@string/([a-z0-9_]+)""").findAll(File(path).readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue(used.isNotEmpty())
        used.forEach { key ->
            assertTrue("""name="$key"""" in english, "manca in inglese: $key")
            assertTrue("""name="$key"""" in italian, "manca in italiano: $key")
        }
    }

    @Test
    fun `the widget layouts only use views a RemoteViews can inflate`() {
        val allowed = setOf("LinearLayout", "FrameLayout", "TextView", "ListView")
        listOf("src/main/res/layout/manual_widget.xml", "src/main/res/layout/manual_widget_item.xml").forEach { path ->
            val views = Regex("""<([A-Za-z]+)[\s>/]""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()
            assertTrue(allowed.containsAll(views), "$path usa viste non ammesse: ${views - allowed}")
        }
    }
}
