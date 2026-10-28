package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppPackagesTest {
    private val youtube = "com.google.android.youtube"
    private val chrome = "com.android.chrome"

    private val ifAction = Action.If(
        condition = Condition.VarCompare("n", CmpOp.GT, expected = "5"),
        then = listOf(Action.LaunchApp(chrome)),
        orElse = listOf(Action.AppControl("com.example.game", AppControlOp.FORCE_STOP)),
    )

    private val actions: List<Action> = listOf(
        Action.UiSteps(pkg = "com.android.deskclock", steps = listOf(UiStep.Type("x"))),
        ifAction,
        Action.While(
            condition = Condition.VarCompare("n", CmpOp.GT, expected = "5"),
            body = listOf(Action.AppPermission("com.example.cam", "android.permission.CAMERA", true)),
            maxIterations = 2,
        ),
        Action.SetWifi(false),
    )

    private val trigger = Trigger.AppEvent(youtube, AppEventKind.OPENED)

    @Test
    fun `slots follow the trigger and then the actions depth first`() {
        val refs = AppPackages.refs(trigger, actions)

        assertEquals(
            listOf(
                AppPackages.Ref(0, AppPackages.Kind.APP_EVENT, youtube),
                AppPackages.Ref(1, AppPackages.Kind.UI_STEPS, "com.android.deskclock"),
                AppPackages.Ref(2, AppPackages.Kind.LAUNCH_APP, chrome),
                AppPackages.Ref(3, AppPackages.Kind.APP_CONTROL, "com.example.game"),
                AppPackages.Ref(4, AppPackages.Kind.APP_PERMISSION, "com.example.cam"),
            ),
            refs,
        )
    }

    @Test
    fun `a placeholder or malformed package has no slot and is not checked`() {
        val rule = listOf(Action.LaunchApp("\${pkg}"), Action.LaunchApp("nodots"), Action.LaunchApp(chrome))

        val refs = AppPackages.refs(Trigger.Immediate, rule)

        assertEquals(listOf(AppPackages.Ref(0, AppPackages.Kind.LAUNCH_APP, chrome)), refs)
        assertEquals(listOf(chrome), AppPackages.packagesToCheck(Trigger.Immediate, rule))
    }

    @Test
    fun `the notification trigger package is checked but never replaceable`() {
        val notification = Trigger.Notification(pkg = "com.whatsapp", conversationId = "c1")
        val rule = listOf(Action.LaunchApp(chrome))

        assertEquals(listOf(AppPackages.Ref(0, AppPackages.Kind.LAUNCH_APP, chrome)), AppPackages.refs(notification, rule))
        assertEquals(listOf("com.whatsapp", chrome), AppPackages.packagesToCheck(notification, rule))
        assertNull(AppPackages.triggerSlot(notification))
    }

    @Test
    fun `packages to check have no duplicates`() {
        val rule = listOf(Action.LaunchApp(chrome), Action.AppControl(chrome, AppControlOp.FREEZE))

        assertEquals(listOf(chrome), AppPackages.packagesToCheck(Trigger.Immediate, rule))
    }

    @Test
    fun `slots by action use the identity of the instance, so equal actions stay distinct`() {
        val first = Action.LaunchApp(chrome)
        val second = Action.LaunchApp(chrome)

        val slots = AppPackages.slotsByAction(trigger, listOf(first, second))

        assertEquals(1, slots[first])
        assertEquals(2, slots[second])
    }

    @Test
    fun `replacing a nested slot changes only that package`() {
        val replaced = AppPackages.replace(trigger, actions, slot = 3, newPackage = "com.example.other")!!

        assertEquals(trigger, replaced.trigger)
        val expected = actions.toMutableList()
        expected[1] = ifAction.copy(orElse = listOf(Action.AppControl("com.example.other", AppControlOp.FORCE_STOP)))
        assertEquals(expected, replaced.actions)
    }

    @Test
    fun `replacing the trigger slot keeps the event kind`() {
        val replaced = AppPackages.replace(trigger, actions, slot = 0, newPackage = "org.example.video")!!

        assertEquals(Trigger.AppEvent("org.example.video", AppEventKind.OPENED), replaced.trigger)
        assertEquals(actions, replaced.actions)
    }

    @Test
    fun `replacing the ui steps package keeps its steps`() {
        val replaced = AppPackages.replace(trigger, actions, slot = 1, newPackage = "com.example.clock")!!

        assertEquals(
            Action.UiSteps(pkg = "com.example.clock", steps = listOf(UiStep.Type("x"))),
            replaced.actions[0],
        )
    }

    @Test
    fun `an unknown slot or an invalid package changes nothing`() {
        assertNull(AppPackages.replace(trigger, actions, slot = 9, newPackage = "com.example.other"))
        assertNull(AppPackages.replace(trigger, actions, slot = -1, newPackage = "com.example.other"))
        assertNull(AppPackages.replace(trigger, actions, slot = 2, newPackage = "nodots"))
        assertNull(AppPackages.replace(trigger, actions, slot = 2, newPackage = "\${pkg}"))
    }

    @Test
    fun `a replacement is a different rule, so it gets a different fingerprint`() {
        val before = Automation(
            AutomationId("a"), "r", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL, trigger, actions,
        )
        val replaced = AppPackages.replace(trigger, actions, slot = 2, newPackage = "com.example.other")!!
        val after = before.copy(trigger = replaced.trigger, actions = replaced.actions)

        assertNotEquals(ApprovalFingerprints.of(before), ApprovalFingerprints.of(after))
    }

    @Test
    fun `untouched actions keep their instance`() {
        val replaced = AppPackages.replace(trigger, actions, slot = 0, newPackage = "org.example.video")!!

        assertSame(actions[3], replaced.actions[3])
    }
}
