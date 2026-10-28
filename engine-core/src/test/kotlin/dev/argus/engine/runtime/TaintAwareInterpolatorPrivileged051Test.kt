package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.AppControlOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Le azioni privilegiate 0.5.1 non hanno segnaposto: passano com'erano o si bloccano per forma. */
class TaintAwareInterpolatorPrivileged051Test {
    private val interpolator = TaintAwareInterpolator()
    private val scope = VarScope(emptyMap())
    private val youtube = "com.google.android.youtube"
    private val mic = "android.permission.RECORD_AUDIO"

    private fun resolved(action: Action): Action =
        assertIs<ActionResolution.Resolved>(interpolator.resolve(action, scope)).value.action

    private fun blocked(action: Action): String =
        assertIs<ActionResolution.Blocked>(interpolator.resolve(action, scope)).code

    @Test
    fun `valid actions come back unchanged`() {
        listOf(
            Action.AppControl(youtube, AppControlOp.FORCE_STOP),
            Action.AppControl(youtube, AppControlOp.FREEZE),
            Action.SetAirplane(true),
            Action.SetLocation(true),
            Action.SetNfc(false),
            Action.SetBatterySaver(true),
            Action.LockScreen,
            Action.Screenshot,
            Action.AppPermission(youtube, mic, grant = true),
        ).forEach { assertEquals(it, resolved(it), it.toString()) }
    }

    @Test
    fun `a forbidden target or a permission outside the list is blocked at run time`() {
        assertEquals("resolved_action_invalid", blocked(Action.AppControl("dev.argus", AppControlOp.FORCE_STOP)))
        assertEquals(
            "resolved_action_invalid",
            blocked(Action.AppControl("com.android.systemui", AppControlOp.FREEZE)),
        )
        assertEquals("resolved_action_invalid", blocked(Action.AppControl("\${x}", AppControlOp.FORCE_STOP)))
        assertEquals(
            "resolved_action_invalid",
            blocked(Action.AppPermission(youtube, "android.permission.WRITE_SECURE_SETTINGS", true)),
        )
        assertEquals("resolved_action_invalid", blocked(Action.AppPermission("dev.argus", mic, false)))
    }
}
