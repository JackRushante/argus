package dev.argus.ui.presentation
import dev.argus.engine.model.*
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.VarRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class RuleRenderMapperTest {
    // --- P4-E: le variabili approvate sono visibili in review (mai i valori runtime) ---
    private fun otpRule() = Automation(
        AutomationId("v1"), "OTP", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
        Trigger.Notification("com.whatsapp", conversationId = "jid:1", sender = "Bank", isGroup = false),
        listOf(Action.CopyToClipboard()),
        vars = listOf(
            VarBinding.TriggerPayload("otp", TriggerField.TEXT, confidentiality = ConfidentialityLabel.SECRET),
            VarBinding.Literal("soglia", "10", VarType.NUMBER, ConfidentialityLabel.PUBLIC),
        ),
    )
    @Test fun `P4 vars render with type, integrity, confidentiality and provenance (IT)`() {
        val r = RuleRenderMapper.map(otpRule(), language = RenderLanguage.IT)
        assertEquals(2, r.vars.size)
        assertEquals(VarRow("otp", "testo", "esterno", "segreto", "notifica"), r.vars[0])
        assertEquals(VarRow("soglia", "numero", "attendibile", "pubblico", "valore fisso"), r.vars[1])
    }
    @Test fun `P4 vars render english labels by default`() {
        val r = RuleRenderMapper.map(otpRule(), language = RenderLanguage.EN)
        assertEquals(VarRow("otp", "text", "external", "secret", "notification"), r.vars[0])
        assertEquals(VarRow("soglia", "number", "trusted", "public", "fixed value"), r.vars[1])
    }
    @Test fun `flat v1 rule renders no vars`() {
        val a = Automation(
            AutomationId("f1"), "flat", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Time(cron = "0 9 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        assertTrue(RuleRenderMapper.map(a, language = RenderLanguage.IT).vars.isEmpty())
    }
    // I test pinnano il rendering ITALIANO passando SEMPRE `RenderLanguage.IT` esplicito: senza,
    // passerebbero solo perché la macchina ha locale it-IT (dipendenza nascosta dal locale). I casi
    // EN espliciti in fondo verificano il default inglese sulle render principali.
    @Test fun `notification 1-1 generative rule renders trigger, condition, generative flag`() {
        val a = Automation(
            AutomationId("w1"), "Rispondi a Moglie", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", sender = "Moglie", isGroup = false),
            listOf(Action.InvokeLlm("rispondi nel tono X", listOf("notification"), listOf("whatsapp_reply"), true)),
            conditions = Condition.TimeWindow("18:00", "22:00", "Europe/Rome"),
        )
        val r: RuleRender = RuleRenderMapper.map(a, language = RenderLanguage.IT)
        assertEquals("notification", r.triggerIconKey)
        assertTrue(r.triggerLine.contains("WhatsApp") && r.triggerLine.contains("Moglie"))
        assertTrue(r.conditionLines.any { it.contains("18:00") && it.contains("22:00") })
        assertTrue(r.isGenerative)
        assertTrue(r.actions.single().isGenerative)
        assertTrue(r.privacyNote != null)
    }
    @Test fun `time DND rule renders cron humanized and deterministic action`() {
        val a = Automation(
            AutomationId("d1"), "DND notte", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val r = RuleRenderMapper.map(a, language = RenderLanguage.IT)
        assertEquals("time", r.triggerIconKey)
        assertTrue(r.triggerLine.contains("23:00"))
        assertTrue(!r.isGenerative && r.privacyNote == null)
        assertEquals(1, r.actions.size)
    }
    @Test fun `relative afterMs time renders a one-shot delay line in italian`() {
        fun line(afterMs: Long) = RuleRenderMapper.map(
            Automation(
                AutomationId("r1"), "tra due minuti", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
                Trigger.Time(afterMs = afterMs, tz = "Europe/Rome"),
                listOf(Action.ShowNotification("Argus", "promemoria")),
            ),
            language = RenderLanguage.IT,
        ).triggerLine
        assertEquals("Una volta, tra 2 minuti", line(120_000))
        assertEquals("Una volta, tra 1 minuto", line(60_000))
        assertEquals("Una volta, tra 1 ora", line(3_600_000))
        assertEquals("Una volta, tra 30 secondi", line(30_000))
        assertEquals("Una volta, tra 2 giorni", line(2L * 24 * 60 * 60 * 1_000))
        assertEquals("Una volta, tra 1 ora e 30 minuti", line(5_400_000))
    }

    @Test fun `immediate trigger renders the on-arm one-shot line`() {
        val a = Automation(
            AutomationId("i1"), "Sveglia adesso", CreatedBy.USER, AutomationStatus.PENDING_APPROVAL,
            Trigger.Immediate,
            listOf(Action.SetAlarm(hour = 7, minute = 30, label = "Palestra")),
        )
        val r = RuleRenderMapper.map(a, language = RenderLanguage.IT)
        assertEquals("immediate", r.triggerIconKey)
        assertEquals("Una volta, all'attivazione", r.triggerLine)
    }
    @Test fun `shell action is flagged and command preserved`() {
        val a = Automation(
            AutomationId("s1"), "Backup", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Time(cron = "0 3 * * *", tz = "Europe/Rome"),
            listOf(Action.RunShell("cp -r /sdcard/DCIM /sdcard/backup")),
        )
        val row = RuleRenderMapper.map(a, language = RenderLanguage.IT).actions.single()
        assertTrue(row.isShell)
        assertEquals("cp -r /sdcard/DCIM /sdcard/backup", row.shellCommand)
        assertEquals(false, row.requiresLiveConfirm)
    }

    /** Helper: automazione con un solo `action` e trigger notifica plausibile. */
    private fun withAction(action: Action) = Automation(
        AutomationId("x"), "n", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
        Trigger.Notification("com.whatsapp", conversationId = "jid:1", isGroup = false),
        listOf(action),
    )

    @Test fun `requiresLiveConfirm matches the always-confirm catalog per action`() {
        // Tap/InputText/WhatsAppReply = conferma live. InvokeLlm e il comando shell statico,
        // già approvato integralmente insieme alla regola, non la richiedono al fire-time.
        val table: List<Pair<Action, Boolean>> = listOf(
            Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true) to false,
            Action.RunShell("/system/bin/id >/dev/null") to false,
            Action.WhatsAppReply("ok") to true,
            Action.Tap(120, 340) to true,
            Action.InputText("ciao") to true,
        )
        table.forEach { (action, want) ->
            val row = RuleRenderMapper.map(withAction(action), language = RenderLanguage.IT).actions.single()
            assertEquals(want, row.requiresLiveConfirm, "requiresLiveConfirm per $action")
        }
    }

    @Test fun `local notification invoke_llm renders the notification title distinct from reply`() {
        val notif = Automation(
            AutomationId("n1"), "Cambio EUR/USD", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Time(at = "2026-07-17T12:00", tz = "Europe/Rome"),
            listOf(
                Action.InvokeLlm(
                    goal = "genera il cambio euro dollaro",
                    contextSources = emptyList(),
                    allowedTools = listOf("web.search"),
                    replyTargetSender = false,
                    deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
                    notificationTitle = "Cambio EUR/USD",
                ),
            ),
        )
        val row = RuleRenderMapper.map(notif, language = RenderLanguage.IT).actions.single()
        assertTrue(row.isGenerative)
        assertTrue(row.label.contains("notifica"), row.label)
        assertTrue(row.detail.orEmpty().contains("Cambio EUR/USD"), row.detail.orEmpty())

        // Il ramo reply resta distinto (verbo "rispondi", nessun titolo di notifica).
        val reply = withAction(
            Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true),
        )
        val replyRow = RuleRenderMapper.map(reply, language = RenderLanguage.IT).actions.single()
        assertTrue(replyRow.label.contains("rispondi"), replyRow.label)
    }

    @Test fun `and-or-not condition tree flattens into indented lines`() {
        val a = Automation(
            AutomationId("c1"), "condizioni", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            listOf(Action.SetWifi(false)),
            conditions = Condition.And(
                listOf(
                    Condition.TimeWindow("08:00", "09:00", "Europe/Rome"),
                    Condition.Or(
                        listOf(
                            Condition.StateEquals("wifi", CmpOp.EQ, "on"),
                            Condition.Not(Condition.AppInForeground("com.whatsapp")),
                        ),
                    ),
                ),
            ),
        )
        val expected = listOf(
            "Tutte le seguenti:",
            "  Solo tra le 08:00 e le 09:00 (Europe/Rome)",
            "  Almeno una delle seguenti:",
            "    Solo se wifi = on",
            "    Non deve valere:",
            "      Solo se WhatsApp è in primo piano",
        )
        assertEquals(expected, RuleRenderMapper.map(a, language = RenderLanguage.IT).conditionLines)
    }

    @Test fun `parametric reader review renders exact family parameters type and operator`() {
        val automation = Automation(
            AutomationId("query"),
            "voltaggio",
            CreatedBy.USER,
            AutomationStatus.PENDING_APPROVAL,
            Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            listOf(Action.ShowNotification("Argus", "batteria")),
            conditions = Condition.StateCompare(
                StateQuery.DumpsysField("battery", "voltage"),
                StateValueType.NUMBER,
                CmpOp.GT,
                "4000",
            ),
        )

        assertEquals(
            listOf("Solo se dumpsys battery · campo voltage [numero] > 4000"),
            RuleRenderMapper.map(automation, language = RenderLanguage.IT).conditionLines,
        )
    }

    @Test fun `generative v2 review exposes exact reader classification and state disclosure`() {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val render = RuleRenderMapper.map(
            withAction(
                Action.InvokeLlmV2(
                    goal = "rispondi considerando il voltaggio",
                    stateContext = listOf(
                        ApprovedStateContext(
                            query = query,
                            valueType = StateValueType.NUMBER,
                            policyVersion = StateQueryPolicy.VERSION,
                            integrity = IntegrityLabel.CLEAN,
                            confidentiality = ConfidentialityLabel.SECRET,
                        ),
                    ),
                    allowedTools = listOf("whatsapp_reply"),
                    replyTargetSender = true,
                    timeoutMs = 60_000,
                ),
            ),
            language = RenderLanguage.IT,
        )

        assertTrue(render.privacyNote.orEmpty().contains("reader di stato"))
        assertTrue(render.actions.single().detail.orEmpty().contains("dumpsys battery · campo voltage"))
        assertTrue(render.actions.single().detail.orEmpty().contains("CLEAN, SECRET"))
    }

    @Test fun `nested generative v2 receives the state disclosure privacy note`() {
        val nested = Action.If(
            condition = Condition.BooleanLiteral(true),
            then = listOf(
                Action.InvokeLlmV2(
                    goal = "use battery state",
                    stateContext = listOf(
                        ApprovedStateContext(
                            query = StateQuery.Builtin("battery.level"),
                            valueType = StateValueType.NUMBER,
                            policyVersion = StateQueryPolicy.VERSION,
                            integrity = IntegrityLabel.CLEAN,
                            confidentiality = ConfidentialityLabel.PRIVATE,
                        ),
                    ),
                    allowedTools = emptyList(),
                    replyTargetSender = false,
                    timeoutMs = 60_000,
                ),
            ),
        )

        val render = RuleRenderMapper.map(withAction(nested), language = RenderLanguage.EN)

        assertTrue(render.isGenerative)
        assertEquals(
            "The notification text and values from the listed state readers will be sent to the " +
                "configured AI service to generate the reply.",
            render.privacyNote,
        )
    }

    @Test fun `non-humanizable cron falls back to the raw expression`() {
        val a = Automation(
            AutomationId("t1"), "cron strano", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Time(cron = "*/15 9-17 * * 1-5", tz = "Europe/Rome"),
            listOf(Action.SetWifi(false)),
        )
        val r = RuleRenderMapper.map(a, language = RenderLanguage.IT)
        assertEquals("time", r.triggerIconKey)
        assertTrue(r.triggerLine.startsWith("Cron '"), r.triggerLine)
        assertTrue(r.triggerLine.contains("*/15 9-17 * * 1-5"), r.triggerLine)
    }

    @Test fun `copy to clipboard renders the extraction regex integrally`() {
        val a = Automation(
            AutomationId("otp"), "OTP autocopy", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.PhoneState(PhoneEvent.SMS_RECEIVED),
            listOf(Action.CopyToClipboard(extractionRegex = "(\\d{4,8})")),
        )
        val row = RuleRenderMapper.map(a, language = RenderLanguage.IT).actions.single()
        assertEquals("Copia negli appunti", row.label)
        assertEquals("estrazione: (\\d{4,8})", row.detail)

        val whole = RuleRenderMapper.map(
            a.copy(actions = listOf(Action.CopyToClipboard(extractionRegex = null))),
            language = RenderLanguage.IT,
        ).actions.single()
        assertEquals("il testo integrale del messaggio", whole.detail)
    }

    @Test fun `sms trigger renders its text filter integrally`() {
        val a = Automation(
            AutomationId("s2"), "SMS prova", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, textMatch = "prova argus"),
            listOf(Action.ShowNotification("Argus", "SMS ricevuto!")),
        )
        assertEquals(
            "Quando: SMS ricevuto da chiunque · testo \"prova argus\"",
            RuleRenderMapper.map(a, language = RenderLanguage.IT).triggerLine,
        )
    }

    @Test fun `whitelisted conversation renders trusted display name instead of hash`() {
        val hash = "shortcut:com.whatsapp:62be4c2af7a1d9e30b5c46ab12cd34ef56ab78cd90ef12ab34cd56ef78ab90cd"
        val a = Automation(
            AutomationId("w2"), "Rispondi a Ottica Marci", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Notification("com.whatsapp", conversationId = hash, isGroup = false),
            listOf(Action.InvokeLlm("rispondi che sono occupato", listOf("notification"), listOf("whatsapp_reply"), true)),
        )
        val labels = mapOf(hash to "Ottica Marci")

        // Lookup deterministico dallo store fidato: nome leggibile + provenienza dell'identità.
        assertEquals(
            "Quando: notifica WhatsApp da Ottica Marci (identità verificata, chat 1:1)",
            RuleRenderMapper.map(a, labels, RenderLanguage.IT).triggerLine,
        )

        // La label fidata vince anche sul sender proposto dall'LLM: il TriggerMatcher ignora il
        // sender quando c'è il conversationId, quindi il nome whitelistato È il criterio reale.
        val withSender = a.copy(
            trigger = Trigger.Notification(
                "com.whatsapp", conversationId = hash, sender = "ottica", isGroup = false,
            ),
        )
        assertTrue(RuleRenderMapper.map(withSender, labels, RenderLanguage.IT).triggerLine.contains("Ottica Marci (identità verificata"))

        // Senza label fidata l'hash resta visibile INTEGRALE: review onesta, mai abbreviata.
        assertTrue(RuleRenderMapper.map(a, language = RenderLanguage.IT).triggerLine.contains(hash))
    }

    @Test fun `draft review resolves trusted labels the same way`() {
        val hash = "shortcut:com.whatsapp:aabbccdd"
        val d = AutomationDraft(
            "Rispondi", Trigger.Notification("com.whatsapp", conversationId = hash, isGroup = false),
            listOf(Action.WhatsAppReply("ok")),
        )
        val line = RuleRenderMapper.mapDraft(d, mapOf(hash to "Moglie"), RenderLanguage.IT).triggerLine
        assertEquals("Quando: notifica WhatsApp da Moglie (identità verificata, chat 1:1)", line)
    }

    @Test fun `geofence enter on current location renders resolved-location line`() {
        val a = Automation(
            AutomationId("g1"), "arrivo casa", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Geofence(radiusM = 75.0, transition = Transition.ENTER, resolveCurrentLocation = true),
            listOf(Action.SetWifi(true)),
        )
        val r = RuleRenderMapper.map(a, language = RenderLanguage.IT)
        assertEquals("geofence", r.triggerIconKey)
        assertEquals("Quando entri nella posizione attuale (±75 m)", r.triggerLine)
    }

    /**
     * Trovato da Lorenzo sul device: la review mostrava `15.266659599999999`, cioè la
     * rappresentazione binaria del Double sbattuta in faccia all'utente. Su un raggio di ±200 m
     * i decimali oltre il quinto (~1 m) non aggiungono informazione, solo rumore.
     */
    @Test fun `geofence coordinates are rounded to a readable precision`() {
        val a = Automation(
            AutomationId("g2"), "arrivo casa", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Geofence(
                lat = 37.0978322,
                lng = 15.266659599999999,
                radiusM = 200.0,
                transition = Transition.ENTER,
            ),
            listOf(Action.SetWifi(true)),
        )
        assertEquals(
            "Quando entri in 37.09783,15.26666 (±200 m)",
            RuleRenderMapper.map(a, language = RenderLanguage.IT).triggerLine,
        )
    }

    @Test fun `rounded geofence coordinates never expose negative zero`() {
        val a = Automation(
            AutomationId("g3"), "equatore", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Geofence(
                lat = -0.000001,
                lng = -0.000001,
                radiusM = 100.0,
                transition = Transition.ENTER,
            ),
            listOf(Action.ShowNotification("Argus", "test")),
        )
        assertEquals(
            "Quando entri in 0,0 (±100 m)",
            RuleRenderMapper.map(a, language = RenderLanguage.IT).triggerLine,
        )
    }

    @Test fun `sensor review names the exact kind and bounded step count`() {
        val automation = Automation(
            AutomationId("s1"),
            "passi",
            CreatedBy.LLM,
            AutomationStatus.PENDING_APPROVAL,
            Trigger.Sensor(SensorKind.STEP_COUNTER, minimumEventCount = 250),
            listOf(Action.ShowNotification("Argus", "camminata")),
            cooldownMs = 60_000,
        )

        val render = RuleRenderMapper.map(automation, language = RenderLanguage.IT)
        assertEquals("sensor", render.triggerIconKey)
        assertEquals("Quando: il contatore aumenta di 250 passi", render.triggerLine)
    }

    // =============================================================================
    // Rendering INGLESE (default dell'app): stessi tipi, `RenderLanguage.EN` esplicito.
    // Verificano che il default non-italiano produca il testo di sistema atteso.
    // =============================================================================

    @Test fun `EN time cron humanizes to english every-day line`() {
        val a = Automation(
            AutomationId("d1en"), "DND night", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val r = RuleRenderMapper.map(a, language = RenderLanguage.EN)
        assertEquals("time", r.triggerIconKey)
        assertEquals("Every day at 23:00 (Europe/Rome)", r.triggerLine)
    }

    @Test fun `EN sms trigger renders the english text filter integrally`() {
        val a = Automation(
            AutomationId("s2en"), "SMS test", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, textMatch = "test argus"),
            listOf(Action.ShowNotification("Argus", "SMS received!")),
        )
        assertEquals(
            "When: SMS received from anyone · text \"test argus\"",
            RuleRenderMapper.map(a, language = RenderLanguage.EN).triggerLine,
        )
    }

    @Test fun `EN notification generative rule renders english trigger, action and privacy note`() {
        val a = Automation(
            AutomationId("w1en"), "Reply to Wife", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", sender = "Wife", isGroup = false),
            listOf(Action.InvokeLlm("reply in tone X", listOf("notification"), listOf("whatsapp_reply"), true)),
        )
        val r = RuleRenderMapper.map(a, language = RenderLanguage.EN)
        assertEquals("When: WhatsApp notification from Wife (1:1 chat)", r.triggerLine)
        assertEquals("Generate and reply with AI", r.actions.single().label)
        assertEquals(
            "The notification text will be sent to the configured AI service to generate the reply.",
            r.privacyNote,
        )
    }

    @Test fun `EN relative afterMs time renders english one-shot delay line`() {
        fun line(afterMs: Long) = RuleRenderMapper.map(
            Automation(
                AutomationId("r1en"), "in two minutes", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
                Trigger.Time(afterMs = afterMs, tz = "Europe/Rome"),
                listOf(Action.ShowNotification("Argus", "reminder")),
            ),
            language = RenderLanguage.EN,
        ).triggerLine
        assertEquals("Once, in 2 minutes", line(120_000))
        assertEquals("Once, in 1 minute", line(60_000))
        assertEquals("Once, in 1 hour and 30 minutes", line(5_400_000))
    }

    @Test fun `EN immediate trigger renders the english on-arm one-shot line`() {
        val a = Automation(
            AutomationId("i1en"), "Alarm now", CreatedBy.USER, AutomationStatus.PENDING_APPROVAL,
            Trigger.Immediate,
            listOf(Action.SetAlarm(hour = 7, minute = 30, label = "Gym")),
        )
        assertEquals("Once, on activation", RuleRenderMapper.map(a, language = RenderLanguage.EN).triggerLine)
    }
}
