package dev.argus.automation.apps

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L'app predefinita per tipo vista da Android (0.7.0), con un PackageManager finto che risponde agli
 * intent dei tipi: l'app presente, nessuna app, più app senza una scelta (selettore di sistema) e più
 * tipi insieme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackageManagerDefaultAppsTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val packageManager = application.packageManager
    private val shadow = shadowOf(packageManager)
    private val lookup = PackageManagerDefaultApps(packageManager)

    private fun answer(pkg: String, label: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.Main"
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        nonLocalizedLabel = label
    }

    @Test
    fun `the app Android answers with for the calculator is the default, with its name`() {
        shadow.addResolveInfoForIntent(AppKind.CALCULATOR.intent(), answer("com.oneplus.calculator", "Calculator"))

        assertEquals(
            InstalledAppCandidate("Calculator", "com.oneplus.calculator", isDefault = true),
            lookup.defaultFor(AppKind.CALCULATOR),
        )
    }

    @Test
    fun `an app that declares the category without the default one is found too`() {
        shadow.addResolveInfoForIntentNoDefaults(AppKind.CALENDAR.intent(), answer("com.google.android.calendar", "Calendar"))

        assertEquals(
            InstalledAppCandidate("Calendar", "com.google.android.calendar", isDefault = true),
            lookup.defaultFor(AppKind.CALENDAR),
        )
    }

    @Test
    fun `no app for the kind means no default`() {
        assertNull(lookup.defaultFor(AppKind.MUSIC))
        assertNull(lookup.defaultFor(AppKind.CAMERA))
    }

    @Test
    fun `several apps and none chosen gives the system chooser, which is not a default`() {
        shadow.addResolveInfoForIntent(AppKind.CALCULATOR.intent(), answer("android", "Chooser"))

        assertNull(lookup.defaultFor(AppKind.CALCULATOR))
    }

    @Test
    fun `the camera is asked with the image capture action`() {
        assertEquals(android.provider.MediaStore.ACTION_IMAGE_CAPTURE, AppKind.CAMERA.intent().action)
        shadow.addResolveInfoForIntent(AppKind.CAMERA.intent(), answer("com.oneplus.camera", "Camera"))

        assertEquals(
            InstalledAppCandidate("Camera", "com.oneplus.camera", isDefault = true),
            lookup.defaultFor(AppKind.CAMERA),
        )
    }

    @Test
    fun `each kind is asked with its own category and gets its own answer`() {
        shadow.addResolveInfoForIntent(AppKind.CALCULATOR.intent(), answer("com.oneplus.calculator", "Calculator"))
        shadow.addResolveInfoForIntent(AppKind.CALENDAR.intent(), answer("com.google.android.calendar", "Calendar"))

        assertEquals(Intent.ACTION_MAIN, AppKind.CALCULATOR.intent().action)
        assertEquals(setOf(Intent.CATEGORY_APP_CALCULATOR), AppKind.CALCULATOR.intent().categories)
        assertEquals("com.oneplus.calculator", lookup.defaultFor(AppKind.CALCULATOR)?.packageName)
        assertEquals("com.google.android.calendar", lookup.defaultFor(AppKind.CALENDAR)?.packageName)
        assertNull(lookup.defaultFor(AppKind.CONTACTS))
    }

    @Test
    fun `an unnamed answer falls back to the package name`() {
        shadow.addResolveInfoForIntent(AppKind.MAPS.intent(), answer("com.example.maps", "").also { it.nonLocalizedLabel = null })

        val found = lookup.defaultFor(AppKind.MAPS)

        assertEquals("com.example.maps", found?.packageName)
        assertEquals(true, found?.label?.isNotBlank())
    }

    @Test
    fun `resolved together with the request, the default reaches the candidates`() {
        shadow.addResolveInfoForIntent(AppKind.CALCULATOR.intent(), answer("com.oneplus.calculator", "Calculator"))
        val installed = listOf(
            InstalledAppCandidate("Calculator", "com.oneplus.calculator"),
            InstalledAppCandidate("Instagram", "com.instagram.android"),
        )

        val candidates = withDefaultApps(
            "fammi un pulsante che apre la calcolatrice",
            matchInstalledApps("fammi un pulsante che apre la calcolatrice", installed),
            lookup,
        )

        assertEquals(listOf(InstalledAppCandidate("Calculator", "com.oneplus.calculator", isDefault = true)), candidates)
    }
}
