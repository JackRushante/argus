package dev.argus.automation.telephony

import dev.argus.automation.base.ActivityStartBlockedException
import dev.argus.automation.base.DEFERRED_TO_NOTIFICATION
import dev.argus.automation.base.DeferredOpenNotifier
import dev.argus.automation.base.DeferredOpenTarget
import dev.argus.engine.model.Action
import dev.argus.engine.model.IntentExtraType
import dev.argus.engine.model.IntentKind
import dev.argus.engine.model.TelephonyActionPolicy
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.FireContext
import kotlinx.coroutines.CancellationException

/** Ciò che il telefono sa fare per SMS, chiamate e Intent senza privilegi (finto nei test). */
interface TelephonySurface {
    /** Il package di Argus in esecuzione (varianti comprese). */
    val ownPackage: String

    /** Il telefono ha la telefonia (SMS e chiamate). */
    fun hasTelephony(): Boolean
    fun canSendSms(): Boolean
    fun canCall(): Boolean

    /** Invia l'SMS, in più parti se serve. Lancia se il sistema lo rifiuta. */
    fun sendSms(number: String, text: String)

    /** Avvia la chiamata. Da background lancia [ActivityStartBlockedException]. */
    fun placeCall(number: String)

    /**
     * I package che riceverebbero l'Intent (implicito) come [kind]. Lancia se non si possono leggere. Per un
     * broadcast senza package comprende Argus anche quando lo riceverebbe un SUO receiver registrato a runtime
     * (che il PackageManager non elenca e che, essendo dello stesso uid, riceve anche se non esportato).
     */
    fun receivers(intent: Action.SendIntent): Set<String>
    fun sendBroadcast(intent: Action.SendIntent)

    /** false se nessuna activity lo apre. Da background lancia [ActivityStartBlockedException]. */
    fun startActivity(intent: Action.SendIntent): Boolean
}

/** `am` privilegiato (Shizuku, Sui o root): lancia [dev.argus.device.DeviceToolException] se fallisce. */
fun interface PrivilegedAm {
    suspend fun run(operation: String, argv: List<String>, ctx: FireContext)
}

/**
 * Esegue `send_sms`, `call_number` e `send_intent` dopo un'ultima verifica sul telefono (0.8). Le stesse
 * regole del validator e dell'interpolatore ([TelephonyActionPolicy]) si ripetono qui, più ciò che solo il
 * telefono sa: il package reale di Argus, chi riceverebbe un Intent implicito, i tetti orari persistiti.
 *
 * Avvio di un'activity (chiamata, Intent ACTIVITY) come le altre azioni che aprono qualcosa: con il backend
 * privilegiato pronto `am start` (affidabile anche da background), altrimenti l'avvio normale e, se Android lo
 * blocca dal background, la notifica «Tocca per …». Un servizio parte SOLO col backend privilegiato.
 *
 * Quando un tetto scatta lo si scrive anche nel Log come blocco di policy (senza il numero).
 */
