package dev.argus.brain

import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Condition
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocationRedactionTest {
    private val home = Trigger.Geofence(
        lat = 45.4642, lng = 9.19, radiusM = 150.0, transition = dev.argus.engine.model.Transition.ENTER,
    )

    private fun nestedConditions(): Condition = Condition.And(
        listOf(
            Condition.LocationIn(lat = 41.9028, lng = 12.4964, radiusM = 200.0),
            Condition.Or(
                listOf(
                    Condition.Not(Condition.LocationIn(lat = 40.8518, lng = 14.2681, radiusM = 80.0)),
                    Condition.AppInForeground("com.example"),
                ),
            ),
        ),
    )

    private fun draft() = AutomationDraft(
        name = "casa",
        trigger = home,
        conditions = nestedConditions(),
        actions = listOf(
            Action.SetDnd(DndMode.PRIORITY),
            Action.If(
                condition = Condition.LocationIn(lat = 44.4949, lng = 11.3426, radiusM = 60.0),
                then = listOf(Action.SetDnd(DndMode.OFF)),
                orElse = listOf(
                    Action.While(
                        condition = Condition.Not(Condition.LocationIn(lat = 43.7696, lng = 11.2558, radiusM = 30.0)),
                        body = listOf(Action.SetDnd(DndMode.TOTAL)),
                        maxIterations = 2,
                    ),
                ),
            ),
        ),
    )

    private val coordinateLiterals = listOf(
        "45.4642", "9.19", "41.9028", "12.4964", "40.8518", "14.2681", "44.4949", "11.3426", "43.7696", "11.2558",
    )

    @Test
    fun `redact replaces every coordinate with zero zero, nested ones included`() {
        val redacted = LocationRedaction.redact(draft())

        val geofence = assertIs<Trigger.Geofence>(redacted.draft.trigger)
        assertEquals(0.0, geofence.lat)
        assertEquals(0.0, geofence.lng)
        assertEquals(150.0, geofence.radiusM, "il raggio non è una coordinata")
        val json = ArgusJson.encodeToString(AutomationDraft.serializer(), redacted.draft)
        coordinateLiterals.forEach { assertFalse(it in json, "coordinata $it ancora presente: $json") }
        assertEquals(5, redacted.originals.size)
        assertEquals(LocationRedaction.Coordinates(45.4642, 9.19), redacted.originals["trigger"])
        assertEquals(LocationRedaction.Coordinates(41.9028, 12.4964), redacted.originals["conditions.all[0]"])
        assertEquals(
            LocationRedaction.Coordinates(40.8518, 14.2681),
            redacted.originals["conditions.all[1].any[0].cond"],
        )
        assertEquals(LocationRedaction.Coordinates(44.4949, 11.3426), redacted.originals["actions[1].condition"])
        assertEquals(
            LocationRedaction.Coordinates(43.7696, 11.2558),
            redacted.originals["actions[1].orElse[0].condition.cond"],
        )
    }

    @Test
    fun `restore puts the original coordinates back where the model kept zero zero`() {
        val redacted = LocationRedaction.redact(draft())

        val restored = LocationRedaction.restore(redacted.draft, redacted.originals)

        assertEquals(draft(), restored)
    }

    @Test
    fun `a place the user asked to change stays the new one`() {
        val redacted = LocationRedaction.redact(draft())
        val moved = redacted.draft.copy(
            trigger = (redacted.draft.trigger as Trigger.Geofence).copy(lat = 52.52, lng = 13.405),
        )

        val restored = LocationRedaction.restore(moved, redacted.originals)

        val geofence = assertIs<Trigger.Geofence>(restored.trigger)
        assertEquals(52.52, geofence.lat)
        assertEquals(13.405, geofence.lng)
        // le altre posizioni, rimaste a 0,0, tornano comunque quelle di prima
        val firstCondition = assertIs<Condition.And>(restored.conditions).all.first()
        assertEquals(41.9028, assertIs<Condition.LocationIn>(firstCondition).lat)
    }

    @Test
    fun `a path the model removed or moved is not invented back`() {
        val redacted = LocationRedaction.redact(draft())
        val withoutConditions = redacted.draft.copy(conditions = null)

        val restored = LocationRedaction.restore(withoutConditions, redacted.originals)

        assertEquals(null, restored.conditions)
        assertEquals(45.4642, assertIs<Trigger.Geofence>(restored.trigger).lat)
    }

    @Test
    fun `drafts without places are returned unchanged`() {
        val plain = AutomationDraft(
            name = "x",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        )

        val redacted = LocationRedaction.redact(plain)

        assertEquals(plain, redacted.draft)
        assertTrue(redacted.originals.isEmpty())
        assertSame(plain, LocationRedaction.restore(plain, redacted.originals))
    }

    @Test
    fun `a geofence already at zero zero such as here has nothing to protect or restore`() {
        val here = AutomationDraft(
            name = "qui",
            trigger = Trigger.Geofence(
                radiusM = 100.0,
                transition = dev.argus.engine.model.Transition.EXIT,
                resolveCurrentLocation = true,
            ),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        )

        val redacted = LocationRedaction.redact(here)

        assertTrue(redacted.originals.isEmpty())
        assertEquals(here, redacted.draft)
    }
}
