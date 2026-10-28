package dev.argus.automation.apps

import dev.argus.automation.vm.composeInstalledAppCandidatesPrompt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * App predefinita per tipo (0.7.0): le parole che nominano un tipo di app (IT ed EN), i candidati che
 * includono l'app predefinita dell'utente segnata come tale, il prompt che la porta al modello e i
 * `<queries>` del manifest che permettono ad Android di dirla.
 */
class DefaultAppsTest {
    private val oneplusCalculator = InstalledAppCandidate("Calculator", "com.oneplus.calculator")
    private val googleCalendar = InstalledAppCandidate("Calendar", "com.google.android.calendar")
    private val simpleCalculator = InstalledAppCandidate("Calcolatrice semplice", "com.simplemobiletools.calculator")

    /** L'app predefinita per tipo, come la darebbe Android; i tipi assenti non hanno una scelta. */
    private fun lookup(vararg defaults: Pair<AppKind, InstalledAppCandidate>) = DefaultAppLookup { kind ->
        defaults.toMap()[kind]
    }

    // --- le parole del tipo ---------------------------------------------------------------------

    @Test
    fun `a request names the kind of app in italian or in english`() {
        assertEquals(setOf(AppKind.CALCULATOR), appKindsIn("Ogni mattina apri la calcolatrice"))
        assertEquals(setOf(AppKind.CALCULATOR), appKindsIn("Open the Calculator at 8"))
        assertEquals(setOf(AppKind.CALENDAR), appKindsIn("apri il Calendario"))
        assertEquals(setOf(AppKind.CALENDAR), appKindsIn("open my calendar"))
        assertEquals(setOf(AppKind.CONTACTS), appKindsIn("apri la rubrica"))
        assertEquals(setOf(AppKind.CONTACTS), appKindsIn("open contacts"))
        assertEquals(setOf(AppKind.EMAIL), appKindsIn("apri la posta"))
        assertEquals(setOf(AppKind.EMAIL), appKindsIn("check my e-mail"))
        assertEquals(setOf(AppKind.GALLERY), appKindsIn("apri la galleria"))
        assertEquals(setOf(AppKind.GALLERY), appKindsIn("open the gallery"))
        assertEquals(setOf(AppKind.MAPS), appKindsIn("apri le mappe"))
        assertEquals(setOf(AppKind.MAPS), appKindsIn("open maps"))
        assertEquals(setOf(AppKind.MESSAGES), appKindsIn("apri i messaggi"))
        assertEquals(setOf(AppKind.MESSAGES), appKindsIn("open messages"))
        assertEquals(setOf(AppKind.MUSIC), appKindsIn("metti la musica"))
        assertEquals(setOf(AppKind.MUSIC), appKindsIn("open the music app"))
        assertEquals(setOf(AppKind.BROWSER), appKindsIn("apri il browser"))
        assertEquals(setOf(AppKind.BROWSER), appKindsIn("open the browser"))
        assertEquals(setOf(AppKind.CAMERA), appKindsIn("apri la fotocamera"))
        assertEquals(setOf(AppKind.CAMERA), appKindsIn("open the camera"))
    }

    @Test
    fun `accents and case do not matter, partial words do not count`() {
        assertEquals(setOf(AppKind.CALCULATOR), appKindsIn("CALCOLATRICE"))
        assertEquals(emptySet(), appKindsIn("calcolatrice1234"))
        assertEquals(emptySet(), appKindsIn("calculate the total"))
        assertEquals(emptySet(), appKindsIn("Metti il telefono in silenzioso alle 22"))
        assertEquals(emptySet(), appKindsIn("   "))
    }

    @Test
    fun `several kinds in one request are all named, up to three`() {
        assertEquals(
            setOf(AppKind.CALCULATOR, AppKind.CALENDAR),
            appKindsIn("apri la calcolatrice e poi il calendario"),
        )
        val many = appKindsIn("calcolatrice calendario rubrica posta galleria mappe")
        assertEquals(3, many.size)
    }

    // --- i candidati con il predefinito ---------------------------------------------------------

    @Test
    fun `the default app is added and marked even when its name shares no word with the request`() {
        val matched = emptyList<InstalledAppCandidate>()

        val result = withDefaultApps("Ogni mattina apri la calcolatrice", matched, lookup(AppKind.CALCULATOR to oneplusCalculator))

        assertEquals(listOf(oneplusCalculator.copy(isDefault = true)), result)
    }

