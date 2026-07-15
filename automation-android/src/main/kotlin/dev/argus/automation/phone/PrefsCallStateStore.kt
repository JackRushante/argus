package dev.argus.automation.phone

import android.content.Context
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId

/**
 * Lo stato chiamata sopravvive alla morte del processo (i receiver manifest rinascono a freddo):
 * SharedPreferences con commit sincrono. Il numero dello snapshot viene rimosso a IDLE; se il
 * dispatch CALL_ENDED è ancora pending, una copia bounded resta nello storage privato fino al
 * completamento/recovery, così il filtro non cambia dopo process death.
 */
class PrefsCallStateStore(context: Context) : CallStateStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun last(): CallStateSnapshot? = runCatching {
        val state = requireNotNull(prefs.getString(KEY_LAST_STATE, null))
        require(state in VALID_STATES)
        val number = readBoundedNumber(KEY_NUMBER)
        val atMillis = prefs.getLong(KEY_TRANSITION_AT, 0L)
        require(atMillis >= 0)
        CallStateSnapshot(
            state = state,
            number = number,
            // Le installazioni P2 precedenti non avevano timestamp: 0 le rende stale al primo
            // evento moderno, evitando un CALL_ENDED spurio dopo upgrade/reboot.
            transitionAtMillis = atMillis,
        )
    }.getOrNull()

    override fun pending(): TriggerEnvelope? = runCatching {
        val id = requireNotNull(prefs.getString(KEY_PENDING_ID, null))
        require(id.matches(EVENT_ID_PATTERN))
        val event = PhoneEvent.valueOf(requireNotNull(prefs.getString(KEY_PENDING_EVENT, null)))
        require(event == PhoneEvent.INCOMING_CALL || event == PhoneEvent.CALL_ENDED)
        val number = readBoundedNumber(KEY_PENDING_NUMBER)
        TriggerEnvelope(
            TriggerEventId(id),
            TriggerEvent.PhoneStateChanged(event, number, smsText = null),
        )
    }.getOrNull()

    override fun record(snapshot: CallStateSnapshot, pending: TriggerEnvelope?) {
        require(snapshot.state in VALID_STATES) { "phone_state_invalid" }
        require(snapshot.transitionAtMillis >= 0) { "phone_transition_time_invalid" }
        require(snapshot.number.isBoundedNumber()) { "phone_number_invalid" }
        val pendingEvent = pending?.let {
            require(it.id.value.matches(EVENT_ID_PATTERN)) { "phone_pending_id_invalid" }
            requireNotNull(it.event as? TriggerEvent.PhoneStateChanged)
                .also { event ->
                    require(event.smsText == null && event.event != PhoneEvent.SMS_RECEIVED) {
                        "phone_pending_event_invalid"
                    }
                    require(event.number.isBoundedNumber()) { "phone_pending_number_invalid" }
                    require(
                        (event.event == PhoneEvent.INCOMING_CALL && snapshot.state == "RINGING") ||
                            (event.event == PhoneEvent.CALL_ENDED && snapshot.state == "IDLE"),
                    ) { "phone_pending_snapshot_mismatch" }
                }
        }
        val persisted = prefs.edit()
            .putString(KEY_LAST_STATE, snapshot.state)
            .putLong(KEY_TRANSITION_AT, snapshot.transitionAtMillis)
            .apply {
                if (snapshot.number == null) remove(KEY_NUMBER) else putString(KEY_NUMBER, snapshot.number)
                if (pending == null || pendingEvent == null) {
                    removePending()
                } else {
                    putString(KEY_PENDING_ID, pending.id.value)
                    putString(KEY_PENDING_EVENT, pendingEvent.event.name)
                    if (pendingEvent.number == null) remove(KEY_PENDING_NUMBER)
                    else putString(KEY_PENDING_NUMBER, pendingEvent.number)
                }
            }
            .commit()
        check(persisted) { "phone_state_persist_failed" }
    }

    override fun complete(eventId: String) {
        require(prefs.getString(KEY_PENDING_ID, null) == eventId) { "phone_pending_stale" }
        check(prefs.edit().apply { removePending() }.commit()) { "phone_state_persist_failed" }
    }

    private fun android.content.SharedPreferences.Editor.removePending() {
        remove(KEY_PENDING_ID)
        remove(KEY_PENDING_EVENT)
        remove(KEY_PENDING_NUMBER)
    }

    private fun readBoundedNumber(storageKey: String): String? {
        val value = prefs.getString(storageKey, null) ?: return null
        require(value.isBoundedNumber())
        return value
    }

    private fun String?.isBoundedNumber(): Boolean =
        this == null || (length <= MAX_NUMBER_CHARS && none(Char::isISOControl))

    private companion object {
        const val PREFS_NAME = "argus_phone_state"
        const val KEY_LAST_STATE = "last_call_state"
        const val KEY_NUMBER = "last_call_number"
        const val KEY_TRANSITION_AT = "last_call_transition_at"
        const val KEY_PENDING_ID = "pending_call_event_id"
        const val KEY_PENDING_EVENT = "pending_call_event"
        const val KEY_PENDING_NUMBER = "pending_call_number"
        val VALID_STATES = setOf("RINGING", "OFFHOOK", "IDLE")
        val EVENT_ID_PATTERN = Regex("^phone:[0-9a-f]{64}$")
        const val MAX_NUMBER_CHARS = 32
    }
}
