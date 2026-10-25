package dev.argus.engine.brain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkBlocksTest {
    @Test fun `complete blocks are removed, also multiple and multiline`() {
        assertEquals("Ciao!", ThinkBlocks.strip("<think>uno</think>Ciao!"))
        assertEquals("A B", ThinkBlocks.strip("A <think>x\ny</think>B<think>z</think>").replace("  ", " "))
    }

    @Test fun `tags are matched case-insensitively`() {
        assertEquals("ok", ThinkBlocks.strip("<THINK>x</THINK>ok"))
    }

    @Test fun `orphan closing tag drops the implicit reasoning before it`() {
        assertEquals("risposta", ThinkBlocks.strip("ragionamento</think>risposta"))
    }

    @Test fun `unclosed block is dropped by default and kept on request`() {
        assertEquals("prima", ThinkBlocks.strip("prima<think>mai chiuso"))
        val kept = ThinkBlocks.strip("prima<think>mai chiuso", dropUnclosed = false)
        assertTrue(ThinkBlocks.hasOpenBlock(kept))
        assertFalse(ThinkBlocks.hasOpenBlock(ThinkBlocks.strip("pulito")))
    }
}
