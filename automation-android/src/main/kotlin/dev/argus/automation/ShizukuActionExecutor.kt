package dev.argus.automation

import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.automation.notification.NotificationReplyRequest
import dev.argus.device.DeviceController
import dev.argus.device.DeviceToolException
import dev.argus.device.RingerMode
import dev.argus.engine.model.Action
import dev.argus.engine.model.GenerativeAction
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.ProgramActionResult
import dev.argus.engine.runtime.ResolvedActionExecutor
import dev.argus.engine.runtime.ResolvedProgramAction
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
    fun trySubmit(context: FireContext, action: GenerativeAction): Boolean
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
    /**
     * Executor delle azioni BASE con API Android normali (decision record §7.3). Quando presente,
     * DND/Ringer/LaunchApp/OpenUrl NON passano più dallo shell Shizuku. Default **null**: finché il
     * DI non lo cabla (tier base attivo), il comportamento resta quello legacy via [tools].
     */
    private val baseActions: AndroidBaseActionExecutor? = null,
    /**
     * Vero quando Shizuku è vivo e concesso. Le azioni **activity-launch** (sveglia/timer/schermate
     * impostazioni/app/URL) partono da un Intent `startActivity`, che Android 14+/OEM **bloccano da
     * background** (caveat BAL): da un'automazione l'Intent BASE fallisce. Quando Shizuku è pronto le
     * instradiamo su `am start` privilegiato (identità shell, esente dal blocco) così funzionano anche
     * da background. Default **false**: senza segnale si resta sul percorso BASE (foreground-only).
     */
    private val shizukuReady: () -> Boolean = { false },
) : ActionExecutor, ResolvedActionExecutor {
    override suspend fun execute(
        action: ResolvedProgramAction,
        context: FireContext,
    ): ProgramActionResult = try {
        // Il framing LLM dei RuntimeDataBinding arriva in P4-D: fino ad allora nessun valore
        // separato può essere degradato concatenandolo al goal approvato.
        if (action.runtimeData.isNotEmpty()) {
            ProgramActionResult(ActionResult.Failure("p4_runtime_data_unavailable"))
        } else {
            when (val leaf = action.action) {
                is Action.RunShell -> if (leaf.captureAs != null) {
                    if (!StaticShellSafety.allows(context.event, whitelistedIds())) {
                        ProgramActionResult(ActionResult.Failure("shell_external_trigger"))
                    } else {
                        staticShell.runCaptured(leaf.cmd, context)
                    }
                } else {
                    ProgramActionResult(execute(leaf, context))
                }
                is Action.InvokeLlm,
                is Action.InvokeLlmV2,
                -> ProgramActionResult(ActionResult.Failure("p4_generative_unavailable"))
                else -> ProgramActionResult(execute(leaf, context))
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: DeviceToolException) {
        ProgramActionResult(ActionResult.Failure(error.code))
    } catch (_: IllegalArgumentException) {
        ProgramActionResult(ActionResult.Failure("action_invalid"))
    } catch (_: Exception) {
        ProgramActionResult(ActionResult.Failure("action_failed"))
    }

    override suspend fun execute(action: Action, ctx: FireContext): ActionResult = try {
        when (action) {
            is Action.SetWifi -> success { tools.setWifi(action.on, ctx.executionId, ctx.priority) }
            is Action.SetBluetooth -> success {
                tools.setBluetooth(action.on, ctx.executionId, ctx.priority)
            }
            // Scrittura impostazioni PARAMETRICA: PRIVILEGED puro (nessun fallback base). key/value
            // sono letterali dell'azione approvata; DeviceTools li ri-valida e costruisce argv.
            is Action.WriteSetting -> success {
                tools.writeSetting(
                    action.namespace,
                    action.key,
                    action.value,
                    ctx.executionId,
                    ctx.priority,
                )
            }
            is Action.SetDnd -> baseActions?.setDnd(action.mode)
                ?: success { tools.setDnd(action.mode, ctx.executionId, ctx.priority) }
            is Action.SetRinger -> {
                val mode = RingerMode.fromEngineValue(action.mode)
                    ?: return ActionResult.Failure("ringer_mode_invalid")
                baseActions?.setRinger(mode)
                    ?: success { tools.setRinger(mode, ctx.executionId, ctx.priority) }
            }
            // Activity-launch: privilegiato `am start` quando Shizuku è pronto (sopravvive al
            // background), altrimenti Intent BASE (foreground) o, senza base, il vecchio percorso tools.
            is Action.LaunchApp -> if (shizukuReady()) {
                success { tools.launchApp(action.pkg, ctx.executionId, ctx.priority) }
            } else {
                baseActions?.launchApp(action.pkg)
                    ?: success { tools.launchApp(action.pkg, ctx.executionId, ctx.priority) }
            }
            is Action.OpenUrl -> if (shizukuReady()) {
                success { tools.openUrl(action.url, ctx.executionId, ctx.priority) }
            } else {
                baseActions?.openUrl(action.url)
                    ?: success { tools.openUrl(action.url, ctx.executionId, ctx.priority) }
            }
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

            // Sveglia/timer: privilegiato `am start` quando Shizuku è pronto (unica via affidabile da
            // background, caveat BAL), altrimenti Intent AlarmClock BASE (permesso normal SET_ALARM,
            // solo foreground). Senza né Shizuku né base executor: fallimento pulito.
            is Action.SetAlarm -> if (shizukuReady()) {
                success { tools.setAlarm(action.hour, action.minute, action.label, action.skipUi, ctx.executionId, ctx.priority) }
            } else {
                baseActions?.setAlarm(action.hour, action.minute, action.label, action.skipUi)
                    ?: ActionResult.Failure("base_executor_unavailable")
            }
            is Action.SetTimer -> if (shizukuReady()) {
                success { tools.setTimer(action.seconds, action.label, action.skipUi, ctx.executionId, ctx.priority) }
            } else {
                baseActions?.setTimer(action.seconds, action.label, action.skipUi)
                    ?: ActionResult.Failure("base_executor_unavailable")
            }

            // Manager/Intent BASE (S4): volume/torcia/schermata impostazioni/vibrazione. Base-only:
            // senza base executor falliscono pulito, non toccano mai lo shell privilegiato.
            is Action.SetVolume -> baseActions?.setVolume(action.stream, action.level)
                ?: ActionResult.Failure("base_executor_unavailable")
            is Action.SetFlashlight -> baseActions?.setFlashlight(action.on)
                ?: ActionResult.Failure("base_executor_unavailable")
            // Activity-launch come sveglia: privilegiato quando Shizuku è pronto, Intent BASE altrimenti.
            is Action.OpenSettingsScreen -> if (shizukuReady()) {
                success { tools.openSettingsScreen(action.screen, action.pkg, ctx.executionId, ctx.priority) }
            } else {
                baseActions?.openSettingsScreen(action.screen, action.pkg)
                    ?: ActionResult.Failure("base_executor_unavailable")
            }
            is Action.Vibrate -> baseActions?.vibrate(action.durationMs)
                ?: ActionResult.Failure("base_executor_unavailable")

            is Action.InvokeLlm -> if (generativeLane.trySubmit(ctx, action)) {
                ActionResult.Submitted
            } else {
                ActionResult.Failure("generative_lane_unavailable")
            }
            is Action.InvokeLlmV2 -> if (generativeLane.trySubmit(ctx, action)) {
                ActionResult.Submitted
            } else {
                ActionResult.Failure("generative_lane_unavailable")
            }

            // Control-flow strutturato (P4): l'interprete deterministico arriva con P4-B/P4-C. Fino
            // ad allora l'esecuzione è rifiutata in modo pulito e tipizzato (fail-closed), come le
            // azioni UI non ancora eseguibili in questa fase: mai eseguire un albero senza interprete.
            is Action.Wait,
            is Action.If,
            is Action.While,
            -> ActionResult.Failure("p4_not_yet_executable")
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
