package dev.argus.automation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppPreferences(
    val privacyAccepted: Boolean,
    val onboardingCompleted: Boolean,
)

val AppPreferences.onboardingReady: Boolean
    get() = privacyAccepted && onboardingCompleted

interface AppPreferencesStore {
    fun observe(): StateFlow<AppPreferences>
    suspend fun setPrivacyAccepted(accepted: Boolean): Boolean
    suspend fun setOnboardingCompleted(completed: Boolean): Boolean
}

class AndroidAppPreferencesStore(context: Context) : AppPreferencesStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val state = MutableStateFlow(read())
    private val writeMutex = Mutex()

    override fun observe(): StateFlow<AppPreferences> = state.asStateFlow()

    override suspend fun setPrivacyAccepted(accepted: Boolean): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val wasAccepted = preferences.getBoolean(KEY_PRIVACY_ACCEPTED, false)
            val editor = preferences.edit().putBoolean(KEY_PRIVACY_ACCEPTED, accepted)
            // Anche un vecchio/corrotto `onboarding=true, privacy=false` non deve poter saltare
            // gli step restanti appena l'utente accetta la sola informativa.
            if (!accepted || !wasAccepted) editor.putBoolean(KEY_ONBOARDING_COMPLETED, false)
            editor.commit().also { saved -> if (saved) state.value = read() }
        }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (completed && !preferences.getBoolean(KEY_PRIVACY_ACCEPTED, false)) {
                return@withContext false
            }
            preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).commit().also { saved ->
                if (saved) state.value = read()
            }
        }
    }

    private fun read(): AppPreferences {
        val privacyAccepted = preferences.getBoolean(KEY_PRIVACY_ACCEPTED, false)
        return AppPreferences(
            privacyAccepted = privacyAccepted,
            onboardingCompleted = privacyAccepted &&
                preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "argus_app_private"
        const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
