package dev.argus.automation.manual

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Avvio manuale (F8): ogni ingresso accetta solo l'id di una regola ARMATA con trigger `manual`; gli
 * intent che possono arrivare da fuori (scorciatoia, widget) portano anche il token di quella regola.
 */
class ManualRuleRunnerTest {
    private fun rejected(result: ManualRunResult): ManualRejection =
        assertIs<ManualRunResult.Rejected>(result).reason

    @Test
    fun `an armed manual rule is handed to the engine with its approved fingerprint`() = runTest {
        val rule = manualRule("m1", name = "Apri il garage")
        val rig = Rig(listOf(rule))

        val result = assertIs<ManualRunResult.Accepted>(rig.runner.runFromApp("m1"))

        assertEquals("Apri il garage", result.ruleName)
        assertEquals(false, result.paused)
        val envelope = rig.dispatcher.envelopes.single()
        val event = assertIs<TriggerEvent.ManualFired>(envelope.event)
        assertEquals(AutomationId("m1"), event.automationId)
        assertEquals(rule.approvalFingerprint, event.approvalFingerprint)
        assertTrue(envelope.id.value.startsWith("manual:${rule.approvalFingerprint?.value}:"), envelope.id.value)
    }

    @Test
    fun `every entry refuses an unknown rule`() = runTest {
        val rig = Rig(listOf(manualRule("m1")))

        assertEquals(ManualRejection.UNKNOWN_RULE, rejected(rig.runner.runFromApp("nope")))
        assertEquals(ManualRejection.UNKNOWN_RULE, rejected(rig.runner.runFromTile("nope")))
        assertEquals(ManualRejection.UNKNOWN_RULE, rejected(rig.runner.runFromApp("")))
        assertEquals(ManualRejection.UNKNOWN_RULE, rejected(rig.runner.runFromApp("x".repeat(500))))
        assertTrue(rig.dispatcher.envelopes.isEmpty())
    }

    @Test
    fun `a rule that is not armed is refused`() = runTest {
        val rig = Rig(
            listOf(
                manualRule("off", status = AutomationStatus.DISABLED),
                manualRule("review", status = AutomationStatus.NEEDS_REVIEW),
                manualRule("pending", status = AutomationStatus.PENDING_APPROVAL),
                manualRule("armed-but-off", status = AutomationStatus.ARMED, enabled = false),
            ),
        )

        listOf("off", "review", "pending", "armed-but-off").forEach { id ->
            assertEquals(ManualRejection.NOT_ARMED, rejected(rig.runner.runFromApp(id)), id)
            assertEquals(ManualRejection.NOT_ARMED, rejected(rig.runner.runFromTile(id)), id)
        }
        assertTrue(rig.dispatcher.envelopes.isEmpty())
    }

    @Test
    fun `an armed rule with another trigger is refused`() = runTest {
        val rig = Rig(
            listOf(
                manualRule("time", trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC")),
                manualRule("now", trigger = Trigger.Immediate),
            ),
        )

        assertEquals(ManualRejection.NOT_MANUAL, rejected(rig.runner.runFromApp("time")))
        assertEquals(ManualRejection.NOT_MANUAL, rejected(rig.runner.runFromTile("now")))
        // Anche con un token valido: l'intent giusto per la regola sbagliata non basta.
        val token = rig.tokens.tokenFor(AutomationId("time"))
        assertEquals(ManualRejection.NOT_MANUAL, rejected(rig.runner.runFromIntent("time", token)))
        assertTrue(rig.dispatcher.envelopes.isEmpty())
    }

    @Test
    fun `an intent without the right token starts nothing`() = runTest {
        val rig = Rig(listOf(manualRule("m1"), manualRule("m2")))
        val tokenOfM1 = rig.tokens.tokenFor(AutomationId("m1"))
        rig.tokens.tokenFor(AutomationId("m2"))

        assertEquals(ManualRejection.BAD_TOKEN, rejected(rig.runner.runFromIntent("m1", null)))
        assertEquals(ManualRejection.BAD_TOKEN, rejected(rig.runner.runFromIntent("m1", "")))
        assertEquals(ManualRejection.BAD_TOKEN, rejected(rig.runner.runFromIntent("m1", "guess")))
        assertEquals(ManualRejection.BAD_TOKEN, rejected(rig.runner.runFromIntent(null, tokenOfM1)))
        assertEquals(ManualRejection.BAD_TOKEN, rejected(rig.runner.runFromIntent("", tokenOfM1)))
        // Il token di una regola non vale per un'altra.
        assertEquals(ManualRejection.BAD_TOKEN, rejected(rig.runner.runFromIntent("m2", tokenOfM1)))
        // Una regola che non ha mai avuto una scorciatoia non ha token: non si lancia da un intent.
        assertEquals(ManualRejection.BAD_TOKEN, rejected(rig.runner.runFromIntent("never-pinned", tokenOfM1)))
        assertTrue(rig.dispatcher.envelopes.isEmpty())
    }

    @Test
    fun `the right token starts the rule and a forgotten token stops working`() = runTest {
        val rig = Rig(listOf(manualRule("m1")))
        val token = rig.tokens.tokenFor(AutomationId("m1"))

        assertIs<ManualRunResult.Accepted>(rig.runner.runFromIntent("m1", token))
        assertEquals(1, rig.dispatcher.envelopes.size)

        rig.tokens.forget(AutomationId("m1"))
        assertEquals(ManualRejection.BAD_TOKEN, rejected(rig.runner.runFromIntent("m1", token)))
        assertEquals(1, rig.dispatcher.envelopes.size)
    }

    @Test
    fun `a rule disarmed after its shortcut was pinned is refused even with the right token`() = runTest {
        val rig = Rig(listOf(manualRule("m1")))
        val token = rig.tokens.tokenFor(AutomationId("m1"))
        rig.store.disable(AutomationId("m1"))

        assertEquals(ManualRejection.NOT_ARMED, rejected(rig.runner.runFromIntent("m1", token)))
        assertTrue(rig.dispatcher.envelopes.isEmpty())
    }

    @Test
    fun `two taps are two distinct events`() = runTest {
        val rig = Rig(listOf(manualRule("m1")))

        rig.runner.runFromApp("m1")
        rig.runner.runFromApp("m1")

        val ids = rig.dispatcher.envelopes.map { it.id.value }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `while paused the event is still delivered so the engine can log the skip`() = runTest {
        val rig = Rig(listOf(manualRule("m1")), paused = true)

        val result = assertIs<ManualRunResult.Accepted>(rig.runner.runFromApp("m1"))

        assertTrue(result.paused)
        assertEquals(1, rig.dispatcher.envelopes.size)
    }

    @Test
    fun `the tile entry needs no token because no foreign intent can reach it`() = runTest {
        val rig = Rig(listOf(manualRule("m1")))

        assertIs<ManualRunResult.Accepted>(rig.runner.runFromTile("m1"))
        assertEquals(1, rig.dispatcher.envelopes.size)
    }
}
