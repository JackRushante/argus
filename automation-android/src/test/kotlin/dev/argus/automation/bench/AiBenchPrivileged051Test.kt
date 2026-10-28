package dev.argus.automation.bench

import dev.argus.engine.model.ActionTypeIds
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Il banco conosce le azioni privilegiate tipizzate 0.5.1: il telefono di prova con Shizuku le
 * pubblica, quello senza no, e i casi nuovi coprono ogni tipo e il rifiuto di toccare Argus.
 */
class AiBenchPrivileged051Test {
    private val eight = listOf(
        ActionTypeIds.APP_CONTROL, ActionTypeIds.SET_AIRPLANE, ActionTypeIds.SET_LOCATION, ActionTypeIds.SET_NFC,
        ActionTypeIds.SET_BATTERY_SAVER, ActionTypeIds.LOCK_SCREEN, ActionTypeIds.SCREENSHOT,
        ActionTypeIds.APP_PERMISSION,
    )

    private val cases = BenchCases.loadCompile()

    @Test
    fun `the bench phone publishes the eight with Shizuku and none without`() = runBlocking {
        val world = BenchWorld.create()
        val full = world.manifest(ManifestKind.FULL)
        val noShizuku = world.manifest(ManifestKind.NO_SHIZUKU)

        assertTrue(full.availableTools.containsAll(eight))
        assertTrue(eight.none { it in noShizuku.availableTools })
        assertTrue(eight.all { it in noShizuku.unavailableTools })
        assertTrue(eight.all { it in BenchVocab.ACTION_TYPES })
    }

    @Test
    fun `every new action has at least two cases, in both languages for the common ones`() {
        eight.forEach { type ->
            val own = cases.filter { case ->
                type in case.draft.actionsInclude || type in case.covers
            }
            assertTrue(own.size >= 2, "$type: ${own.size} casi")
        }
        listOf(
            ActionTypeIds.SET_AIRPLANE, ActionTypeIds.SET_LOCATION, ActionTypeIds.SET_BATTERY_SAVER,
            ActionTypeIds.APP_CONTROL, ActionTypeIds.APP_PERMISSION,
        ).forEach { type ->
            val languages = cases.filter { type in it.draft.actionsInclude }.map { it.lang }.toSet()
            assertEquals(setOf("it", "en"), languages, type)
        }
    }

    @Test
    fun `touching Argus is a refusal case in both languages`() {
        val refusals = cases.filter { it.id.startsWith("act51.refuse.") }
        assertEquals(setOf("it", "en"), refusals.map { it.lang }.toSet())
        refusals.forEach { case ->
            assertEquals(setOf(Outcome.NO_DRAFT), case.outcomes, case.id)
            assertTrue("argus" in case.text.lowercase(), case.id)
        }
    }
}
