package dev.argus.automation.phone

import dev.argus.engine.model.SimSlotPolicy

/**
 * Da quale SIM (1 o 2) arriva un evento di telefonia, dagli extra dell'intent.
 *
 * Il framework mette negli intent `SMS_RECEIVED` e `PHONE_STATE` lo slot fisico (indice a base 0:
 * `SubscriptionManager.EXTRA_SLOT_INDEX`, e il legacy `slot`) e l'id di abbonamento
 * (`EXTRA_SUBSCRIPTION_INDEX`, e il legacy `subscription`). Lo slot è la fonte preferita; se manca si
 * prova a risalire dall'abbonamento con [slotOfSubscription] (serve `READ_PHONE_STATE`).
 *
 * FAIL-CLOSED: se lo slot non c'è, non è leggibile o sta oltre la SIM 2, il risultato è `null` e una
 * regola col filtro SIM non scatta. Mai un valore di ripiego.
 */
internal object SimSlotResolver {
    fun resolve(
        slotIndexExtra: Int?,
        subscriptionIdExtra: Int?,
        slotOfSubscription: (Int) -> Int?,
    ): Int? {
        if (slotIndexExtra != null && slotIndexExtra >= 0) return fromSlotIndex(slotIndexExtra)
        val subscription = subscriptionIdExtra?.takeIf { it >= 0 } ?: return null
        val slot = try {
            slotOfSubscription(subscription)
        } catch (_: RuntimeException) {
            null
        }
        return slot?.takeIf { it >= 0 }?.let { fromSlotIndex(it) }
    }

    /** Slot a base 0 -> SIM 1|2; oltre la seconda non è rappresentabile nel filtro. */
    fun fromSlotIndex(slotIndex: Int): Int? = (slotIndex + 1).takeIf(SimSlotPolicy::validSlot)
}
