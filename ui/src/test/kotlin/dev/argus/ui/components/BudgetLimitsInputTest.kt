package dev.argus.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BudgetLimitsInputTest {
    @Test fun `campo vuoto o zero significa illimitato`() {
        assertEquals(0, BudgetLimitsInput.parseCount(""))
        assertEquals(0, BudgetLimitsInput.parseCount("   "))
        assertEquals(0, BudgetLimitsInput.parseCount("0"))
        assertEquals(0L, BudgetLimitsInput.parseTokens(""))
        assertEquals(0L, BudgetLimitsInput.parseCostMicros(""))
        assertEquals(0L, BudgetLimitsInput.parseCostMicros("0"))
    }

    @Test fun `NaN e testo non numerico sono errore e mai illimitato`() {
        for (bad in listOf("NaN", "nan", "Infinity", "-Infinity", "abc", "1e5", "0x10", "12a", "$5", "1 000")) {
            assertNull(BudgetLimitsInput.parseCostMicros(bad), "costo '$bad'")
            assertNull(BudgetLimitsInput.parseCount(bad), "conteggio '$bad'")
            assertNull(BudgetLimitsInput.parseTokens(bad), "token '$bad'")
        }
    }

    @Test fun `i segni e i separatori sono ammessi solo dove previsto`() {
        assertNull(BudgetLimitsInput.parseCount("-1"))
        assertNull(BudgetLimitsInput.parseCount("+5"))
        assertNull(BudgetLimitsInput.parseCount("1.5"))
        assertNull(BudgetLimitsInput.parseCount("1,5"))
        assertNull(BudgetLimitsInput.parseTokens("-1"))
        assertNull(BudgetLimitsInput.parseTokens("1.5"))
        assertNull(BudgetLimitsInput.parseCostMicros("-1"))
        assertNull(BudgetLimitsInput.parseCostMicros("+1"))
        assertNull(BudgetLimitsInput.parseCostMicros("1.2.3"))
        assertNull(BudgetLimitsInput.parseCostMicros("1,2,3"))
        assertNull(BudgetLimitsInput.parseCostMicros("1.234,56"))
        assertNull(BudgetLimitsInput.parseCostMicros("."))
        assertNull(BudgetLimitsInput.parseCostMicros(","))
    }

    @Test fun `le cifre non ASCII non sono accettate`() {
        assertNull(BudgetLimitsInput.parseCount("٣"))
        assertNull(BudgetLimitsInput.parseTokens("１２"))
        assertNull(BudgetLimitsInput.parseCostMicros("２.５"))
    }

    @Test fun `interi validi e spazi ai bordi`() {
        assertEquals(25, BudgetLimitsInput.parseCount("25"))
        assertEquals(25, BudgetLimitsInput.parseCount(" 25 "))
        assertEquals(1_500_000L, BudgetLimitsInput.parseTokens("1500000"))
        assertEquals(7, BudgetLimitsInput.parseCount("007"))
    }

    @Test fun `i valori oltre il tipo sono errore`() {
        assertNull(BudgetLimitsInput.parseCount("2147483648"))
        assertEquals(Int.MAX_VALUE, BudgetLimitsInput.parseCount("2147483647"))
        assertNull(BudgetLimitsInput.parseTokens("9223372036854775808"))
        assertNull(BudgetLimitsInput.parseCostMicros("9999999999999999999999"))
        assertNull(BudgetLimitsInput.parseCostMicros("9223372036855"))
    }

    @Test fun `il costo accetta virgola o punto e converte in micro USD`() {
        assertEquals(2_250_000L, BudgetLimitsInput.parseCostMicros("2.25"))
        assertEquals(2_250_000L, BudgetLimitsInput.parseCostMicros("2,25"))
        assertEquals(5_000_000L, BudgetLimitsInput.parseCostMicros("5"))
        assertEquals(5_000_000L, BudgetLimitsInput.parseCostMicros("5."))
        assertEquals(500_000L, BudgetLimitsInput.parseCostMicros(".5"))
        assertEquals(500_000L, BudgetLimitsInput.parseCostMicros(",5"))
        assertEquals(1L, BudgetLimitsInput.parseCostMicros("0.000001"))
        assertEquals(0L, BudgetLimitsInput.parseCostMicros("0.0000004"))
        assertEquals(1L, BudgetLimitsInput.parseCostMicros("0.0000005"))
        assertEquals(10_100_000L, BudgetLimitsInput.parseCostMicros("10.1"))
    }
}
