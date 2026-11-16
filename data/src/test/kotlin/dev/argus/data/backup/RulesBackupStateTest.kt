package dev.argus.data.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomBackupState
import dev.argus.data.RoomDraftRepository
import dev.argus.data.RoomStoredValueStore
import dev.argus.data.RoomUserModeStore
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredOp
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Modalità e valori conservati nel backup (0.8): l'export completo li porta, quello senza dati personali tiene
 * solo i nomi delle modalità; si ripristinano dall'import di Sistema, mai da un testo ricevuto da un'altra app;
 * da un file in chiaro ogni valore torna TAINTED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesBackupStateTest {
    private lateinit var db: ArgusDatabase
    private lateinit var repository: RoomDraftRepository
    private lateinit var values: RoomStoredValueStore
    private lateinit var modes: RoomUserModeStore
    private val documents = Documents()
    private val plainFile: Uri = Uri.parse("content://test/rules.json")
    private val encryptedFile: Uri = Uri.parse("content://test/rules.argusbackup")
    private var nextId = 0
    private val fastDeriver = BackupKeyDeriver { passphrase, salt ->
        BackupKeyDerivation.derive(passphrase, salt, iterations = 1_000)
    }

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repository = RoomDraftRepository(db)
        values = RoomStoredValueStore(db) { 50L }
        modes = RoomUserModeStore(db) { 60L }
    }

    @After
    fun tearDown() = db.close()

    private fun service() = RulesBackupService(
        automations = RoomAutomationStore(db.automationDao()),
        drafts = repository,
        documents = documents,
        appVersion = { "0.8-test" },
        nowMillis = { 1_000L },
        newId = { "imp-${++nextId}" },
        keyDeriver = fastDeriver,
        state = RoomBackupState(values, modes),
    )

    private suspend fun seed() {
        val saved = assertIs<DraftWriteResult.Saved>(
            repository.create(NewDraft(DraftId("d1"), AutomationId("r1"), BackupFixtures.simple("Caffè"), atMillis = 1_000)),
        ).snapshot
        assertIs<DraftArmResult.Armed>(repository.arm(saved.id, saved.revision, saved.fingerprint))
        modes.add("Casa")
        modes.add("Lavoro")
        modes.activate("Lavoro", ChangeAuthor.User, 5L)
        values.apply(
            Action.SetStored("caffe_oggi", StoredOp.SET, "2", StateValueType.NUMBER),
            IntegrityLabel.CLEAN,
            ConfidentialityLabel.PUBLIC,
            ChangeAuthor.Rule(AutomationId("r1")),
            7L,
        )
        values.apply(
            Action.SetStored("ultimo", StoredOp.SET, "Mario"),
            IntegrityLabel.TAINTED,
            ConfidentialityLabel.PRIVATE,
            ChangeAuthor.Rule(AutomationId("r1")),
            8L,
        )
    }

    private suspend fun wipeState() {
        modes.delete("Casa")
        modes.delete("Lavoro")
        values.delete("caffe_oggi")
        values.delete("ultimo")
    }

    @Test
    fun `the full export carries modes and values, the stripped one only the mode names`() = runTest {
        seed()
        service().export(plainFile, stripPersonalData = false)
        val full = Json.parseToJsonElement(documents.text(plainFile)).jsonObject
        assertEquals("""{"names":["Casa","Lavoro"],"active":"Lavoro"}""", full.getValue("modes").toString())
        assertEquals(2, full.getValue("storedValues").jsonArray.size)

        service().export(plainFile, stripPersonalData = true)
        val stripped = Json.parseToJsonElement(documents.text(plainFile)).jsonObject
        assertEquals("""{"names":["Casa","Lavoro"]}""", stripped.getValue("modes").toString())
        assertFalse("storedValues" in stripped.keys)
        assertFalse("Mario" in documents.text(plainFile))
    }

    @Test
    fun `a plain file restores modes and values, every value tainted`() = runTest {
        seed()
        service().export(plainFile, stripPersonalData = false)
        wipeState()

        assertIs<ImportOutcome.Done>(service().import(plainFile))
        assertEquals(listOf("Casa", "Lavoro"), modes.snapshot().modes)
        assertEquals("Lavoro", modes.snapshot().active)
        val restored = values.all().associateBy { it.key }
        assertEquals("2", restored.getValue("caffe_oggi").text)
        assertTrue(restored.values.all { it.integrity == IntegrityLabel.TAINTED })
        assertEquals(ConfidentialityLabel.PRIVATE, restored.getValue("ultimo").confidentiality)
    }

    @Test
    fun `an encrypted backup restores the saved labels`() = runTest {
        seed()
        val built = assertIs<BuiltBackup.Ready>(service().buildFullBackup())
        val salt = ByteArray(16) { 3 }
        val key = BackupKeyDerivation.derive("passphrase".toCharArray(), salt, iterations = 1_000)
        documents.files[encryptedFile] = EncryptedBackupFormat.seal(built.bytes, key, salt, ByteArray(12) { 1 })
        wipeState()

        assertIs<ImportOutcome.Done>(service().importEncrypted(encryptedFile, "passphrase".toCharArray()))
        val restored = values.all().associateBy { it.key }
        assertEquals(IntegrityLabel.CLEAN, restored.getValue("caffe_oggi").integrity)
        assertEquals(IntegrityLabel.TAINTED, restored.getValue("ultimo").integrity)
        assertEquals(ChangeAuthor.Rule(AutomationId("r1")), restored.getValue("ultimo").writer)
    }

    @Test
    fun `text received from another app imports rules only`() = runTest {
        seed()
        service().export(plainFile, stripPersonalData = false)
        val bytes = documents.files.getValue(plainFile)
        wipeState()

        assertIs<ImportOutcome.Done>(service().importBytes(bytes))
        assertEquals(emptyList(), modes.snapshot().modes)
        assertNull(modes.snapshot().active)
        assertEquals(emptyList(), values.all())
    }

    @Test
    fun `a malformed state section never discards a rule and bad entries are skipped`() {
        val rule = Json.parseToJsonElement(
            RulesBackupCodec.encode(listOf(BackupRule(true, BackupFixtures.simple())), "now", "0.8"),
        ).jsonObject.getValue("rules").jsonArray.single().toString()
        val text = """{"format":"argus-rules","version":1,"rules":[$rule],
            "modes":{"names":["Casa"," casa ","","${"x".repeat(25)}",3],"active":"CASA"},
            "storedValues":[{"key":"Bad","type":"TEXT","value":"x"},{"key":"n","type":"NUMBER","value":"due"},
              {"key":"ok","type":"TEXT","value":"v","integrity":"WHATEVER"},"x"]}"""
        val decoded = assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(text))
        assertIs<DecodedEntry.Rule>(decoded.entries.single())
        assertEquals(listOf("Casa"), decoded.state.modes)
        assertEquals("Casa", decoded.state.activeMode)
        val ok = decoded.state.values.single()
        assertEquals("ok", ok.key)
        // Etichette mancanti o sconosciute: il caso peggiore.
        assertEquals(IntegrityLabel.TAINTED, ok.integrity)
        assertEquals(ConfidentialityLabel.SECRET, ok.confidentiality)

        val broken = """{"format":"argus-rules","version":1,"rules":[$rule],"modes":"Casa","storedValues":{}}"""
        val plain = assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(broken))
        assertTrue(plain.state.isEmpty)
        assertEquals(1, plain.entries.size)
    }

    @Test
    fun `a file without state is written exactly as before`() {
        val text = RulesBackupCodec.encode(listOf(BackupRule(true, BackupFixtures.simple())), "now", "0.8")
        val keys = Json.parseToJsonElement(text).jsonObject.keys
        assertFalse("modes" in keys || "storedValues" in keys)
    }

    private class Documents : BackupDocumentIo {
        val files = mutableMapOf<Uri, ByteArray>()

        fun text(uri: Uri): String = String(requireNotNull(files[uri]))

        override suspend fun read(source: Uri, maxBytes: Int): ByteArray? {
            val bytes = files[source] ?: throw FileNotFoundException()
            return if (bytes.size > maxBytes) null else bytes
        }

        override suspend fun write(destination: Uri, bytes: ByteArray) {
            files[destination] = bytes
        }
    }
}
