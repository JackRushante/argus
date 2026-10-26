package dev.argus.automation.manual

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logica di widget, riquadri e scorciatoia (F8/F21) estratta dai componenti Android. */
class ManualCatalogAndTilesTest {
    // --- Elenco del widget ---

    @Test
    fun `the catalog lists only armed manual rules, sorted by name, each with its own token`() = runTest {
        val store = MapStore(
            manualRule("b", name = "Zeta"),
            manualRule("a", name = "alfa"),
            manualRule("c", name = "Beta"),
            manualRule("time", name = "Orario", trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC")),
            manualRule("off", name = "Spenta", status = AutomationStatus.DISABLED),
            manualRule("rev", name = "Da rivedere", status = AutomationStatus.NEEDS_REVIEW),
        )
        val tokens = FakeTokenStore()

        val entries = ManualRuleCatalog(store, tokens).entries()

        assertEquals(listOf("alfa", "Beta", "Zeta"), entries.map { it.name })
        assertEquals(listOf("a", "c", "b"), entries.map { it.id })
        entries.forEach { assertTrue(tokens.isValid(AutomationId(it.id), it.token), it.id) }
        assertEquals(entries.size, entries.map { it.token }.toSet().size, "un token per regola")
    }

    @Test
    fun `the catalog caps the number of rows and is empty without manual rules`() = runTest {
        val many = (1..45).map { manualRule("m$it", name = "Regola $it") }
        assertEquals(
            ManualRuleCatalog.MAX_ENTRIES,
            ManualRuleCatalog(MapStore(*many.toTypedArray()), FakeTokenStore()).entries().size,
        )
        assertEquals(emptyList(), ManualRuleCatalog(MapStore(), FakeTokenStore()).entries())
    }

    @Test
    fun `runnable returns the rule only while it is an armed manual one`() = runTest {
        val store = MapStore(manualRule("m1"), manualRule("t", trigger = Trigger.Immediate))
        val catalog = ManualRuleCatalog(store, FakeTokenStore())

        assertEquals("m1", catalog.runnable("m1")?.id?.value)
        assertNull(catalog.runnable("t"))
        assertNull(catalog.runnable("missing"))
        store.disable(AutomationId("m1"))
        assertNull(catalog.runnable("m1"))
    }

    // --- Riquadro «Argus: esegui regola» ---

    @Test
    fun `the manual tile is labelled with the rule name and unavailable without a valid rule`() {
        val ready = QuickTileModels.manualTile(manualRule("m1", name = "Apri il garage"), paused = false)
        assertEquals("Apri il garage", ready.ruleName)
        assertEquals(TileUiState.INACTIVE, ready.state)
        assertEquals(TileNote.NONE, ready.note)

        assertEquals(TileNote.PAUSED, QuickTileModels.manualTile(manualRule("m1"), paused = true).note)

        listOf(
            null,
            manualRule("t", trigger = Trigger.Immediate),
            manualRule("off", status = AutomationStatus.DISABLED),
        ).forEach { rule ->
            val spec = QuickTileModels.manualTile(rule, paused = false)
            assertNull(spec.ruleName)
            assertEquals(TileUiState.UNAVAILABLE, spec.state)
            assertEquals(TileNote.NO_RULE, spec.note)
        }
    }

    @Test
    fun `a very long rule name is cut for the tile label`() {
        val spec = QuickTileModels.manualTile(manualRule("m1", name = "x".repeat(200)), paused = false)
        assertEquals(QuickTileModels.MAX_LABEL_CHARS, spec.ruleName?.length)
    }

    @Test
    fun `the pause tile is active exactly while paused`() {
        assertEquals(TileUiState.ACTIVE, QuickTileModels.pauseTile(true).state)
        assertEquals(TileUiState.INACTIVE, QuickTileModels.pauseTile(false).state)
    }

    private fun controller(rig: Rig, selection: FakeTileSelection) = QuickTileController(
        selection = selection,
        catalog = ManualRuleCatalog(rig.store, rig.tokens),
        runner = rig.runner,
        pause = rig.pause,
    )

    @Test
    fun `tapping the manual tile runs the chosen rule and does nothing without one`() = runTest {
        val rig = Rig(listOf(manualRule("m1"), manualRule("m2")))
        val selection = FakeTileSelection()
        val tiles = controller(rig, selection)

        assertNull(tiles.onManualTileClick())
        assertTrue(rig.dispatcher.envelopes.isEmpty())

        selection.select("m2")
        val result = assertIs<ManualRunResult.Accepted>(tiles.onManualTileClick())
        assertEquals("Apri il garage", result.ruleName)
        assertEquals(1, rig.dispatcher.envelopes.size)

        // La regola scelta è stata disattivata: il tocco non parte.
        rig.store.disable(AutomationId("m2"))
        assertNull(tiles.onManualTileClick())
        assertEquals(1, rig.dispatcher.envelopes.size)
        assertEquals(TileUiState.UNAVAILABLE, tiles.manualSpec().state)
    }

    @Test
    fun `tapping the pause tile toggles the global switch`() = runTest {
        val rig = Rig(emptyList())
        val tiles = controller(rig, FakeTileSelection())

        assertEquals(TileUiState.INACTIVE, tiles.pauseSpec().state)
        assertTrue(tiles.onPauseTileClick())
        assertTrue(rig.pause.isPaused())
        assertEquals(TileUiState.ACTIVE, tiles.pauseSpec().state)
        assertFalse(tiles.onPauseTileClick())
        assertFalse(rig.pause.isPaused())
    }

    // --- Faccia delle schermate ---

    private fun service(rig: Rig, pinner: FakePinner, selection: FakeTileSelection = FakeTileSelection()) =
        ManualEntryService(
            catalog = ManualRuleCatalog(rig.store, rig.tokens),
            tokens = rig.tokens,
            runner = rig.runner,
            pinner = pinner,
            tile = selection,
            pause = rig.pause,
        )

    @Test
    fun `adding to the Home screen hands the launcher the rule with its token`() = runTest {
        val rig = Rig(listOf(manualRule("m1", name = "Apri il garage")))
        val pinner = FakePinner()

        assertEquals(AddToHomeResult.REQUESTED, service(rig, pinner).addToHome("m1"))

        val entry = pinner.requested.single()
        assertEquals("m1", entry.id)
        assertEquals("Apri il garage", entry.name)
        assertTrue(rig.tokens.isValid(AutomationId("m1"), entry.token))
    }

    @Test
    fun `adding to the Home screen is refused for a rule that is not armed manual or on a launcher without support`() = runTest {
        val rig = Rig(listOf(manualRule("t", trigger = Trigger.Immediate), manualRule("m1")))

        assertEquals(AddToHomeResult.UNAVAILABLE, service(rig, FakePinner()).addToHome("t"))
        assertEquals(AddToHomeResult.UNAVAILABLE, service(rig, FakePinner()).addToHome("missing"))
        val unsupported = FakePinner(supported = false)
        assertEquals(AddToHomeResult.UNSUPPORTED, service(rig, unsupported).addToHome("m1"))
        assertTrue(unsupported.requested.isEmpty())
        assertFalse(service(rig, unsupported).canPinShortcut())
        assertEquals(
            AddToHomeResult.UNSUPPORTED,
            service(rig, FakePinner(accepts = false)).addToHome("m1"),
        )
    }

    @Test
    fun `choosing the tile rule and forgetting a deleted rule`() = runTest {
        val rig = Rig(listOf(manualRule("m1"), manualRule("m2")))
        val selection = FakeTileSelection()
        val service = service(rig, FakePinner(), selection)

        service.useInTile("m1")
        assertEquals("m1", service.tileRuleId.value)

        val token = rig.tokens.tokenFor(AutomationId("m1"))
        service.forget("m2")
        assertEquals("m1", service.tileRuleId.value, "altra regola: la scelta resta")
        service.forget("m1")
        assertNull(service.tileRuleId.value)
        assertNotEquals(token, rig.tokens.tokenFor(AutomationId("m1")), "il vecchio token non vale più")
        assertFalse(rig.tokens.isValid(AutomationId("m1"), token))
    }

    @Test
    fun `running from the app goes through the same runner as every other entry`() = runTest {
        val rig = Rig(listOf(manualRule("m1")), paused = true)
        val service = service(rig, FakePinner())

        assertTrue(assertIs<ManualRunResult.Accepted>(service.run("m1")).paused)
        assertTrue(service.paused.value)
        assertIs<ManualRunResult.Rejected>(service.run("nope"))
    }
}
