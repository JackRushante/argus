package dev.argus.data.backup

import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.SolarEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opzione «Togli posizioni e contatti (per condividere)»: ogni tipo del dominio, uno per uno. */
class PersonalDataStripperTest {
    private val validator = DraftValidator(setOf("whatsapp_reply"))

    private fun draftWith(trigger: Trigger) = AutomationDraft(
        name = "t",
        trigger = trigger,
        actions = listOf(Action.SetWifi(true)),
    )

    private fun strip(trigger: Trigger): Trigger =
        PersonalDataStripper.strip(draftWith(trigger)).trigger

    private fun errorCodes(draft: AutomationDraft, whitelist: Set<String> = emptySet()): Set<String> =
        validator.validate(draft, whitelist).filter { it.severity == Severity.ERROR }.map { it.code }.toSet()

    // --- trigger -----------------------------------------------------------------------------

    @Test
    fun `geofence loses its coordinates and asks for the current location when armed`() {
        val stripped = strip(BackupFixtures.geofence) as Trigger.Geofence

        assertTrue(stripped.resolveCurrentLocation)
        assertEquals(0.0, stripped.lat)
        assertEquals(0.0, stripped.lng)
        // Tutto il resto della regola resta com'era.
        assertEquals(BackupFixtures.geofence.radiusM, stripped.radiusM)
        assertEquals(BackupFixtures.geofence.transition, stripped.transition)
    }

    @Test
    fun `a stripped geofence is still a valid draft that resolves the location at arm`() {
        val stripped = PersonalDataStripper.strip(draftWith(BackupFixtures.geofence))

        assertEquals(emptySet(), errorCodes(stripped))
    }

    @Test
    fun `notification trigger loses conversation id sender and title but keeps package and text filter`() {
        val stripped = strip(BackupFixtures.notification) as Trigger.Notification

        assertEquals(PersonalDataStripper.REMOVED_TEXT, stripped.conversationId)
        assertEquals(PersonalDataStripper.REMOVED_TEXT, stripped.sender)
        assertEquals(PersonalDataStripper.REMOVED_TEXT, stripped.titleMatch)
        assertEquals("com.whatsapp", stripped.pkg)
        assertEquals(false, stripped.isGroup)
        assertEquals("cena", stripped.textMatch)
    }

    @Test
    fun `phone trigger loses the number but keeps event and text filter`() {
        val stripped = strip(BackupFixtures.phoneState) as Trigger.PhoneState

        assertEquals(PersonalDataStripper.REMOVED_TEXT, stripped.number)
        assertEquals(BackupFixtures.phoneState.event, stripped.event)
        assertEquals("codice", stripped.textMatch)
    }

    @Test
    fun `connectivity trigger loses the network name but keeps medium and state`() {
        val stripped = strip(BackupFixtures.connectivity) as Trigger.Connectivity

        assertEquals(PersonalDataStripper.REMOVED_TEXT, stripped.match)
        assertEquals(BackupFixtures.connectivity.medium, stripped.medium)
        assertEquals(BackupFixtures.connectivity.state, stripped.state)
        // Senza nome di rete la regola valeva per qualunque rete: resta da completare.
        val any = Trigger.Connectivity(BackupFixtures.connectivity.medium, BackupFixtures.connectivity.state, match = null)
        assertEquals(any, strip(any))
    }

    @Test
    fun `a stripped identity never widens the trigger: the validator blocks the rule until it is completed`() {
        mapOf(
            BackupFixtures.notification to setOf("conversation_id_invalid", "sender_invalid", "title_match_invalid"),
            BackupFixtures.phoneState to setOf("number_invalid"),
            BackupFixtures.connectivity to setOf("match_invalid"),
        ).forEach { (trigger, expected) ->
            assertEquals(emptySet(), errorCodes(draftWith(trigger)) intersect expected, "originale $trigger")
            val codes = errorCodes(PersonalDataStripper.strip(draftWith(trigger)))
            assertTrue(codes.containsAll(expected), "$trigger: $codes")
        }
        // Un campo che era già null resta null: nessun errore nuovo.
        val bySender = Trigger.Notification(pkg = "com.whatsapp", sender = BackupFixtures.SENDER)
        val stripped = strip(bySender) as Trigger.Notification
        assertNull(stripped.conversationId)
        assertNull(stripped.titleMatch)
        assertEquals(PersonalDataStripper.REMOVED_TEXT, stripped.sender)
    }

