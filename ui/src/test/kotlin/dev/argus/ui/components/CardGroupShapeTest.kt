package dev.argus.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Posizione e angoli delle card in un gruppo (tavola «Forme e raggi»: esterno 24, interno 6). */
class CardGroupShapeTest {
    @Test
    fun `a single element is alone and a group has first middle last`() {
        assertEquals(GroupPosition.Only, groupPositionOf(0, 1))
        assertEquals(GroupPosition.Only, groupPositionOf(0, 0))
        assertEquals(GroupPosition.First, groupPositionOf(0, 3))
        assertEquals(GroupPosition.Middle, groupPositionOf(1, 3))
        assertEquals(GroupPosition.Last, groupPositionOf(2, 3))
        assertEquals(GroupPosition.First, groupPositionOf(0, 2))
        assertEquals(GroupPosition.Last, groupPositionOf(1, 2))
    }

    @Test
    fun `corners are large on the outside of the group and small where cards touch`() {
        val density = Density(1f)
        val size = Size(100f, 100f)
        fun corners(position: GroupPosition): List<Float> {
            val shape = groupShape(position)
            return listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart).map { it.toPx(size, density) }
        }
        assertEquals(listOf(24f, 24f, 24f, 24f), corners(GroupPosition.Only))
        assertEquals(listOf(24f, 24f, 6f, 6f), corners(GroupPosition.First))
        assertEquals(listOf(6f, 6f, 6f, 6f), corners(GroupPosition.Middle))
        assertEquals(listOf(6f, 6f, 24f, 24f), corners(GroupPosition.Last))
    }

    @Test
    fun `log and recent runs groups can pass their own radii`() {
        assertEquals(
            RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 5.dp, bottomEnd = 5.dp),
            groupShape(GroupPosition.First, outer = 22.dp, inner = 5.dp),
        )
    }
}
