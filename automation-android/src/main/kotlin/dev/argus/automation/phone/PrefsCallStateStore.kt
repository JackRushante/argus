package dev.argus.automation.phone

import android.content.Context

/**
 * Lo stato chiamata sopravvive alla morte del processo (i receiver manifest rinascono a freddo):
 * SharedPreferences con commit sincrono. Il numero resta nello storage privato solo durante la
 * chiamata e viene rimosso appena arriva IDLE; serve a mantenere il filtro su CALL_ENDED anche se
 * il processo rinasce tra RINGING e IDLE.
 */
class PrefsCallStateStore(context: Context) : CallStateStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun last(): CallStateSnapshot? {
        val state = prefs.getString(KEY_LAST_STATE, null) ?: return null
        return CallStateSnapshot(
            state = state,
            number = prefs.getString(KEY_NUMBER, null),
            // Le installazioni P2 precedenti non avevano timestamp: 0 le rende stale al primo
            // evento moderno, evitando un CALL_ENDED spurio dopo upgrade/reboot.
            transitionAtMillis = prefs.getLong(KEY_TRANSITION_AT, 0L),
        )
    }

    override fun record(snapshot: CallStateSnapshot) {
        prefs.edit()
            .putString(KEY_LAST_STATE, snapshot.state)
            .putLong(KEY_TRANSITION_AT, snapshot.transitionAtMillis)
            .apply {
                if (snapshot.number == null) remove(KEY_NUMBER) else putString(KEY_NUMBER, snapshot.number)
            }
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "argus_phone_state"
        const val KEY_LAST_STATE = "last_call_state"
        const val KEY_NUMBER = "last_call_number"
        const val KEY_TRANSITION_AT = "last_call_transition_at"
    }
}
