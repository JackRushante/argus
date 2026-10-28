package dev.argus.e2e

import dev.argus.engine.connectivity.ConnectivityEventParser
import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.Automation
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.phone.PhoneEventParser
import dev.argus.engine.runtime.SensorEventIds
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

sealed interface E2eEventResult {
    data class Built(val envelope: TriggerEnvelope, val type: String) : E2eEventResult
    data class Invalid(val code: String) : E2eEventResult
}

/** Nome wire del trigger (stesso `type` del JSON delle regole). */
fun triggerTypeOf(trigger: Trigger): String = when (trigger) {
    is Trigger.Time -> "time"
    is Trigger.Immediate -> "immediate"
    is Trigger.Geofence -> "geofence"
    is Trigger.Notification -> "notification"
    is Trigger.PhoneState -> "phone_state"
    is Trigger.Connectivity -> "connectivity"
    is Trigger.Sensor -> "sensor"
    is Trigger.Screen -> "screen"
    is Trigger.Battery -> "battery"
    is Trigger.AudioOutput -> "audio_output"
    is Trigger.ModeChanged -> "mode_changed"
    is Trigger.Media -> "media"
    is Trigger.Manual -> "manual"
    is Trigger.AppEvent -> "app_event"
}

/**
 * Costruisce l'evento sintetico che l'aggancio consegna al motore. Per default è l'evento che la
 * regola si aspetta (derivato dal suo trigger, quindi il match riesce); il payload può cambiare i
 * singoli campi o, con `as`, il tipo stesso dell'evento (per provare un evento che NON deve scattare).
 *
 * Gli identificativi passano dagli stessi parser delle sorgenti reali: stesso evento (stesso `at`)
 * = stesso id, e il motore lo deduplica come farebbe in produzione.
 */
