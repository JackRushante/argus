package dev.argus.automation.phone

import android.content.Context

/**
 * Lo stato chiamata sopravvive alla morte del processo (i receiver manifest rinascono a freddo):
 * SharedPreferences con commit sincrono — è un singolo enum, non un dato sensibile.
 */
class PrefsCallStateStore(context: Context) : CallStateStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastState(): String? = prefs.getString(KEY_LAST_STATE, null)

    override fun record(state: String) {
        prefs.edit().putString(KEY_LAST_STATE, state).commit()
    }

    private companion object {
        const val PREFS_NAME = "argus_phone_state"
        const val KEY_LAST_STATE = "last_call_state"
    }
}
