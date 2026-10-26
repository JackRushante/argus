package dev.argus.data.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDraftRepository
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.toDraft
import dev.argus.engine.safety.ApprovalResult
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import dev.argus.engine.safety.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesBackupServiceTest {
    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var repository: RoomDraftRepository
    private lateinit var whitelist: RoomContactWhitelistStore
    private val documents = FakeDocuments()
    private val file: Uri = Uri.parse("content://test/rules.json")
    private var nextId = 0

    private fun service(writer: BulkDraftWriter = repository) = RulesBackupService(
        automations = store,
        drafts = writer,
        documents = documents,
        appVersion = { "0.4.0-test" },
        nowMillis = { NOW },
        newId = { "imp-${++nextId}" },
    )

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        repository = RoomDraftRepository(db)
        whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
    }

    @After
    fun tearDown() = db.close()

    // --- export --------------------------------------------------------------------------------

    @Test
    fun `export writes approved and review rules and never pending drafts`() = runTest {
        armed("armed", BackupFixtures.simple("Armata"))
        armed("off", BackupFixtures.simple("Disattivata"))
        store.disable(AutomationId("off"))
        armed("review", BackupFixtures.simple("Da rivedere"))
        store.markNeedsReview(AutomationId("review"))
        pending("pending", BackupFixtures.simple("Bozza"))

        val outcome = assertIs<ExportOutcome.Done>(service().export(file, stripPersonalData = false))

        assertEquals(3, outcome.exported)
        assertEquals(0, outcome.skipped)
        val rules = exported().getValue("rules").jsonArray
        assertEquals(
            setOf("Armata", "Disattivata", "Da rivedere"),
            rules.map { it.jsonObject.getValue("draft").jsonObject.getValue("name").jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun `export carries the documented fields and nothing of the approval, the logs or the settings`() = runTest {
        armed("a", BackupFixtures.rich())

        service().export(file, stripPersonalData = false)

        val root = exported()
        assertEquals(setOf("format", "version", "exportedAt", "appVersion", "rules"), root.keys)
        assertEquals("argus-rules", root.getValue("format").jsonPrimitive.content)
        assertEquals("1", root.getValue("version").jsonPrimitive.content)
        assertEquals("0.4.0-test", root.getValue("appVersion").jsonPrimitive.content)
        val entry = root.getValue("rules").jsonArray.single().jsonObject
        assertEquals(setOf("enabled", "draft"), entry.keys)
        val draftKeys = entry.getValue("draft").jsonObject.keys
        assertTrue(
            draftKeys.all { it in setOf("name", "trigger", "actions", "vars", "conditions", "rationale", "cooldownMs") },
            "chiavi inattese nella bozza: $draftKeys",
        )
        val text = documents.text(file)
        listOf("approvalFingerprint", "requiredCapabilities", "createdBy", "status", "schemaVersion", "priority")
            .forEach { assertFalse(it in text, "$it non deve finire nel file") }
    }

    @Test
    fun `export takes the timestamp from the clock in ISO form`() = runTest {
        armed("a", BackupFixtures.simple())

        service().export(file, stripPersonalData = false)

        assertEquals("1970-01-01T00:20:34.567Z", exported().getValue("exportedAt").jsonPrimitive.content)
    }

    @Test
    fun `enabled is true only for an armed rule`() = runTest {
        armed("armed", BackupFixtures.simple("Armata"))
        armed("off", BackupFixtures.simple("Disattivata"))
        store.disable(AutomationId("off"))
        armed("review", BackupFixtures.simple("Da rivedere"))
        store.markNeedsReview(AutomationId("review"))

        service().export(file, stripPersonalData = false)

        val enabledByName = exported().getValue("rules").jsonArray.associate {
            val entry = it.jsonObject
            entry.getValue("draft").jsonObject.getValue("name").jsonPrimitive.content to
                entry.getValue("enabled").jsonPrimitive.content
        }
        assertEquals(mapOf("Armata" to "true", "Disattivata" to "false", "Da rivedere" to "false"), enabledByName)
    }

    @Test
    fun `export with the option on leaves no position and no contact in the file`() = runTest {
        armed("geo", AutomationDraft("Casa", BackupFixtures.geofence, listOf(Action.SetWifi(true))))
        armed("wa", BackupFixtures.notificationReply)
        armed("sms", AutomationDraft("Codice", BackupFixtures.phoneState, listOf(Action.CopyToClipboard("(\\d+)"))))
        armed("rich", BackupFixtures.rich())

        service().export(file, stripPersonalData = true)

        val text = documents.text(file)
        listOf(BackupFixtures.CONVERSATION, BackupFixtures.SENDER, BackupFixtures.NUMBER, "45.4642", "9.19\"")
            .forEach { assertFalse(it in text, "$it deve essere stato tolto") }
        assertTrue("\"resolveCurrentLocation\": true" in text)
    }

    @Test
    fun `export with the option off keeps everything`() = runTest {
        armed("wa", BackupFixtures.notificationReply)

        service().export(file, stripPersonalData = false)

        val text = documents.text(file)
        assertTrue(BackupFixtures.CONVERSATION in text)
        assertTrue(BackupFixtures.SENDER in text)
    }

    @Test
    fun `export with nothing to export writes nothing`() = runTest {
        pending("pending", BackupFixtures.simple())

        val outcome = service().export(file, stripPersonalData = false)

        assertEquals(ExportOutcome.Failed(BackupFailure.NOTHING_TO_EXPORT), outcome)
        assertTrue(documents.files.isEmpty())
    }

    @Test
    fun `a quarantined rule that cannot be read is left out and counted`() = runTest {
        armed("ok", BackupFixtures.simple("Buona"))
        armed("broken", BackupFixtures.simple("Rotta"))
        // JSON rovinato: lo store la restituisce come segnaposto «da rivedere» senza azioni.
        val row = assertNotNull(db.automationDao().getById("broken"))
        db.automationDao().upsertPreservingLastFired(row.copy(json = "{non è json"))

        val outcome = assertIs<ExportOutcome.Done>(service().export(file, stripPersonalData = false))

        assertEquals(1, outcome.exported)
        assertEquals(1, outcome.skipped)
        assertEquals(1, exported().getValue("rules").jsonArray.size)
    }

    @Test
    fun `export fails cleanly when the file cannot be written`() = runTest {
        armed("a", BackupFixtures.simple())
        documents.writeFailure = IOException("disco pieno")

        assertEquals(
            ExportOutcome.Failed(BackupFailure.WRITE_FAILED),
            service().export(file, stripPersonalData = false),
        )
    }

    // --- andata e ritorno ----------------------------------------------------------------------

    @Test
    fun `export then import gives equal drafts, all waiting for approval and none armed`() = runTest {
        val originals = listOf(
            armed("a", BackupFixtures.simple("Semplice")),
            armed("g", AutomationDraft("Casa", BackupFixtures.geofence, listOf(Action.SetWifi(true)))),
            armed("n", BackupFixtures.notificationReply),
            armed("p", AutomationDraft("Codice", BackupFixtures.phoneState, listOf(Action.CopyToClipboard("(\\d+)")))),
            armed("r", BackupFixtures.rich()),
            armed("off", BackupFixtures.simple("Disattivata")).also { store.disable(it.id) },
            armed("review", BackupFixtures.simple("Da rivedere")).also { store.markNeedsReview(it.id) },
        )
        val before = store.all()
        service().export(file, stripPersonalData = false)

        val outcome = assertIs<ImportOutcome.Done>(service().import(file))

        assertEquals(ImportSummary(imported = originals.size, discarded = emptyMap()), outcome.summary)
        val imported = repository.observeAll().first()
        assertEquals(originals.size, imported.size)
        originals.forEach { original ->
            val match = imported.single { it.draft.name == original.name }
            assertEquals(original.toDraft(), match.draft, "bozza di ${original.name}")
            assertEquals(CreatedBy.IMPORT, match.createdBy)
            assertNotEquals(original.id, match.automationId, "ogni importata ha un id nuovo")
            assertEquals(0, match.priority)
            assertEquals(null, match.baseAutomationFingerprint)
            assertEquals(null, match.integrityError)
            assertTrue(match.hasValidFingerprint())
        }
        // Le regole esistenti sono identiche, impronte comprese, e nulla è stato armato.
        assertEquals(before, store.all())
        assertEquals(
            setOf("a", "g", "n", "p", "r"),
            store.armed().map { it.id.value }.toSet(),
            "le armate di prima restano armate e nessuna regola nuova si arma",
        )
        assertTrue(store.all().none { it.id.value.startsWith("imp-") })
    }

    @Test
    fun `an imported draft is one the normal approval can arm, and the armed rule keeps a valid fingerprint`() = runTest {
        armed("a", BackupFixtures.simple("Semplice"))
        service().export(file, stripPersonalData = false)
        service().import(file)
        val draft = repository.observeAll().first().single()
        val approvals = ApprovalService(repository, DraftValidator(setOf("whatsapp_reply")), whitelist)

        val review = assertNotNull(approvals.review(draft.id))
        assertTrue(review.canArm, "problemi: ${review.issues}")
        val armed = assertIs<ApprovalResult.Armed>(
            approvals.arm(draft.id, draft.revision, draft.fingerprint),
        ).automation

        assertEquals(CreatedBy.IMPORT, armed.createdBy)
        assertEquals(AutomationStatus.ARMED, armed.status)
        // Riletta dallo store (che ne ricalcola l'impronta) resta armata: non finisce in quarantena.
        val reread = assertNotNull(store.get(armed.id))
        assertEquals(AutomationStatus.ARMED, reread.status)
        assertEquals(armed.approvalFingerprint, reread.approvalFingerprint)
    }

    @Test
    fun `importing never touches the rules that are already there`() = runTest {
        val existing = armed("a", BackupFixtures.simple("Semplice"))
        store.recordFired(existing.id, 4_242)
        val before = store.all()
        val firedBefore = store.lastFiredAt(existing.id)
        documents.files[file] = fileOf(rule(BackupFixtures.simple("Semplice")), rule(BackupFixtures.simple("Altra")))

        service().import(file)

        assertEquals(before, store.all())
        assertEquals(firedBefore, store.lastFiredAt(existing.id))
        assertEquals(listOf(existing.id), store.armed().map { it.id })
    }

    // --- validatore e whitelist ----------------------------------------------------------------

    @Test
    fun `a rule that replies to a contact outside the whitelist is imported, flagged by the validator, and the whitelist stays empty`() = runTest {
        documents.files[file] = fileOf(rule(BackupFixtures.notificationReply))
        val approvals = ApprovalService(repository, DraftValidator(setOf("whatsapp_reply")), whitelist)

        assertIs<ImportOutcome.Done>(service().import(file))

        assertEquals(emptyList(), whitelist.observeAll().first(), "l'import non aggiunge contatti")
        val draft = repository.observeAll().first().single()
        val review = assertNotNull(approvals.review(draft.id))
        assertFalse(review.canArm)
        assertTrue(
            review.issues.any { it.severity == Severity.ERROR && it.code == "target_not_whitelisted" },
            "problemi: ${review.issues}",
        )
        val result = approvals.arm(draft.id, draft.revision, draft.fingerprint)
        assertIs<ApprovalResult.ValidationFailed>(result)
        assertTrue(store.all().isEmpty(), "niente armato")
        assertEquals(emptyList(), whitelist.observeAll().first())
    }

    @Test
    fun `once the contact is whitelisted by the user the same imported draft can be armed`() = runTest {
        documents.files[file] = fileOf(rule(BackupFixtures.notificationReply))
        service().import(file)
        val draft = repository.observeAll().first().single()
        val approvals = ApprovalService(repository, DraftValidator(setOf("whatsapp_reply")), whitelist)
        whitelist.upsert(WhitelistedContact(BackupFixtures.SENDER, BackupFixtures.CONVERSATION))

        assertTrue(assertNotNull(approvals.review(draft.id)).canArm)
    }

    @Test
    fun `a stripped export imported again cannot be armed until it is remade`() = runTest {
        armed("geo", AutomationDraft("Casa", BackupFixtures.geofence, listOf(Action.SetWifi(true))))
        armed("loc", AutomationDraft("Dove", Trigger.Immediate, listOf(Action.SetWifi(true)), conditions = BackupFixtures.locationIn))
        service().export(file, stripPersonalData = true)
        service().import(file)
        val approvals = ApprovalService(repository, DraftValidator(setOf("whatsapp_reply")), whitelist)

        val reviews = repository.observeAll().first().associate { it.draft.name to assertNotNull(approvals.review(it.id)) }

        assertTrue(reviews.getValue("Casa").canArm, "il geofence chiede la posizione quando si arma")
        assertFalse(reviews.getValue("Dove").canArm)
        assertTrue(reviews.getValue("Dove").issues.any { it.code == "location_invalid" })
    }

    // --- file rifiutati ------------------------------------------------------------------------

    @Test
    fun `a file over the size limit is refused and nothing is imported`() = runTest {
        documents.files[file] = ByteArray(RulesBackupFormat.MAX_FILE_BYTES + 1) { '{'.code.toByte() }

        assertEquals(ImportOutcome.Failed(BackupFailure.FILE_TOO_LARGE), service().import(file))
        assertEquals(emptyList(), repository.observeAll().first())
    }

    @Test
    fun `a file exactly at the limit is read`() = runTest {
        documents.files[file] = ByteArray(RulesBackupFormat.MAX_FILE_BYTES) { ' '.code.toByte() }

        // Solo spazi: letto per intero, poi rifiutato perché non è JSON (non per la dimensione).
        assertEquals(ImportOutcome.Failed(BackupFailure.NOT_JSON), service().import(file))
    }

    @Test
    fun `an unreadable file is refused`() = runTest {
        documents.readFailure = FileNotFoundException("sparito")

        assertEquals(ImportOutcome.Failed(BackupFailure.READ_FAILED), service().import(file))
    }

    @Test
    fun `a provider that throws a security exception is a read failure, not a crash`() = runTest {
        documents.readFailure = SecurityException("permesso revocato")

        assertEquals(ImportOutcome.Failed(BackupFailure.READ_FAILED), service().import(file))
    }

    @Test
    fun `malformed and wrong files are refused with a clear reason and nothing is imported`() = runTest {
        val cases = mapOf(
            "non json" to ("ciao".toByteArray() to BackupFailure.NOT_JSON),
            "non utf8" to (byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D) to BackupFailure.NOT_JSON),
            "array" to ("[]".toByteArray() to BackupFailure.WRONG_FORMAT),
            "altro formato" to ("""{"format":"tasker","version":1,"rules":[]}""".toByteArray() to BackupFailure.WRONG_FORMAT),
            "versione 2" to ("""{"format":"argus-rules","version":2,"rules":[]}""".toByteArray() to BackupFailure.UNSUPPORTED_VERSION),
            "senza regole" to ("""{"format":"argus-rules","version":1,"rules":[]}""".toByteArray() to BackupFailure.NO_RULES),
            "troppe regole" to (
                fileOf(*Array(RulesBackupFormat.MAX_RULES + 1) { rule(BackupFixtures.simple("r$it")) }) to
                    BackupFailure.TOO_MANY_RULES
                ),
        )
        cases.forEach { (label, case) ->
            documents.files[file] = case.first
            assertEquals(ImportOutcome.Failed(case.second), service().import(file), label)
        }
        assertEquals(emptyList(), repository.observeAll().first())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `invalid rules are discarded with their reason and the valid ones are imported`() = runTest {
        documents.files[file] = (
            """{"format":"argus-rules","version":1,"rules":[""" +
                String(rule(BackupFixtures.simple("Buona uno"))) + "," +
                """{"enabled":true,"draft":{"name":"x","trigger":{"type":"teleport"},"actions":[]}},""" +
                """{"enabled":"si","draft":{}},""" +
                """{"enabled":true,"draft":{"name":"y","armed":true,"trigger":{"type":"immediate"},"actions":[{"type":"set_wifi","on":true}]}},""" +
                String(rule(BackupFixtures.simple("Buona due"))) +
                "]}"
            ).toByteArray()

        val outcome = assertIs<ImportOutcome.Done>(service().import(file))

        assertEquals(2, outcome.summary.imported)
        assertEquals(
            mapOf(RuleDiscardReason.INVALID_RULE to 2, RuleDiscardReason.MALFORMED_ENTRY to 1),
            outcome.summary.discarded,
        )
        assertEquals(3, outcome.summary.discardedTotal)
        assertEquals(
            setOf("Buona uno", "Buona due"),
            repository.observeAll().first().map { it.draft.name }.toSet(),
        )
    }

    @Test
    fun `the enabled flag in the file never arms anything`() = runTest {
        documents.files[file] = fileOf(rule(BackupFixtures.simple("Attiva"), enabled = true))

        service().import(file)

        assertTrue(store.armed().isEmpty())
        assertTrue(store.all().isEmpty())
        assertEquals(1, repository.observeAll().first().size)
    }

    @Test
    fun `when the drafts cannot be saved the import fails as a whole`() = runTest {
        documents.files[file] = fileOf(rule(BackupFixtures.simple("Una")), rule(BackupFixtures.simple("Due")))
        val failing = BulkDraftWriter { error("disco pieno") }

        assertEquals(ImportOutcome.Failed(BackupFailure.SAVE_FAILED), service(writer = failing).import(file))
        assertEquals(emptyList(), repository.observeAll().first())
    }

    @Test
    fun `a draft the repository rejects is counted as not saved, the others go in`() = runTest {
        documents.files[file] = fileOf(rule(BackupFixtures.simple("Una")), rule(BackupFixtures.simple("Due")))
        val rejectFirst = BulkDraftWriter { drafts ->
            val results = repository.createAll(drafts.drop(1))
            listOf<DraftWriteResult>(DraftWriteResult.Rejected("automation_exists")) + results
        }

        val outcome = assertIs<ImportOutcome.Done>(service(writer = rejectFirst).import(file))

        assertEquals(1, outcome.summary.imported)
        assertEquals(mapOf(RuleDiscardReason.NOT_SAVED to 1), outcome.summary.discarded)
    }

    // --- scrittura in blocco -------------------------------------------------------------------

    @Test
    fun `20 imported rules reach the list in a single update, never half done`() = runTest {
        documents.files[file] = fileOf(*Array(20) { rule(BackupFixtures.simple("Regola $it")) })
        val sizes = CopyOnWriteArrayList<Int>()
        val collector = backgroundScope.launch(Dispatchers.Default) {
            repository.observeAll().collect { sizes += it.size }
        }
        awaitUntil("elenco iniziale") { sizes.isNotEmpty() }

        service().import(file)
        awaitUntil("elenco con le 20 bozze") { sizes.lastOrNull() == 20 }
        withContext(Dispatchers.Default) { delay(300) }
        collector.cancel()

        assertTrue(sizes.all { it == 0 || it == 20 }, "aggiornamenti intermedi: ${sizes.toList()}")
    }

    @Test
    fun `createAll answers in order and a rejected draft does not stop the others`() = runTest {
        val fresh = NewDraft(DraftId("d1"), AutomationId("a1"), BackupFixtures.simple("Uno"), atMillis = 1)
        val missingBase = NewDraft(
            id = DraftId("d2"),
            automationId = AutomationId("a2"),
            draft = BackupFixtures.simple("Due"),
            atMillis = 1,
            expectedAutomationFingerprint = dev.argus.engine.model.ApprovalFingerprint("a".repeat(64)),
        )
        val third = NewDraft(DraftId("d3"), AutomationId("a3"), BackupFixtures.simple("Tre"), atMillis = 1)

        val results = repository.createAll(listOf(fresh, missingBase, third))

        assertIs<DraftWriteResult.Saved>(results[0])
        assertEquals(DraftWriteResult.Rejected("automation_missing"), results[1])
        assertIs<DraftWriteResult.Saved>(results[2])
        assertEquals(setOf("Uno", "Tre"), repository.observeAll().first().map { it.draft.name }.toSet())
    }

    @Test
    fun `createdBy IMPORT is stored by name and read back`() = runTest {
        val saved = assertIs<DraftWriteResult.Saved>(
            repository.create(
                NewDraft(
                    DraftId("d1"), AutomationId("a1"), BackupFixtures.simple(),
                    createdBy = CreatedBy.IMPORT, atMillis = 1,
                ),
            ),
        ).snapshot

        assertEquals(CreatedBy.IMPORT, saved.createdBy)
        assertEquals(CreatedBy.IMPORT, assertNotNull(repository.get(saved.id)).createdBy)
        assertEquals(
            "IMPORT",
            db.openHelper.readableDatabase.query("SELECT createdBy FROM pending_drafts").use {
                it.moveToFirst()
                it.getString(0)
            },
        )
    }

    // --- aiuti ---------------------------------------------------------------------------------

    private fun exported() = Json.parseToJsonElement(documents.text(file)).jsonObject

    private fun rule(draft: AutomationDraft, enabled: Boolean = true): ByteArray =
        RulesBackupCodec.encode(listOf(BackupRule(enabled, draft)), "now", "0.4.0")
            .let { Json.parseToJsonElement(it).jsonObject.getValue("rules").jsonArray.single().toString() }
            .toByteArray()

    private fun fileOf(vararg rules: ByteArray): ByteArray =
        ("""{"format":"argus-rules","version":1,"exportedAt":"now","appVersion":"0.4.0","rules":[""" +
            rules.joinToString(",") { String(it) } + "]}").toByteArray()

    /** Bozza approvata e armata, come farebbe l'approvazione. */
    private suspend fun armed(id: String, draft: AutomationDraft): Automation {
        val saved = pending(id, draft)
        return assertIs<DraftArmResult.Armed>(
            repository.arm(saved.id, saved.revision, saved.fingerprint),
        ).automation
    }

    private suspend fun pending(id: String, draft: AutomationDraft): PendingDraft = assertIs<DraftWriteResult.Saved>(
        repository.create(
            NewDraft(
                id = DraftId("draft-$id"),
                automationId = AutomationId(id),
                draft = draft,
                createdBy = CreatedBy.LLM,
                atMillis = 1_000,
            ),
        ),
    ).snapshot

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

    private class FakeDocuments : BackupDocumentIo {
        val files = mutableMapOf<Uri, ByteArray>()
        var readFailure: Exception? = null
        var writeFailure: Exception? = null

        fun text(uri: Uri): String = String(requireNotNull(files[uri]) { "nessun file scritto" })

        override suspend fun read(source: Uri, maxBytes: Int): ByteArray? {
            readFailure?.let { throw it }
            val bytes = files[source] ?: throw FileNotFoundException()
            return if (bytes.size > maxBytes) null else bytes
        }

        override suspend fun write(destination: Uri, bytes: ByteArray) {
            writeFailure?.let { throw it }
            files[destination] = bytes
        }
    }

    private companion object {
        const val NOW = 1_234_567L
    }
}
