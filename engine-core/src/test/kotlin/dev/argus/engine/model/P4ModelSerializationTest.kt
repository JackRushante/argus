package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

/** Copertura serializzazione + BYTE-COMPAT del modello P4-A. */
class P4ModelSerializationTest {

    // --- VarBinding ---------------------------------------------------------------------------

    @Test fun `var bindings round-trip with their wire discriminator`() {
        val bindings: List<VarBinding> = listOf(
            VarBinding.TriggerPayload(
                "sms_text",
                TriggerField.TEXT,
                extractionRegex = "([0-9]{4,8})",
                confidentiality = ConfidentialityLabel.PRIVATE,
            ),
            VarBinding.State(
                "batt",
                StateQuery.Builtin(StateKeys.BATTERY),
                StateValueType.NUMBER,
                StateQueryPolicy.VERSION,
                ConfidentialityLabel.PRIVATE,
            ),
            VarBinding.Literal("soglia", "20", VarType.NUMBER, ConfidentialityLabel.PUBLIC),
        )
        bindings.forEach { b ->
            val json = ArgusJson.encodeToString(VarBinding.serializer(), b)
            assertEquals(b, ArgusJson.decodeFromString(VarBinding.serializer(), json))
        }
        // Discriminatori wire attesi (coerenti con ArgusJson.classDiscriminator = "type").
        assertTrue(
            ArgusJson.encodeToString(VarBinding.serializer(), bindings[0]).contains("\"type\":\"trigger_payload\""),
        )
        assertTrue(
            ArgusJson.encodeToString(VarBinding.serializer(), bindings[1]).contains("\"type\":\"state\""),
        )
        assertTrue(
            ArgusJson.encodeToString(VarBinding.serializer(), bindings[2]).contains("\"type\":\"literal\""),
        )
    }

    @Test fun `random_int binding round-trips, is CLEAN NUMBER and defaults to PUBLIC`() {
        val binding: VarBinding = VarBinding.RandomInt("dice", max = 6)
        val json = ArgusJson.encodeToString(VarBinding.serializer(), binding)
        assertTrue(json.contains("\"type\":\"random_int\""), json)
        assertEquals(binding, ArgusJson.decodeFromString(VarBinding.serializer(), json))
        // Default di riservatezza = livello più basso (PUBLIC); non è un segreto.
        assertEquals(ConfidentialityLabel.PUBLIC, VarBinding.RandomInt("d", max = 2).confidentiality)
        // Integrità CLEAN (generato dal motore), tipo dichiarato NUMBER, provenienza ENGINE.
        assertEquals(IntegrityLabel.CLEAN, binding.integrity)
        assertEquals(VarType.NUMBER, binding.declaredType)
        assertEquals(
            setOf(ValueProvenance.ENGINE),
            binding.provenance(Trigger.Time(cron = "0 8 * * *", tz = "UTC")),
        )
        // Confidentiality esplicita override del default e round-trip.
        val secret: VarBinding = VarBinding.RandomInt("d", max = 3, ConfidentialityLabel.SECRET)
        assertEquals(
            secret,
            ArgusJson.decodeFromString(
                VarBinding.serializer(),
                ArgusJson.encodeToString(VarBinding.serializer(), secret),
            ),
        )
    }

