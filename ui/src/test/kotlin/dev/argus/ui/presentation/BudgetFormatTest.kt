package dev.argus.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetFormatTest {
    // Le label con parole ("illimitato"/"n/d") passano SEMPRE `RenderLanguage.IT` esplicito: senza,
    // dipenderebbero dal locale della macchina. I casi EN in fondo pinnano il default inglese.
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
        assertEquals("n/d", BudgetFormat.costLabel(null, language = RenderLanguage.IT))
        assertEquals("n/a", BudgetFormat.costLabel(null, language = RenderLanguage.EN))
    }

    @Test fun `calls label con e senza limite`() {
        assertEquals("3 / 20", BudgetFormat.callsLabel(3, 20, RenderLanguage.IT))
        assertEquals("3 · illimitato", BudgetFormat.callsLabel(3, null, RenderLanguage.IT))
        // Default EN dell'app.
        assertEquals("3 · unlimited", BudgetFormat.callsLabel(3, null, RenderLanguage.EN))
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

    @Test fun `tokens label separa in e out e nd solo se entrambi mancano`() {
        assertEquals("in 1.500 · out 300", BudgetFormat.tokensLabel(1_500, 300, RenderLanguage.IT))
        assertEquals("in 0 · out 0", BudgetFormat.tokensLabel(0, 0, RenderLanguage.IT))
        assertEquals("in 1.500 · out n/d", BudgetFormat.tokensLabel(1_500, null, RenderLanguage.IT))
        assertEquals("in n/d · out 300", BudgetFormat.tokensLabel(null, 300, RenderLanguage.IT))
        assertEquals("n/d", BudgetFormat.tokensLabel(null, null, RenderLanguage.IT))
        // Default EN dell'app: "n/d" diventa "n/a".
        assertEquals("in 1,500 · out n/a", BudgetFormat.tokensLabel(1_500, null, RenderLanguage.EN))
        assertEquals("n/a", BudgetFormat.tokensLabel(null, null, RenderLanguage.EN))
    }

    @Test fun `token cap label con e senza limite`() {
        assertEquals("300 / 1.000", BudgetFormat.tokensCapLabel(300, 1_000, RenderLanguage.IT))
        assertEquals("300 · illimitato", BudgetFormat.tokensCapLabel(300, null, RenderLanguage.IT))
        assertEquals("300 · illimitato", BudgetFormat.tokensCapLabel(300, 0, RenderLanguage.IT))
        assertEquals("n/d · illimitato", BudgetFormat.tokensCapLabel(null, null, RenderLanguage.IT))
        assertEquals("n/d / 1.000", BudgetFormat.tokensCapLabel(null, 1_000, RenderLanguage.IT))
        // Default EN dell'app.
        assertEquals("300 · unlimited", BudgetFormat.tokensCapLabel(300, null, RenderLanguage.EN))
        assertEquals("n/a · unlimited", BudgetFormat.tokensCapLabel(null, null, RenderLanguage.EN))
    }

    // B49: i token a sei o sette cifre senza separatori non si leggono (1450000).
    @Test fun `i numeri grandi usano il separatore delle migliaia della lingua`() {
        assertEquals("1,450,000", BudgetFormat.grouped(1_450_000, RenderLanguage.EN))
        assertEquals("1.450.000", BudgetFormat.grouped(1_450_000, RenderLanguage.IT))
        assertEquals("12,345", BudgetFormat.grouped(12_345, RenderLanguage.EN))
        assertEquals("1.000", BudgetFormat.grouped(1_000, RenderLanguage.IT))
        assertEquals("999", BudgetFormat.grouped(999, RenderLanguage.IT))
        assertEquals("0", BudgetFormat.grouped(0, RenderLanguage.EN))
        assertEquals("-1,234", BudgetFormat.grouped(-1_234, RenderLanguage.EN))
    }

    @Test fun `le etichette dei token usano il separatore in entrambe le lingue`() {
        assertEquals(
            "in 1.450.000 · out 210.000",
            BudgetFormat.tokensLabel(1_450_000, 210_000, RenderLanguage.IT),
        )
        assertEquals(
            "in 1,450,000 · out 210,000",
            BudgetFormat.tokensLabel(1_450_000, 210_000, RenderLanguage.EN),
        )
        assertEquals(
            "1.450.000 / 5.000.000",
            BudgetFormat.tokensCapLabel(1_450_000, 5_000_000, RenderLanguage.IT),
        )
        assertEquals(
            "1,450,000 / 5,000,000",
            BudgetFormat.tokensCapLabel(1_450_000, 5_000_000, RenderLanguage.EN),
        )
        assertEquals("1.200 · illimitato", BudgetFormat.callsLabel(1_200, null, RenderLanguage.IT))
        assertEquals("1,200 / 5,000", BudgetFormat.callsLabel(1_200, 5_000, RenderLanguage.EN))
    }

    @Test fun `token ratio clampata e zero se illimitato o nd`() {
        assertEquals(0f, BudgetFormat.tokenRatio(500, null))
        assertEquals(0f, BudgetFormat.tokenRatio(null, 1_000))
        assertEquals(0.5f, BudgetFormat.tokenRatio(500, 1_000))
        assertEquals(1f, BudgetFormat.tokenRatio(2_000, 1_000))
    }

    @Test fun `parse tokens interi non negativi`() {
        assertEquals(1_500L, BudgetFormat.parseTokens("1500"))
        assertEquals(0L, BudgetFormat.parseTokens(""))
        assertEquals(0L, BudgetFormat.parseTokens("0"))
        assertNull(BudgetFormat.parseTokens("abc"))
        assertNull(BudgetFormat.parseTokens("-1"))
        assertNull(BudgetFormat.parseTokens("1.5"))
    }
}
