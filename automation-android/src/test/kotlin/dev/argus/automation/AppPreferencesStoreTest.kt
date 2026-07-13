package dev.argus.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppPreferencesStoreTest {
    private lateinit var context: Context

    @Before
    fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("argus_app_private", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `privacy and onboarding survive store recreation`() = runTest {
        val first = AndroidAppPreferencesStore(context)
        assertEquals(AppPreferences(false, false), first.observe().value)

        assertTrue(first.setPrivacyAccepted(true))
        assertTrue(first.setOnboardingCompleted(true))
        assertEquals(AppPreferences(true, true), first.observe().value)

        val restored = AndroidAppPreferencesStore(context)
        assertEquals(AppPreferences(true, true), restored.observe().value)
        assertTrue(restored.setPrivacyAccepted(false))
        assertFalse(restored.observe().value.privacyAccepted)
    }
}
