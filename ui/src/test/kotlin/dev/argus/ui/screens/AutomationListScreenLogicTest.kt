package dev.argus.ui.screens

import dev.argus.ui.components.GroupPosition
import dev.argus.ui.components.TriggerShape
import dev.argus.ui.components.TriggerTone
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.model.TodayErrorsSummary
import dev.argus.ui.theme.ArgusSpacing
import androidx.compose.ui.unit.dp
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

    // --- 0.7.0 (redesign A): gruppi per stato, forme dei trigger, contatori del riepilogo ---------------

    private fun row(id: String, status: StatusBadge, triggerIconKey: String = "time") = AutomationRow(
        id = id, name = "regola $id", triggerIconKey = triggerIconKey, triggerSummary = "sommario",
        status = status, enabled = status == StatusBadge.ARMED, isGenerative = false, hasWarnings = false,
        lastFiredLabel = null, nextFireLabel = null,
    )

    @Test
    fun `rows come out ordered pending then needs review then armed then disabled, whatever the arrival order`() {
        val arrival = listOf(
            row("d1", StatusBadge.DISABLED), row("a1", StatusBadge.ARMED), row("n1", StatusBadge.NEEDS_REVIEW),
            row("p1", StatusBadge.PENDING_APPROVAL), row("a2", StatusBadge.ARMED), row("p2", StatusBadge.PENDING_APPROVAL),
        )

        assertEquals(
            listOf("p1", "p2", "n1", "a1", "a2", "d1"),
            listEntriesOf(arrival).map { it.row.id },
            "a parita' di stato resta l'ordine dell'host",
        )
    }

    @Test
    fun `each status is its own group with its own corners and a gap only between groups`() {
        val entries = listEntriesOf(
            listOf(
                row("a1", StatusBadge.ARMED), row("a2", StatusBadge.ARMED), row("a3", StatusBadge.ARMED),
                row("p1", StatusBadge.PENDING_APPROVAL), row("d1", StatusBadge.DISABLED),
            ),
        )

        assertEquals(
            listOf(
                GroupPosition.Only, GroupPosition.First, GroupPosition.Middle, GroupPosition.Last, GroupPosition.Only,
            ),
            entries.map { it.position },
        )
        assertEquals(
            listOf(0.dp, ArgusSpacing.Medium, ArgusSpacing.GroupGap, ArgusSpacing.GroupGap, ArgusSpacing.Medium),
            entries.map { it.topGap },
            "nessuno spazio sopra la prima, 12 tra i gruppi, 3 dentro il gruppo",
        )
    }

    @Test
    fun `no rows, no entries`() {
        assertEquals(emptyList(), listEntriesOf(emptyList()))
    }

    @Test
    fun `the trigger kind picks the shape and an unknown kind falls back to the cookie`() {
        assertEquals(TriggerShape.Circle, listTriggerShape("time"))
        assertEquals(TriggerShape.Squircle, listTriggerShape("notification"))
        assertEquals(TriggerShape.Drop, listTriggerShape("geofence"))
        assertEquals(TriggerShape.Rhombus, listTriggerShape("phone"))
        assertEquals(TriggerShape.Arch, listTriggerShape("connectivity"))
        listOf("screen", "battery", "audio_output", "mode_changed", "media", "manual", "app_event", "sconosciuto")
            .forEach { assertEquals(TriggerShape.Cookie, listTriggerShape(it), it) }
    }

    @Test
    fun `the status picks the tone of the trigger container`() {
        assertEquals(TriggerTone.Normal, listTriggerTone(StatusBadge.ARMED))
        assertEquals(TriggerTone.Pending, listTriggerTone(StatusBadge.PENDING_APPROVAL))
        assertEquals(TriggerTone.NeedsReview, listTriggerTone(StatusBadge.NEEDS_REVIEW))
        assertEquals(TriggerTone.Disabled, listTriggerTone(StatusBadge.DISABLED))
    }

    @Test
    fun `every summary counter applies the filter that lists exactly what it counts`() {
        assertEquals(StatusFilter.ARMED, counterFilter(StatusBadge.ARMED))
        assertEquals(StatusFilter.PENDING, counterFilter(StatusBadge.PENDING_APPROVAL))
        assertEquals(StatusFilter.NEEDS_REVIEW, counterFilter(StatusBadge.NEEDS_REVIEW))
    }

    @Test
    fun `the today tile tells unknown from none, one and many, never a zero for a log it could not read`() {
        assertEquals(TodayKind.UNKNOWN, todayKindOf(null))
        assertEquals(TodayKind.NONE, todayKindOf(TodayErrorsSummary(0, null, null)))
        assertEquals(TodayKind.ONE, todayKindOf(TodayErrorsSummary(1, "Backup", "12:05")))
        assertEquals(TodayKind.MANY, todayKindOf(TodayErrorsSummary(4, "Backup", "12:05")))
    }
}
