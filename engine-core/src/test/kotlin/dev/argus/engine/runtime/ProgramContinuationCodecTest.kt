package dev.argus.engine.runtime

import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 0.8 `ask`: la continuazione di un programma torna identica, label comprese; il resto non si legge. */
class ProgramContinuationCodecTest {
    private val continuation = ProgramContinuation(
        askPath = ActionPath("2.while[3].1"),
        variables = linkedMapOf(
            "msg" to VarValue("Ci vediamo \"alle\" 8\n", VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, setOf(ValueProvenance.NOTIFICATION)),
            "n" to VarValue("3", VarType.NUMBER, IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, setOf(ValueProvenance.LITERAL)),
            "out" to VarValue("ok", VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.SECRET, setOf(ValueProvenance.SHELL, ValueProvenance.MODEL)),
        ),
        loops = mapOf("2" to LoopPosition(iteration = 3, effective = 5)),
        generativeCalls = 4,
        startedAtMillis = 1_700_000_000_000,
    )

    @Test
    fun `a continuation round trips with every label`() {
        val decoded = ProgramContinuationCodec.decode(ProgramContinuationCodec.encode(continuation))
        requireNotNull(decoded)
        assertEquals(continuation.askPath, decoded.askPath)
        assertEquals(continuation.variables, decoded.variables)
        assertEquals(continuation.loops, decoded.loops)
        assertEquals(continuation.generativeCalls, decoded.generativeCalls)
        assertEquals(continuation.startedAtMillis, decoded.startedAtMillis)
    }

    @Test
    fun `anything malformed decodes to nothing`() {
        val good = ProgramContinuationCodec.encode(continuation)
        listOf(
            "",
            "[]",
            good.replace("\"v\":1", "\"v\":2"),
            good.replace("TAINTED", "WASHED"),
            good.replace("\"2.while[3].1\"", "\"../1\""),
            good.replace("\"msg\"", "\"Bad Name\""),
            good.replace("\"iteration\":3", "\"iteration\":0"),
            good.replace("\"generativeCalls\":4", "\"generativeCalls\":-1"),
            good.replace("\"NOTIFICATION\"", "\"NOWHERE\""),
        ).forEach { assertNull(ProgramContinuationCodec.decode(it), it) }
    }

    @Test
    fun `a continuation never prints its values`() {
        val text = continuation.toString()
        listOf("Ci vediamo", "ok\"").forEach { assertEquals(false, it in text, text) }
    }
}
