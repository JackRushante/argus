package dev.argus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.AppPreferencesStore
import dev.argus.data.entities.AutomationEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArgusNavigationInstrumentedTest {
    private val compose = createAndroidComposeRule<ArgusTestHostActivity>()
    private var seededAutomationId: AutomationId? = null

    private val onboardingState = object : TestWatcher() {
        override fun starting(description: Description) {
            val completed = description.methodName !=
                "firstLaunchIsGatedByPrivacyAndHidesTopLevelNavigation"
            setOnboarding(privacyAccepted = completed, completed = completed)
            if (description.methodName == "automationListOpensRealDetail") seedAutomation()
        }

        override fun finished(description: Description) {
            try {
                clearSeededAutomation()
            } finally {
                setOnboarding(privacyAccepted = false, completed = false)
            }
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
        compose.onNodeWithTag("nav_log").performClick()
        awaitScreen("screen_log")
        compose.onNodeWithTag("nav_settings").performClick()
        awaitScreen("screen_settings")
    }

    @Test
    fun automationListOpensRealDetail() {
        compose.onNodeWithTag("screen_chat").assertIsDisplayed()
        compose.onNodeWithTag("nav_list").performClick()
        awaitScreen("screen_list")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(SEED_AUTOMATION_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(SEED_AUTOMATION_NAME).performClick()
        awaitScreen("screen_detail")
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

    private fun seedAutomation() = runBlocking {
        val zone = ZoneId.systemDefault()
        val id = AutomationId("navigation-smoke-${UUID.randomUUID()}")
        val unsigned = Automation(
            id = id,
            name = SEED_AUTOMATION_NAME,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.DISABLED,
            trigger = Trigger.Time(
                cron = null,
                at = LocalDateTime.now(zone).plusDays(1).withNano(0).toString(),
                tz = zone.id,
            ),
            actions = listOf(Action.SetDnd(DndMode.OFF)),
            enabled = false,
        )
        val automation = unsigned.copy(
            approvalFingerprint = ApprovalFingerprints.of(unsigned),
        )
        services.database().automationDao().upsert(
            AutomationEntity(
                id = automation.id.value,
                name = automation.name,
                status = automation.status,
                enabled = automation.enabled,
                priority = automation.priority,
                cooldownMs = automation.cooldownMs,
                schemaVersion = automation.schemaVersion,
                json = ArgusJson.encodeToString(Automation.serializer(), automation),
            ),
        )
        check(services.automationStore().get(id) == automation) { "Seed smoke non persistito" }
        seededAutomationId = id
    }

    private fun clearSeededAutomation() = runBlocking {
        seededAutomationId?.let { id ->
            services.automationStore().delete(id)
            check(services.automationStore().get(id) == null) { "Seed smoke non eliminato" }
        }
        seededAutomationId = null
    }

    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    private companion object {
        const val SEED_AUTOMATION_NAME = "Smoke dettaglio"
    }
}