    @Test fun `integrity confidentiality and provenance remain separate and monotone`() {
        // Enum senza @SerialName → UPPERCASE (ArgusJson è case-sensitive, come GenerativeDeliverMode).
        val literal = VarBinding.Literal("x", "1", VarType.NUMBER, ConfidentialityLabel.PUBLIC)
        assertTrue(ArgusJson.encodeToString(VarBinding.serializer(), literal).contains("\"varType\":\"NUMBER\""))

        val state = VarBinding.State(
            "b",
            StateQuery.Builtin(StateKeys.BATTERY),
            StateValueType.NUMBER,
            StateQueryPolicy.VERSION,
            ConfidentialityLabel.PRIVATE,
        )
        val external = VarBinding.TriggerPayload(
            "c",
            TriggerField.TEXT,
            confidentiality = ConfidentialityLabel.SECRET,
        )
        assertEquals(IntegrityLabel.CLEAN, literal.integrity)
        assertEquals(IntegrityLabel.CLEAN, state.integrity)
        assertEquals(IntegrityLabel.TAINTED, external.integrity)
        assertEquals(IntegrityLabel.TAINTED, joinIntegrity(state.integrity, external.integrity))
        assertEquals(
            ConfidentialityLabel.SECRET,
            joinConfidentiality(state.confidentiality, external.confidentiality),
        )
        val value = VarValue(
            text = "redacted",
            type = VarType.TEXT,
            integrity = IntegrityLabel.TAINTED,
            confidentiality = ConfidentialityLabel.SECRET,
            provenance = setOf(ValueProvenance.SMS, ValueProvenance.MODEL),
        )
        assertEquals(setOf(ValueProvenance.SMS, ValueProvenance.MODEL), value.provenance)
    }

    @Test fun `state binding carries its approved type instead of degrading to text`() {
        assertEquals(
            VarType.NUMBER,
            VarBinding.Literal("n", "1", VarType.NUMBER, ConfidentialityLabel.PUBLIC).declaredType,
        )
        assertEquals(
            VarType.NUMBER,
            VarBinding.State(
                "s",
                StateQuery.Builtin(StateKeys.BATTERY),
                StateValueType.NUMBER,
                StateQueryPolicy.VERSION,
                ConfidentialityLabel.PRIVATE,
            ).declaredType,
        )
        assertEquals(
            VarType.TEXT,
            VarBinding.TriggerPayload(
                "p",
                TriggerField.NUMBER,
                confidentiality = ConfidentialityLabel.PRIVATE,
            ).declaredType,
        )
    }

    // --- Control-flow: If / While / VarCompare ------------------------------------------------

    @Test fun `if action round-trips with its then and orElse branches`() {
        val action: Action = Action.If(
            condition = Condition.VarCompare("batt", CmpOp.LT, "20"),
            then = listOf(Action.SetFlashlight(true)),
            orElse = listOf(Action.SetFlashlight(false)),
        )
        val json = ArgusJson.encodeToString(Action.serializer(), action)
        assertTrue(json.contains("\"type\":\"if\""), json)
        assertTrue(json.contains("\"type\":\"var_compare\""), json)
        assertEquals(action, ArgusJson.decodeFromString(Action.serializer(), json))
    }

    @Test fun `while action round-trips and maxIterations is a required wire field`() {
        val action: Action = Action.While(
            condition = Condition.VarCompare("run", CmpOp.EQ, "true"),
            body = listOf(Action.SetFlashlight(true), Action.SetFlashlight(false)),
            maxIterations = 20,
            delayBetweenMs = 500,
        )
        val json = ArgusJson.encodeToString(Action.serializer(), action)
        assertTrue(json.contains("\"type\":\"while\""), json)
        assertEquals(action, ArgusJson.decodeFromString(Action.serializer(), json))
        // maxIterations OBBLIGATORIO: un while senza il campo non deserializza (come InvokeLlmV2.timeoutMs).
        assertFailsWith<SerializationException> {
            ArgusJson.decodeFromString(
                Action.serializer(),
                json.replace(",\"maxIterations\":20", ""),
            )
        }
    }

    @Test fun `wait action is explicit and round-trips`() {
        val action: Action = Action.Wait(durationMs = 500)
        val json = ArgusJson.encodeToString(Action.serializer(), action)

        assertEquals("{\"type\":\"wait\",\"durationMs\":500}", json)
        assertEquals(action, ArgusJson.decodeFromString(Action.serializer(), json))
        assertEquals(ActionPrivilege.BASE, ActionPrivileges.of(action))
        assertEquals(
            setOf(CapabilityIds.TRIGGER_TIME),
            CapabilityRequirements.derive(
                Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
                listOf(action),
            ),
        )
        assertEquals(
            AUTOMATION_SCHEMA_VERSION_P4,
            AutomationSchema.versionFor(
                AutomationDraft(
                    name = "delayed",
                    trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
                    actions = listOf(action),
                ),
            ),
        )
    }

