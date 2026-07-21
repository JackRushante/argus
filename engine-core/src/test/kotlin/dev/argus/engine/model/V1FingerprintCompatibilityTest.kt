package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

class V1FingerprintCompatibilityTest {
    @Test
    fun `v1 approval fingerprints remain byte-for-byte stable`() {
        val fixtures = linkedMapOf(
            "time-all-conditions-actions" to automation(
                id = "v1-time",
                trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
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
                    Action.InvokeLlm(
                        "rispondi",
                        listOf("notification"),
                        listOf("whatsapp_reply"),
                        true,
                    ),
                ),
            ),
            "geofence" to automation(
                "v1-geofence",
                Trigger.Geofence(45.0, 9.0, 150.0, Transition.EXIT),
            ),
            "notification" to automation(
                "v1-notification",
                Trigger.Notification("com.whatsapp", "conversation:fixture", isGroup = false),
            ),
            "phone-state" to automation(
                "v1-phone",
                Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, textMatch = "codice"),
            ),
            "connectivity" to automation(
                "v1-connectivity",
                Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, "fixture"),
            ),
        ).mapValues { ApprovalFingerprints.of(it.value).value }

        assertEquals(
            mapOf(
                "time-all-conditions-actions" to
                    "bfb074706c51d70975894c379a00df4636bc5b0729d074fe418c4f4aa8fb858c",
                "geofence" to
                    "d386d32322bad46989add0786f9c967e1c592f4cb84578ed8d9b2634e0d5f720",
                "notification" to
                    "be12b5a12129996a258d14f5e8bcdcef21ab2d7dfc7ac7df697a9bfa33a90910",
                "phone-state" to
                    "63ea2dc5dbaf1fadea1de24512ed890a6436135e480f2e57bd49ba05ca907179",
                "connectivity" to
                    "86e3a67bbda757d2890c6675ca3a4e6199919dd34e641852d27380f3a113f664",
            ),
            fixtures,
        )
    }

    /**
     * ADDITIVO (P4 §2.6): una regola con variabili, control-flow (if/while), VarCompare, captureAs
     * e interpolazione ${…}, con un hash PROPRIO calcolato e PINNATO. Non tocca gli hash v1 sopra:
     * quelle regole non usano le feature P4 e serializzano byte-identiche (verificato dal test sopra).
     * Se questo hash cambia, la serializzazione del modello P4 è cambiata e va ri-approvata a monte.
     */
    @Test
    fun `p4 rule fingerprint is pinned`() {
        val p4 = Automation(
            id = AutomationId("v1-p4"),
            name = "v1-p4",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Notification("com.whatsapp", "conversation:fixture", isGroup = false),
            actions = listOf(
                Action.If(
                    condition = Condition.VarCompare("cmd", CmpOp.CONTAINS, "urgente"),
                    then = listOf(
                        Action.RunShell("id", captureAs = "uid"),
                        Action.While(
                            condition = Condition.VarCompare("flag", CmpOp.EQ, "true"),
                            body = listOf(Action.SetFlashlight(true), Action.SetFlashlight(false)),
                            maxIterations = 20,
                            delayBetweenMs = 500,
                        ),
                    ),
                    orElse = listOf(Action.ShowNotification("stato", "Da: \${sender}")),
                ),
                Action.InvokeLlm(
                    "riassumi",
                    listOf("notification"),
                    listOf("whatsapp_reply"),
                    true,
                    captureAs = "answer",
                ),
            ),
            vars = listOf(
                VarBinding.TriggerPayload(
                    "cmd",
                    TriggerField.TEXT,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
                VarBinding.TriggerPayload(
                    "sender",
                    TriggerField.SENDER,
                    confidentiality = ConfidentialityLabel.PRIVATE,
                ),
                VarBinding.Literal(
                    "flag",
                    "true",
                    VarType.BOOLEAN,
                    ConfidentialityLabel.PUBLIC,
                ),
            ),
            enabled = false,
            priority = 3,
            cooldownMs = 12_345,
            schemaVersion = AUTOMATION_SCHEMA_VERSION_P4,
        )
        assertEquals(
            "f50bc0201fca1bef5feaa2e2dad3677b2e204ee8a6d696261d2d74dc42b6fa40",
            ApprovalFingerprints.of(p4).value,
        )
    }

    private fun automation(
        id: String,
        trigger: Trigger,
        conditions: Condition? = null,
        actions: List<Action> = listOf(Action.ShowNotification("fixture", "fixture")),
    ) = Automation(
        id = AutomationId(id),
        name = id,
        createdBy = CreatedBy.USER,
        status = AutomationStatus.PENDING_APPROVAL,
        trigger = trigger,
        actions = actions,
        conditions = conditions,
        enabled = false,
        priority = 3,
        cooldownMs = 12_345,
    )
}
