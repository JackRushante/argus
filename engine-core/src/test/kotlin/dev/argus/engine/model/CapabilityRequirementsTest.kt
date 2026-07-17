package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityRequirementsTest {
    @Test
    fun `sensor capability is kind-specific`() {
        assertEquals(
            setOf(CapabilityIds.triggerSensor(SensorKind.SIGNIFICANT_MOTION)),
            CapabilityRequirements.derive(
                trigger = Trigger.Sensor(SensorKind.SIGNIFICANT_MOTION),
                actions = emptyList(),
            ),
        )
    }

    @Test
    fun `geofence trigger is OS managed and does not require Shizuku state location`() {
        assertEquals(
            setOf(CapabilityIds.TRIGGER_GEOFENCE, CapabilityIds.ACTION_SET_WIFI),
            CapabilityRequirements.derive(
                trigger = Trigger.Geofence(
                    lat = 45.0,
                    lng = 9.0,
                    radiusM = 150.0,
                    transition = Transition.EXIT,
                ),
                actions = listOf(Action.SetWifi(false)),
            ),
        )
    }

    @Test
    fun `requirements include trigger condition and action capabilities`() {
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_TIME,
                CapabilityIds.STATE_LOCATION,
                CapabilityIds.ACTION_SET_DND,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
                conditions = Condition.LocationIn(45.46, 9.19, 100.0),
                actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
            ),
        )
    }

    @Test
    fun `generative reply rule derives trigger llm and raw tool capabilities`() {
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_NOTIFICATION,
                CapabilityIds.ACTION_INVOKE_LLM,
                GenerativeContract.TOOL_WHATSAPP_REPLY,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Notification(
                    "com.whatsapp",
                    conversationId = "jid:42",
                    isGroup = false,
                ),
                actions = listOf(
                    Action.InvokeLlm(
                        goal = "rispondi",
                        contextSources = listOf("notification"),
                        allowedTools = listOf("whatsapp_reply"),
                        replyTargetSender = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `state context source adds the state read requirement exactly once`() {
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_NOTIFICATION,
                CapabilityIds.ACTION_INVOKE_LLM,
                GenerativeContract.TOOL_WHATSAPP_REPLY,
                GenerativeContract.TOOL_STATE_READ,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Notification(
                    "com.whatsapp",
                    conversationId = "jid:42",
                    isGroup = false,
                ),
                actions = listOf(
                    Action.InvokeLlm(
                        goal = "rispondi",
                        contextSources = listOf("notification", "state"),
                        allowedTools = listOf("whatsapp_reply"),
                        replyTargetSender = true,
                    ),
                    Action.InvokeLlm(
                        goal = "riassumi",
                        contextSources = listOf("notification", "state"),
                        allowedTools = listOf("whatsapp_reply"),
                        replyTargetSender = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `notification only context does not require state read`() {
        assertEquals(
            emptySet<String>(),
            CapabilityRequirements.derive(
                trigger = Trigger.Notification("com.whatsapp"),
                actions = listOf(
                    Action.InvokeLlm(
                        goal = "rispondi",
                        contextSources = listOf("notification"),
                        allowedTools = listOf("whatsapp_reply"),
                        replyTargetSender = true,
                    ),
                ),
            ).filter { it == GenerativeContract.TOOL_STATE_READ }.toSet(),
        )
    }

    @Test
    fun `phone state derives granular capabilities per event kind`() {
        // I grant OS differiscono (RECEIVE_SMS vs READ_PHONE_STATE): la capability persistita
        // deve distinguere, così la probe pubblica solo ciò che il grant reale copre.
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_SMS),
            CapabilityRequirements.derive(
                trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_CALL),
            CapabilityRequirements.derive(
                trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "333"),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_CALL),
            CapabilityRequirements.derive(
                trigger = Trigger.PhoneState(PhoneEvent.CALL_ENDED),
                actions = emptyList(),
            ),
        )
    }

    @Test
    fun `copy to clipboard derives its own action capability`() {
        assertEquals(
            setOf(CapabilityIds.TRIGGER_PHONE_SMS, CapabilityIds.ACTION_COPY_TO_CLIPBOARD),
            CapabilityRequirements.derive(
                trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED),
                actions = listOf(Action.CopyToClipboard(extractionRegex = "(\\d{4,8})")),
            ),
        )
    }

    @Test
    fun `set alarm and set timer derive their own base action capabilities`() {
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME, CapabilityIds.ACTION_SET_ALARM),
            CapabilityRequirements.derive(
                trigger = Trigger.Time(at = "2026-07-17T07:00", tz = "Europe/Rome"),
                actions = listOf(Action.SetAlarm(hour = 7, minute = 0, label = "Palestra")),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME, CapabilityIds.ACTION_SET_TIMER),
            CapabilityRequirements.derive(
                trigger = Trigger.Time(at = "2026-07-17T07:00", tz = "Europe/Rome"),
                actions = listOf(Action.SetTimer(seconds = 600)),
            ),
        )
    }

    @Test
    fun `write setting derives only the family action capability not a per-key id`() {
        // Gate famiglia (come i reader): il binding per-chiave sta nel fingerprint, non qui.
        // Un canonicalId per-chiave brickierebbe la regola nel CapabilityReconciler.
        val caps = CapabilityRequirements.derive(
            trigger = Trigger.Time(cron = "0 22 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "30000")),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME, CapabilityIds.ACTION_WRITE_SETTING),
            caps,
        )
        assertTrue(caps.none { it.startsWith("action.write_setting.v1.") })
    }

    @Test
    fun `manager pack actions derive their own base action capabilities`() {
        fun caps(action: Action) = CapabilityRequirements.derive(
            trigger = Trigger.Time(at = "2026-07-17T07:00", tz = "Europe/Rome"),
            actions = listOf(action),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME, CapabilityIds.ACTION_SET_VOLUME),
            caps(Action.SetVolume(VolumeStream.MEDIA, level = 5)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME, CapabilityIds.ACTION_SET_FLASHLIGHT),
            caps(Action.SetFlashlight(on = true)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME, CapabilityIds.ACTION_OPEN_SETTINGS_SCREEN),
            caps(Action.OpenSettingsScreen(SettingsScreen.WIFI)),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME, CapabilityIds.ACTION_VIBRATE),
            caps(Action.Vibrate(durationMs = 200)),
        )
    }

    @Test
    fun `connectivity requirements are granular and wifi identity needs location`() {
        assertEquals(
            setOf(CapabilityIds.TRIGGER_CONNECTIVITY_POWER),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_CONNECTIVITY_BT),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED, "Auto"),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(CapabilityIds.TRIGGER_CONNECTIVITY_WIFI),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED),
                actions = emptyList(),
            ),
        )
        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_CONNECTIVITY_WIFI,
                CapabilityIds.TRIGGER_CONNECTIVITY_WIFI_IDENTITY,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"),
                actions = emptyList(),
            ),
        )
    }

    @Test
    fun `nested conditions retain every state dependency`() {
        val conditions = Condition.And(
            listOf(
                Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "normal"),
                Condition.Or(
                    listOf(
                        Condition.AppInForeground("com.example"),
                        Condition.Not(Condition.StateEquals(StateKeys.WIFI, CmpOp.EQ, "off")),
                    ),
                ),
            ),
        )

        assertEquals(
            setOf(
                CapabilityIds.TRIGGER_CONNECTIVITY_POWER,
                CapabilityIds.state(StateKeys.RINGER),
                CapabilityIds.STATE_FOREGROUND_APP,
                CapabilityIds.state(StateKeys.WIFI),
                CapabilityIds.ACTION_SHOW_NOTIFICATION,
            ),
            CapabilityRequirements.derive(
                trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
                conditions = conditions,
                actions = listOf(Action.ShowNotification("Argus", "test")),
            ),
        )
    }

    @Test
    fun `parametric readers require their family capability while parameters stay fingerprinted`() {
        val conditions = Condition.And(
            listOf(
                Condition.StateCompare(
                    StateQuery.Builtin(StateKeys.BATTERY),
                    StateValueType.NUMBER,
                    CmpOp.GT,
                    "20",
                ),
                Condition.StateCompare(
                    StateQuery.Setting(SettingNamespace.GLOBAL, "airplane_mode_on"),
                    StateValueType.BOOLEAN,
                    CmpOp.EQ,
                    "false",
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
                    CmpOp.GT,
                    "0",
                ),
            ),
        )

        val capabilities = CapabilityRequirements.derive(
            Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            listOf(Action.ShowNotification("Argus", "reader")),
            conditions,
        )

        assertTrue(CapabilityIds.STATE_READER_BUILTIN in capabilities)
        assertTrue(CapabilityIds.STATE_READER_SETTING in capabilities)
        assertTrue(CapabilityIds.STATE_READER_SYSTEM_PROPERTY in capabilities)
        assertTrue(CapabilityIds.STATE_READER_SYSFS in capabilities)
        assertTrue(CapabilityIds.STATE_READER_DUMPSYS_FIELD in capabilities)
    }
}
