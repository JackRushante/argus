package dev.argus.automation

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.automation.notification.NotificationReplyHandle
import dev.argus.data.DeferredReplyStore
import dev.argus.data.entities.DeferredReplyEntity
import dev.argus.engine.notification.ObservedConversation
import dev.argus.engine.notification.ObservedConversationStore
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyRevocationCoordinatorTest {
    @Test
    fun `revocation closes the gate then purges handles observed rows and deferred replies`() =
        runTest {
            val preferences = FakePreferences(privacyAccepted = true)
            val registry = registryWithHandle()
            val observed = FakeObservedStore()
            val deferred = FakeDeferredStore()
            val coordinator = PrivacyRevocationCoordinator(preferences, registry, observed, deferred)

            assertEquals(PrivacyRevocationResult.Revoked, coordinator.revoke())
            assertFalse(preferences.observe().value.privacyAccepted)
            assertEquals(0, registry.size())
            assertTrue(observed.cleared)
            assertTrue(deferred.cleared)

            // Idempotente: una seconda revoca senza residui resta un esito pieno.
            assertEquals(PrivacyRevocationResult.Revoked, coordinator.revoke())
        }

    @Test
    fun `preference failure leaves everything untouched`() = runTest {
        val preferences = FakePreferences(privacyAccepted = true, writeResult = false)
        val registry = registryWithHandle()
        val observed = FakeObservedStore()
        val deferred = FakeDeferredStore()

        assertEquals(
            PrivacyRevocationResult.Failed,
            PrivacyRevocationCoordinator(preferences, registry, observed, deferred).revoke(),
        )
        assertTrue(preferences.observe().value.privacyAccepted)
        assertEquals(1, registry.size())
        assertFalse(observed.cleared)
        assertFalse(deferred.cleared)
    }

    @Test
    fun `partial purge failure reports residual data but still purges the rest`() = runTest {
        val preferences = FakePreferences(privacyAccepted = true)
        val registry = registryWithHandle()
        val observed = FakeObservedStore(failure = IllegalStateException("db offline"))
        val deferred = FakeDeferredStore()
        val coordinator = PrivacyRevocationCoordinator(preferences, registry, observed, deferred)

        assertEquals(PrivacyRevocationResult.RevokedWithResidualData, coordinator.revoke())
        assertFalse(preferences.observe().value.privacyAccepted)
        assertEquals(0, registry.size())
        assertTrue(deferred.cleared, "il fallimento di una purge non blocca le altre")

        // Richiamabile: quando la purge torna a funzionare l'esito diventa pieno.
        observed.failure = null
        assertEquals(PrivacyRevocationResult.Revoked, coordinator.revoke())
        assertTrue(observed.cleared)
    }

    private fun registryWithHandle(): ActiveNotificationReplyRegistry {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActiveNotificationReplyRegistry().apply {
            replace(
                NotificationReplyHandle(
                    packageName = "com.whatsapp",
                    notificationKey = "sbn:test",
                    conversationId = "shortcut:com.whatsapp:hash",
                    eventId = TriggerEventId("notification:test"),
                    isGroup = false,
                    remoteInput = RemoteInput.Builder("reply_text")
                        .setAllowFreeFormInput(true)
                        .build(),
                    pendingIntent = PendingIntent.getBroadcast(
                        context,
                        1,
                        Intent("dev.argus.test.REPLY").setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ),
                ),
            )
        }
    }

    private class FakePreferences(
        privacyAccepted: Boolean,
        private val writeResult: Boolean = true,
    ) : AppPreferencesStore {
        private val state = MutableStateFlow(
            AppPreferences(privacyAccepted = privacyAccepted, onboardingCompleted = privacyAccepted),
        )

        override fun observe(): StateFlow<AppPreferences> = state.asStateFlow()

        override suspend fun setPrivacyAccepted(accepted: Boolean): Boolean {
            if (!writeResult) return false
            state.value = AppPreferences(accepted, onboardingCompleted = false)
            return true
        }

        override suspend fun setOnboardingCompleted(completed: Boolean): Boolean = false
    }

    private class FakeObservedStore(
        var failure: Exception? = null,
    ) : ObservedConversationStore {
        var cleared = false

        override suspend fun recent(limit: Int): List<ObservedConversation> = emptyList()
        override fun observeRecent(limit: Int): Flow<List<ObservedConversation>> =
            flowOf(emptyList())

        override suspend fun record(conversation: ObservedConversation) = Unit

        override suspend fun clear() {
            failure?.let { throw it }
            cleared = true
        }
    }

    private class FakeDeferredStore : DeferredReplyStore {
        var cleared = false

        override suspend fun save(entity: DeferredReplyEntity): Boolean = false
        override suspend fun firstActionable(
            executionId: String,
            nowMillis: Long,
        ): DeferredReplyEntity? = null

        override suspend fun markConsumed(
            executionId: String,
            actionIndex: Int,
            atMillis: Long,
        ): Boolean = false

        override suspend fun purgeExpired(nowMillis: Long): Int = 0

        override suspend fun clear(): Int {
            cleared = true
            return 0
        }
    }
}
