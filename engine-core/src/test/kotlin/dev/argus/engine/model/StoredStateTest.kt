package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredStateTest {
    private val rule = ChangeAuthor.Rule(AutomationId("r1"))

    private fun value(
        key: String,
        type: StateValueType,
        text: String,
        integrity: IntegrityLabel = IntegrityLabel.CLEAN,
        confidentiality: ConfidentialityLabel = ConfidentialityLabel.PUBLIC,
    ) = StoredValue(key, type, text, integrity, confidentiality, ChangeAuthor.User, 1L)

    private fun plan(
        action: Action.SetStored,
        current: StoredValue? = null,
        keyCount: Int = if (current == null) 0 else 1,
        integrity: IntegrityLabel = IntegrityLabel.CLEAN,
        confidentiality: ConfidentialityLabel = ConfidentialityLabel.PUBLIC,
    ): StoredWrite = StoredValueOps.plan(action, current, keyCount, integrity, confidentiality, rule, 42L)

    @Test
    fun `set writes the filled-in text with the labels of what went into it`() {
        val write = assertIs<StoredWrite.Put>(
            plan(
                Action.SetStored("last_sender", StoredOp.SET, "Mario"),
                integrity = IntegrityLabel.TAINTED,
                confidentiality = ConfidentialityLabel.PRIVATE,
            ),
        )
        assertEquals(
            StoredValue(
                "last_sender",
                StateValueType.TEXT,
                "Mario",
                IntegrityLabel.TAINTED,
                ConfidentialityLabel.PRIVATE,
                rule,
                42L,
            ),
            write.value,
        )
    }

    @Test
    fun `set with a declared type stores the canonical form or fails closed`() {
        val number = assertIs<StoredWrite.Put>(
            plan(Action.SetStored("count", StoredOp.SET, "007.50", StateValueType.NUMBER)),
        )
        assertEquals("7.5", number.value.text)
        assertEquals(StateValueType.NUMBER, number.value.type)
        val flag = assertIs<StoredWrite.Put>(plan(Action.SetStored("flag", StoredOp.SET, "TRUE", StateValueType.BOOLEAN)))
        assertEquals("true", flag.value.text)
        assertEquals(
            StoredWrite.Rejected(StoredValueOps.TYPE_MISMATCH),
            plan(Action.SetStored("count", StoredOp.SET, "tre", StateValueType.NUMBER)),
        )
        assertEquals(
            StoredWrite.Rejected(StoredValueOps.TYPE_MISMATCH),
            plan(Action.SetStored("count", StoredOp.SET, "1e9", StateValueType.NUMBER)),
        )
        assertEquals(
            StoredWrite.Rejected(StoredValueOps.TYPE_MISMATCH),
            plan(Action.SetStored("flag", StoredOp.SET, "1", StateValueType.BOOLEAN)),
        )
    }

    @Test
    fun `set refuses a value over 1024 characters and a missing value`() {
        assertEquals(
            StoredWrite.Rejected(StoredValueOps.VALUE_INVALID),
            plan(Action.SetStored("note", StoredOp.SET, "x".repeat(1_025))),
        )
        assertIs<StoredWrite.Put>(plan(Action.SetStored("note", StoredOp.SET, "x".repeat(1_024))))
        assertEquals(StoredWrite.Rejected(StoredValueOps.VALUE_INVALID), plan(Action.SetStored("note", StoredOp.SET)))
    }

    @Test
    fun `set replaces a value of another type`() {
        val write = assertIs<StoredWrite.Put>(
            plan(Action.SetStored("k", StoredOp.SET, "ciao"), current = value("k", StateValueType.NUMBER, "3")),
        )
        assertEquals(StateValueType.TEXT, write.value.type)
    }

    @Test
    fun `increment starts from zero, adds a literal integer and keeps the saved labels`() {
        assertEquals("1", assertIs<StoredWrite.Put>(plan(Action.SetStored("count", StoredOp.INCREMENT))).value.text)
        val tainted = value(
            "count",
            StateValueType.NUMBER,
            "2.5",
            IntegrityLabel.TAINTED,
            ConfidentialityLabel.PRIVATE,
        )
        val write = assertIs<StoredWrite.Put>(plan(Action.SetStored("count", StoredOp.INCREMENT, "-3"), tainted))
        assertEquals("-0.5", write.value.text)
        assertEquals(IntegrityLabel.TAINTED, write.value.integrity)
        assertEquals(ConfidentialityLabel.PRIVATE, write.value.confidentiality)
        assertEquals(rule, write.value.writer)
    }

    @Test
    fun `increment and toggle on the wrong type fail with a closed code and change nothing`() {
        val text = value("k", StateValueType.TEXT, "ciao")
        assertEquals(StoredWrite.Rejected(StoredValueOps.TYPE_MISMATCH), plan(Action.SetStored("k", StoredOp.INCREMENT), text))
        assertEquals(StoredWrite.Rejected(StoredValueOps.TYPE_MISMATCH), plan(Action.SetStored("k", StoredOp.TOGGLE), text))
        assertEquals(
            StoredWrite.Rejected(StoredValueOps.TYPE_MISMATCH),
            plan(Action.SetStored("k", StoredOp.TOGGLE), value("k", StateValueType.NUMBER, "1")),
        )
        // Un incremento non letterale non si esegue mai (il validator lo rifiuta prima).
        assertEquals(
            StoredWrite.Rejected(StoredValueOps.VALUE_INVALID),
            plan(Action.SetStored("k", StoredOp.INCREMENT, "\${n}")),
        )
    }

    @Test
    fun `toggle starts from false and flips a boolean`() {
        assertEquals("true", assertIs<StoredWrite.Put>(plan(Action.SetStored("on", StoredOp.TOGGLE))).value.text)
        val flipped = assertIs<StoredWrite.Put>(
            plan(Action.SetStored("on", StoredOp.TOGGLE), value("on", StateValueType.BOOLEAN, "true")),
        )
        assertEquals("false", flipped.value.text)
        assertEquals(StateValueType.BOOLEAN, flipped.value.type)
    }

    @Test
    fun `clear removes the key and is a no-op when it is missing`() {
        assertEquals(
            StoredWrite.Remove("k"),
            plan(Action.SetStored("k", StoredOp.CLEAR), value("k", StateValueType.TEXT, "x")),
        )
        assertEquals(StoredWrite.Unchanged, plan(Action.SetStored("k", StoredOp.CLEAR)))
    }

    @Test
    fun `a new key past the 64 key limit is refused, an existing one can still change`() {
        assertEquals(
            StoredWrite.Rejected(StoredValueOps.KEY_LIMIT),
            plan(Action.SetStored("k65", StoredOp.SET, "x"), keyCount = 64),
        )
        assertIs<StoredWrite.Put>(
            plan(Action.SetStored("k", StoredOp.SET, "y"), value("k", StateValueType.TEXT, "x"), keyCount = 64),
        )
    }

    @Test
    fun `an invalid key is refused`() {
        listOf("", "Count", "1count", "a".repeat(33), "con-trattino").forEach { key ->
            assertEquals(StoredWrite.Rejected(StoredValueOps.KEY_INVALID), plan(Action.SetStored(key, StoredOp.CLEAR)), key)
        }
    }

    @Test
    fun `canonical forms are strict`() {
        assertEquals("12", StoredValuePolicy.canonical(" 12.000 ", StateValueType.NUMBER))
        assertEquals("0", StoredValuePolicy.canonical("-0", StateValueType.NUMBER))
        assertNull(StoredValuePolicy.canonical("NaN", StateValueType.NUMBER))
        assertNull(StoredValuePolicy.canonical("1".repeat(31), StateValueType.NUMBER))
        assertEquals("false", StoredValuePolicy.canonical("False", StateValueType.BOOLEAN))
        assertNull(StoredValuePolicy.canonical("on", StateValueType.BOOLEAN))
        assertEquals(" spazi ", StoredValuePolicy.canonical(" spazi ", StateValueType.TEXT))
        assertNull(StoredValuePolicy.canonical("a\u0000b", StateValueType.TEXT))
    }

    @Test
    fun `the stored value never prints its text`() {
        assertFalse("segreto" in value("k", StateValueType.TEXT, "segreto").toString())
    }

    @Test
    fun `mode names are 1 to 24 visible characters and compare ignoring case`() {
        assertEquals("Casa", UserModePolicy.normalize("  Casa "))
        assertNull(UserModePolicy.normalize(""))
        assertNull(UserModePolicy.normalize("   "))
        assertNull(UserModePolicy.normalize("x".repeat(25)))
        assertEquals("x".repeat(24), UserModePolicy.normalize("x".repeat(24)))
        assertNull(UserModePolicy.normalize("a\nb"))
        assertNull(UserModePolicy.normalize("\${mode}"))
        assertTrue(UserModePolicy.validLiteral("Lavoro"))
        assertFalse(UserModePolicy.validLiteral(" Lavoro"))
        assertTrue(UserModePolicy.same("NOTTE", "notte"))
        val snapshot = UserModeSnapshot(listOf("Casa", "Lavoro"), active = "Casa")
        assertEquals("Lavoro", snapshot.find("lavoro"))
        assertNull(snapshot.find("Fuori"))
    }

    @Test
    fun `the mode list rejects duplicates ignoring case and more than twelve modes`() {
        assertEquals(UserModeListIssue.DUPLICATE, UserModePolicy.checkAdd(listOf("Casa"), "casa"))
        assertEquals(UserModeListIssue.NAME_INVALID, UserModePolicy.checkAdd(listOf("Casa"), " "))
        assertEquals(UserModeListIssue.TOO_MANY, UserModePolicy.checkAdd((1..12).map { "m$it" }, "Nuova"))
        assertNull(UserModePolicy.checkAdd(listOf("Casa"), "Lavoro"))
        // Rinominare cambiando solo le maiuscole è ammesso; verso il nome di un'altra no.
        assertNull(UserModePolicy.checkRename(listOf("Casa", "Lavoro"), "Casa", "CASA"))
        assertEquals(UserModeListIssue.DUPLICATE, UserModePolicy.checkRename(listOf("Casa", "Lavoro"), "Casa", "lavoro"))
        assertEquals(UserModeListIssue.UNKNOWN, UserModePolicy.checkRename(listOf("Casa"), "Fuori", "Via"))
    }

    @Test
    fun `rules that use a mode are found in trigger, conditions and actions`() {
        val draft = AutomationDraft(
            name = "r",
            trigger = Trigger.UserModeChanged("casa"),
            conditions = Condition.Not(Condition.UserModeIs("Notte")),
            actions = listOf(
                Action.If(Condition.UserModeIs("Fuori"), then = listOf(Action.SetUserMode("Lavoro"))),
            ),
        )
        assertEquals(listOf("casa", "Notte", "Fuori", "Lavoro"), UserModePolicy.modesUsedBy(draft))
        // Anche le condizioni di if/while contano come uso «come condizione».
        assertEquals(listOf("Notte", "Fuori"), UserModePolicy.modesInConditions(draft))
        assertTrue(UserModePolicy.uses(draft, "CASA"))
        assertFalse(UserModePolicy.uses(draft, "Palestra"))
    }
}