class TelephonyActionRunner(
    private val surface: TelephonySurface,
    private val limiter: TelephonyRateLimiter,
    private val audit: AuditSink,
    private val privileged: PrivilegedAm? = null,
    private val deferredOpen: DeferredOpenNotifier? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sendSms(action: Action.SendSms, ctx: FireContext): ActionResult {
        if (!surface.hasTelephony()) return ActionResult.Failure(TELEPHONY_UNAVAILABLE)
        if (!TelephonyActionPolicy.isValidNumber(action.number) || action.text.isBlank() ||
            action.text.length > TelephonyActionPolicy.MAX_SMS_CHARS
        ) {
            return ActionResult.Failure(ACTION_INVALID)
        }
        if (!surface.canSendSms()) return ActionResult.Failure(SMS_PERMISSION_MISSING)
        val verdict = limiter.trySms(action.number)
        if (verdict is RateVerdict.Limited) return limited(verdict, ctx)
        surface.sendSms(action.number, action.text)
        return ActionResult.Success
    }

    suspend fun callNumber(action: Action.CallNumber, ctx: FireContext, privilegedReady: Boolean): ActionResult {
        if (!surface.hasTelephony()) return ActionResult.Failure(TELEPHONY_UNAVAILABLE)
        if (!TelephonyActionPolicy.isValidNumber(action.number)) return ActionResult.Failure(ACTION_INVALID)
        if (!surface.canCall()) return ActionResult.Failure(CALL_PERMISSION_MISSING)
        val verdict = limiter.tryCall()
        if (verdict is RateVerdict.Limited) return limited(verdict, ctx)
        val am = privileged
        if (privilegedReady && am != null) {
            am.run("call_number", AmIntentCommand.call(action.number), ctx)
            return ActionResult.Success
        }
        return deferrable(DeferredOpenTarget.Call(action.number)) {
            surface.placeCall(action.number)
            ActionResult.Success
        }
    }

    suspend fun sendIntent(action: Action.SendIntent, ctx: FireContext, privilegedReady: Boolean): ActionResult {
        TelephonyActionPolicy.intentProblems(action, resolved = true).firstOrNull()?.let {
            return ActionResult.Failure(it)
        }
        if (targetsArgus(action)) return ActionResult.Failure(TelephonyActionPolicy.INTENT_TARGET_FORBIDDEN)
        val am = privileged
        return when (action.kind) {
            IntentKind.BROADCAST -> {
                surface.sendBroadcast(action)
                ActionResult.Success
            }
            IntentKind.ACTIVITY -> if (privilegedReady && am != null) {
                am.run("send_intent", AmIntentCommand.startActivity(action), ctx)
                ActionResult.Success
            } else {
                deferrable(DeferredOpenTarget.IntentActivity(action)) {
                    if (surface.startActivity(action)) ActionResult.Success else ActionResult.Failure(INTENT_UNRESOLVED)
                }
            }
            IntentKind.SERVICE -> if (privilegedReady && am != null) {
                am.run("send_intent", AmIntentCommand.startService(action), ctx)
                ActionResult.Success
            } else {
                ActionResult.Failure(SHIZUKU_UNAVAILABLE)
            }
        }
    }

    /**
     * Argus non è mai un bersaglio: il package reale (anche con un suffisso di variante) e, per un Intent
     * implicito, ogni componente di Argus che lo riceverebbe, esportato o no (un broadcast che Argus stesso
     * invia raggiunge anche i suoi receiver non esportati). Se non si riesce a saperlo si rifiuta.
     */
    private fun targetsArgus(action: Action.SendIntent): Boolean {
        fun isArgus(pkg: String) = pkg == surface.ownPackage || pkg.startsWith("${surface.ownPackage}.") ||
            TelephonyActionPolicy.isArgusName(pkg)
        if (action.cls?.let(::isArgus) == true) return true
        if (action.pkg?.let(::isArgus) == true) return true
        if (action.pkg != null && action.cls != null) return false
        val receivers = try {
            surface.receivers(action)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return true
        }
        return receivers.any(::isArgus)
    }

    private inline fun deferrable(target: DeferredOpenTarget, block: () -> ActionResult): ActionResult = try {
        block()
    } catch (_: ActivityStartBlockedException) {
        if (deferredOpen?.publish(target) == true) {
            ActionResult.Failure(DEFERRED_TO_NOTIFICATION)
        } else {
            ActionResult.Failure(ACTIVITY_START_BLOCKED)
        }
    }

    private suspend fun limited(verdict: RateVerdict.Limited, ctx: FireContext): ActionResult {
        try {
            audit.record(
                AuditEvent(
                    automationId = ctx.automationId,
                    kind = AuditKind.BLOCKED_POLICY,
                    atMillis = now(),
                    detail = verdict.code,
                    eventId = ctx.eventId,
                    executionId = ctx.executionId,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il Log non cambia l'esito: l'invio resta fermato.
        }
        return ActionResult.Failure(verdict.code)
    }

    companion object {
        const val TELEPHONY_UNAVAILABLE = "telephony_unavailable"
        const val SMS_PERMISSION_MISSING = "sms_send_permission_missing"
        const val CALL_PERMISSION_MISSING = "call_permission_missing"
        const val INTENT_UNRESOLVED = "intent_unresolved"
        const val ACTIVITY_START_BLOCKED = "activity_start_blocked"
        const val SHIZUKU_UNAVAILABLE = "shizuku_unavailable"
        const val ACTION_INVALID = "action_invalid"
    }
}

/**
 * Argomenti di `am` per un Intent approvato: una lista argv (mai una riga di shell). Ogni valore è un
 * argomento separato, quindi un testo esterno in un extra STRING resta un solo valore.
 */
internal object AmIntentCommand {
    private const val AM = "am"

    fun call(number: String): List<String> =
        listOf(AM, "start", "--user", "current", "-a", "android.intent.action.CALL", "-d", "tel:$number")

    fun startActivity(intent: Action.SendIntent): List<String> =
        listOf(AM, "start", "--user", "current") + intentArgs(intent)

    fun startService(intent: Action.SendIntent): List<String> =
        listOf(AM, "startservice", "--user", "current") + intentArgs(intent)

    fun intentArgs(intent: Action.SendIntent): List<String> = buildList {
        intent.action?.let { addAll(listOf("-a", it)) }
        intent.data?.let { addAll(listOf("-d", it)) }
        intent.mimeType?.let { addAll(listOf("-t", it)) }
        val pkg = intent.pkg
        val cls = intent.cls
        when {
            pkg != null && cls != null -> addAll(listOf("-n", "$pkg/$cls"))
            pkg != null -> addAll(listOf("-p", pkg))
        }
        intent.extras.forEach { extra ->
            val flag = when (extra.type) {
                IntentExtraType.STRING -> "--es"
                IntentExtraType.INT -> "--ei"
                IntentExtraType.LONG -> "--el"
                IntentExtraType.BOOLEAN -> "--ez"
                IntentExtraType.FLOAT -> "--ef"
            }
            addAll(listOf(flag, extra.name, extra.value))
        }
    }
}
