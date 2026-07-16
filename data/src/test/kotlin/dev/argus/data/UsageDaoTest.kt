package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.entities.UsageEventEntity
import dev.argus.data.entities.UsageEventKind
import dev.argus.data.entities.UsageEventOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class UsageDaoTest {
    private lateinit var db: ArgusDatabase

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private fun event(
        providerId: String = "openai",
        timestampMs: Long = 1_000,
        model: String = "gpt-5.5",
        kind: UsageEventKind = UsageEventKind.ACT_V2,
        outcome: UsageEventOutcome = UsageEventOutcome.OK,
        tokensIn: Long? = null,
        tokensOut: Long? = null,
        costMicros: Long? = null,
        pricingVersion: String? = null,
    ) = UsageEventEntity(
        timestampMs = timestampMs,
        providerId = providerId,
        model = model,
        kind = kind,
        outcome = outcome,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        costMicros = costMicros,
        pricingVersion = pricingVersion,
    )

    @Test
    fun `insert assigns id and event roundtrips enums`() = runTest {
        val id = db.usageDao().insert(
            event(
                kind = UsageEventKind.ACT_V2,
                outcome = UsageEventOutcome.BLOCKED_BUDGET,
                tokensIn = null,
                tokensOut = null,
            ),
        )
        assertTrue(id > 0)

        val rows = db.usageDao().aggregateBetween(0, 10_000)
        assertEquals(1, rows.size)
        val agg = rows.single()
        assertEquals("openai", agg.providerId)
        assertEquals(1, agg.calls)
        assertEquals(1, agg.blockedCalls)
        assertNull(agg.tokensIn)
    }

    @Test
    fun `aggregateBetween groups by provider inside the window only`() = runTest {
        db.usageDao().insert(event(providerId = "openai", timestampMs = 1_000, tokensIn = 100, tokensOut = 10))
        db.usageDao().insert(event(providerId = "openai", timestampMs = 1_100, tokensIn = 50, tokensOut = 5))
        db.usageDao().insert(
            event(
                providerId = "anthropic",
                timestampMs = 1_050,
                outcome = UsageEventOutcome.ERROR,
                tokensIn = null,
                tokensOut = null,
            ),
        )
        // Fuori finestra.
        db.usageDao().insert(event(providerId = "openai", timestampMs = 9_000, tokensIn = 999, tokensOut = 99))

        val rows = db.usageDao().aggregateBetween(500, 2_000)
        assertEquals(2, rows.size)
        assertEquals(listOf("anthropic", "openai"), rows.map { it.providerId })

        val openai = rows.first { it.providerId == "openai" }
        assertEquals(2, openai.calls)
        assertEquals(2, openai.okCalls)
        assertEquals(150L, openai.tokensIn)
        assertEquals(15L, openai.tokensOut)

        val anthropic = rows.first { it.providerId == "anthropic" }
        assertEquals(1, anthropic.calls)
        assertEquals(1, anthropic.errorCalls)
        assertNull(anthropic.tokensIn)
    }

    @Test
    fun `blocked budget events count as calls without tokens`() = runTest {
        db.usageDao().insert(
            event(
                providerId = "openai",
                model = "unknown",
                outcome = UsageEventOutcome.BLOCKED_BUDGET,
                tokensIn = null,
                tokensOut = null,
            ),
        )
        val agg = db.usageDao().aggregateBetween(0, 10_000).single()
        assertEquals(1, agg.calls)
        assertEquals(1, agg.blockedCalls)
        assertEquals(0, agg.okCalls)
        assertNull(agg.tokensIn)
    }

    @Test
    fun `sum of cost ignores null rows but keeps non-null total`() = runTest {
        db.usageDao().insert(event(providerId = "openai", timestampMs = 1_000, costMicros = 1_250))
        db.usageDao().insert(event(providerId = "openai", timestampMs = 1_100, costMicros = null))
        val agg = db.usageDao().aggregateBetween(0, 10_000).single()
        assertEquals(1_250L, agg.costMicros)
    }

    @Test
    fun `purgeBefore deletes only strictly older events`() = runTest {
        db.usageDao().insert(event(timestampMs = 1_000))
        db.usageDao().insert(event(timestampMs = 2_000))

        val purged = db.usageDao().purgeBefore(2_000)
        assertEquals(1, purged)

        val agg = db.usageDao().aggregateBetween(0, 10_000).single()
        assertEquals(1, agg.calls)
    }

    @Test
    fun `maintenance purges usage events older than policy window`() = runTest {
        db.usageDao().insert(event(timestampMs = 1_000))
        db.usageDao().insert(event(timestampMs = 4_500))

        val result = RoomJournalMaintenance(
            db,
            JournalRetentionPolicy(runningStaleAfterMillis = 100, usageRetentionMillis = 1_000),
        ).run(nowMillis = 5_000)

        assertEquals(1, result.purgedUsageEvents)
        assertEquals(1, db.usageDao().aggregateBetween(0, 10_000).single().calls)
    }
}
