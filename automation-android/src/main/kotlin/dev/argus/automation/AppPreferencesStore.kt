package dev.argus.automation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AppPreferences(
    val privacyAccepted: Boolean,
    val onboardingCompleted: Boolean,
)

internal interface AppPreferencesStore {
    fun observe(): StateFlow<AppPreferences>
    suspend fun setPrivacyAccepted(accepted: Boolean): Boolean
    suspend fun setOnboardingCompleted(completed: Boolean): Boolean
}

internal class AndroidAppPreferencesStore(context: Context) : AppPreferencesStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val state = MutableStateFlow(read())

    override fun observe(): StateFlow<AppPreferences> = state.asStateFlow()

    override suspend fun setPrivacyAccepted(accepted: Boolean): Boolean =
        persist(KEY_PRIVACY_ACCEPTED, accepted)

    override suspend fun setOnboardingCompleted(completed: Boolean): Boolean =
        persist(KEY_ONBOARDING_COMPLETED, completed)

    private suspend fun persist(key: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean(key, value).commit().also { saved ->
            if (saved) state.value = read()
        }
    }

    private fun read() = AppPreferences(
        privacyAccepted = preferences.getBoolean(KEY_PRIVACY_ACCEPTED, false),
        onboardingCompleted = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false),
    )

    private companion object {
        const val PREFERENCES_NAME = "argus_app_private"
        const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
