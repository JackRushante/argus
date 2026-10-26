package dev.argus.data.backup

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.NightMode
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VolumeStream

/** Una istanza di ogni tipo del dominio, con dati personali riconoscibili dove il tipo li prevede. */
internal object BackupFixtures {
    const val CONVERSATION = "wa::393331112222::c1a9"
    const val SENDER = "Moglie"
    const val NUMBER = "+393331112222"

    val geofence = Trigger.Geofence(
        lat = 45.4642, lng = 9.19, radiusM = 150.0, transition = Transition.ENTER,
    )
    val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome", precision = TimePrecision.EXACT)
    val notification = Trigger.Notification(
        pkg = "com.whatsapp",
        conversationId = CONVERSATION,
        sender = SENDER,
        isGroup = false,
        titleMatch = "ciao",
        textMatch = "cena",
    )
    val phoneState = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, number = NUMBER, textMatch = "codice")
    val connectivity = Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, match = "CasaMia")
    val sensor = Trigger.Sensor(SensorKind.STEP_COUNTER, minimumEventCount = 100)

    /** Tutti i sottotipi di [Trigger]. */
    val allTriggers: List<Trigger> = listOf(geofence, time, Trigger.Immediate, notification, phoneState, connectivity, sensor)

    val locationIn = Condition.LocationIn(lat = 45.4642, lng = 9.19, radiusM = 200.0)

    /** Tutti i sottotipi di [Condition] che non sono contenitori, più i contenitori. */
    val leafConditions: List<Condition> = listOf(
        Condition.TimeWindow("22:00", "07:00", "Europe/Rome"),
        Condition.StateEquals("wifi", CmpOp.EQ, "on"),
        Condition.StateCompare(StateQuery.Builtin("battery"), StateValueType.NUMBER, CmpOp.LT, "20"),
        Condition.AppInForeground("com.android.chrome"),
        locationIn,
        Condition.BooleanLiteral(true),
        Condition.VarCompare("n", CmpOp.GT, expected = "3"),
    )

    /** Tutte le azioni foglia: nessuna porta oggi un contatto o una posizione tipati. */
    val leafActions: List<Action> = listOf(
        Action.SetWifi(true),
        Action.SetBluetooth(false),
        Action.SetMobileData(true),
        Action.SetDnd(DndMode.PRIORITY),
        Action.SetRinger("silent"),
        Action.LaunchApp("com.android.chrome"),
        Action.OpenUrl("https://example.org"),
        Action.ShowNotification("Argus", "ciao"),
        Action.Tap(10, 20),
        Action.InputText("testo"),
        Action.WhatsAppReply("arrivo"),
        Action.RunShell("echo ciao"),
        Action.CopyToClipboard("(\\d+)"),
        Action.CopyText("testo"),
        Action.SetAlarm(7, 30, "sveglia"),
        Action.SetTimer(600, "uovo"),
        Action.SetVolume(VolumeStream.MEDIA, 50),
        Action.SetFlashlight(true),
        Action.SetDarkMode(NightMode.AUTO),
        Action.OpenSettingsScreen(SettingsScreen.WIFI),
        Action.Vibrate(200),
        Action.WriteSetting(SettingNamespace.SYSTEM, "screen_brightness", "100"),
        Action.Wait(500),
        Action.InvokeLlm(
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
        ),
        Action.InvokeLlmV2(
            goal = "stato",
            stateContext = listOf(
                ApprovedStateContext(
                    StateQuery.Builtin("battery"),
                    StateValueType.NUMBER,
                    policyVersion = 1,
                    integrity = IntegrityLabel.CLEAN,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
            ),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 30_000,
        ),
    )

    fun simple(name: String = "Wi-Fi alle 8") = AutomationDraft(
        name = name,
        trigger = time,
        actions = listOf(Action.SetWifi(true)),
    )

    /** Una regola P4 con variabili, condizioni annidate e control-flow che contiene una `location_in`. */
    fun rich() = AutomationDraft(
        name = "Regola ricca",
        trigger = notification,
        vars = listOf(
            VarBinding.Literal("saluto", "ciao", VarType.TEXT, ConfidentialityLabel.PUBLIC),
            VarBinding.RandomInt("n", max = 6),
        ),
        conditions = Condition.And(
            listOf(
                Condition.Not(locationIn),
                Condition.Or(listOf(Condition.StateEquals("wifi", CmpOp.EQ, "on"), locationIn)),
            ),
        ),
        actions = listOf(
            Action.If(
                condition = Condition.And(listOf(locationIn, Condition.VarCompare("n", CmpOp.GT, expected = "3"))),
                then = listOf(Action.ShowNotification("Argus", "alto")),
                orElse = listOf(
                    Action.While(
                        condition = Condition.Not(locationIn),
                        body = listOf(Action.Wait(1000)),
                        maxIterations = 2,
                    ),
                ),
            ),
        ),
        cooldownMs = 60_000,
        rationale = "",
    )

    val notificationReply = AutomationDraft(
        name = "Rispondi in chat",
        trigger = notification,
        actions = listOf(
            Action.InvokeLlm(
                goal = "rispondi",
                contextSources = listOf("notification"),
                allowedTools = listOf("whatsapp_reply"),
                replyTargetSender = true,
            ),
        ),
        cooldownMs = 30_000,
    )
}
