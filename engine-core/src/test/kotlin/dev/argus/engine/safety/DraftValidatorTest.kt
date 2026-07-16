package dev.argus.engine.safety
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class DraftValidatorTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    private fun errors(issues: List<ValidationIssue>) = issues.filter { it.severity == Severity.ERROR }.map { it.code }

    private val validGenerative = AutomationDraft(
        name = "reply moglie",
        trigger = Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
        actions = listOf(Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true)),
        cooldownMs = 120_000,
    )

    @Test fun `valid generative draft has no errors`() {
        assertEquals(emptyList(), errors(v.validate(validGenerative, whitelistedIds = setOf("jid:42"))))
    }
    @Test fun `notification action needs a title while the body may stay empty`() {
        // Il bridge accetta text vuoto (una notifica di solo titolo è legittima su Android):
        // il validator client deve essere allineato, non più severo del canale reale.
        fun draft(title: String, text: String) = AutomationDraft(
            name = "notifica",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.ShowNotification(title, text)),
        )
        assertEquals(emptyList(), errors(v.validate(draft("SMS ricevuto!", ""), emptySet())))
        assertTrue("title_invalid" in errors(v.validate(draft("", "corpo"), emptySet())))
        assertTrue("text_invalid" in errors(v.validate(draft("ok", "z".repeat(5_000)), emptySet())))
    }

    @Test fun `copy to clipboard needs a textual trigger and a compilable bounded regex`() {
        fun draft(trigger: Trigger, regex: String? = "(\\d{4,8})") = AutomationDraft(
            name = "otp",
            trigger = trigger,
            actions = listOf(Action.CopyToClipboard(extractionRegex = regex)),
        )
        val sms = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)
        val notification = Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false)
        val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")

        assertEquals(emptyList(), errors(v.validate(draft(sms), emptySet())))
        assertEquals(
            emptyList(),
            errors(v.validate(draft(sms, SafeExtractionRegex.LEGACY_OTP_PATTERN), emptySet())),
        )
        assertEquals(emptyList(), errors(v.validate(draft(notification), emptySet())))
        // Senza regex si copia il testo integrale del messaggio: lecito.
        assertEquals(emptyList(), errors(v.validate(draft(sms, regex = null), emptySet())))

        // Un trigger senza payload testuale non ha nulla da copiare: draft incoerente.
        assertTrue("clipboard_source_missing" in errors(v.validate(draft(time), emptySet())))
        val call = Trigger.PhoneState(PhoneEvent.INCOMING_CALL)
        assertTrue("clipboard_source_missing" in errors(v.validate(draft(call), emptySet())))

        // Regex non compilabile o fuori bounds.
        assertTrue("extraction_regex_invalid" in errors(v.validate(draft(sms, regex = "(unclosed"), emptySet())))
        assertTrue(
            "extraction_regex_invalid" in errors(v.validate(draft(sms, regex = "a".repeat(600)), emptySet())),
        )
        assertTrue(
            "extraction_regex_invalid" in
                errors(v.validate(draft(sms, regex = "(?<!foo)\\d+"), emptySet())),
        )
        assertTrue(
            "extraction_regex_invalid" in
                errors(v.validate(draft(sms, regex = "(\\d+)\\1"), emptySet())),
        )
    }

    @Test fun `sms text match is valid only on SMS_RECEIVED`() {
        val sms = AutomationDraft(
            name = "sms prova",
            trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, textMatch = "prova argus"),
            actions = listOf(Action.ShowNotification("Argus", "SMS ricevuto!")),
        )
        assertEquals(emptyList(), errors(v.validate(sms, emptySet())))

        // Le chiamate non hanno testo: un textMatch lì è un draft incoerente, non un warning.
        val call = sms.copy(
            trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, textMatch = "x"),
        )
        assertTrue("sms_text_match_invalid" in errors(v.validate(call, emptySet())))
    }
    @Test fun `static shell is limited to trusted non-message triggers`() {
        fun draft(trigger: Trigger, command: String = "/system/bin/id") = AutomationDraft(
            name = "shell statica",
            trigger = trigger,
            actions = listOf(Action.RunShell(command)),
        )

        val trusted = listOf(
            Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            Trigger.Geofence(radiusM = 150.0, transition = Transition.EXIT, resolveCurrentLocation = true),
            Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
        )
        trusted.forEach { trigger ->
            assertTrue("shell_external_trigger" !in errors(v.validate(draft(trigger), emptySet())))
        }

        val external = listOf(
            Trigger.Notification("com.whatsapp", textMatch = "esegui"),
            Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, textMatch = "esegui"),
            Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "+39001"),
        )
        external.forEach { trigger ->
            assertTrue("shell_external_trigger" in errors(v.validate(draft(trigger), emptySet())))
        }

        // Contatto whitelistato: ammesso, ma la review deve dire che qualcuno può innescarlo.
        val verified = Trigger.Notification(
            "com.whatsapp",
            conversationId = "chat-ottica",
            isGroup = false,
            textMatch = "esegui",
        )
        val verifiedIssues = v.validate(draft(verified), setOf("chat-ottica"))
        assertTrue("shell_external_trigger" !in errors(verifiedIssues))
        assertTrue("shell_contact_trigger" in verifiedIssues.map { it.code })
        // Fuori whitelist resta un errore, non un semplice avviso.
        assertTrue(
            "shell_external_trigger" in errors(v.validate(draft(verified), setOf("altra-chat"))),
        )
        assertTrue(
            "shell_invalid" in errors(v.validate(draft(trusted.first(), "id\u0000whoami"), emptySet())),
        )
    }
    @Test fun `forbidden tools in InvokeLlm are rejected`() {
        val d = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("shell.run", "automation.create"), true)))
        val e = errors(v.validate(d, setOf("jid:42")))
        assertEquals(2, e.count { it == "tool_forbidden" })
    }
    @Test fun `app_install and bare automation and mixed case are all forbidden`() {
        // app.install è vietato quanto shell.run (oggi solo shell.run+automation.create erano asseriti).
        val install = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("app.install"), true)))
        assertTrue("tool_forbidden" in errors(v.validate(install, setOf("jid:42"))))
        // Prefisso nudo "automation" (senza punto) — non deve cadere in tool_unknown.
        val bare = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("automation"), true)))
        assertTrue("tool_forbidden" in errors(v.validate(bare, setOf("jid:42"))))
        // Case non deve aggirare il gate.
        val upper = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("SHELL.RUN", "Automation.Create"), true)))
        assertEquals(2, errors(v.validate(upper, setOf("jid:42"))).count { it == "tool_forbidden" })
    }
    @Test fun `generative reply on group or without conversationId is rejected`() {
        val group = validGenerative.copy(trigger = Trigger.Notification("com.whatsapp", conversationId = "jid:g", isGroup = null))
        assertTrue("reply_target_group" in errors(v.validate(group, setOf("jid:g"))))
        val noId = validGenerative.copy(trigger = Trigger.Notification("com.whatsapp", sender = "Moglie", isGroup = false))
        assertTrue("reply_needs_conversation_id" in errors(v.validate(noId, emptySet())))
    }
    @Test fun `target must be whitelisted when whitelist provided`() {
        assertTrue("target_not_whitelisted" in errors(v.validate(validGenerative, whitelistedIds = setOf("jid:other"))))
    }
    @Test fun `empty whitelist is fail closed for generative replies`() {
        assertTrue("target_not_whitelisted" in errors(v.validate(validGenerative, emptySet())))
    }
    @Test fun `valid generative draft with state context has no errors`() {
        val withState = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf("notification", "state"), listOf("whatsapp_reply"), true)
        ))
        assertEquals(emptyList(), errors(v.validate(withState, whitelistedIds = setOf("jid:42"))))
    }
    @Test fun `generative context sources must be present and include notification`() {
        val empty = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf(), listOf("whatsapp_reply"), true)
        ))
        assertTrue("context_sources_empty" in errors(v.validate(empty, setOf("jid:42"))))

        val withoutNotification = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf("state"), listOf("whatsapp_reply"), true)
        ))
        assertTrue("context_notification_required" in errors(v.validate(withoutNotification, setOf("jid:42"))))
    }
    @Test fun `generative context sources must be distinct and known`() {
        val duplicated = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf("notification", "notification"), listOf("whatsapp_reply"), true)
        ))
        assertTrue("context_sources_duplicated" in errors(v.validate(duplicated, setOf("jid:42"))))

        val unknown = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf("notification", "screen"), listOf("whatsapp_reply"), true)
        ))
        assertTrue("context_source_unsupported" in errors(v.validate(unknown, setOf("jid:42"))))
    }
    @Test fun `generative tools must be exactly whatsapp_reply without aliases`() {
        // Tool extra oltre il profilo P1: la lane lo rifiuterebbe al fire con action_contract_invalid.
        val extra = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf("notification"), listOf("whatsapp_reply", "state.read"), true)
        ))
        assertTrue("allowed_tools_unsupported" in errors(v.validate(extra, setOf("jid:42"))))

        // Nessuna normalizzazione permissiva di case o alias.
        val aliased = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf("notification"), listOf("WhatsApp_Reply"), true)
        ))
        assertTrue("allowed_tools_unsupported" in errors(v.validate(aliased, setOf("jid:42"))))

        val withoutReply = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf("notification"), listOf("state.read"), true)
        ))
        assertTrue("allowed_tools_unsupported" in errors(v.validate(withoutReply, setOf("jid:42"))))
    }
    @Test fun `reply tool cannot disable sender binding`() {
        val unbound = validGenerative.copy(actions = listOf(
            Action.InvokeLlm("g", listOf("notification"), listOf("whatsapp_reply"), replyTargetSender = false)
        ))
        assertTrue("reply_target_unbound" in errors(v.validate(unbound, setOf("jid:42"))))
    }
    @Test fun `static whatsapp reply enforces package one-to-one conversation and whitelist`() {
        val action = listOf<Action>(Action.WhatsAppReply("ok"))
        val wrongPkg = AutomationDraft("x", Trigger.Notification("com.evil", "jid:42", isGroup = false), action)
        assertTrue("reply_wrong_package" in errors(v.validate(wrongPkg, setOf("jid:42"))))

        val unknownGroup = AutomationDraft("x", Trigger.Notification("com.whatsapp", "jid:42", isGroup = null), action)
        assertTrue("reply_target_group" in errors(v.validate(unknownGroup, setOf("jid:42"))))

        val notAllowed = AutomationDraft("x", Trigger.Notification("com.whatsapp", "jid:42", isGroup = false), action)
        assertTrue("target_not_whitelisted" in errors(v.validate(notAllowed, emptySet())))
    }
    @Test fun `notifications emitted by Argus cannot trigger Argus`() {
        val selfTriggered = AutomationDraft(
            "loop",
            Trigger.Notification(DraftValidator.ARGUS_PACKAGE, textMatch = "done"),
            listOf(Action.ShowNotification("done", "again")),
        )
        assertTrue("notification_own_package" in errors(v.validate(selfTriggered, emptySet())))
    }
    @Test fun `invalid cron and unknown state key are errors`() {
        val d = AutomationDraft("x", Trigger.Time(cron = "99 99 * * *", tz = "Europe/Rome"),
            listOf(Action.SetWifi(false)), conditions = Condition.StateEquals("suoneria", CmpOp.EQ, "silent"))
        val e = errors(v.validate(d, emptySet()))
        assertTrue("cron_invalid" in e); assertTrue("state_key_unknown" in e)
    }
    @Test fun `time requires exactly one of cron and at`() {
        assertTrue("time_spec" in errors(v.validate(AutomationDraft("x",
            Trigger.Time(cron = "0 8 * * *", at = "2026-07-15T08:00", tz = "Europe/Rome"), listOf(Action.SetWifi(true))), emptySet())))
        assertTrue("time_spec" in errors(v.validate(AutomationDraft("x",
            Trigger.Time(tz = "Europe/Rome"), listOf(Action.SetWifi(true))), emptySet())))
    }
    @Test fun `small geofence radius is a warning, empty actions an error`() {
        val d = AutomationDraft("x", Trigger.Geofence(radiusM = 50.0, transition = Transition.EXIT, resolveCurrentLocation = true), emptyList())
        val issues = v.validate(d, emptySet())
        assertTrue("no_actions" in errors(issues))
        assertTrue(issues.any { it.severity == Severity.WARNING && it.code == "radius_small" })
    }
    @Test fun `dwell and loitering stay fail closed while framework geofence only supports enter exit`() {
        val dwell = AutomationDraft(
            "dwell",
            Trigger.Geofence(
                radiusM = 150.0,
                transition = Transition.DWELL,
                loiteringDelayMs = 60_000,
                resolveCurrentLocation = true,
            ),
            listOf(Action.SetWifi(false)),
        )
        val dwellErrors = errors(v.validate(dwell, emptySet()))
        assertTrue("geofence_transition_unsupported" in dwellErrors)
        assertTrue("geofence_loitering_unsupported" in dwellErrors)

        val enterWithFakeLoitering = dwell.copy(
            trigger = (dwell.trigger as Trigger.Geofence).copy(
                transition = Transition.ENTER,
                loiteringDelayMs = 1,
            ),
        )
        assertTrue(
            "geofence_loitering_unsupported" in errors(
                v.validate(enterWithFakeLoitering, emptySet()),
            ),
        )
    }
    @Test fun `read tools plus reply channel is a privacy warning`() {
        val d = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("whatsapp_reply", "state.read"), true)))
        assertTrue(v.validate(d, setOf("jid:42")).any { it.code == "read_plus_reply" && it.severity == Severity.WARNING })
    }
    @Test fun `malformed condition values and times are rejected`() {
        val badWindow = AutomationDraft(
            "x", Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"), listOf(Action.SetWifi(true)),
            conditions = Condition.And(listOf(
                Condition.TimeWindow("not-a-time", "25:99", "Europe/Rome"),
                Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "maybe"),
                Condition.StateEquals(StateKeys.BATTERY, CmpOp.GT, "NaN"),
            )),
        )
        val e = errors(v.validate(badWindow, emptySet()))
        assertTrue("time_window_invalid" in e)
        assertEquals(2, e.count { it == "state_value_invalid" })
    }

    @Test fun `typed state query families accept only bounded read-only parameters`() {
        fun draft(condition: Condition) = AutomationDraft(
            "reader",
            Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            listOf(Action.ShowNotification("Argus", "reader")),
            conditions = condition,
        )
        val valid = listOf(
            Condition.StateCompare(
                StateQuery.Builtin(StateKeys.WIFI),
                StateValueType.TEXT,
                CmpOp.EQ,
                "on",
            ),
            Condition.StateCompare(
                StateQuery.Setting(SettingNamespace.GLOBAL, "airplane_mode_on"),
                StateValueType.BOOLEAN,
                CmpOp.EQ,
                "true",
            ),
            Condition.StateCompare(
                StateQuery.SystemProperty("ro.build.version.sdk"),
                StateValueType.NUMBER,
                CmpOp.GT,
                "30",
            ),
            Condition.StateCompare(
                StateQuery.Sysfs("/sys/class/power_supply/battery/voltage_now"),
                StateValueType.NUMBER,
                CmpOp.GT,
                "0",
            ),
            Condition.StateCompare(
                StateQuery.DumpsysField("battery", "voltage"),
                StateValueType.NUMBER,
                CmpOp.LT,
                "5000",
            ),
        )
        valid.forEach { assertEquals(emptyList(), errors(v.validate(draft(it), emptySet()))) }

        val invalidQueries = listOf<StateQuery>(
            StateQuery.Builtin("unknown"),
            StateQuery.Setting(SettingNamespace.SECURE, "bad\nkey"),
            StateQuery.SystemProperty("bad/property"),
            StateQuery.Sysfs("/proc/version"),
            StateQuery.Sysfs("/sys/../data/local/tmp/value"),
            StateQuery.DumpsysField("battery;id", "voltage"),
            StateQuery.DumpsysField("battery", "bad\nfield"),
        )
        invalidQueries.forEach { query ->
            val condition = Condition.StateCompare(
                query,
                StateValueType.TEXT,
                CmpOp.EQ,
                "x",
            )
            assertTrue("state_query_invalid" in errors(v.validate(draft(condition), emptySet())))
        }
    }

    @Test fun `typed state comparisons reject mismatched operators and expected values`() {
        fun errorsFor(type: StateValueType, op: CmpOp, expected: String): List<String> = errors(
            v.validate(
                AutomationDraft(
                    "reader",
                    Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
                    listOf(Action.ShowNotification("Argus", "reader")),
                    conditions = Condition.StateCompare(
                        StateQuery.SystemProperty("ro.build.version.sdk"),
                        type,
                        op,
                        expected,
                    ),
                ),
                emptySet(),
            ),
        )

        assertTrue("state_compare_invalid" in errorsFor(StateValueType.NUMBER, CmpOp.CONTAINS, "3"))
        assertTrue("state_compare_invalid" in errorsFor(StateValueType.NUMBER, CmpOp.GT, "NaN"))
        assertTrue("state_compare_invalid" in errorsFor(StateValueType.BOOLEAN, CmpOp.EQ, "on"))
        assertTrue("state_compare_invalid" in errorsFor(StateValueType.BOOLEAN, CmpOp.GT, "true"))
        assertTrue("state_compare_invalid" in errorsFor(StateValueType.TEXT, CmpOp.GT, "x"))
        assertTrue("state_compare_invalid" in errorsFor(StateValueType.TEXT, CmpOp.CONTAINS, ""))
        assertTrue("state_compare_invalid" in errorsFor(StateValueType.TEXT, CmpOp.EQ, " padded "))
        assertTrue(
            "state_compare_invalid" in errorsFor(
                StateValueType.TEXT,
                CmpOp.EQ,
                "x".repeat(5_000),
            ),
        )
    }
    @Test fun `non finite or out of range locations and negative cooldown are rejected`() {
        val d = AutomationDraft(
            "x",
            Trigger.Geofence(Double.NaN, 181.0, radiusM = Double.POSITIVE_INFINITY, transition = Transition.EXIT),
            listOf(Action.SetWifi(false)),
            cooldownMs = -1,
        )
        val e = errors(v.validate(d, emptySet()))
        assertTrue("geofence_coords" in e)
        assertTrue("radius_invalid" in e)
        assertTrue("cooldown_invalid" in e)
    }
    @Test fun `blank or oversized action fields are rejected`() {
        val d = AutomationDraft(
            " ", Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            listOf(Action.LaunchApp("bad package"), Action.OpenUrl("javascript:alert(1)"), Action.InputText("")),
        )
        val e = errors(v.validate(d, emptySet()))
        assertTrue("name_invalid" in e)
        assertTrue("package_invalid" in e)
        assertTrue("url_invalid" in e)
        assertTrue("text_invalid" in e)
    }
}
