package dev.argus.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetFormatTest {
    @Test fun `usd label due decimali`() {
        assertEquals("$2.25", BudgetFormat.usdLabel(2_250_000))
        assertEquals("$0.00", BudgetFormat.usdLabel(0))
    }

    @Test fun `cost label mostra usd ed eur al tasso fisso`() {
        val label = BudgetFormat.costLabel(10_000_000, eurPerUsd = 0.92)
        assertTrue(label.contains("$10.00"), "manca USD in: $label")
        assertTrue(label.contains("€9.20"), "manca EUR in: $label")
    }

    @Test fun `cost label null e' nd`() {
        assertEquals("n/d", BudgetFormat.costLabel(null))
    }

    @Test fun `calls label con e senza limite`() {
        assertEquals("3 / 20", BudgetFormat.callsLabel(3, 20))
        assertEquals("3 · illimitato", BudgetFormat.callsLabel(3, null))
    }

    @Test fun `ratio clampata e zero se illimitato`() {
        assertEquals(0f, BudgetFormat.ratio(5, null))
        assertEquals(1f, BudgetFormat.ratio(30, 20))
        assertEquals(0.5f, BudgetFormat.ratio(10, 20))
    }

    @Test fun `cost ratio clampata e zero se nullo`() {
        assertEquals(0f, BudgetFormat.costRatio(1_000_000, null))
        assertEquals(0f, BudgetFormat.costRatio(null, 5_000_000))
        assertEquals(0.5f, BudgetFormat.costRatio(2_500_000, 5_000_000))
        assertEquals(1f, BudgetFormat.costRatio(9_000_000, 5_000_000))
    }

    @Test fun `parse usd accetta punto e virgola`() {
        assertEquals(2_250_000L, BudgetFormat.parseUsdToMicros("2.25"))
        assertEquals(2_250_000L, BudgetFormat.parseUsdToMicros("2,25"))
        assertNull(BudgetFormat.parseUsdToMicros("abc"))
        assertEquals(0L, BudgetFormat.parseUsdToMicros(""))
        assertEquals(0L, BudgetFormat.parseUsdToMicros("0"))
        assertNull(BudgetFormat.parseUsdToMicros("-1"))
    }
}
