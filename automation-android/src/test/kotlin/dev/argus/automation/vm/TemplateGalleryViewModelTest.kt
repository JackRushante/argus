package dev.argus.automation.vm

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.RenderLanguageSource
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.backup.BackupDocumentIo
import dev.argus.data.backup.RuleTemplateCatalog
import dev.argus.data.backup.RulesBackupService
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.safety.DraftId
import dev.argus.ui.model.TemplateBlankUi
import dev.argus.ui.model.TemplateCategoryUi
import dev.argus.ui.model.TemplateNeedUi
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 0.7.2, galleria dei modelli: le card dicono cosa serve (dalle capacità vere della regola) e cosa resta da
 * completare; «Usa questo modello» passa dall'import e apre l'approvazione di una bozza `IMPORT`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateGalleryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private val viewModels = mutableListOf<TemplateGalleryViewModel>()

    /** Gli asset veri del modulo `data`, dove i modelli vivono. */
    private val catalog = RuleTemplateCatalog { path -> File("../data/src/main/assets", path).inputStream() }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        repository = RoomDraftRepository(db)
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the cards say what each template needs and what is left to complete`() = runTest {
        val viewModel = viewModel(RenderLanguage.IT)
        awaitUntil("modelli caricati") { !viewModel.state.value.loading }

        val cards = viewModel.state.value.templates.associateBy { it.id }
        assertTrue(cards.size >= 10, "modelli: ${cards.size}")

        val battery = cards.getValue("battery-low-saver")
        assertEquals("Batteria al 20%: risparmio", battery.title)
        assertEquals(TemplateCategoryUi.BATTERY, battery.category)
        assertEquals(listOf(TemplateNeedUi.PRIVILEGED, TemplateNeedUi.WRITE_SETTINGS), battery.needs)

        val ai = cards.getValue("messages-ai-busy-reply")
        assertTrue(ai.isGenerative)
        assertEquals(listOf(TemplateNeedUi.NOTIFICATION_ACCESS), ai.needs)
        assertEquals(listOf(TemplateBlankUi.CHAT), ai.blanks)
        assertEquals("notification", ai.triggerIconKey)

        val leaving = cards.getValue("home-leave-wifi-off")
        assertEquals(listOf(TemplateNeedUi.PRIVILEGED, TemplateNeedUi.LOCATION), leaving.needs)
        assertEquals(listOf(TemplateBlankUi.CURRENT_LOCATION), leaving.blanks)

        assertEquals(listOf(TemplateNeedUi.SMS), cards.getValue("messages-sms-code").needs)
        assertEquals(listOf(TemplateNeedUi.NOTIFICATIONS), cards.getValue("work-end-of-day").needs)
        assertEquals(listOf(TemplateNeedUi.DND_ACCESS), cards.getValue("home-night-dnd").needs)
        assertEquals(
            listOf(TemplateNeedUi.DND_ACCESS, TemplateNeedUi.LOCATION),
            cards.getValue("home-wifi-ringer").needs,
        )
        assertEquals(listOf(TemplateBlankUi.NETWORK), cards.getValue("home-wifi-ringer").blanks)
        // Solo la regola generativa porta la coppia «generativa» + «esce verso il cloud».
        assertEquals(listOf("messages-ai-busy-reply"), cards.values.filter { it.isGenerative }.map { it.id })
    }

    @Test
    fun `cards are in English for an English phone`() = runTest {
        val viewModel = viewModel(RenderLanguage.EN)
        awaitUntil("modelli caricati") { !viewModel.state.value.loading }

        assertEquals("Battery at 20%: saver", viewModel.state.value.templates.single { it.id == "battery-low-saver" }.title)
    }

    @Test
    fun `using a template creates an imported draft in the phone's zone and opens its approval`() = runTest {
        val viewModel = viewModel(RenderLanguage.IT)
        val events = CopyOnWriteArrayList<TemplateGalleryEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect(events::add) }
        awaitUntil("modelli caricati") { !viewModel.state.value.loading }

        viewModel.onUseTemplate("home-night-dnd")
        awaitUntil("bozza aperta") { events.any { it is TemplateGalleryEvent.OpenDraft } }

        val open = events.filterIsInstance<TemplateGalleryEvent.OpenDraft>().single()
        val draft = assertNotNull(repository.get(DraftId(open.draftId)))
        assertEquals(CreatedBy.IMPORT, draft.createdBy)
        assertEquals("Notte tranquilla", draft.draft.name)
        assertEquals("Asia/Tokyo", (draft.draft.trigger as Trigger.Time).tz)
        assertEquals(emptyList(), store.armed())
        assertTrue(events.any { it is TemplateGalleryEvent.Message })
    }

    @Test
    fun `the filter is kept in the state`() = runTest {
        val viewModel = viewModel(RenderLanguage.IT)
        viewModel.onFilter(TemplateCategoryUi.WORK)
        assertEquals(TemplateCategoryUi.WORK, viewModel.state.value.filter)
        viewModel.onFilter(null)
        assertEquals(null, viewModel.state.value.filter)
    }

    @Test
    fun `needs come from the capabilities a rule requires`() {
        assertEquals(emptyList(), TemplateNeeds.of(setOf(CapabilityIds.TRIGGER_TIME, ActionCapabilities.VIBRATE)))
        assertEquals(
            listOf(TemplateNeedUi.PRIVILEGED, TemplateNeedUi.LOCATION),
            TemplateNeeds.of(setOf(ActionCapabilities.SET_WIFI, CapabilityIds.TRIGGER_GEOFENCE)),
        )
        assertEquals(
            listOf(TemplateNeedUi.BLUETOOTH, TemplateNeedUi.USAGE_ACCESS, TemplateNeedUi.ACCESSIBILITY),
            TemplateNeeds.of(
                setOf(CapabilityIds.TRIGGER_CONNECTIVITY_BT, CapabilityIds.TRIGGER_APP_EVENT, ActionCapabilities.UI_STEPS),
            ),
        )
        assertEquals(listOf(TemplateNeedUi.CALLS), TemplateNeeds.of(setOf(CapabilityIds.TRIGGER_PHONE_CALL)))
    }

    // --- aiuti ---------------------------------------------------------------------------------

    private fun viewModel(language: RenderLanguage): TemplateGalleryViewModel {
        val service = RulesBackupService(
            automations = store,
            drafts = repository,
            documents = object : BackupDocumentIo {
                override suspend fun read(source: Uri, maxBytes: Int): ByteArray? = error("non usato")
                override suspend fun write(destination: Uri, bytes: ByteArray) = error("non usato")
            },
            appVersion = { "0.7.2" },
        )
        return TemplateGalleryViewModel(catalog, service, RenderLanguageSource { language })
            .also {
                it.zone = { ZoneId.of("Asia/Tokyo") }
                viewModels += it
            }
    }

    /** Il lavoro attraversa executor Room reali: attesa in tempo reale, non virtuale. */
    private suspend fun awaitUntil(label: String, condition: () -> Boolean) {
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (!condition()) delay(20)
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timeout in attesa di: $label", error)
        }
    }
}
