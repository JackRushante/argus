package dev.argus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.AppPreferencesStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArgusNavigationInstrumentedTest {
    private val compose = createAndroidComposeRule<ArgusTestHostActivity>()

    private val onboardingState = object : TestWatcher() {
        override fun starting(description: Description) {
            val completed = description.methodName !=
                "firstLaunchIsGatedByPrivacyAndHidesTopLevelNavigation"
            setOnboarding(privacyAccepted = completed, completed = completed)
        }

        override fun finished(description: Description) {
            setOnboarding(privacyAccepted = false, completed = false)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(onboardingState).around(compose)

    private val preferences: AppPreferencesStore
        get() = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        ).appPreferencesStore()

    @Test
    fun firstLaunchIsGatedByPrivacyAndHidesTopLevelNavigation() {
        compose.onNodeWithTag("screen_onboarding").assertIsDisplayed()
        compose.onNodeWithTag("nav_chat").assertDoesNotExist()
        compose.onNodeWithTag("nav_list").assertDoesNotExist()
        compose.onNodeWithText("Ho capito, acconsento").performClick()
        compose.onNodeWithText("Collega Hermes").assertIsDisplayed()
    }

    @Test
    fun completedOnboardingOpensChatAndTopLevelDestinations() {
        compose.onNodeWithTag("screen_chat").assertIsDisplayed()
        compose.onNodeWithTag("nav_list").performClick()
        awaitScreen("screen_list")
        compose.onNodeWithTag("nav_settings").performClick()
        awaitScreen("screen_settings")
    }

    @Test
    fun privacyRevocationImmediatelyClosesRuntimeNavigation() {
        compose.onNodeWithTag("screen_chat").assertIsDisplayed()

        compose.runOnIdle {
            runBlocking { check(preferences.setPrivacyAccepted(false)) }
        }

        awaitScreen("screen_onboarding")
        compose.onNodeWithTag("nav_chat").assertDoesNotExist()
        compose.onNodeWithTag("nav_list").assertDoesNotExist()
    }

    private fun awaitScreen(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun setOnboarding(privacyAccepted: Boolean, completed: Boolean) = runBlocking {
        check(preferences.setPrivacyAccepted(privacyAccepted))
        check(preferences.setOnboardingCompleted(completed))
    }
}
