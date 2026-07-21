package dev.argus.engine.safety
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class DraftValidatorTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    // Come in produzione (AndroidCapabilityProbe.KNOWN_TOOLS) web.search è nel catalogo: serve
    // perché il check per-tool tool_unknown non lo respinga mentre verifichiamo il contratto.
    private val vWeb = DraftValidator(
        knownTools = setOf("whatsapp_reply", "notify.show", "state.read", "web.search"),
    )
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

    @Test fun `copy text needs no textual trigger and only a non-blank bounded literal`() {
        fun draft(trigger: Trigger, text: String) = AutomationDraft(
            name = "copy literale",
            trigger = trigger,
            actions = listOf(Action.CopyText(text)),
        )
        // A differenza di copy_to_clipboard, non serve un trigger con testo: un Time va bene.
        val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
        assertEquals(emptyList(), errors(v.validate(draft(time, "ordine #4821"), emptySet())))
        // Vuoto o fuori bound: draft incoerente.
        assertTrue("text_invalid" in errors(v.validate(draft(time, ""), emptySet())))
        assertTrue("text_invalid" in errors(v.validate(draft(time, "z".repeat(5_000)), emptySet())))
        // Nessuna dipendenza dal trigger: clipboard_source_missing non deve mai comparire.
        assertTrue("clipboard_source_missing" !in errors(v.validate(draft(time, "x"), emptySet())))
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
    @Test fun `v2 generative state is exact typed classified and never underclassified`() {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val context = ApprovedStateContext(
            query = query,
            valueType = StateValueType.NUMBER,
            policyVersion = StateQueryPolicy.VERSION,
            integrity = IntegrityLabel.CLEAN,
            confidentiality = ConfidentialityLabel.SECRET,
        )
        fun draft(state: List<ApprovedStateContext>) = validGenerative.copy(
            actions = listOf(
                Action.InvokeLlmV2(
                    goal = "rispondi considerando il voltaggio",
                    stateContext = state,
                    allowedTools = listOf("whatsapp_reply"),
                    replyTargetSender = true,
                    timeoutMs = 60_000,
                ),
            ),
        )

        val valid = v.validate(draft(listOf(context)), setOf("jid:42"))
        assertEquals(emptyList(), errors(valid))
        assertTrue(valid.any { it.code == "state_context_disclosure" })
        assertTrue(valid.any { it.code == "secret_state_context" })

        assertTrue(
            "state_context_underclassified" in errors(
                v.validate(
                    draft(listOf(context.copy(confidentiality = ConfidentialityLabel.PRIVATE))),
                    setOf("jid:42"),
                ),
            ),
        )
        assertTrue(
            "state_context_integrity_invalid" in errors(
                v.validate(
                    draft(listOf(context.copy(integrity = IntegrityLabel.TAINTED))),
                    setOf("jid:42"),
                ),
            ),
        )
        assertTrue(
            "state_context_duplicated" in errors(
                v.validate(draft(listOf(context, context)), setOf("jid:42")),
            ),
        )
        assertTrue(
            "state_context_type_invalid" in errors(
                v.validate(
                    draft(
                        listOf(
                            context.copy(
                                query = StateQuery.Builtin(StateKeys.BATTERY),
                                valueType = StateValueType.BOOLEAN,
                            ),
                        ),
                    ),
                    setOf("jid:42"),
                ),
            ),
        )
        assertTrue(
            "state_context_policy_incompatible" in errors(
                v.validate(
                    draft(listOf(context.copy(policyVersion = StateQueryPolicy.VERSION + 1))),
                    setOf("jid:42"),
                ),
            ),
        )
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
    @Test fun `generative tools require the reply and reject non-web read tools or aliases`() {
        // Il reply resta obbligatorio; oltre ad esso è ammesso SOLO il tool di contesto web
        // opzionale (web.search). state.read/screen.* e gli alias di case restano fuori dal contratto.
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

    @Test fun `invoke_llm allows the optional web search tool beside the mandatory reply`() {
        fun draft(tools: List<String>) = validGenerative.copy(
            actions = listOf(Action.InvokeLlm("g", listOf("notification"), tools, true)),
        )
        // reply-only resta valido (regressione).
        assertEquals(emptyList(), errors(vWeb.validate(draft(listOf("whatsapp_reply")), setOf("jid:42"))))
        // reply + web.search: nuovo contratto valido.
        assertEquals(
            emptyList(),
            errors(vWeb.validate(draft(listOf("whatsapp_reply", "web.search")), setOf("jid:42"))),
        )
        // web.search senza reply (manca il sink obbligatorio) → non valido.
        assertTrue(
            "allowed_tools_unsupported" in errors(vWeb.validate(draft(listOf("web.search")), setOf("jid:42"))),
        )
        // reply duplicato → non valido.
        assertTrue(
            "allowed_tools_unsupported" in errors(
                vWeb.validate(draft(listOf("whatsapp_reply", "whatsapp_reply")), setOf("jid:42")),
            ),
        )
        // reply + shell.run: il toolset è illegale (allowed_tools_unsupported) e in più la difesa
        // in profondità per-tool marca shell.run come tool_forbidden.
        val shell = errors(vWeb.validate(draft(listOf("whatsapp_reply", "shell.run")), setOf("jid:42")))
        assertTrue("allowed_tools_unsupported" in shell)
        assertTrue("tool_forbidden" in shell)
    }

    @Test fun `invoke_llm_v2 reply contract allows the optional web search tool`() {
        val context = ApprovedStateContext(
            query = StateQuery.DumpsysField("battery", "voltage"),
            valueType = StateValueType.NUMBER,
            policyVersion = StateQueryPolicy.VERSION,
            integrity = IntegrityLabel.CLEAN,
            confidentiality = ConfidentialityLabel.SECRET,
        )
        fun draft(tools: List<String>) = validGenerative.copy(
            actions = listOf(
                Action.InvokeLlmV2(
                    goal = "rispondi considerando il voltaggio",
                    stateContext = listOf(context),
                    allowedTools = tools,
                    replyTargetSender = true,
                    timeoutMs = 60_000,
                ),
            ),
        )
        // reply + web.search valido anche sul contratto v2.
        assertEquals(
            emptyList(),
            errors(vWeb.validate(draft(listOf("whatsapp_reply", "web.search")), setOf("jid:42"))),
        )
        // web.search da solo → non valido (manca il reply).
        assertTrue(
            "allowed_tools_unsupported" in errors(vWeb.validate(draft(listOf("web.search")), setOf("jid:42"))),
        )
        // reply duplicato → non valido.
        assertTrue(
            "allowed_tools_unsupported" in errors(
                vWeb.validate(draft(listOf("whatsapp_reply", "whatsapp_reply")), setOf("jid:42")),
            ),
        )
    }
    @Test fun `local notification sink is valid with title, empty or web tools, no reply target`() {
        fun draft(action: Action) = AutomationDraft(
            name = "notifica cambio",
            trigger = Trigger.Time(at = "2026-07-17T12:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        val bare = Action.InvokeLlm(
            goal = "genera il cambio euro dollaro",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
            notificationTitle = "Cambio EUR/USD",
        )
        // Nessun trigger notification richiesto: qualsiasi trigger va bene.
        assertEquals(emptyList(), errors(v.validate(draft(bare), emptySet())))
        // web.search opzionale + contextSources subset di {state}: valido (catalogo con web).
        val withWeb = bare.copy(allowedTools = listOf("web.search"), contextSources = listOf("state"))
        assertEquals(emptyList(), errors(vWeb.validate(draft(withWeb), emptySet())))
    }

    @Test fun `local notification sink rejects bad title, reply tool, reply target and notification context`() {
        fun draft(action: Action) = AutomationDraft(
            name = "notifica cambio",
            trigger = Trigger.Time(at = "2026-07-17T12:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        val base = Action.InvokeLlm(
            goal = "genera",
            contextSources = emptyList(),
            allowedTools = emptyList(),
            replyTargetSender = false,
            deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
            notificationTitle = "Titolo",
        )
        // Titolo mancante / blank.
        assertTrue("notification_title_invalid" in errors(v.validate(draft(base.copy(notificationTitle = null)), emptySet())))
        assertTrue("notification_title_invalid" in errors(v.validate(draft(base.copy(notificationTitle = "   ")), emptySet())))
        // Titolo con control char o oltre i 120 caratteri.
        assertTrue("notification_title_invalid" in errors(v.validate(draft(base.copy(notificationTitle = "ab")), emptySet())))
        assertTrue("notification_title_invalid" in errors(v.validate(draft(base.copy(notificationTitle = "x".repeat(121))), emptySet())))
        // whatsapp_reply nel sink notifica → toolset non supportato (la notifica È il sink).
        assertTrue("allowed_tools_unsupported" in errors(v.validate(draft(base.copy(allowedTools = listOf("whatsapp_reply"))), emptySet())))
        // replyTargetSender DEVE essere false.
        assertTrue("reply_target_forbidden" in errors(v.validate(draft(base.copy(replyTargetSender = true)), emptySet())))
        // contextSources con 'notification' non è supportato dal sink notifica.
        assertTrue("context_source_unsupported" in errors(v.validate(draft(base.copy(contextSources = listOf("notification"))), emptySet())))
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
    @Test fun `immediate trigger with valid actions has no trigger errors`() {
        val d = AutomationDraft(
            name = "sveglia adesso",
            trigger = Trigger.Immediate,
            actions = listOf(
                Action.SetVolume(VolumeStream.ALARM, level = 80),
                Action.SetAlarm(hour = 7, minute = 30, label = "Palestra"),
            ),
        )
        assertEquals(emptyList(), errors(v.validate(d, emptySet())))
    }
    @Test fun `time requires exactly one of cron and at`() {
        assertTrue("time_spec" in errors(v.validate(AutomationDraft("x",
            Trigger.Time(cron = "0 8 * * *", at = "2026-07-15T08:00", tz = "Europe/Rome"), listOf(Action.SetWifi(true))), emptySet())))
        assertTrue("time_spec" in errors(v.validate(AutomationDraft("x",
            Trigger.Time(tz = "Europe/Rome"), listOf(Action.SetWifi(true))), emptySet())))
    }
    @Test fun `time accepts a relative afterMs and enforces exactly one spec and its bounds`() {
        fun draft(trigger: Trigger.Time) =
            AutomationDraft("x", trigger, listOf(Action.SetWifi(true)))

        // afterMs valido = esattamente uno tra cron/at/afterMs.
        assertEquals(
            emptyList(),
            errors(v.validate(draft(Trigger.Time(afterMs = 120_000, tz = "Europe/Rome")), emptySet())),
        )
        // afterMs+at oppure afterMs+cron = due spec = time_spec.
        assertTrue("time_spec" in errors(v.validate(
            draft(Trigger.Time(at = "2026-07-15T08:00", afterMs = 120_000, tz = "Europe/Rome")), emptySet())))
        assertTrue("time_spec" in errors(v.validate(
            draft(Trigger.Time(cron = "0 8 * * *", afterMs = 120_000, tz = "Europe/Rome")), emptySet())))
        // Bound: 0, negativo e oltre il massimo (7g) = after_ms_invalid.
        assertTrue("after_ms_invalid" in errors(v.validate(
            draft(Trigger.Time(afterMs = 0, tz = "Europe/Rome")), emptySet())))
        assertTrue("after_ms_invalid" in errors(v.validate(
            draft(Trigger.Time(afterMs = -1, tz = "Europe/Rome")), emptySet())))
        assertTrue("after_ms_invalid" in errors(v.validate(
            draft(Trigger.Time(afterMs = 7L * 24 * 60 * 60 * 1_000 + 1, tz = "Europe/Rome")), emptySet())))
        // Estremi ammessi: 1s e 7g esatti.
        assertEquals(
            emptyList(),
            errors(v.validate(draft(Trigger.Time(afterMs = 1_000, tz = "Europe/Rome")), emptySet())),
        )
        assertEquals(
            emptyList(),
            errors(v.validate(draft(Trigger.Time(afterMs = 7L * 24 * 60 * 60 * 1_000, tz = "Europe/Rome")), emptySet())),
        )
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
        val incompatiblePolicy = AutomationDraft(
            "reader",
            Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            listOf(Action.ShowNotification("Argus", "reader")),
            conditions = Condition.StateCompare(
                StateQuery.SystemProperty("ro.build.version.sdk"),
                StateValueType.NUMBER,
                CmpOp.GT,
                "30",
                policyVersion = StateQueryPolicy.VERSION + 1,
            ),
        )
        assertTrue(
            "state_query_policy_incompatible" in errors(v.validate(incompatiblePolicy, emptySet())),
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

    @Test fun `set alarm accepts valid ranges and rejects out of range hour or minute`() {
        fun draft(action: Action) = AutomationDraft(
            name = "sveglia",
            trigger = Trigger.Time(at = "2026-07-17T07:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetAlarm(0, 0)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetAlarm(23, 59, "Palestra")), emptySet())))
        assertTrue("alarm_time_invalid" in errors(v.validate(draft(Action.SetAlarm(24, 0)), emptySet())))
        assertTrue("alarm_time_invalid" in errors(v.validate(draft(Action.SetAlarm(-1, 0)), emptySet())))
        assertTrue("alarm_time_invalid" in errors(v.validate(draft(Action.SetAlarm(7, 60)), emptySet())))
        assertTrue("alarm_label_invalid" in errors(v.validate(draft(Action.SetAlarm(7, 0, "x".repeat(5_000))), emptySet())))
    }

    @Test fun `set timer accepts valid seconds and rejects out of range`() {
        fun draft(action: Action) = AutomationDraft(
            name = "timer",
            trigger = Trigger.Time(at = "2026-07-17T07:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetTimer(1)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetTimer(86_400, "Pasta")), emptySet())))
        assertTrue("timer_seconds_invalid" in errors(v.validate(draft(Action.SetTimer(0)), emptySet())))
        assertTrue("timer_seconds_invalid" in errors(v.validate(draft(Action.SetTimer(86_401)), emptySet())))
        assertTrue("timer_label_invalid" in errors(v.validate(draft(Action.SetTimer(60, "x".repeat(5_000))), emptySet())))
    }

    @Test fun `set volume accepts a bounded level and rejects out of range`() {
        fun draft(action: Action) = AutomationDraft(
            name = "volume",
            trigger = Trigger.Time(at = "2026-07-17T07:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetVolume(VolumeStream.MEDIA, 0)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetVolume(VolumeStream.RING, 100)), emptySet())))
        assertTrue("volume_level_invalid" in errors(v.validate(draft(Action.SetVolume(VolumeStream.MEDIA, -1)), emptySet())))
        assertTrue("volume_level_invalid" in errors(v.validate(draft(Action.SetVolume(VolumeStream.MEDIA, 101)), emptySet())))
    }

    @Test fun `set flashlight and vibrate validate their bounds`() {
        fun draft(action: Action) = AutomationDraft(
            name = "base",
            trigger = Trigger.Time(at = "2026-07-17T07:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetFlashlight(on = true)), emptySet())))
        // set_dark_mode: NightMode è un enum chiuso, ogni valore valida senza errori.
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetDarkMode(NightMode.OFF)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetDarkMode(NightMode.ON)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetDarkMode(NightMode.AUTO)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.Vibrate(1)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.Vibrate(10_000)), emptySet())))
        assertTrue("vibrate_duration_invalid" in errors(v.validate(draft(Action.Vibrate(0)), emptySet())))
        assertTrue("vibrate_duration_invalid" in errors(v.validate(draft(Action.Vibrate(10_001)), emptySet())))
    }

    @Test fun `open settings screen requires a package only for app details`() {
        fun draft(action: Action) = AutomationDraft(
            name = "settings",
            trigger = Trigger.Time(at = "2026-07-17T07:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        assertEquals(emptyList(), errors(v.validate(draft(Action.OpenSettingsScreen(SettingsScreen.WIFI)), emptySet())))
        assertEquals(
            emptyList(),
            errors(v.validate(draft(Action.OpenSettingsScreen(SettingsScreen.APP_DETAILS, pkg = "com.example.app")), emptySet())),
        )
        assertTrue("settings_pkg_missing" in errors(v.validate(draft(Action.OpenSettingsScreen(SettingsScreen.APP_DETAILS)), emptySet())))
        assertTrue("settings_pkg_unexpected" in errors(v.validate(draft(Action.OpenSettingsScreen(SettingsScreen.WIFI, pkg = "com.example.app")), emptySet())))
    }

    @Test fun `write setting is parametric accepting any well-formed key and rejecting malformed key or value`() {
        fun draft(action: Action) = AutomationDraft(
            name = "impostazione",
            trigger = Trigger.Time(at = "2026-07-17T22:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        // Parametrica (D0): qualsiasi chiave ben formata passa, incluse quelle "di autorità".
        assertEquals(
            emptyList(),
            errors(v.validate(draft(Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "30000")), emptySet())),
        )
        assertEquals(
            emptyList(),
            errors(v.validate(draft(Action.WriteSetting(SettingNamespace.SECURE, "adb_enabled", "1")), emptySet())),
        )
        // Key malformata / value con control char sono errori tipizzati.
        assertTrue(
            "write_setting_key_invalid" in
                errors(v.validate(draft(Action.WriteSetting(SettingNamespace.SYSTEM, "bad key", "1")), emptySet())),
        )
        assertTrue(
            "write_setting_value_invalid" in
                errors(v.validate(draft(Action.WriteSetting(SettingNamespace.SYSTEM, "font_scale", "1.0\n")), emptySet())),
        )
        assertTrue(
            "write_setting_value_invalid" in
                errors(v.validate(draft(Action.WriteSetting(SettingNamespace.SYSTEM, "font_scale", "")), emptySet())),
        )
    }

    @Test fun `sensor triggers require bounded parameters and a real cooldown`() {
        fun draft(trigger: Trigger.Sensor, cooldownMs: Long) = AutomationDraft(
            "sensore",
            trigger,
            listOf(Action.ShowNotification("Argus", "evento")),
            cooldownMs = cooldownMs,
        )

        assertEquals(
            emptyList(),
            errors(
                v.validate(
                    draft(Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION), 60_000),
                    emptySet(),
                ),
            ),
        )
        assertTrue(
            "sensor_cooldown_invalid" in errors(
                v.validate(draft(Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION), 59_999), emptySet()),
            ),
        )
        assertTrue(
            "sensor_event_count_invalid" in errors(
                v.validate(
                    draft(Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION, minimumEventCount = 2), 60_000),
                    emptySet(),
                ),
            ),
        )
        assertTrue(
            "sensor_event_count_invalid" in errors(
                v.validate(
                    draft(
                        Trigger.Sensor(
                            SensorKind.STEP_COUNTER,
                            minimumEventCount = SensorTriggerPolicy.MAX_EVENT_COUNT + 1,
                        ),
                        60_000,
                    ),
                    emptySet(),
                ),
            ),
        )
        assertTrue(
            "sensor_sampling_unsupported" in errors(
                v.validate(
                    draft(
                        Trigger.Sensor(SensorKind.STEP_COUNTER, samplingPeriodUs = 1_000),
                        60_000,
                    ),
                    emptySet(),
                ),
            ),
        )
    }
}
