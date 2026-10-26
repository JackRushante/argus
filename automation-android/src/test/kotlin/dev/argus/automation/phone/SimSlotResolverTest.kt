package dev.argus.automation.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimSlotResolverTest {
    private val noLookup: (Int) -> Int? = { error("la ricerca dell'abbonamento non doveva servire") }

    @Test
    fun `the physical slot extra is preferred and maps to SIM 1 or 2`() {
        assertEquals(1, SimSlotResolver.resolve(slotIndexExtra = 0, subscriptionIdExtra = 5, slotOfSubscription = noLookup))
        assertEquals(2, SimSlotResolver.resolve(slotIndexExtra = 1, subscriptionIdExtra = null, slotOfSubscription = noLookup))
    }

    @Test
    fun `a slot beyond the second SIM is unknown and does not fall back to a guess`() {
        assertNull(SimSlotResolver.resolve(slotIndexExtra = 2, subscriptionIdExtra = 7, slotOfSubscription = { 0 }))
    }

    @Test
    fun `without a slot the subscription is looked up`() {
        assertEquals(2, SimSlotResolver.resolve(null, 42) { id -> if (id == 42) 1 else null })
        assertEquals(1, SimSlotResolver.resolve(-1, 42) { 0 })
    }

    @Test
    fun `an unreadable or unknown subscription is unknown, never a default`() {
        assertNull(SimSlotResolver.resolve(null, 42) { null })
        assertNull(SimSlotResolver.resolve(null, 42) { throw SecurityException("READ_PHONE_STATE") })
        assertNull(SimSlotResolver.resolve(null, 42) { -1 })
        assertNull(SimSlotResolver.resolve(null, 42) { 2 })
        assertNull(SimSlotResolver.resolve(null, -1, noLookup))
    }

    @Test
    fun `no extras at all means unknown`() {
        assertNull(SimSlotResolver.resolve(null, null, noLookup))
        assertNull(SimSlotResolver.resolve(-1, -1, noLookup))
    }
}
