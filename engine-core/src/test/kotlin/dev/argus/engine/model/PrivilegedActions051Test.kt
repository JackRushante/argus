package dev.argus.engine.model

import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.StateReadPlanner
import dev.argus.engine.runtime.journalType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Azioni privilegiate tipizzate 0.5.1: modello, wire, privilegio, capacità e policy dei bersagli. */
class PrivilegedActions051Test {
    private val youtube = "com.google.android.youtube"
    private val mic = "android.permission.RECORD_AUDIO"

    private val newActions: List<Action> = listOf(
        Action.AppControl(youtube, AppControlOp.FORCE_STOP),
        Action.AppControl(youtube, AppControlOp.FREEZE),
        Action.AppControl(youtube, AppControlOp.UNFREEZE),
        Action.SetAirplane(true),
        Action.SetLocation(false),
        Action.SetNfc(true),
        Action.SetBatterySaver(false),
        Action.LockScreen,
        Action.Screenshot,
        Action.AppPermission(youtube, mic, grant = false),
    )

    private val wireTypes = listOf(
        "app_control", "app_control", "app_control", "set_airplane", "set_location", "set_nfc",
        "set_battery_saver", "lock_screen", "screenshot", "app_permission",
    )

    private fun wire(action: Action) = ArgusJson.encodeToString(Action.serializer(), action)

    @Test
    fun `every new action round trips through the wire format`() {
        newActions.forEach { action ->
            assertEquals(action, ArgusJson.decodeFromString(Action.serializer(), wire(action)), wire(action))
        }
    }

    @Test
    fun `the wire shape is the registered one`() {
        assertEquals(
            "{\"type\":\"app_control\",\"pkg\":\"$youtube\",\"op\":\"FORCE_STOP\"}",
            wire(Action.AppControl(youtube, AppControlOp.FORCE_STOP)),
        )
        assertEquals("{\"type\":\"set_airplane\",\"on\":true}", wire(Action.SetAirplane(true)))
        assertEquals("{\"type\":\"set_location\",\"on\":false}", wire(Action.SetLocation(false)))
        assertEquals("{\"type\":\"set_nfc\",\"on\":true}", wire(Action.SetNfc(true)))
        assertEquals("{\"type\":\"set_battery_saver\",\"on\":false}", wire(Action.SetBatterySaver(false)))
        assertEquals("{\"type\":\"lock_screen\"}", wire(Action.LockScreen))
        assertEquals("{\"type\":\"screenshot\"}", wire(Action.Screenshot))
        assertEquals(
            "{\"type\":\"app_permission\",\"pkg\":\"$youtube\",\"permission\":\"$mic\",\"grant\":false}",
            wire(Action.AppPermission(youtube, mic, grant = false)),
        )
        val types = newActions.map {
            (Json.parseToJsonElement(wire(it)) as JsonObject)["type"].toString().trim('"')
        }
        assertEquals(wireTypes, types)
    }

    @Test
    fun `a model that writes the operation in lowercase is understood and the engine writes it uppercase`() {
        val action = ArgusJson.decodeFromString(
            Action.serializer(),
            "{\"type\":\"app_control\",\"pkg\":\"$youtube\",\"op\":\"force_stop\"}",
        )
        assertEquals(Action.AppControl(youtube, AppControlOp.FORCE_STOP), action)
        assertTrue("\"op\":\"FORCE_STOP\"" in wire(action))
        assertFailsWith<Exception> {
            ArgusJson.decodeFromString(
                Action.serializer(),
                "{\"type\":\"app_control\",\"pkg\":\"$youtube\",\"op\":\"UNINSTALL\"}",
            )
        }
    }

    @Test
    fun `an existing rule keeps its fingerprint bytes`() {
        // Nessun campo nuovo sui tipi esistenti: la serializzazione di una regola vecchia non cambia.
        assertEquals("{\"type\":\"set_wifi\",\"on\":true}", wire(Action.SetWifi(true)))
        assertEquals("{\"type\":\"set_dark_mode\",\"mode\":\"AUTO\"}", wire(Action.SetDarkMode(NightMode.AUTO)))
    }

    @Test
    fun `the new actions are privileged and deterministic`() {
        newActions.forEach { action ->
            assertEquals(ActionPrivilege.PRIVILEGED, ActionPrivileges.of(action), action.toString())
            assertTrue(ActionPrivileges.requiresShizuku(action), action.toString())
            assertEquals(ActionTier.DETERMINISTIC, action.tier, action.toString())
        }
        // Dentro un while il massimo resta PRIVILEGED.
        val nested = Action.While(
            condition = Condition.BooleanLiteral(true),
            body = listOf(Action.Screenshot),
            maxIterations = 2,
        )
        assertEquals(ActionPrivilege.PRIVILEGED, ActionPrivileges.of(nested))
    }

    @Test
    fun `each new action requires its own capability`() {
        fun caps(action: Action) = CapabilityRequirements.derive(Trigger.Immediate, listOf(action))
        val expected = mapOf(
            newActions[0] to CapabilityIds.ACTION_APP_CONTROL,
            newActions[3] to CapabilityIds.ACTION_SET_AIRPLANE,
            newActions[4] to CapabilityIds.ACTION_SET_LOCATION,
            newActions[5] to CapabilityIds.ACTION_SET_NFC,
            newActions[6] to CapabilityIds.ACTION_SET_BATTERY_SAVER,
            newActions[7] to CapabilityIds.ACTION_LOCK_SCREEN,
            newActions[8] to CapabilityIds.ACTION_SCREENSHOT,
            newActions[9] to CapabilityIds.ACTION_APP_PERMISSION,
        )
        expected.forEach { (action, id) ->
            assertEquals(setOf(CapabilityIds.TRIGGER_IMMEDIATE, id), caps(action), action.toString())
        }
        assertEquals("action.app_control", ActionCapabilities.APP_CONTROL)
        assertEquals("action.set_nfc", ActionCapabilities.SET_NFC)
        assertEquals("action.app_permission", ActionCapabilities.APP_PERMISSION)
    }

