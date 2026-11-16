package dev.argus.automation.privileged

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Le app che `app_control` non tocca sul telefono (0.7.2): tutti i launcher, la tastiera, WebView; fallisce chiuso. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPrivilegedActionEnvironmentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun addHome(pkg: String) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Home"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(home, info)
    }

    @Test
    fun `every home handler, the default keyboard and the webview provider are protected`() {
        addHome("com.example.launcher")
        addHome("com.example.fallback")
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, "com.example.ime/.Ime")
        val environment = AndroidPrivilegedActionEnvironment(context) { "com.example.webview" }

        assertEquals(
            setOf("com.example.launcher", "com.example.fallback", "com.example.ime", "com.example.webview"),
            environment.protectedPackages(),
        )
    }

    @Test
    fun `no keyboard set and no webview leave just the launchers`() {
        addHome("com.example.launcher")
        val environment = AndroidPrivilegedActionEnvironment(context) { null }

        assertEquals(setOf("com.example.launcher"), environment.protectedPackages())
    }

    @Test
    fun `no visible home handler fails closed instead of protecting nothing`() {
        val environment = AndroidPrivilegedActionEnvironment(context) { null }

        assertFailsWith<IllegalStateException> { environment.protectedPackages() }
    }

    @Test
    fun `an unreadable keyboard or webview fails closed`() {
        addHome("com.example.launcher")
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, "not a component")
        assertFailsWith<IllegalStateException> {
            AndroidPrivilegedActionEnvironment(context) { null }.protectedPackages()
        }

        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, "")
        assertFailsWith<SecurityException> {
            AndroidPrivilegedActionEnvironment(context) { throw SecurityException("webview") }.protectedPackages()
        }
    }
}
