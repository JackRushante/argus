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
import dev.argus.engine.model.SettingsScreen
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
        val surface = AndroidBaseActionSurface(context, isProcessForeground = { true })

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
        val surface = AndroidBaseActionSurface(context, isProcessForeground = { true })

        assertEquals(false, surface.launchPackage("com.example.missing"))
        assertEquals(false, surface.setTimer(30, null, false))
    }

    @Test
    fun `non resolution failures remain distinguishable as blocked starts`() {
        val context = RecordingContext(base).apply {
            failure = SecurityException("background start blocked")
        }
        val surface = AndroidBaseActionSurface(context, isProcessForeground = { true })

        assertFailsWith<ActivityStartBlockedException> {
            surface.launchPackage("com.example.blocked")
        }
    }

    @Test
    fun `without Shizuku a background process never calls startActivity and reports a blocked start`() {
        val context = RecordingContext(base)
        val surface = AndroidBaseActionSurface(context, isProcessForeground = { false })

        // B4: da background `startActivity` potrebbe «riuscire» senza aprire nulla (falso riuscito).
        assertFailsWith<ActivityStartBlockedException> { surface.launchPackage("com.example.app") }
        assertFailsWith<ActivityStartBlockedException> { surface.openHttpUrl("https://example.com") }
        assertFailsWith<ActivityStartBlockedException> { surface.setAlarm(7, 30, "sveglia", true) }
        assertFailsWith<ActivityStartBlockedException> { surface.setTimer(60, null, true) }
        assertFailsWith<ActivityStartBlockedException> {
            surface.openSettingsScreen(SettingsScreen.WIFI, null)
        }

        assertTrue(context.started.isEmpty(), "nessun startActivity da background")
    }

    @Test
    fun `in the foreground every activity start behaves as before`() {
        val context = RecordingContext(base)
        val surface = AndroidBaseActionSurface(context, isProcessForeground = { true })

        assertTrue(surface.launchPackage("com.example.app"))
        surface.openHttpUrl("https://example.com")
        assertTrue(surface.setAlarm(7, 30, "sveglia", true))
        assertTrue(surface.setTimer(60, null, true))
        assertTrue(surface.openSettingsScreen(SettingsScreen.WIFI, null))

        assertEquals(5, context.started.size)
    }

    @Test
    fun `the foreground check follows a lifecycle that can change between calls`() {
        val context = RecordingContext(base)
        var foreground = false
        val surface = AndroidBaseActionSurface(context, isProcessForeground = { foreground })

        assertFailsWith<ActivityStartBlockedException> { surface.launchPackage("com.example.app") }
        foreground = true
        assertTrue(surface.launchPackage("com.example.app"))
        assertEquals(1, context.started.size)
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
