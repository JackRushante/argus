package dev.argus.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Angoli dei segmenti del filtro: pillola per il segmento scelto e per le due estremita', 8 negli altri. */
class SegmentedFilterTest {
    private val pill = 20.dp
    private val inner = 8.dp

    @Test
    fun `the selected segment is a pill wherever it sits`() {
        (0..4).forEach { index ->
            assertEquals(pill to pill, segmentCornerRadii(index, 5, selected = true, pillRadius = pill), "indice $index")
        }
    }

    @Test
    fun `unselected inner segments are small and the group ends stay round`() {
        assertEquals(pill to inner, segmentCornerRadii(0, 5, selected = false, pillRadius = pill))
        assertEquals(inner to inner, segmentCornerRadii(1, 5, selected = false, pillRadius = pill))
        assertEquals(inner to inner, segmentCornerRadii(3, 5, selected = false, pillRadius = pill))
        assertEquals(inner to pill, segmentCornerRadii(4, 5, selected = false, pillRadius = pill))
    }

    @Test
    fun `a lone segment is a pill on both sides`() {
        assertEquals(pill to pill, segmentCornerRadii(0, 1, selected = false, pillRadius = pill))
    }
}
