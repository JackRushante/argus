package dev.argus.engine.model

import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.engine.safety.ConflictDetector
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.StaticShellSafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trigger «app aperta/chiusa» (0.5.1, F15): wire, matcher, validatore, capacità, shell, conflitti, parser. */
class AppEventTriggerTest {
    private val youtube = "com.google.android.youtube"

    private fun encode(trigger: Trigger) = ArgusJson.encodeToString(Trigger.serializer(), trigger)
    private fun decode(json: String) = ArgusJson.decodeFromString(Trigger.serializer(), json)

    // --- Wire ---------------------------------------------------------------------------------

    @Test
    fun `the trigger round-trips with the exact wire names of the registry`() {
        val opened = Trigger.AppEvent(youtube, AppEventKind.OPENED)
        val closed = Trigger.AppEvent(youtube, AppEventKind.CLOSED)
        assertEquals("""{"type":"app_event","pkg":"$youtube","event":"OPENED"}""", encode(opened))
        assertEquals("""{"type":"app_event","pkg":"$youtube","event":"CLOSED"}""", encode(closed))
        assertEquals(opened, decode(encode(opened)))
        assertEquals(closed, decode(encode(closed)))
    }

    @Test
    fun `an unknown direction does not decode`() {
        val broken = """{"type":"app_event","pkg":"$youtube","event":"MINIMIZED"}"""
        assertTrue(runCatching { decode(broken) }.isFailure)
    }

    // --- Matcher ------------------------------------------------------------------------------

    @Test
    fun `the matcher needs the same package and the same direction`() {
        val m = TriggerMatcher()
        val spec = Trigger.AppEvent(youtube, AppEventKind.OPENED)
        assertTrue(m.matches(spec, TriggerEvent.AppForegroundChanged(youtube, AppEventKind.OPENED)))
        assertFalse(m.matches(spec, TriggerEvent.AppForegroundChanged(youtube, AppEventKind.CLOSED)))
        assertFalse(m.matches(spec, TriggerEvent.AppForegroundChanged("com.android.chrome", AppEventKind.OPENED)))
        // Il confronto del package è esatto: né maiuscole/minuscole né prefissi.
        assertFalse(m.matches(spec, TriggerEvent.AppForegroundChanged("com.Google.android.youtube", AppEventKind.OPENED)))
        assertFalse(m.matches(spec, TriggerEvent.AppForegroundChanged("com.google.android.youtube.music", AppEventKind.OPENED)))
    }

    @Test
    fun `an app event matches no other trigger and no other event matches it`() {
        val m = TriggerMatcher()
        val event = TriggerEvent.AppForegroundChanged(youtube, AppEventKind.OPENED)
        assertFalse(m.matches(Trigger.Screen(ScreenEvent.ON), event))
        assertFalse(m.matches(Trigger.Media(MediaPlayState.PLAYING), event))
        assertFalse(m.matches(Trigger.Notification(pkg = youtube), event))
        assertFalse(m.matches(Trigger.AppEvent(youtube, AppEventKind.OPENED), TriggerEvent.ScreenChanged(ScreenEvent.ON)))
    }

    // --- Validatore ---------------------------------------------------------------------------

    private val validator = DraftValidator(knownTools = emptySet())

    private fun errors(trigger: Trigger) = validator.validate(
        AutomationDraft(name = "regola", trigger = trigger, actions = listOf(Action.SetRotation(auto = false))),
        emptySet(),
    ).filter { it.severity == Severity.ERROR }.map { it.code }

    @Test
    fun `the package must have the shape of an Android package`() {
        assertEquals(emptyList(), errors(Trigger.AppEvent(youtube, AppEventKind.OPENED)))
        assertEquals(emptyList(), errors(Trigger.AppEvent("org.telegram.messenger", AppEventKind.CLOSED)))
        assertEquals(emptyList(), errors(Trigger.AppEvent("com.example.app_2", AppEventKind.CLOSED)))
        assertTrue("pkg_blank" in errors(Trigger.AppEvent("", AppEventKind.OPENED)))
        assertTrue("pkg_blank" in errors(Trigger.AppEvent("   ", AppEventKind.OPENED)))
        assertTrue("package_invalid" in errors(Trigger.AppEvent("YouTube", AppEventKind.OPENED)))
        assertTrue("package_invalid" in errors(Trigger.AppEvent("com..youtube", AppEventKind.OPENED)))
        assertTrue("package_invalid" in errors(Trigger.AppEvent("1com.youtube", AppEventKind.OPENED)))
        assertTrue("package_invalid" in errors(Trigger.AppEvent("com.you tube", AppEventKind.OPENED)))
        assertTrue("package_invalid" in errors(Trigger.AppEvent("com.youtube; rm -rf /", AppEventKind.OPENED)))
    }

