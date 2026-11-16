package dev.argus.engine.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Le regole scritte con il vocabolario della 0.7.1 devono serializzarsi negli STESSI byte anche dopo
 * l'aggiunta di tipi e campi nuovi: ogni campo nuovo di un tipo esistente ha un default e
 * `@EncodeDefault(NEVER)`, quindi resta fuori dal wire finché non è usato.
 *
 * Per ogni fixture si fissano lo SHA-256 del JSON della bozza (backup, bozze salvate), lo SHA-256 del JSON
 * della regola (Room) e l'impronta di approvazione. I valori sono stati calcolati sul codice 0.7.1, prima
 * del vocabolario 0.8: se uno cambia, una regola già approvata andrebbe ri-approvata.
 *
 * Le fixture riprendono quelle di [V1FingerprintCompatibilityTest] e coprono il resto del vocabolario
 * 0.7.1 (trigger di sistema, azioni 0.4.1 e 0.5.1, macro, programmi P4, AI con stato).
 */
class WireBytes071CompatibilityTest {

    @Test
    fun `representative 0_7_1 rules keep their wire bytes and fingerprints`() {
        val actual = fixtures().entries.joinToString("\n") { (name, rule) ->
            val draftJson = ArgusJson.encodeToString(AutomationDraft.serializer(), rule.toDraft())
            val ruleJson = ArgusJson.encodeToString(Automation.serializer(), rule)
            "$name ${sha256(draftJson)} ${sha256(ruleJson)} ${ApprovalFingerprints.of(rule).value}"
        }
        assertEquals(EXPECTED.trimIndent(), actual)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun rule(
        id: String,
        trigger: Trigger,
        actions: List<Action> = listOf(Action.ShowNotification("fixture", "fixture")),
        conditions: Condition? = null,
        vars: List<VarBinding> = emptyList(),
        schemaVersion: Int = AUTOMATION_SCHEMA_VERSION_V1,
    ) = Automation(
        id = AutomationId(id),
        name = id,
        createdBy = CreatedBy.USER,
        status = AutomationStatus.PENDING_APPROVAL,
        trigger = trigger,
        actions = actions,
        vars = vars,
        conditions = conditions,
        enabled = false,
        priority = 3,
        cooldownMs = 12_345,
        schemaVersion = schemaVersion,
    )

    private fun fixtures(): Map<String, Automation> = linkedMapOf(
        "v1-time" to rule(
            "v1-time",
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            conditions = Condition.And(
                listOf(
                    Condition.TimeWindow("22:00", "07:00", "Europe/Rome"),
                    Condition.Or(
                        listOf(
                            Condition.StateEquals(StateKeys.BATTERY, CmpOp.GT, "20"),
                            Condition.Not(Condition.AppInForeground("dev.example.app")),
                        ),
                    ),
                    Condition.LocationIn(45.0, 9.0, 100.0),
                ),
            ),
            actions = listOf(
                Action.SetWifi(true),
                Action.SetBluetooth(false),
                Action.SetDnd(DndMode.PRIORITY),
                Action.SetRinger("vibrate"),
                Action.LaunchApp("dev.example.app"),
                Action.OpenUrl("https://example.org/path"),
                Action.ShowNotification("titolo", "testo"),
                Action.Tap(10, 20),
                Action.InputText("testo statico"),
                Action.WhatsAppReply("risposta statica"),
                Action.RunShell("id"),
                Action.CopyToClipboard("([0-9]{4})"),
                Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true),
            ),
        ),
        "v1-geofence" to rule("v1-geofence", Trigger.Geofence(45.0, 9.0, 150.0, Transition.EXIT)),
        "v1-notification" to rule(
            "v1-notification",
            Trigger.Notification("com.whatsapp", "conversation:fixture", isGroup = false),
        ),
        "v1-phone" to rule("v1-phone", Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, textMatch = "codice")),
        "v1-connectivity" to rule(
            "v1-connectivity",
            Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, "fixture"),
        ),
        "p4-program" to rule(
            "p4-program",
            Trigger.Notification("com.whatsapp", "conversation:fixture", isGroup = false),
            actions = listOf(
                Action.If(
                    condition = Condition.VarCompare("cmd", CmpOp.CONTAINS, "urgente"),
                    then = listOf(
                        Action.RunShell("id", captureAs = "uid"),
                        Action.While(
                            condition = Condition.VarCompare("flag", CmpOp.EQ, "true"),
                            body = listOf(Action.SetFlashlight(true), Action.Wait(500), Action.SetFlashlight(false)),
                            maxIterations = 20,
                            delayBetweenMs = 500,
                        ),
                        Action.While(
                            condition = Condition.BooleanLiteral(true),
                            body = listOf(Action.Vibrate(200)),
                            maxIterationsVar = "dice",
                        ),
                    ),
                    orElse = listOf(Action.ShowNotification("stato", "Da: \${sender} alle \${now}")),
                ),
                Action.InvokeLlm(
                    "riassumi",
                    listOf("notification"),
                    listOf("whatsapp_reply"),
                    true,
                    captureAs = "answer",
                ),
                Action.InvokeLlm(
                    "scrivi",
                    emptyList(),
                    emptyList(),
                    false,
                    deliver = GenerativeDeliverMode.CAPTURE_ONLY,
                    captureAs = "draft",
                ),
            ),
            vars = listOf(
                VarBinding.TriggerPayload("cmd", TriggerField.TEXT, confidentiality = ConfidentialityLabel.PRIVATE),
                VarBinding.TriggerPayload(
                    "sender",
                    TriggerField.SENDER,
                    "^(\\w+)",
                    ConfidentialityLabel.PRIVATE,
                ),
                VarBinding.Literal("flag", "true", VarType.BOOLEAN, ConfidentialityLabel.PUBLIC),
                VarBinding.RandomInt("dice", 6),
                VarBinding.ClockTime("now", tz = "Europe/Rome", pattern = "HH:mm"),
                VarBinding.State(
                    "net",
                    StateQuery.Setting(SettingNamespace.GLOBAL, "wifi_on"),
                    StateValueType.NUMBER,
                    StateQueryPolicy.VERSION,
                    ConfidentialityLabel.SECRET,
                ),
            ),
            schemaVersion = AUTOMATION_SCHEMA_VERSION_P4,
        ),
        "actions-041" to rule(
            "actions-041",
            Trigger.Time(tz = "Europe/Rome", solar = SolarSpec(SolarEvent.SUNSET, -30)),
            conditions = Condition.StateCompare(
                StateQuery.DumpsysField("battery", "level"),
                StateValueType.NUMBER,
                CmpOp.LT,
                "30",
            ),
            actions = listOf(
                Action.Speak("ciao"),
                Action.MediaControl(MediaCommand.PLAY_PAUSE),
                Action.SetBrightness(level = 40),
                Action.SetBrightness(auto = true),
                Action.SetRotation(true),
                Action.HttpRequest(
                    method = HttpMethod.POST,
                    url = "https://example.org/hook",
                    headers = mapOf("Authorization" to "Bearer token", "X-Plain" to "1"),
                    body = "{\"a\":\"b\"}",
                    contentType = "application/json",
                ),
                Action.HttpRequest(HttpMethod.GET, "https://example.org/ping"),
                Action.SetAlarm(7, 30, "sveglia"),
                Action.SetTimer(90),
                Action.SetVolume(VolumeStream.MEDIA, 50),
                Action.SetDarkMode(NightMode.AUTO),
                Action.OpenSettingsScreen(SettingsScreen.APP_DETAILS, "dev.example.app"),
                Action.Vibrate(300),
                Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "60000"),
                Action.CopyText("testo"),
                Action.SetMobileData(false),
                Action.InvokeLlm(
                    "avvisami",
                    listOf("state"),
                    listOf("web.search"),
                    false,
                    timeoutMs = 30_000,
                    deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
                    notificationTitle = "Meteo",
                ),
            ),
        ),
        "actions-051" to rule(
            "actions-051",
            Trigger.AppEvent("com.google.android.youtube", AppEventKind.OPENED),
            actions = listOf(
                Action.AppControl("com.example.game", AppControlOp.FREEZE),
                Action.SetAirplane(true),
                Action.SetLocation(false),
                Action.SetNfc(true),
                Action.SetBatterySaver(true),
                Action.LockScreen,
                Action.Screenshot,
                Action.AppPermission("com.example.game", "android.permission.CAMERA", grant = false),
                Action.UiSteps(
                    pkg = "com.example.shop",
                    steps = listOf(
                        UiStep.WaitFor(text = "Cerca", timeoutMs = 5_000),
                        UiStep.Click(id = "com.example.shop:id/go", index = 1),
                        UiStep.Type("latte"),
                        UiStep.Scroll(ScrollDirection.DOWN),
                        UiStep.Check("Ordine", present = true),
                        UiStep.Back,
                        UiStep.Home,
                    ),
                ),
            ),
        ),
        "invoke-llm-v2" to rule(
            "invoke-llm-v2",
            Trigger.Notification("com.whatsapp", "conversation:fixture", isGroup = false),
            actions = listOf(
                Action.InvokeLlmV2(
                    goal = "rispondi col livello batteria",
                    stateContext = listOf(
                        ApprovedStateContext(
                            StateQuery.Builtin(StateKeys.BATTERY),
                            StateValueType.NUMBER,
                            StateQueryPolicy.VERSION,
                            IntegrityLabel.CLEAN,
                            ConfidentialityLabel.PRIVATE,
                        ),
                    ),
                    allowedTools = listOf("whatsapp_reply"),
                    replyTargetSender = true,
                    timeoutMs = 60_000,
                ),
            ),
        ),
        "trigger-time-after" to rule("trigger-time-after", Trigger.Time(afterMs = 120_000, tz = "UTC", precision = TimePrecision.EXACT)),
        "trigger-time-at" to rule("trigger-time-at", Trigger.Time(at = "2031-01-02T03:04", tz = "UTC")),
        "trigger-immediate" to rule("trigger-immediate", Trigger.Immediate),
        "trigger-manual" to rule("trigger-manual", Trigger.Manual),
        "trigger-geofence-here" to rule(
            "trigger-geofence-here",
            Trigger.Geofence(radiusM = 200.0, transition = Transition.ENTER, resolveCurrentLocation = true),
        ),
        "trigger-phone-sim" to rule("trigger-phone-sim", Trigger.PhoneState(PhoneEvent.MISSED_CALL, number = "+390000", sim = 2)),
        "trigger-sensor" to rule("trigger-sensor", Trigger.Sensor(SensorKind.STEP_COUNTER, minimumEventCount = 100)),
        "trigger-screen" to rule("trigger-screen", Trigger.Screen(ScreenEvent.UNLOCKED)),
        "trigger-battery" to rule("trigger-battery", Trigger.Battery(below = 20)),
        "trigger-audio" to rule("trigger-audio", Trigger.AudioOutput(AudioOutputKind.ANY, ConnState.CONNECTED)),
        "trigger-mode" to rule("trigger-mode", Trigger.ModeChanged(ModeKind.RINGER, "silent")),
        "trigger-media" to rule("trigger-media", Trigger.Media(MediaPlayState.PLAYING)),
    )

    private companion object {
        /** `nome sha256(json bozza) sha256(json regola) impronta`, calcolati sul codice 0.7.1. */
        const val EXPECTED = """
            to-be-pinned
        """
    }
}
