package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.StoredValueOps
import dev.argus.engine.model.StoredWrite
import dev.argus.engine.model.UserModeListIssue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Valori conservati e modalità in Room (0.8): transazioni, etichette salvate, correzioni dell'utente, import. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomStoredStateTest {
    private lateinit var db: ArgusDatabase
    private lateinit var values: RoomStoredValueStore
    private lateinit var modes: RoomUserModeStore
    private var clock = 1_000L
    private val rule = ChangeAuthor.Rule(AutomationId("r1"))

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        values = RoomStoredValueStore(db) { clock++ }
        modes = RoomUserModeStore(db) { clock++ }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a set keeps the labels and the writer, and reading back gives the same value`() = runTest {
        values.apply(
            Action.SetStored("last", StoredOp.SET, "Mario"),
            IntegrityLabel.TAINTED,
            ConfidentialityLabel.PRIVATE,
            rule,
            5L,
        )
        val read = values.read(setOf("last", "missing"))
        assertEquals(setOf("last"), read.keys)
        val saved = read.getValue("last")
        assertEquals(
            StoredValue("last", StateValueType.TEXT, "Mario", IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, rule, 5L),
            saved,
        )
        assertEquals(listOf(saved), values.observe().first())
    }

    @Test
    fun `increments are atomic and a mismatch changes nothing`() = runTest {
        (1..20).map { async { values.apply(Action.SetStored("n", StoredOp.INCREMENT), IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, rule, 1L) } }
            .awaitAll()
        assertEquals("20", values.read(setOf("n")).getValue("n").text)
        val write = values.apply(Action.SetStored("n", StoredOp.TOGGLE), IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, rule, 2L)
        assertEquals(StoredWrite.Rejected(StoredValueOps.TYPE_MISMATCH), write)
        assertEquals("20", values.read(setOf("n")).getValue("n").text)
        values.apply(Action.SetStored("n", StoredOp.CLEAR), IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, rule, 3L)
        assertEquals(emptyMap(), values.read(setOf("n")))
    }

    @Test
    fun `the 64 key limit is enforced in the transaction`() = runTest {
        (1..64).forEach { i ->
            assertIs<StoredWrite.Put>(
                values.apply(Action.SetStored("k$i", StoredOp.SET, "x"), IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, rule, 1L),
            )
        }
        assertEquals(
            StoredWrite.Rejected(StoredValueOps.KEY_LIMIT),
            values.apply(Action.SetStored("k65", StoredOp.SET, "x"), IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, rule, 1L),
        )
    }

    @Test
    fun `a user correction is clean, keeps type and confidentiality, and must fit the type`() = runTest {
        values.apply(
            Action.SetStored("n", StoredOp.SET, "3", StateValueType.NUMBER),
            IntegrityLabel.TAINTED,
            ConfidentialityLabel.SECRET,
            rule,
            1L,
        )
        assertEquals(StoredEditResult.INVALID, values.editByUser("n", "tre"))
        assertEquals(StoredEditResult.SAVED, values.editByUser("n", "4.0"))
        val edited = values.read(setOf("n")).getValue("n")
        assertEquals("4", edited.text)
        assertEquals(IntegrityLabel.CLEAN, edited.integrity)
        assertEquals(ConfidentialityLabel.SECRET, edited.confidentiality)
        assertEquals(ChangeAuthor.User, edited.writer)
        assertEquals(StoredEditResult.MISSING, values.editByUser("other", "1"))
        assertEquals(true, values.delete("n"))
        assertEquals(emptyList(), values.all())
    }

    @Test
    fun `modes are unique ignoring case, at most twelve, and activation is by name`() = runTest {
        assertEquals(ModeEditResult.Done("Casa"), modes.add(" Casa "))
        assertEquals(ModeEditResult.Refused(UserModeListIssue.DUPLICATE), modes.add("casa"))
        assertEquals(ModeEditResult.Done("Lavoro"), modes.add("Lavoro"))
        assertEquals(listOf("Casa", "Lavoro"), modes.snapshot().modes)
        assertNull(modes.snapshot().active)

        assertEquals(ModeSwitch.Changed(null, "Casa"), modes.activate("CASA", ChangeAuthor.User, 10L))
        assertEquals(ModeSwitch.Unchanged, modes.activate("casa", rule, 11L))
        assertEquals(ModeSwitch.UnknownMode, modes.activate("Fuori", rule, 12L))
        assertEquals(ModeSwitch.Changed("Casa", "Lavoro"), modes.activate("Lavoro", rule, 13L))
        val snapshot = modes.observe().first()
        assertEquals("Lavoro", snapshot.active)
        assertEquals(rule, snapshot.changedBy)
        assertEquals(13L, snapshot.changedAtMillis)

        (3..12).forEach { assertIs<ModeEditResult.Done>(modes.add("m$it")) }
        assertEquals(ModeEditResult.Refused(UserModeListIssue.TOO_MANY), modes.add("Altra"))
    }

    @Test
    fun `renaming keeps the active mode and deleting it leaves none active`() = runTest {
        modes.add("Casa")
        modes.add("Notte")
        modes.activate("Notte", ChangeAuthor.User, 1L)
        assertEquals(ModeEditResult.Done("Sonno"), modes.rename("notte", "Sonno"))
        assertEquals(listOf("Casa", "Sonno"), modes.snapshot().modes)
        assertEquals("Sonno", modes.snapshot().active)
        assertEquals(ModeEditResult.Refused(UserModeListIssue.DUPLICATE), modes.rename("Sonno", "casa"))
        assertEquals(ModeEditResult.Done("Sonno"), modes.delete("SONNO"))
        assertNull(modes.snapshot().active)
        assertEquals(ChangeAuthor.User, modes.snapshot().changedBy)
        assertEquals(ModeEditResult.Refused(UserModeListIssue.UNKNOWN), modes.delete("Sonno"))
    }

    @Test
    fun `a restore adds missing modes and values, keeps the rest and drops invalid entries`() = runTest {
        modes.add("Vecchia")
        assertEquals(2, modes.restore(listOf("Casa", "casa", "", "vecchia", "Lavoro"), active = "lavoro"))
        assertEquals(listOf("Vecchia", "Casa", "Lavoro"), modes.snapshot().modes)
        assertEquals("Lavoro", modes.snapshot().active)
        // Una modalità già attiva non cambia per un ripristino.
        modes.restore(listOf("Casa"), active = "Casa")
        assertEquals("Lavoro", modes.snapshot().active)

        values.editByUser("x", "1")
        values.apply(Action.SetStored("keep", StoredOp.SET, "a"), IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, rule, 1L)
        val written = values.restore(
            listOf(
                StoredValue("ok", StateValueType.NUMBER, "2", IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, rule, 3L),
                StoredValue("Bad", StateValueType.TEXT, "x", IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, ChangeAuthor.User, 3L),
                StoredValue("num", StateValueType.NUMBER, "due", IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, ChangeAuthor.User, 3L),
            ),
        )
        assertEquals(1, written)
        assertEquals(listOf("keep", "ok"), values.all().map { it.key })
        assertEquals(IntegrityLabel.TAINTED, values.read(setOf("ok")).getValue("ok").integrity)
    }
}