    @Test
    fun `secret literals lose their value and are rejected, the other variables stay`() {
        val draft = AutomationDraft(
            name = "v",
            trigger = Trigger.Immediate,
            actions = listOf(Action.CopyText("\${token} \${saluto}")),
            vars = listOf(
                VarBinding.Literal("token", "sk-segreto-123", VarType.TEXT, ConfidentialityLabel.SECRET),
                VarBinding.Literal("saluto", "ciao", VarType.TEXT, ConfidentialityLabel.PUBLIC),
            ),
        )
        assertFalse("var_literal_invalid" in errorCodes(draft))

        val stripped = PersonalDataStripper.strip(draft)

        assertEquals(
            listOf(
                VarBinding.Literal("token", PersonalDataStripper.REMOVED_SECRET, VarType.TEXT, ConfidentialityLabel.SECRET),
                VarBinding.Literal("saluto", "ciao", VarType.TEXT, ConfidentialityLabel.PUBLIC),
            ),
            stripped.vars,
        )
        assertFalse("sk-segreto-123" in ArgusJson.encodeToString(AutomationDraft.serializer(), stripped))
        assertTrue("var_literal_invalid" in errorCodes(stripped))
        assertEquals(stripped, PersonalDataStripper.strip(stripped))
    }

    @Test
    fun `a sunset trigger loses the position frozen at arm and keeps event and offset`() {
        val stripped = strip(BackupFixtures.solar) as Trigger.Time

        val solar = assertNotNull(stripped.solar)
        assertNull(solar.lat)
        assertNull(solar.lng)
        assertEquals(SolarEvent.SUNSET, solar.event)
        assertEquals(-30, solar.offsetMinutes)
        assertEquals(BackupFixtures.solar.tz, stripped.tz)
        // Resta una bozza valida: chi la importa la arma con la posizione del proprio telefono.
        assertEquals(emptySet(), errorCodes(PersonalDataStripper.strip(draftWith(BackupFixtures.solar))))
    }

    @Test
    fun `triggers without positions or contacts are left exactly as they are`() {
        listOf(BackupFixtures.time, Trigger.Immediate, BackupFixtures.sensor)
            .forEach { trigger -> assertEquals(trigger, strip(trigger)) }
    }

    @Test
    fun `no trigger type keeps a coordinate, a conversation, a sender or a number`() {
        BackupFixtures.allTriggers.forEach { trigger ->
            val json = ArgusJson.encodeToString(Trigger.serializer(), strip(trigger))
            assertFalse(BackupFixtures.CONVERSATION in json, "conversationId in $trigger")
            assertFalse(BackupFixtures.SENDER in json, "sender in $trigger")
            assertFalse(BackupFixtures.NUMBER in json, "number in $trigger")
            assertFalse("CasaMia" in json, "rete in $trigger")
            assertFalse("\"ciao\"" in json, "titolo in $trigger")
            assertFalse("45.4642" in json, "latitudine in $trigger")
        }
    }

    // --- condizioni ----------------------------------------------------------------------------

    @Test
    fun `location_in keeps its radius and moves its coordinates out of range`() {
        val stripped = PersonalDataStripper.strip(
            AutomationDraft(
                name = "c",
                trigger = Trigger.Immediate,
                actions = listOf(Action.SetWifi(true)),
                conditions = BackupFixtures.locationIn,
            ),
        ).conditions as Condition.LocationIn

        assertEquals(PersonalDataStripper.REMOVED_LATITUDE, stripped.lat)
        assertEquals(PersonalDataStripper.REMOVED_LONGITUDE, stripped.lng)
        assertEquals(BackupFixtures.locationIn.radiusM, stripped.radiusM)
    }

