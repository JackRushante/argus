package dev.argus.automation.base

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBaseActionSurfaceTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `launch package starts a package-scoped launcher intent without querying visibility`() {
        val context = RecordingContext(base)
        val surface = AndroidBaseActionSurface(context)

        assertTrue(surface.launchPackage("com.example.hidden"))

        val intent = context.started.single()
        assertEquals("com.example.hidden", intent.`package`)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(Intent.CATEGORY_LAUNCHER in intent.categories)
    }

    @Test
    fun `activity not found is an honest unresolved result`() {
        val context = RecordingContext(base).apply {
            failure = ActivityNotFoundException("missing")
        }
        val surface = AndroidBaseActionSurface(context)

        assertEquals(false, surface.launchPackage("com.example.missing"))
        assertEquals(false, surface.setTimer(30, null, false))
    }

    @Test
    fun `non resolution failures remain distinguishable as blocked starts`() {
        val context = RecordingContext(base).apply {
            failure = SecurityException("background start blocked")
        }
        val surface = AndroidBaseActionSurface(context)

        assertFailsWith<ActivityStartBlockedException> {
            surface.launchPackage("com.example.blocked")
        }
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        var failure: RuntimeException? = null

        override fun getApplicationContext(): Context = this

        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            started += Intent(intent)
        }
    }
}
