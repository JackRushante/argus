package dev.argus.automation.notification

import dev.argus.engine.runtime.TriggerEventId

/** Un messaggio della raffica: l'evento che l'ha portato e il suo testo (solo in RAM). */
class BurstMessage(val eventId: TriggerEventId, val text: String) {
    /** Il testo è contenuto privato dell'utente: mai in log o toString. */
    override fun toString(): String = "BurstMessage(eventId=$eventId, text=<redacted>)"
}

/** Messaggi consecutivi di una stessa conversazione, dal primo evento della raffica in poi. */
class ReplyBurst(val messages: List<BurstMessage>) {
    val latestEventId: TriggerEventId get() = messages.last().eventId
}

/**
 * Sorgente della raffica di messaggi di una conversazione 1:1 (D8). La lane generativa la usa per
 * accorpare più messaggi ravvicinati in UNA sola risposta: aspetta il silenzio e poi genera sul
 * testo di tutta la raffica, invece di rispondere a ogni messaggio (o perdere i successivi quando
 * il cooldown dell'Engine li sopprime).
 */
interface ReplyBurstSource {
    /**
     * La raffica della conversazione a partire dall'evento [firstEventId] (incluso), oppure `null`
     * se non c'è più un canale attivo per la conversazione. Se [firstEventId] non è più nel log
     * restituisce soltanto l'ultimo messaggio.
     */
    fun burst(
        notificationKey: String,
        conversationId: String,
        firstEventId: TriggerEventId,
    ): ReplyBurst?

    /**
     * Sospende finché l'ultimo messaggio noto della conversazione non è diverso da
     * [seenEventId]; restituisce il suo event id. Il chiamante la limita con un timeout.
     */
    suspend fun awaitNewMessage(
        notificationKey: String,
        conversationId: String,
        seenEventId: TriggerEventId,
    ): TriggerEventId
}
