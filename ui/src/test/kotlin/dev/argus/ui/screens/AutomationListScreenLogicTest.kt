package dev.argus.ui.screens

import dev.argus.ui.model.StatusFilter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B49 (0.4.0): con un filtro attivo e nessun risultato la lista diceva «Nessuna automazione», come se le
 * regole fossero sparite. Ora distingue «non ce ne sono» da «nessuna con questo filtro».
 */
class AutomationListScreenLogicTest {
    @Test
    fun `rows on screen mean no empty state, filter or not`() {
        StatusFilter.entries.forEach { filter ->
            assertEquals(ListEmptyKind.NONE, listEmptyKind(rowCount = 3, filter = filter), filter.name)
        }
    }

    @Test
    fun `no rules and no filter invites to ask for the first one`() {
        assertEquals(ListEmptyKind.NO_RULES, listEmptyKind(rowCount = 0, filter = StatusFilter.ALL))
    }

    @Test
    fun `no rows under an active filter is its own message, for every filter`() {
        StatusFilter.entries.filter { it != StatusFilter.ALL }.forEach { filter ->
            assertEquals(
                ListEmptyKind.NO_MATCH_FOR_FILTER,
                listEmptyKind(rowCount = 0, filter = filter),
                filter.name,
            )
        }
    }
}