    @Test fun `var compare serializes exactly one rhs and boolean literal round-trips`() {
        val literal: Condition = Condition.VarCompare("a", CmpOp.GT, expected = "0")
        val literalJson = ArgusJson.encodeToString(Condition.serializer(), literal)
        assertTrue(literalJson.contains("\"expected\":\"0\""), literalJson)
        assertFalse(literalJson.contains("expectedVar"), literalJson)
        assertEquals(literal, ArgusJson.decodeFromString(Condition.serializer(), literalJson))

        val variable: Condition = Condition.VarCompare("a", CmpOp.GT, expectedVar = "b")
        val variableJson = ArgusJson.encodeToString(Condition.serializer(), variable)
        assertTrue(variableJson.contains("\"expectedVar\":\"b\""), variableJson)
        assertFalse(variableJson.contains("\"expected\""), variableJson)
        assertEquals(variable, ArgusJson.decodeFromString(Condition.serializer(), variableJson))

        val always: Condition = Condition.BooleanLiteral(true)
        assertEquals(
            always,
            ArgusJson.decodeFromString(
                Condition.serializer(),
                ArgusJson.encodeToString(Condition.serializer(), always),
            ),
        )
    }

    // --- captureAs: @EncodeDefault(NEVER) sulle azioni catturabili ----------------------------

    @Test fun `run_shell without captureAs stays byte-identical and round-trips`() {
        val bare: Action = Action.RunShell("id")
        val json = ArgusJson.encodeToString(Action.serializer(), bare)
        // @EncodeDefault(NEVER): assente ⇒ nessun campo captureAs sul wire (byte v1 stabili).
        assertFalse(json.contains("captureAs"), json)
        assertEquals("{\"type\":\"run_shell\",\"cmd\":\"id\"}", json)
        assertEquals(bare, ArgusJson.decodeFromString(Action.serializer(), json))

        val capturing = Action.RunShell("id", captureAs = "uid")
        val json2 = ArgusJson.encodeToString(Action.serializer(), capturing)
        assertTrue(json2.contains("\"captureAs\":\"uid\""), json2)
        assertEquals(capturing, ArgusJson.decodeFromString(Action.serializer(), json2))
    }

    @Test fun `invoke_llm without captureAs stays byte-identical`() {
        val reply = Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true)
        val json = ArgusJson.encodeToString(Action.serializer(), reply)
        assertFalse(json.contains("captureAs"), json)
        assertFalse(json.contains("deliver"), json) // regressione: i campi #59 restano omessi
        assertEquals(reply, ArgusJson.decodeFromString(Action.serializer(), json))