class E2eEventFactory(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val devices = DeviceEventParser()
    private val phones = PhoneEventParser()
    private val connectivity = ConnectivityEventParser()
    private var sequence = 0L

    @Synchronized
    private fun uniqueInstant(): Long = nowMillis() + sequence++

    fun build(automation: Automation, payload: JsonObject): E2eEventResult = try {
        buildInternal(automation, payload)
    } catch (_: IllegalArgumentException) {
        E2eEventResult.Invalid("bad_payload")
    }

    private fun buildInternal(automation: Automation, payload: JsonObject): E2eEventResult {
        val type = payload.string("as") ?: triggerTypeOf(automation.trigger)
        val own = automation.trigger.takeIf { triggerTypeOf(it) == type }
        val at = payload.long("at") ?: uniqueInstant()
        val built: TriggerEnvelope? = when (type) {
            "time" -> registered(automation, type, at) { TriggerEvent.TimeFired(automation.id, it) }
            "immediate" -> registered(automation, type, at) { TriggerEvent.ImmediateFired(automation.id, it) }
            "manual" -> registered(automation, type, at) { TriggerEvent.ManualFired(automation.id, it) }
            "geofence" -> {
                val transition = payload.enum<Transition>("transition")
                    ?: (own as? Trigger.Geofence)?.transition ?: Transition.ENTER
                registered(automation, type, at) {
                    TriggerEvent.GeofenceTransitioned(automation.id, transition, it)
                }
            }
            "sensor" -> {
                val kind = payload.enum<SensorKind>("kind")
                    ?: (own as? Trigger.Sensor)?.kind ?: SensorKind.SIGNIFICANT_MOTION
                val fingerprint = automation.approvalFingerprint ?: return E2eEventResult.Invalid("rule_not_approved")
                TriggerEnvelope(
                    SensorEventIds.create(automation.id, fingerprint, kind, at.coerceAtLeast(0)),
                    TriggerEvent.SensorChanged(automation.id, kind, fingerprint),
                )
            }
            "notification" -> {
                val trigger = own as? Trigger.Notification
                val isGroup: Boolean? = if (payload.containsKey("isGroup")) {
                    (payload["isGroup"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.booleanOrNull
                } else {
                    trigger?.isGroup ?: false
                }
                TriggerEnvelope(
                    TriggerEventId("e2e:notification:${automation.id.value}:$at"),
                    TriggerEvent.NotificationPosted(
                        pkg = payload.string("pkg") ?: trigger?.pkg ?: DEFAULT_PACKAGE,
                        conversationId = payload.string("conversationId") ?: trigger?.conversationId,
                        sender = payload.string("sender") ?: trigger?.sender,
                        title = payload.string("title") ?: trigger?.titleMatch ?: "e2e title",
                        text = payload.string("text") ?: trigger?.textMatch ?: "e2e text",
                        isGroup = isGroup,
                        notificationKey = payload.string("key") ?: "e2e-key-${automation.id.value}",
                    ),
                )
            }
            "phone_state" -> {
                val trigger = own as? Trigger.PhoneState
                val event = payload.enum<PhoneEvent>("event") ?: trigger?.event ?: PhoneEvent.SMS_RECEIVED
                phones.parse(
                    event = event,
                    number = payload.string("number") ?: trigger?.number ?: DEFAULT_NUMBER,
                    smsText = if (event == PhoneEvent.SMS_RECEIVED) {
                        payload.string("text") ?: trigger?.textMatch ?: "Your code is 123456"
                    } else {
                        null
                    },
                    atMillis = at,
                    sim = if (payload.containsKey("sim")) payload.int("sim") else trigger?.sim,
                )
            }
            "connectivity" -> {
                val trigger = own as? Trigger.Connectivity
                connectivity.parse(
                    medium = payload.enum<ConnMedium>("medium") ?: trigger?.medium ?: ConnMedium.POWER,
                    state = payload.enum<ConnState>("state") ?: trigger?.state ?: ConnState.CONNECTED,
                    name = payload.string("name") ?: trigger?.match,
                    sourceIdentity = payload.string("source") ?: "e2e",
                    atMillis = at,
                )
            }
            "screen" -> devices.screen(
                payload.enum<ScreenEvent>("event") ?: (own as? Trigger.Screen)?.event ?: ScreenEvent.ON,
                at,
            )
            "battery" -> {
                val trigger = own as? Trigger.Battery
                val below = trigger?.below
                val above = trigger?.above
                val (defaultPrevious, defaultCurrent) = when {
                    below != null -> below to below - 1
                    above != null -> above to above + 1
                    else -> 50 to 49
                }
                devices.battery(
                    payload.int("previous") ?: defaultPrevious,
                    payload.int("current") ?: defaultCurrent,
                    at,
                )
            }
            "audio_output" -> {
                val trigger = own as? Trigger.AudioOutput
                val kind = payload.enum<AudioOutputKind>("kind")
                    ?: trigger?.kind?.takeIf { it != AudioOutputKind.ANY } ?: AudioOutputKind.WIRED
                devices.audioOutput(
                    kind,
                    payload.enum<ConnState>("state") ?: trigger?.state ?: ConnState.CONNECTED,
                    payload.string("source") ?: "e2e",
                    at,
                )
            }
            "mode_changed" -> {
                val trigger = own as? Trigger.ModeChanged
                val what = payload.enum<ModeKind>("what") ?: trigger?.what ?: ModeKind.RINGER
                devices.mode(
                    what,
                    payload.string("to") ?: trigger?.to ?: what.allowedValues.first(),
                    payload.string("from"),
                    at,
                )
            }
            "media" -> devices.media(
                payload.enum<MediaPlayState>("state") ?: (own as? Trigger.Media)?.state ?: MediaPlayState.PLAYING,
                at,
            )
            "app_event" -> {
                val trigger = own as? Trigger.AppEvent
                devices.appEvent(
                    payload.string("pkg") ?: trigger?.pkg ?: DEFAULT_PACKAGE,
                    payload.enum<AppEventKind>("event") ?: trigger?.event ?: AppEventKind.OPENED,
                    at,
                )
            }
            else -> return E2eEventResult.Invalid("unknown_event_type")
        }
        return if (built == null) E2eEventResult.Invalid("event_rejected_by_parser") else E2eEventResult.Built(built, type)
    }

    private fun registered(
        automation: Automation,
        type: String,
        at: Long,
        make: (ApprovalFingerprint) -> TriggerEvent,
    ): TriggerEnvelope? {
        val fingerprint = automation.approvalFingerprint ?: return null
        return TriggerEnvelope(TriggerEventId("e2e:$type:${automation.id.value}:$at"), make(fingerprint))
    }

    private companion object {
        const val DEFAULT_PACKAGE = "com.example.e2e"
        const val DEFAULT_NUMBER = "+393331234567"
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Enum per nome, senza badare alle maiuscole; assente = null, nome sconosciuto = errore. */
private inline fun <reified E : Enum<E>> JsonObject.enum(key: String): E? {
    val name = string(key) ?: return null
    return enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: throw IllegalArgumentException("valore non valido per $key")
}
