package dev.argus.device

import dev.argus.shizuku.PrivilegedShell
import dev.argus.shizuku.ShellResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Accendere il servizio di Accessibilità di Argus (0.5.1): si AGGIUNGE all'elenco di sistema, non lo
 * si sovrascrive mai, e lo si fa solo con la conferma esplicita del dialogo dei permessi permanenti.
 */
class AccessibilityEnablementTest {
    private val pkg = "dev.argus"
    private val argus = "dev.argus/dev.argus.automation.screen.ArgusAccessibilityService"
    private val other = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    @Test
    fun `the component is the package slash the declared class`() {
        assertEquals(argus, AccessibilityEnablement.component(pkg))
    }

    @Test
    fun `adding to an empty or unset list gives just the argus service`() {
        assertEquals(argus, AccessibilityEnablement.withService(null, pkg))
        assertEquals(argus, AccessibilityEnablement.withService("", pkg))
        assertEquals(argus, AccessibilityEnablement.withService("null", pkg))
        assertEquals(argus, AccessibilityEnablement.withService("  \n", pkg))
    }

    @Test
    fun `other services stay, in their order, and argus goes last`() {
        assertEquals("$other:$argus", AccessibilityEnablement.withService(other, pkg))
        assertEquals("a/b.C:$other:$argus", AccessibilityEnablement.withService("a/b.C:$other", pkg))
        assertEquals("$other:$argus", AccessibilityEnablement.withService("$other:", pkg))
    }

    @Test
    fun `a list that already has argus is left exactly as it is`() {
        assertEquals("$other:$argus", AccessibilityEnablement.withService("$other:$argus", pkg))
        // Anche scritto nella forma corta, con il punto davanti alla classe.
        val short = "$other:dev.argus/.automation.screen.ArgusAccessibilityService"
        assertEquals(short, AccessibilityEnablement.withService(short, pkg))
    }

    @Test
    fun `the short component form is read as the long one`() {
        assertEquals(
            listOf(argus),
            AccessibilityEnablement.entries("dev.argus/.automation.screen.ArgusAccessibilityService"),
        )
        assertTrue(AccessibilityEnablement.isEnabled("dev.argus/.automation.screen.ArgusAccessibilityService", 1, pkg))
    }

    @Test
    fun `it is enabled only when the service is listed and the general switch is on`() {
        assertTrue(AccessibilityEnablement.isEnabled("$other:$argus", 1, pkg))
        assertFalse(AccessibilityEnablement.isEnabled("$other:$argus", 0, pkg))
        assertFalse(AccessibilityEnablement.isEnabled("$other:$argus", null, pkg))
        assertFalse(AccessibilityEnablement.isEnabled(other, 1, pkg))
        assertFalse(AccessibilityEnablement.isEnabled(null, 1, pkg))
        assertFalse(AccessibilityEnablement.isEnabled("dev.argus.other/dev.argus.automation.screen.ArgusAccessibilityService", 1, pkg))
    }

    @Test
    fun `only plain component lists can be written`() {
        assertTrue(AccessibilityEnablement.isSafeValue("$other:$argus"))
        assertFalse(AccessibilityEnablement.isSafeValue(""))
        assertFalse(AccessibilityEnablement.isSafeValue("a/b;reboot"))
        assertFalse(AccessibilityEnablement.isSafeValue("a/b c"))
        assertFalse(AccessibilityEnablement.isSafeValue("a/b\nc/d"))
        assertFalse(AccessibilityEnablement.isSafeValue("a/b" + "x".repeat(3_000)))
    }

    @Test
    fun `the declared class is the one the manifest of the runtime module names`() {
        val manifest = File("../automation-android/src/main/AndroidManifest.xml").readText()
        assertTrue(AccessibilityEnablement.SERVICE_CLASS in manifest)
    }

    // --- il controller -------------------------------------------------------------------------

    private class ScriptedShell(private val currentList: String, private val failOn: String? = null) : PrivilegedShell {
        val commands = mutableListOf<List<String>>()

