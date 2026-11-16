package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelephonyActionPolicyTest {
    private fun problems(intent: Action.SendIntent, resolved: Boolean = false) =
        TelephonyActionPolicy.intentProblems(intent, resolved)

    @Test
    fun `a number is an optional plus and 7 to 15 digits, nothing else`() {
        listOf("+393331112222", "3331112222", "1234567", "+123456789012345").forEach {
            assertTrue(TelephonyActionPolicy.isValidNumber(it), it)
        }
        listOf(
            "", "+", "123456", "+1234567890123456", "4242", "*21*3331112222#", "#31#3331112222",
            "+39 333 1112222", "333-1112222", "3331112222,1234", "3331112222;", "+39333111222p1", "++393331112222",
            "39+3331112222", "\${caller}", "+３９３３３１１１２２２２",
        ).forEach { assertFalse(TelephonyActionPolicy.isValidNumber(it), it) }
    }

    @Test
    fun `a placeholder is exactly one reference and nothing around it`() {
        assertEquals("caller", TelephonyActionPolicy.placeholder("\${caller}"))
        assertNull(TelephonyActionPolicy.placeholder("+39\${caller}"))
        assertNull(TelephonyActionPolicy.placeholder("\${caller} "))
        assertNull(TelephonyActionPolicy.placeholder("\${a}\${b}"))
        assertNull(TelephonyActionPolicy.placeholder("\${Caller}"))
        assertNull(TelephonyActionPolicy.placeholder("+393331112222"))
    }

    @Test
    fun `a complete intent is valid in every kind`() {
        val activity = Action.SendIntent(
            IntentKind.ACTIVITY,
            action = "android.intent.action.VIEW",
            pkg = "com.example.app",
            cls = "com.example.app.MainActivity",
            data = "geo:45.46,9.19?q=Duomo",
            mimeType = "text/plain",
            extras = listOf(
                IntentExtra("android.intent.extra.TEXT", IntentExtraType.STRING, "ciao \${name}"),
                IntentExtra("count", IntentExtraType.INT, "-3"),
                IntentExtra("big", IntentExtraType.LONG, "9000000000"),
                IntentExtra("on", IntentExtraType.BOOLEAN, "true"),
                IntentExtra("ratio", IntentExtraType.FLOAT, "0.5"),
            ),
        )
        assertEquals(emptyList(), problems(activity))
        assertEquals(emptyList(), problems(Action.SendIntent(IntentKind.BROADCAST, action = "com.example.PING")))
        assertEquals(
            emptyList(),
            problems(Action.SendIntent(IntentKind.SERVICE, pkg = "com.example.app", cls = "com.example.app.Sync")),
        )
    }

    @Test
    fun `an action or a full component is required, and a service needs the component`() {
        assertEquals(listOf("intent_target_missing"), problems(Action.SendIntent(IntentKind.BROADCAST)))
        assertEquals(listOf("intent_target_missing"), problems(Action.SendIntent(IntentKind.ACTIVITY, pkg = "com.example.app")))
        assertTrue(
            "intent_target_missing" in problems(Action.SendIntent(IntentKind.ACTIVITY, action = "a.B", cls = "com.example.A")),
        )
        assertEquals(
            listOf("intent_service_needs_component"),
            problems(Action.SendIntent(IntentKind.SERVICE, action = "com.example.SYNC", pkg = "com.example.app")),
        )
    }

    @Test
    fun `argus itself is never a target`() {
        listOf(
            Action.SendIntent(IntentKind.ACTIVITY, pkg = "dev.argus", cls = "com.example.A"),
            Action.SendIntent(IntentKind.ACTIVITY, pkg = "dev.argus.debug", cls = "dev.argus.debug.A"),
            Action.SendIntent(IntentKind.BROADCAST, pkg = "com.example.app", cls = "dev.argus.TimeAlarmReceiver"),
            Action.SendIntent(IntentKind.BROADCAST, action = "dev.argus.ACTION_RUN"),
        ).forEach { assertTrue("intent_target_forbidden" in problems(it), it.toString()) }
    }

    @Test
    fun `actions that call, install, uninstall, delete or reset are forbidden in any case`() {
        listOf(
            "android.intent.action.CALL", "android.intent.action.CALL_PRIVILEGED", "android.intent.action.CALL_EMERGENCY",
            "android.intent.action.INSTALL_PACKAGE", "android.intent.action.UNINSTALL_PACKAGE",
            "android.intent.action.DELETE", "android.intent.action.FACTORY_RESET", "android.intent.action.MASTER_CLEAR",
            "ANDROID.INTENT.ACTION.call",
        ).forEach { action ->
            assertEquals(listOf("intent_action_forbidden"), problems(Action.SendIntent(IntentKind.ACTIVITY, action = action)), action)
        }
        assertEquals(emptyList(), problems(Action.SendIntent(IntentKind.ACTIVITY, action = "android.intent.action.DIAL")))
    }

    @Test
    fun `data must be a uri with an allowed scheme and no placeholder`() {
        fun data(value: String) = problems(Action.SendIntent(IntentKind.ACTIVITY, action = "android.intent.action.VIEW", data = value))
        listOf("file:///sdcard/x", "FILE:/x", "javascript:alert(1)", "intent:#Intent;end", "Intent://x#Intent;end")
            .forEach { assertEquals(listOf("intent_data_scheme_forbidden"), data(it), it) }
        listOf("", " ", "no-scheme", "https://example.org/\${x}", "https://example.org/\na", "x".repeat(3_000))
            .forEach { assertEquals(listOf("intent_data_invalid"), data(it), it) }
        listOf("https://example.org/a?b=c", "tel:+393331112222", "content://media/external/images/1", "mailto:a@b.c")
            .forEach { assertEquals(emptyList(), data(it), it) }
    }

    @Test
    fun `names and values with control or invisible characters are refused`() {
        assertEquals(
            listOf("intent_name_invalid"),
            problems(Action.SendIntent(IntentKind.BROADCAST, action = "com.example.PI\u0000NG")),
        )
        assertEquals(
            listOf("intent_name_invalid"),
            problems(Action.SendIntent(IntentKind.BROADCAST, action = "com.example.PING", mimeType = "text/‮plain")),
        )
        assertEquals(
            listOf("intent_extra_invalid"),
            problems(
                Action.SendIntent(
                    IntentKind.BROADCAST,
                    action = "com.example.PING",
                    extras = listOf(IntentExtra("msg", IntentExtraType.STRING, "riga\nnuova")),
                ),
            ),
        )
        // Anche il valore risolto allo scatto.
        assertEquals(
            listOf("intent_extra_invalid"),
            problems(
                Action.SendIntent(
                    IntentKind.BROADCAST,
                    action = "com.example.PING",
                    extras = listOf(IntentExtra("msg", IntentExtraType.STRING, "a​b")),
                ),
                resolved = true,
            ),
        )
        assertTrue(TelephonyActionPolicy.hasControlCharacters("a b"))
        assertFalse(TelephonyActionPolicy.hasControlCharacters("città è ok"))
    }

    @Test
    fun `extras are at most sixteen, unique, typed and never empty`() {
        val ok = IntentExtra("n", IntentExtraType.INT, "1")
        fun with(vararg extras: IntentExtra) =
            problems(Action.SendIntent(IntentKind.BROADCAST, action = "com.example.PING", extras = extras.toList()))
        assertEquals(emptyList(), with(*Array(16) { IntentExtra("k$it", IntentExtraType.INT, "1") }))
        assertEquals(listOf("intent_too_many_extras"), with(*Array(17) { IntentExtra("k$it", IntentExtraType.INT, "1") }))
        assertEquals(listOf("intent_extra_invalid"), with(ok, ok))
        listOf(
            IntentExtra("", IntentExtraType.STRING, "x"),
            IntentExtra("bad name", IntentExtraType.STRING, "x"),
            IntentExtra("s", IntentExtraType.STRING, ""),
            IntentExtra("i", IntentExtraType.INT, ""),
            IntentExtra("i", IntentExtraType.INT, "1.5"),
            IntentExtra("i", IntentExtraType.INT, "\${n}"),
            IntentExtra("l", IntentExtraType.LONG, "x"),
            IntentExtra("b", IntentExtraType.BOOLEAN, "yes"),
            IntentExtra("f", IntentExtraType.FLOAT, "NaN"),
            IntentExtra("s", IntentExtraType.STRING, "\${broken"),
        ).forEach { assertEquals(listOf("intent_extra_invalid"), with(it), it.toString()) }
    }

    @Test
    fun `only clock conditions decide a call before it rings`() {
        val window = Condition.TimeWindow("22:00", "07:00", "Europe/Rome")
        val weekday = Condition.Weekday(setOf(Day.SAT, Day.SUN), "Europe/Rome")
        assertTrue(TelephonyActionPolicy.isScreeningCondition(window))
        assertTrue(
            TelephonyActionPolicy.isScreeningCondition(
                Condition.And(listOf(Condition.Not(weekday), Condition.Or(listOf(window, Condition.BooleanLiteral(true))))),
            ),
        )
        listOf(
            Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "silent"),
            Condition.And(listOf(window, Condition.LocationIn(45.0, 9.0, 100.0))),
            Condition.Not(Condition.AppInForeground("com.example.app")),
            Condition.CalendarBusy(),
        ).forEach { assertFalse(TelephonyActionPolicy.isScreeningCondition(it), it.toString()) }
    }
}
