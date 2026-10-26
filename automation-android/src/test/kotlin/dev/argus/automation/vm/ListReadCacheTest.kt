package dev.argus.automation.vm

import dev.argus.automation.ApprovalFlowReview
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftReview
import dev.argus.engine.safety.PendingDraft
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * B32: le letture costose della lista (review di ogni bozza, batteria) si rifanno solo quando
 * cambia l'input che le determina, non a ogni emissione (ogni scatto aggiorna le regole).
 */
class ListReadCacheTest {
    private val reviewed = mutableListOf<String>()
    private var batteryReads = 0
    private var battery = true
    private var failing = emptySet<String>()

    private val cache = ListReadCache(
        review = { id ->
            reviewed += id.value
            if (id.value in failing) null else reviewOf(id.value)
        },
        readBatteryExempt = {
            batteryReads += 1
            battery
        },
    )

    /** Un fingerprint valido (64 esadecimali minuscoli) e diverso per ogni bozza e revisione. */
    private fun hexOf(seed: String): String = seed.hashCode().toUInt().toString(16).padStart(64, '0')

    private fun draft(id: String, revision: Long = 1) = PendingDraft(
        id = DraftId(id),
        automationId = AutomationId("auto-$id"),
        revision = revision,
        fingerprint = ApprovalFingerprint(hexOf("$id-$revision")),
        draft = AutomationDraft(
            name = "regola $id",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "test")),
        ),
        createdBy = CreatedBy.LLM,
        priority = 0,
        schemaVersion = 1,
        createdAtMillis = 1_000,
        updatedAtMillis = 1_000,
    )

    private fun reviewOf(id: String) =
        ApprovalFlowReview(DraftReview(draft(id), emptyList()), emptyList())

    @Test
    fun `ten emissions with the same drafts read each review once`() = runBlocking {
        val drafts = listOf(draft("a"), draft("b"))
        repeat(10) { cache.reviews(drafts, context = "ctx") }

        assertEquals(listOf("a", "b"), reviewed)
    }

    @Test
    fun `a new draft is reviewed alone while the known ones are reused`() = runBlocking {
        val first = cache.reviews(listOf(draft("a")), context = "ctx")
        val second = cache.reviews(listOf(draft("a"), draft("b")), context = "ctx")

        assertEquals(listOf("a", "b"), reviewed)
        assertSame(first.getValue(DraftId("a")), second.getValue(DraftId("a")))
    }

    @Test
    fun `a revised draft is reviewed again`() = runBlocking {
        cache.reviews(listOf(draft("a", revision = 1)), context = "ctx")
        cache.reviews(listOf(draft("a", revision = 2)), context = "ctx")

        assertEquals(listOf("a", "a"), reviewed)
    }

    @Test
    fun `a changed context rereads every review`() = runBlocking {
        val drafts = listOf(draft("a"), draft("b"))
        cache.reviews(drafts, context = "shizuku-down")
        cache.reviews(drafts, context = "shizuku-down")
        cache.reviews(drafts, context = "shizuku-up")

        assertEquals(listOf("a", "b", "a", "b"), reviewed)
    }

    @Test
    fun `a draft that left the list is read again when it comes back`() = runBlocking {
        cache.reviews(listOf(draft("a")), context = "ctx")
        cache.reviews(emptyList(), context = "ctx")
        cache.reviews(listOf(draft("a")), context = "ctx")

        assertEquals(listOf("a", "a"), reviewed)
    }

    @Test
    fun `a review that failed is retried on the next emission`() = runBlocking {
        failing = setOf("a")
        val first = cache.reviews(listOf(draft("a")), context = "ctx")
        assertNull(first.getValue(DraftId("a")))

        failing = emptySet()
        val second = cache.reviews(listOf(draft("a")), context = "ctx")

        assertEquals(listOf("a", "a"), reviewed)
        assertEquals(reviewOf("a"), second.getValue(DraftId("a")))
    }

    @Test
    fun `the battery is read once per key`() {
        repeat(10) { assertEquals(true, cache.batteryExempt(key = "k1")) }
        assertEquals(1, batteryReads)

        battery = false
        assertEquals(true, cache.batteryExempt(key = "k1"), "stessa chiave: nessuna nuova lettura")
        assertEquals(false, cache.batteryExempt(key = "k2"))
        assertEquals(2, batteryReads)
    }
}