        override suspend fun run(
            command: List<String>,
            priority: Int,
            timeoutMillis: Long,
            maxOutputBytes: Int,
            executionId: String?,
        ): ShellResult {
            commands += command
            if (failOn != null && command.getOrNull(1) == failOn) return ShellResult(1)
            return if (command.getOrNull(1) == "get") {
                ShellResult(0, stdout = (currentList + "\n").toByteArray())
            } else {
                ShellResult(0)
            }
        }

        override suspend fun runToFile(
            command: List<String>,
            destination: File,
            priority: Int,
            timeoutMillis: Long,
            maxOutputBytes: Int,
            executionId: String?,
        ): ShellResult = run(command)
    }

    private fun puts(shell: ScriptedShell) = shell.commands.filter { it.getOrNull(1) == "put" }

    @Test
    fun `granting reads the list, adds argus without removing anything, then turns the switch on`() = runBlocking {
        val shell = ScriptedShell(other)
        val outcome = PermanentGrantsController(shell, pkg).grant(setOf(PermanentGrant.ACCESSIBILITY))

        assertEquals(GrantOutcome.SENT, outcome[PermanentGrant.ACCESSIBILITY])
        assertEquals(
            listOf(
                listOf("settings", "get", "secure", "enabled_accessibility_services"),
                listOf("settings", "put", "secure", "enabled_accessibility_services", "$other:$argus"),
                listOf("settings", "put", "secure", "accessibility_enabled", "1"),
            ),
            shell.commands,
        )
    }

    @Test
    fun `with nothing enabled yet only argus is written`() = runBlocking {
        val shell = ScriptedShell("null")
        PermanentGrantsController(shell, pkg).grant(setOf(PermanentGrant.ACCESSIBILITY))
        assertEquals(argus, puts(shell).first()[4])
    }

    @Test
    fun `an already listed service is not written again, the switch is still turned on`() = runBlocking {
        val shell = ScriptedShell("$other:$argus")
        val outcome = PermanentGrantsController(shell, pkg).grant(setOf(PermanentGrant.ACCESSIBILITY))
        assertEquals(GrantOutcome.SENT, outcome[PermanentGrant.ACCESSIBILITY])
        assertEquals(listOf(listOf("settings", "put", "secure", "accessibility_enabled", "1")), puts(shell))
    }

    @Test
    fun `a list that cannot be read or has odd characters is never overwritten`() = runBlocking {
        val unreadable = ScriptedShell(other, failOn = "get")
        assertEquals(
            GrantOutcome.FAILED,
            PermanentGrantsController(unreadable, pkg).grant(setOf(PermanentGrant.ACCESSIBILITY))[PermanentGrant.ACCESSIBILITY],
        )
        assertTrue(puts(unreadable).isEmpty())

        val odd = ScriptedShell("a/b;reboot")
        assertEquals(
            GrantOutcome.FAILED,
            PermanentGrantsController(odd, pkg).grant(setOf(PermanentGrant.ACCESSIBILITY))[PermanentGrant.ACCESSIBILITY],
        )
        assertTrue(puts(odd).isEmpty())
    }

    @Test
    fun `a refused write is a failure`() = runBlocking {
        val shell = ScriptedShell(other, failOn = "put")
        assertEquals(
            GrantOutcome.FAILED,
            PermanentGrantsController(shell, pkg).grant(setOf(PermanentGrant.ACCESSIBILITY))[PermanentGrant.ACCESSIBILITY],
        )
    }

    @Test
    fun `the accessibility grant is the last in the fixed order and the others are unchanged`() {
        assertEquals(PermanentGrant.ACCESSIBILITY, PermanentGrant.entries.last())
        assertEquals("accessibility", PermanentGrant.ACCESSIBILITY.id)
        assertEquals(
            listOf("settings", "put", "secure", "accessibility_enabled", "1"),
            PermanentGrant.ACCESSIBILITY.command(pkg),
        )
    }
}