        val capturing = reply.copy(captureAs = "answer")
        val json2 = ArgusJson.encodeToString(Action.serializer(), capturing)
        assertTrue(json2.contains("\"captureAs\":\"answer\""), json2)
        assertEquals(capturing, ArgusJson.decodeFromString(Action.serializer(), json2))
    }

    @Test fun `invoke_llm_v2 captureAs is additive and omitted by default`() {
        val action = Action.InvokeLlmV2(
            goal = "g",
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
        )
        val bareJson = ArgusJson.encodeToString(Action.serializer(), action)
        assertFalse(bareJson.contains("captureAs"), bareJson)
        val capturing = action.copy(captureAs = "answer")
        val capturingJson = ArgusJson.encodeToString(Action.serializer(), capturing)
        assertTrue(capturingJson.contains("\"captureAs\":\"answer\""), capturingJson)
        assertEquals(capturing, ArgusJson.decodeFromString(Action.serializer(), capturingJson))
    }

    // --- vars su Automation/Draft: byte-compat quando assenti ---------------------------------

    @Test fun `automation without vars serializes without the vars key`() {
        val a = Automation(
            id = AutomationId("a1"),
            name = "dnd",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val json = ArgusJson.encodeToString(Automation.serializer(), a)
        // @EncodeDefault(NEVER) + default emptyList ⇒ nessuna "vars" sul wire (fingerprint stabile).
        assertFalse(json.contains("\"vars\""), json)
        assertEquals(a, ArgusJson.decodeFromString(Automation.serializer(), json))
    }

    @Test fun `automation with vars round-trips and derives state reader capability`() {
        val a = Automation(
            id = AutomationId("p4"),
            name = "flash se batteria bassa",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Time(cron = "*/30 * * * *", tz = "Europe/Rome"),
            actions = listOf(
                Action.While(
                    condition = Condition.VarCompare("batt", CmpOp.LT, "20"),
                    body = listOf(Action.SetFlashlight(true), Action.SetFlashlight(false)),
                    maxIterations = 20,
                    delayBetweenMs = 500,
                ),
            ),
            vars = listOf(
                VarBinding.State(
                    "batt",
                    StateQuery.Builtin(StateKeys.BATTERY),
                    StateValueType.NUMBER,
                    StateQueryPolicy.VERSION,
                    ConfidentialityLabel.PRIVATE,
                ),
                VarBinding.Literal("soglia", "20", VarType.NUMBER, ConfidentialityLabel.PUBLIC),
            ),
            schemaVersion = AUTOMATION_SCHEMA_VERSION_P4,
        )
        val json = ArgusJson.encodeToString(Automation.serializer(), a)
        assertTrue(json.contains("\"vars\""), json)
        assertEquals(a, ArgusJson.decodeFromString(Automation.serializer(), json))
        // La var State eredita la capability di famiglia del reader (fail-closed).
        assertTrue(CapabilityIds.STATE_READER_BUILTIN in a.requiredCapabilities)
        assertTrue(AutomationSchema.isCompatible(a))
        assertFalse(AutomationSchema.isCompatible(a.copy(schemaVersion = AUTOMATION_SCHEMA_VERSION_V1)))
        assertEquals(ApprovalFingerprints.MATERIAL_VERSION_P4, ApprovalFingerprints.materialVersionFor(a))
    }

    @Test fun `automation draft without vars omits the key`() {
        val d = AutomationDraft(
            name = "x",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetWifi(true)),
        )
        val json = ArgusJson.encodeToString(AutomationDraft.serializer(), d)
        assertFalse(json.contains("\"vars\""), json)
        assertEquals(d, ArgusJson.decodeFromString(AutomationDraft.serializer(), json))
        val legacy = Automation(
            AutomationId("legacy"),
            "legacy",
            CreatedBy.USER,
            AutomationStatus.PENDING_APPROVAL,
            d.trigger,
            d.actions,
        )
        assertEquals(
            ApprovalFingerprints.MATERIAL_VERSION_V1,
            ApprovalFingerprints.materialVersionFor(legacy),
        )
    }

    // --- tier aggregato: control-flow con generativo annidato è GENERATIVE ---------------------

    @Test fun `control-flow tier reflects nested generative actions`() {
        val det = Action.While(
            condition = Condition.VarCompare("x", CmpOp.EQ, "1"),
            body = listOf(Action.SetFlashlight(true)),
            maxIterations = 3,
        )
        assertEquals(ActionTier.DETERMINISTIC, det.tier)

        val gen = Action.If(
            condition = Condition.VarCompare("x", CmpOp.EQ, "1"),
            then = listOf(Action.InvokeLlm("g", listOf("notification"), listOf("whatsapp_reply"), true)),
        )
        assertEquals(ActionTier.GENERATIVE, gen.tier)
    }
}
