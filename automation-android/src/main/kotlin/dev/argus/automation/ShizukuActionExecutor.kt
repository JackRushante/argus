package dev.argus.automation

import dev.argus.device.DeviceController
import dev.argus.device.DeviceToolException
import dev.argus.device.RingerMode
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.FireContext
import kotlinx.coroutines.CancellationException

/** Boundary Android per le notifiche prodotte da una regola. */
fun interface AutomationNotifier {
    suspend fun show(title: String, text: String, context: FireContext)
}

/**
 * La lane deve limitarsi ad accodare. `trySubmit` non è suspend proprio per impedire
 * all'executor deterministico di attendere la chiamata LLM.
 */
fun interface GenerativeLane {
    fun trySubmit(context: FireContext, action: Action.InvokeLlm): Boolean
}

class ShizukuActionExecutor(
    private val tools: DeviceController,
    private val notifier: AutomationNotifier,
    private val generativeLane: GenerativeLane,
) : ActionExecutor {
    override suspend fun execute(action: Action, ctx: FireContext): ActionResult = try {
        when (action) {
            is Action.SetWifi -> success { tools.setWifi(action.on, ctx.executionId, ctx.priority) }
            is Action.SetBluetooth -> success {
                tools.setBluetooth(action.on, ctx.executionId, ctx.priority)
            }
            is Action.SetDnd -> success { tools.setDnd(action.mode, ctx.executionId, ctx.priority) }
            is Action.SetRinger -> {
                val mode = RingerMode.fromEngineValue(action.mode)
                    ?: return ActionResult.Failure("ringer_mode_invalid")
                success { tools.setRinger(mode, ctx.executionId, ctx.priority) }
            }
            is Action.LaunchApp -> success {
                tools.launchApp(action.pkg, ctx.executionId, ctx.priority)
            }
            is Action.OpenUrl -> success { tools.openUrl(action.url, ctx.executionId, ctx.priority) }
            is Action.ShowNotification -> success {
                notifier.show(action.title, action.text, ctx)
            }
            // Le azioni UI e RemoteInput non appartengono al percorso P0-B: non eseguirle
            // soltanto perché il gateway privilegiato sa materialmente inviare input.
            is Action.Tap,
            is Action.InputText,
            is Action.WhatsAppReply,
            -> ActionResult.Failure("unsupported_phase")

            // Difesa in profondità oltre al FirePolicy: nessuna shell senza conferma live.
            is Action.RunShell -> ActionResult.Failure("live_confirmation_required")

            is Action.InvokeLlm -> if (generativeLane.trySubmit(ctx, action)) {
                ActionResult.Submitted
            } else {
                ActionResult.Failure("generative_lane_unavailable")
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: DeviceToolException) {
        ActionResult.Failure(error.code)
    } catch (_: IllegalArgumentException) {
        ActionResult.Failure("action_invalid")
    } catch (_: Exception) {
        ActionResult.Failure("action_failed")
    }

    private suspend inline fun success(block: () -> Unit): ActionResult {
        block()
        return ActionResult.Success
    }
}
