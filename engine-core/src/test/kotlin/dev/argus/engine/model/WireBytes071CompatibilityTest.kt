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
            v1-time 59673636c1fb836a19f51fb42b05c2c296bdd240c20ec06ca3f24b2f8d56cdcb 087783867dc8f47d77b726a038a30aa5b70f56ee7213c54c07aca0bf23480eef bfb074706c51d70975894c379a00df4636bc5b0729d074fe418c4f4aa8fb858c
            v1-geofence a6a4cd3d49274bd32cfd99436a254b7b0104b6ac96d490809ee3918f4c7291ff 97065a3d9415fd4ed97b492759877b677ed00d6532da8209f7e389b8740a5e54 d386d32322bad46989add0786f9c967e1c592f4cb84578ed8d9b2634e0d5f720
            v1-notification f5631a97d073e6e4e9b7a7911f8fb4fcd50484e7b3b187000a46ab30b871ac62 0889c49d3f2594718a9b9007a558caf167e7a6c80350d03919a564f00960a666 be12b5a12129996a258d14f5e8bcdcef21ab2d7dfc7ac7df697a9bfa33a90910
            v1-phone 84fcd804ca7771602532968826f5809ab46bb3249b9077af63352bffff580118 c3338bce2697238a73373a857393d42379fdaeeda737fa9edb6df4cae576ca4c 63ea2dc5dbaf1fadea1de24512ed890a6436135e480f2e57bd49ba05ca907179
            v1-connectivity 007cd2ffe4074aaa2ffa7a464720874576a5771ac1e49108170bc9bcedb413bb 3b66ec26f88b03d713068ee75f10f01c1babd8247ff41cf639cc590b8e527061 86e3a67bbda757d2890c6675ca3a4e6199919dd34e641852d27380f3a113f664
            p4-program c4cf3c69af37103ddfcf25ffe20a8c5c1811b535951cc988febeba561e010433 10964c7b7e88acbb4ba6ef50a38fcb46fe04c038d9ca90d29ce8e5ea4a9d77a6 4be27acc1eaed41cd0ead7c2cb1c7037e6b383f831ce4921bb4801d8546d5587
            actions-041 aaeaa3de44c8db20f661c487fcb76f9f2ba0a6381da6e94aab1a039bf6ca831a 4bfcf5546934358c10b51addd8c8f44d2d6472cd13253fc4d15e1768169f87f9 6537e3b006c7001f23923c06c58ebef9cc245d8a37652572de9599a6e4e6154f
            actions-051 d82fe20c3afa075a6074c91fed97d9b7da6caea09596ac0ccc30038eaed61f26 0d7949151393ffc3bb7f5bcc33d4e256afe0ca789a530933509c74f215decf98 4b55bd297b5f877c8913733ec4924c9e86b15bf674bd4b774110ed7492949681
            invoke-llm-v2 7251cf4ff61ab084d1b9ccd4800fb61c52ccb53b4cb59ef3916e209682b16155 beb2b4af98c6e7c4e2b67e2923eff8bc981fd60a5ae76671b3796787c92b988e aef6948667ea8a2288f4a84722287d57c4129518020387fcc9852a5b9ec87084
            trigger-time-after 969532469f855b0183739ac0656d4e1b6bdcc667e35fcc9bbb880d066de21e2d 66ee46739138777f3522f8611ff2a4ced4043b5e5c16f62cbe11754d02ad5025 01269e7ee4553bfd17463ea771e26f38e6cdafd861962711bede19be6a041779
            trigger-time-at aa54f612fb9d193646fc2ac2c9f520e9147a62a65906f8f55500646b3d5c5857 efb5ad7c6a7adf7db66c6a1a4ed6d2e8c6809161de7c496bafed9e91a53c5b09 8fbee6f7c86b802f358df617be82f8c12e9ef53e88e88296f5804000a5f742a3
            trigger-immediate 288df214575c2af575b8d60b4e5ea0adc7b775e0a631e8ee8c7d0c77833355fb 3816d467cb16e6561e14476579754b040a1b92854ac149d8aa472955920dfd91 cf46caf54f94b63d4aad4cf938a46204ae888c540db8ddb132b5217ebadc73f1
            trigger-manual 77e5f754bf3f27cb7dadbf2461c715583188cee572ace68192f61c3772dc712c 9fbb7391f76100103fef254e4d47f60426ea3604f94a989f35b9620f0737c137 f8910d432a3dc3a6990ee86b8569c986b0a6eb70bdfd6b756ebd2d4f628d0e76
            trigger-geofence-here eaeea4d0553f2d5a4d255b6533a10330888d297ee4ede464485cb3a22c301816 1e32388aea59dfc2aca17c0c1b21e1ff312904ea2d931e702fc45eb89f1cb649 c1c663b876d7559b28956fe2c6507d34d643f1c2c385af04adc25ef9885a07a6
            trigger-phone-sim 2f6c4cf4f4ee23e4e34c752a188acabd1e6133f61293c69c88fa78aff721afad 31955d27d761379f1a844beddb906f8b3b1cb7aa0cf65e75daeed63f82116998 48dc80402b1175845401fea498ebfc621a4982c3c39fb1d016e4eabf55a754bd
            trigger-sensor a66d5720d0c405df426f83d66a291fcb7bd29310a8a8f32c418dac6e44195863 422ef5629b7fccfc95673042bcaa32fbbf9a96a642ca50ba7b60ec8783c4f14d cd6e3f7663fe71496dd62ae8d7d123902faedb26f951aad9b84bc60bfdc574ff
            trigger-screen 1418684ad999e814d2980b824080585ac9c2c6d0a23b5eb1ee8429dfbd257efd c311bdd109833af0e08668bcdedc7239045871c9e73708e4c222aa2527b82112 d8f52562599813c52d707a61df904a8498c69f52b27d3525c616286d446177a1
            trigger-battery 434815a2807f35b47652aff1f9e8aa7f78d10f1eca9ce5a3252f269be5c7648e b66613ce9f85b91c8cacdfa3f4c4754a594aa519dc3d5f19c7812f920eadbf88 284adadbdc60389d0528ecb2ad61e90b75fb4c0330bc52617b412f281c1b24ac
            trigger-audio e6e43ebe72b2f45dbb40d22131859903ab69b8e5f11f651888332224e575b2f8 ad4bf2440101168d31ebcc316ae5d1d9336297ee74e3c791e60225d0c0e0bba2 b28225bbab629ee0956d1eaa5fa0f2d5cf0d2b444f057e8fc532c8fd795bd45e
            trigger-mode 8f1ca034804ab2482b61a3cc2b41880b97fd328b49a267f4c662ad0d2c0c07e9 2de2c4cdc923ebf3358f155224181d6dfe78146fd0762f1df791d7179b6b4335 0ed270d643045f7ce9df46271a324c1a5f6d183eca66f859256ca39a2e57a70e
            trigger-media 6288b6dd753f97b117d0c836f1dc2cfeb25f9a77519a29be2e4b81da67016410 3393e06aee233127e3ebbc722789d5ae4d38e49c6c87dd47d1e40ceb77b4e6c2 7f419fa19c0b67b7e8a48d0a6a508a1ae43979c4126ea40f976215076e5a6ffe
        """
    }
}