    @Test
    fun `a stripped location_in is flagged by the validator so the rule cannot be armed as is`() {
        val stripped = PersonalDataStripper.strip(
            AutomationDraft(
                name = "c",
                trigger = Trigger.Immediate,
                actions = listOf(Action.SetWifi(true)),
                conditions = BackupFixtures.locationIn,
            ),
        )

        assertTrue("location_invalid" in errorCodes(stripped))
    }

    @Test
    fun `location_in is stripped at any depth, in the conditions and inside if and while`() {
        val stripped = PersonalDataStripper.strip(BackupFixtures.rich())

        val found = mutableListOf<Condition.LocationIn>()
        fun visitCondition(condition: Condition) {
            when (condition) {
                is Condition.LocationIn -> found += condition
                is Condition.And -> condition.all.forEach { visitCondition(it) }
                is Condition.Or -> condition.any.forEach { visitCondition(it) }
                is Condition.Not -> visitCondition(condition.cond)
                else -> Unit
            }
        }
        fun visitAction(action: Action) {
            when (action) {
                is Action.If -> {
                    visitCondition(action.condition)
                    action.then.forEach { visitAction(it) }
                    action.orElse.forEach { visitAction(it) }
                }
                is Action.While -> {
                    visitCondition(action.condition)
                    action.body.forEach { visitAction(it) }
                }
                else -> Unit
            }
        }
        stripped.conditions?.let { visitCondition(it) }
        stripped.actions.forEach { visitAction(it) }

        // 2 nelle condizioni (Not e Or) + 1 nell'if + 1 nel while.
        assertEquals(4, found.size)
        found.forEach {
            assertEquals(PersonalDataStripper.REMOVED_LATITUDE, it.lat)
            assertEquals(PersonalDataStripper.REMOVED_LONGITUDE, it.lng)
            assertEquals(200.0, it.radiusM)
        }
        assertFalse("45.4642" in ArgusJson.encodeToString(AutomationDraft.serializer(), stripped))
    }

    @Test
    fun `the shape of the conditions and of the control flow survives the strip`() {
        val original = BackupFixtures.rich()
        val stripped = PersonalDataStripper.strip(original)

        assertEquals(original.name, stripped.name)
        assertEquals(original.vars, stripped.vars)
        assertEquals(original.cooldownMs, stripped.cooldownMs)
        assertTrue(stripped.conditions is Condition.And)
        val flow = stripped.actions.single() as Action.If
        assertEquals(1, flow.then.size)
        assertTrue(flow.orElse.single() is Action.While)
    }

    @Test
    fun `conditions that are not a position are left exactly as they are`() {
        BackupFixtures.leafConditions
            .filterNot { it is Condition.LocationIn }
            .forEach { condition ->
                val draft = AutomationDraft(
                    name = "c",
                    trigger = Trigger.Immediate,
                    actions = listOf(Action.SetWifi(true)),
                    conditions = condition,
                )
                assertEquals(draft, PersonalDataStripper.strip(draft), "condizione $condition")
            }
    }

    // --- azioni --------------------------------------------------------------------------------

    @Test
    fun `no leaf action carries a position or a contact, so each one is left exactly as it is`() {
        BackupFixtures.leafActions.forEach { action ->
            val draft = AutomationDraft(name = "a", trigger = Trigger.Immediate, actions = listOf(action))
            assertEquals(draft, PersonalDataStripper.strip(draft), "azione $action")
        }
    }

    // --- effetto sulle regole che rispondono ---------------------------------------------------

    @Test
    fun `a generative reply rule loses its conversation, so the validator blocks it until it is remade`() {
        val whitelist = setOf(BackupFixtures.CONVERSATION)
        assertEquals(emptySet(), errorCodes(BackupFixtures.notificationReply, whitelist))

        val stripped = PersonalDataStripper.strip(BackupFixtures.notificationReply)

        assertTrue("reply_needs_conversation_id" in errorCodes(stripped, whitelist))
    }

    @Test
    fun `stripping twice gives the same draft`() {
        val once = PersonalDataStripper.strip(BackupFixtures.rich())

        assertEquals(once, PersonalDataStripper.strip(once))
    }
}