    @Test
    fun `the label match and the default are one candidate, the default goes first and keeps the matched label`() {
        val matched = listOf(simpleCalculator, InstalledAppCandidate("Calcolatrice", "com.oneplus.calculator"))

        val result = withDefaultApps(
            "apri la calcolatrice",
            matched,
            lookup(AppKind.CALCULATOR to oneplusCalculator),
        )

        assertEquals(
            listOf(
                InstalledAppCandidate("Calcolatrice", "com.oneplus.calculator", isDefault = true),
                simpleCalculator,
            ),
            result,
        )
        assertEquals(1, result.count { it.isDefault })
    }

    @Test
    fun `without a default for the kind the candidates stay as they were, none marked`() {
        val matched = listOf(simpleCalculator, InstalledAppCandidate("Calcolatrice", "com.google.android.calculator"))

        val result = withDefaultApps("apri la calcolatrice", matched, lookup())

        assertEquals(matched, result)
        assertTrue(result.none { it.isDefault })
    }

    @Test
    fun `a request that names no kind never asks for defaults`() {
        var asked = 0
        val counting = DefaultAppLookup { asked++; oneplusCalculator }
        val matched = listOf(InstalledAppCandidate("Instagram", "com.instagram.android"))

        assertEquals(matched, withDefaultApps("apri instagram", matched, counting))
        assertEquals(0, asked)
    }

    @Test
    fun `each kind named gets its own default, and the matched apps follow`() {
        val matched = listOf(simpleCalculator)

        val result = withDefaultApps(
            "apri la calcolatrice e il calendario",
            matched,
            lookup(AppKind.CALCULATOR to oneplusCalculator, AppKind.CALENDAR to googleCalendar),
        )

        assertEquals(
            listOf(
                oneplusCalculator.copy(isDefault = true),
                googleCalendar.copy(isDefault = true),
                simpleCalculator,
            ),
            result,
        )
    }

    @Test
    fun `two kinds that resolve to the same app give one candidate`() {
        val gallery = InstalledAppCandidate("Photos", "com.google.android.apps.photos")

        val result = withDefaultApps(
            "apri la galleria e la fotocamera",
            emptyList(),
            lookup(AppKind.GALLERY to gallery, AppKind.CAMERA to gallery),
        )

        assertEquals(listOf(gallery.copy(isDefault = true)), result)
    }

    @Test
    fun `a lookup that fails never breaks the request`() {
        val broken = DefaultAppLookup { throw IllegalStateException("pm") }
        val matched = listOf(simpleCalculator)

        assertEquals(matched, withDefaultApps("apri la calcolatrice", matched, broken))
    }

    // --- il prompt ------------------------------------------------------------------------------

    @Test
    fun `the prompt carries the default flag and says what it means, only when there is one`() {
        val withDefault = composeInstalledAppCandidatesPrompt(
            "apri la calcolatrice",
            listOf(oneplusCalculator.copy(isDefault = true), simpleCalculator),
        )

        assertTrue(
            "installed_app_candidates=[{\"label\":\"Calculator\",\"package\":\"com.oneplus.calculator\",\"default\":true}," +
                "{\"label\":\"Calcolatrice semplice\",\"package\":\"com.simplemobiletools.calculator\"}]" in withDefault,
            withDefault,
        )
        assertTrue("\"default\":true is the user's default app" in withDefault, withDefault)
        assertTrue(withDefault.endsWith("User request:\napri la calcolatrice"), withDefault)

        val without = composeInstalledAppCandidatesPrompt("apri la calcolatrice", listOf(simpleCalculator))
        assertFalse("default" in without, without)
        assertTrue(
            "installed_app_candidates=[{\"label\":\"Calcolatrice semplice\",\"package\":\"com.simplemobiletools.calculator\"}]" in without,
            without,
        )
    }

    // --- il manifest ----------------------------------------------------------------------------

    @Test
    fun `the manifest lets Android answer for every kind, without seeing the whole phone`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        AppKind.entries.forEach { kind ->
            val declared = kind.category?.let { "<category android:name=\"$it\" />" }
                ?: "<action android:name=\"android.media.action.IMAGE_CAPTURE\" />"
            assertTrue(declared in manifest, "manca il <queries> per $kind: $declared")
        }
        assertFalse("<uses-permission android:name=\"android.permission.QUERY_ALL_PACKAGES\"" in manifest)
    }
}