    @Test
    fun `a package longer than the limit is refused`() {
        val long = "com." + "a".repeat(AppPackagePolicy.MAX_LENGTH)
        assertTrue("package_invalid" in errors(Trigger.AppEvent(long, AppEventKind.OPENED)))
        assertFalse(AppPackagePolicy.valid(long))
        assertTrue(AppPackagePolicy.valid("com." + "a".repeat(AppPackagePolicy.MAX_LENGTH - 4)))
    }

    // --- Capacità, shell, conflitti -----------------------------------------------------------

    @Test
    fun `the trigger requires only the app event capability`() {
        assertEquals(
            setOf(CapabilityIds.TRIGGER_APP_EVENT),
            CapabilityRequirements.derive(Trigger.AppEvent(youtube, AppEventKind.OPENED), emptyList()),
        )
        assertEquals("trigger.app_event", CapabilityIds.TRIGGER_APP_EVENT)
    }

    @Test
    fun `an app event can never start the autonomous shell`() {
        val spec = Trigger.AppEvent(youtube, AppEventKind.OPENED)
        val live = TriggerEvent.AppForegroundChanged(youtube, AppEventKind.OPENED)
        assertFalse(StaticShellSafety.allows(spec, emptySet()))
        assertFalse(StaticShellSafety.allows(live, emptySet()))
        assertFalse(StaticShellSafety.allows(spec, live, emptySet()))
        // Neanche con una whitelist piena: l'identità di chi apre un'app non esiste.
        assertFalse(StaticShellSafety.allows(spec, live, setOf("any-conversation")))
    }

    @Test
    fun `an open and close pair of the same app is complementary and not a conflict`() {
        fun rule(id: String, event: AppEventKind, pkg: String = youtube, auto: Boolean) = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.AppEvent(pkg, event),
            actions = listOf(Action.SetDarkMode(if (auto) NightMode.ON else NightMode.OFF)),
        )
        val detector = ConflictDetector()
        assertTrue(
            detector.detect(
                listOf(rule("open", AppEventKind.OPENED, auto = true), rule("close", AppEventKind.CLOSED, auto = false)),
            ).isEmpty(),
        )
        // Due app diverse, o la stessa direzione, possono davvero sovrapporsi.
        assertEquals(
            1,
            detector.detect(
                listOf(
                    rule("open-a", AppEventKind.OPENED, auto = true),
                    rule("open-b", AppEventKind.OPENED, pkg = "com.android.chrome", auto = false),
                ),
            ).size,
        )
    }

    // --- Parser -------------------------------------------------------------------------------

    private val parser = DeviceEventParser()

    @Test
    fun `the event id is an opaque digest of package direction and instant`() {
        val envelope = assertNotNull(parser.appEvent(youtube, AppEventKind.OPENED, 1_000L))
        assertEquals(TriggerEvent.AppForegroundChanged(youtube, AppEventKind.OPENED), envelope.event)
        assertTrue(Regex("^device:[0-9a-f]{64}$").matches(envelope.id.value), envelope.id.value)
        // La stessa transizione riletta dà lo stesso id: il motore non la esegue due volte.
        assertEquals(envelope.id, parser.appEvent(youtube, AppEventKind.OPENED, 1_000L)!!.id)
        assertNotEquals(envelope.id, parser.appEvent(youtube, AppEventKind.CLOSED, 1_000L)!!.id)
        assertNotEquals(envelope.id, parser.appEvent(youtube, AppEventKind.OPENED, 2_000L)!!.id)
        assertNotEquals(envelope.id, parser.appEvent("com.android.chrome", AppEventKind.OPENED, 1_000L)!!.id)
        // Il package non compare in chiaro nell'id.
        assertFalse(youtube in envelope.id.value)
    }

    @Test
    fun `a package that is not an Android package produces no event`() {
        assertNull(parser.appEvent("", AppEventKind.OPENED, 1L))
        assertNull(parser.appEvent("not a package", AppEventKind.OPENED, 1L))
        assertNull(parser.appEvent("com.x\nmalicious", AppEventKind.CLOSED, 1L))
    }
}
