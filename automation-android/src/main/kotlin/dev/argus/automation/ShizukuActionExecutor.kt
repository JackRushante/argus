package dev.argus.automation

import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyRequest
import dev.argus.device.DeviceController
import dev.argus.device.DeviceToolException
import dev.argus.device.RingerMode
import dev.argus.engine.model.Action
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.safety.StaticShellSafety
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
    private val replies: NotificationReplyGateway,
    private val clipboard: ClipboardCopier,
    private val staticShell: StaticShellRunner = StaticShellRunner { _, _ ->
        ActionResult.Failure("shell_unavailable")
    },
    /**
     * Identità autorizzate a innescare la shell già approvata. Default **vuoto**: un caller che
     * dimentica di cablare la whitelist ottiene il comportamento chiuso, mai quello aperto.
     */
    private val whitelistedIds: suspend () -> Set<String> = { emptySet() },
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
            // Le azioni UI non appartengono a questa fase: non eseguirle soltanto perché il
            // gateway privilegiato sa materialmente inviare input.
            is Action.Tap,
            is Action.InputText,
            -> ActionResult.Failure("unsupported_phase")

            is Action.WhatsAppReply -> staticReply(action, ctx)

            // Ultima linea dopo validator e FirePolicy: l'identità si verifica sull'evento
            // realmente arrivato, non su quella dichiarata nella regola.
            is Action.RunShell -> if (StaticShellSafety.allows(ctx.event, whitelistedIds())) {
                staticShell.run(action.cmd, ctx)
            } else {
                ActionResult.Failure("shell_external_trigger")
            }

            // Clipboard: locale, senza privilegi; il payload arriva dall'evento del trigger.
            is Action.CopyToClipboard -> clipboard.copy(ctx.event, action.extractionRegex)

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

    /**
     * Reply statica: testo approvato nello snapshot e destinatario congelato dal trigger
     * verificato. Il gateway ripete package trusted, key/conversation/event esatti, 1:1 e
     * consumo one-shot: gruppo, handle stale o canale scaduto restano fallimenti tipizzati.
     */
    private fun staticReply(action: Action.WhatsAppReply, ctx: FireContext): ActionResult {
        val notification = ctx.event as? TriggerEvent.NotificationPosted
            ?: return ActionResult.Failure("reply_event_unverified")
        val delivery = replies.send(
            NotificationReplyRequest(
                packageName = notification.pkg,
                notificationKey = notification.notificationKey.orEmpty(),
                conversationId = notification.conversationId.orEmpty(),
                eventId = ctx.eventId,
                text = action.text,
            ),
        )
        return when (delivery) {
            NotificationReplyDelivery.Sent -> ActionResult.Success
            is NotificationReplyDelivery.Failed -> ActionResult.Failure(delivery.code)
        }
    }

    private suspend inline fun success(block: () -> Unit): ActionResult {
        block()
        return ActionResult.Success
    }
}
