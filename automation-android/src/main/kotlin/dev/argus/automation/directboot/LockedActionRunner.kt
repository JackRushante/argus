package dev.argus.automation.directboot

import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.device.RingerMode
import dev.argus.engine.directboot.DirectBootActionRunner
import dev.argus.engine.directboot.DirectBootPolicy
import dev.argus.engine.directboot.DirectBootRunner
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.ActionResult

/**
 * The minimal executor of the locked path: only the allow-listed switches, through the normal Android managers
 * ([AndroidBaseActionExecutor]), never Shizuku (Do Not Disturb has no privileged retry here), never an activity.
 * Every other action is refused without touching anything, even if the copy said otherwise.
 */
class LockedActionRunner(private val base: AndroidBaseActionExecutor) : DirectBootActionRunner {
    override suspend fun run(action: Action): ActionResult {
        if (!DirectBootPolicy.allowsAction(action)) return ActionResult.Failure(DirectBootRunner.NOT_ALLOWED)
        return when (action) {
            is Action.SetDnd -> base.setDnd(action.mode, privilegedRetry = null)
            is Action.SetRinger -> RingerMode.fromEngineValue(action.mode)?.let { base.setRinger(it) }
                ?: ActionResult.Failure(ACTION_INVALID)
            is Action.SetVolume -> base.setVolume(action.stream, action.level)
            is Action.SetFlashlight -> base.setFlashlight(action.on)
            is Action.Vibrate -> base.vibrate(action.durationMs)
            else -> ActionResult.Failure(DirectBootRunner.NOT_ALLOWED)
        }
    }

    private companion object {
        const val ACTION_INVALID = "action_invalid"
    }
}
