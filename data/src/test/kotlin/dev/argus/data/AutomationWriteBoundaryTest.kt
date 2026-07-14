package dev.argus.data

import dev.argus.engine.runtime.AutomationStore
import kotlin.test.Test
import kotlin.test.assertFalse

class AutomationWriteBoundaryTest {
    @Test
    fun `runtime store does not expose a rule save bypass`() {
        assertFalse(
            AutomationStore::class.java.methods.any { it.name == "save" },
            "AutomationStore non deve permettere di fabbricare regole approvate",
        )
        assertFalse(
            RoomAutomationStore::class.java.methods.any { it.name == "save" },
            "RoomAutomationStore deve essere read-runtime; l'arm passa dal DraftRepository",
        )
    }
}