    @Test
    fun `the journal and the state planner know the new actions`() {
        assertEquals(wireTypes, newActions.map { it.journalType() })
        newActions.forEach { assertTrue(StateReadPlanner.forAction(it).isEmpty, it.toString()) }
    }

    @Test
    fun `package and permission are never templates`() {
        newActions.forEach { assertTrue(InterpolationPolicy.textFields(it).isEmpty(), it.toString()) }
    }

    // --- policy dei bersagli -------------------------------------------------------------------

    @Test
    fun `package shape is closed and excludes placeholders`() {
        assertTrue(PrivilegedActionPolicy.isValidPackage(youtube))
        assertTrue(PrivilegedActionPolicy.isValidPackage("com.example.app_2"))
        listOf("", "youtube", "com.", ".com", "com..x", "1com.x", "com.x y", "com.x;rm", "\${pkg}", "com.\${x}")
            .forEach { assertFalse(PrivilegedActionPolicy.isValidPackage(it), it) }
        assertFalse(PrivilegedActionPolicy.isValidPackage("a." + "b".repeat(300)))
    }

    @Test
    fun `app control refuses Argus, its variants, the essential system packages and the launcher`() {
        listOf(
            "dev.argus", "dev.argus.debug", "com.android.systemui", "com.android.phone",
            "com.android.settings", "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        ).forEach {
            assertEquals(PrivilegedActionPolicy.TargetVerdict.FORBIDDEN, PrivilegedActionPolicy.appControlTarget(it), it)
        }
        // `android` ha un solo segmento: la forma lo rifiuta comunque.
        assertEquals(
            PrivilegedActionPolicy.TargetVerdict.PACKAGE_INVALID,
            PrivilegedActionPolicy.appControlTarget("android"),
        )
        assertTrue("android" in PrivilegedActionPolicy.PROTECTED_SYSTEM_PACKAGES)
        assertEquals(PrivilegedActionPolicy.TargetVerdict.OK, PrivilegedActionPolicy.appControlTarget(youtube))
        assertEquals(
            PrivilegedActionPolicy.TargetVerdict.FORBIDDEN,
            PrivilegedActionPolicy.appControlTarget("com.example.launcher", launcher = setOf("com.example.launcher")),
        )
        // Un nome che inizia come Argus ma non lo è resta consentito.
        assertEquals(PrivilegedActionPolicy.TargetVerdict.OK, PrivilegedActionPolicy.appControlTarget("dev.argusx.app"))
    }

    @Test
    fun `permissions refuse only Argus as a target`() {
        assertEquals(PrivilegedActionPolicy.TargetVerdict.FORBIDDEN, PrivilegedActionPolicy.appPermissionTarget("dev.argus"))
        assertEquals(PrivilegedActionPolicy.TargetVerdict.OK, PrivilegedActionPolicy.appPermissionTarget("com.android.settings"))
        assertEquals(PrivilegedActionPolicy.TargetVerdict.PACKAGE_INVALID, PrivilegedActionPolicy.appPermissionTarget("x"))
    }

    @Test
    fun `the permission list is closed to runtime android permissions`() {
        assertTrue(PrivilegedActionPolicy.RUNTIME_PERMISSIONS.all { it.startsWith("android.permission.") })
        val groups = PrivilegedActionPolicy.RUNTIME_PERMISSIONS.mapNotNull(PrivilegedActionPolicy::groupOf).toSet()
        assertEquals(PrivilegedActionPolicy.PermissionGroup.entries.toSet(), groups)
        assertEquals(PrivilegedActionPolicy.PermissionGroup.MICROPHONE, PrivilegedActionPolicy.groupOf(mic))
        assertEquals(
            PrivilegedActionPolicy.PermissionGroup.LOCATION,
            PrivilegedActionPolicy.groupOf("android.permission.ACCESS_FINE_LOCATION"),
        )
        listOf(
            "android.permission.WRITE_SECURE_SETTINGS", "android.permission.INTERNET",
            "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "RECORD_AUDIO", "android.permission.", "android.permission.camera", "com.x.permission.CAMERA", "",
        ).forEach {
            assertFalse(PrivilegedActionPolicy.isRuntimePermission(it), it)
            assertNull(PrivilegedActionPolicy.groupOf(it), it)
        }
        assertNotNull(PrivilegedActionPolicy.groupOf("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun `the resolved shape check repeats the validator rules`() {
        assertTrue(PrivilegedActionPolicy.isShapeValid(Action.AppControl(youtube, AppControlOp.FREEZE)))
        assertFalse(PrivilegedActionPolicy.isShapeValid(Action.AppControl("dev.argus", AppControlOp.FREEZE)))
        assertTrue(PrivilegedActionPolicy.isShapeValid(Action.AppPermission(youtube, mic, true)))
        assertFalse(PrivilegedActionPolicy.isShapeValid(Action.AppPermission(youtube, "android.permission.INTERNET", true)))
        assertFalse(PrivilegedActionPolicy.isShapeValid(Action.AppPermission("dev.argus", mic, false)))
        assertTrue(PrivilegedActionPolicy.isShapeValid(Action.Screenshot))
    }
}
