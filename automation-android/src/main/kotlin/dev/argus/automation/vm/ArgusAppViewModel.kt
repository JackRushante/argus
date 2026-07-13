package dev.argus.automation.vm

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.automation.AppPreferencesStore
import dev.argus.automation.onboardingReady
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject

@HiltViewModel
class ArgusAppViewModel @Inject constructor(
    preferences: AppPreferencesStore,
) : ViewModel() {
    private val initial = preferences.observe().value
    val onboardingCompleted: StateFlow<Boolean> = preferences.observe()
        .map { it.onboardingReady }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            initial.onboardingReady,
        )
}
