package dev.argus.engine.runtime

import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.MediaPlayState
import dev.argus.engine.model.ModeKind
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenEvent
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Transition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Forma persistibile di un [TriggerEnvelope] (0.8 `confirm`): l'evento che ha fatto scattare una regola
 * deve sopravvivere alla morte del processo finché l'utente risponde. Il testo può contenere un messaggio:
 * chi lo conserva lo CIFRA (qui solo la forma). La decodifica è stretta: un tipo o un campo che non
 * torna dà null, mai un evento parziale.
 */
object TriggerEnvelopeCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(envelope: TriggerEnvelope): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(mapOf("id" to JsonPrimitive(envelope.id.value), "event" to encodeEvent(envelope.event))),
    )

    fun decode(text: String): TriggerEnvelope? = try {
        val root = json.parseToJsonElement(text).jsonObject
        val id = root.string("id") ?: return null
        val event = decodeEvent(root["event"]?.jsonObject ?: return null) ?: return null
        TriggerEnvelope(TriggerEventId(id), event)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun encodeEvent(event: TriggerEvent): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        fun put(name: String, value: String?) {
            fields[name] = value?.let(::JsonPrimitive) ?: JsonNull
        }
        fun put(name: String, value: Int?) {
            fields[name] = value?.let(::JsonPrimitive) ?: JsonNull
        }
        fun put(name: String, value: Boolean?) {
            fields[name] = value?.let(::JsonPrimitive) ?: JsonNull
        }
        fun registered(type: String, event: TriggerEvent.Registered) {
            put("type", type)
            put("automationId", event.automationId.value)
            put("fingerprint", event.approvalFingerprint.value)
        }
        when (event) {
            is TriggerEvent.TimeFired -> registered("time", event)
            is TriggerEvent.ImmediateFired -> registered("immediate", event)
            is TriggerEvent.ManualFired -> registered("manual", event)
            is TriggerEvent.NextAlarmFired -> registered("next_alarm", event)
            is TriggerEvent.GeofenceTransitioned -> {
                registered("geofence", event)
                put("transition", event.transition.name)
            }
            is TriggerEvent.SensorChanged -> {
                registered("sensor", event)
                put("kind", event.kind.name)
            }
            is TriggerEvent.CalendarEventFired -> {
                registered("calendar", event)
                put("phase", event.phase.name)
                put("title", event.title)
            }
            is TriggerEvent.NotificationPosted -> {
                put("type", "notification")
                put("pkg", event.pkg)
                put("conversationId", event.conversationId)
                put("sender", event.sender)
                put("title", event.title)
                put("text", event.text)
                put("isGroup", event.isGroup)
                put("notificationKey", event.notificationKey)
            }
            is TriggerEvent.PhoneStateChanged -> {
                put("type", "phone")
                put("event", event.event.name)
                put("number", event.number)
                put("smsText", event.smsText)
                put("sim", event.sim)
            }
            is TriggerEvent.ConnectivityChanged -> {
                put("type", "connectivity")
                put("medium", event.medium.name)
                put("state", event.state.name)
                put("name", event.name)
            }
            is TriggerEvent.ScreenChanged -> {
                put("type", "screen")
                put("event", event.event.name)
            }
            is TriggerEvent.BatteryLevelChanged -> {
                put("type", "battery")
                put("previous", event.previous)
                put("current", event.current)
            }
            is TriggerEvent.AudioOutputChanged -> {
                put("type", "audio_output")
                put("kind", event.kind.name)
                put("state", event.state.name)
            }
            is TriggerEvent.DeviceModeChanged -> {
                put("type", "mode")
                put("what", event.what.name)
                put("to", event.to)
                put("from", event.from)
            }
            is TriggerEvent.MediaPlaybackChanged -> {
                put("type", "media")
                put("state", event.state.name)
            }
            is TriggerEvent.AppForegroundChanged -> {
                put("type", "app_event")
                put("pkg", event.pkg)
                put("event", event.event.name)
            }
            is TriggerEvent.NfcTagScanned -> {
                put("type", "nfc")
                put("tagId", event.tagId)
                // 0.8: una scansione provata dal servizio NFC resta tale, e solo lei (vedi decodeEvent).
                put("verified", event.verified)
            }
            is TriggerEvent.AppUsageChanged -> {
                put("type", "app_usage")
                put("pkg", event.pkg)
                put("previousMinutes", event.previousMinutes)
                put("currentMinutes", event.currentMinutes)
            }
            is TriggerEvent.UserModeChanged -> {
                put("type", "user_mode")
                put("to", event.to)
                put("from", event.from)
                // Solo se vero: un cambio non concatenato conserva la forma di prima.
                if (event.chained) put("chained", true)
            }
        }
        return JsonObject(fields)
    }

    private fun decodeEvent(o: JsonObject): TriggerEvent? {
        fun id() = o.string("automationId")?.let(::AutomationId)
        fun fp() = o.string("fingerprint")?.let(::ApprovalFingerprint)
        return when (o.string("type")) {
            "time" -> TriggerEvent.TimeFired(id() ?: return null, fp() ?: return null)
            "immediate" -> TriggerEvent.ImmediateFired(id() ?: return null, fp() ?: return null)
            "manual" -> TriggerEvent.ManualFired(id() ?: return null, fp() ?: return null)
            "next_alarm" -> TriggerEvent.NextAlarmFired(id() ?: return null, fp() ?: return null)
            "geofence" -> TriggerEvent.GeofenceTransitioned(
                id() ?: return null,
                o.enum<Transition>("transition") ?: return null,
                fp() ?: return null,
            )
            "sensor" -> TriggerEvent.SensorChanged(
                id() ?: return null,
                o.enum<SensorKind>("kind") ?: return null,
                fp() ?: return null,
            )
            "calendar" -> TriggerEvent.CalendarEventFired(
                id() ?: return null,
                o.enum<CalendarPhase>("phase") ?: return null,
                fp() ?: return null,
                title = o.string("title"),
            )
            "notification" -> TriggerEvent.NotificationPosted(
                pkg = o.string("pkg") ?: return null,
                conversationId = o.string("conversationId"),
                sender = o.string("sender"),
                title = o.string("title"),
                text = o.string("text"),
                isGroup = o.bool("isGroup"),
                notificationKey = o.string("notificationKey"),
            )
            "phone" -> TriggerEvent.PhoneStateChanged(
                event = o.enum<PhoneEvent>("event") ?: return null,
                number = o.string("number"),
                smsText = o.string("smsText"),
                sim = o.int("sim"),
            )
            "connectivity" -> TriggerEvent.ConnectivityChanged(
                o.enum<ConnMedium>("medium") ?: return null,
                o.enum<ConnState>("state") ?: return null,
                o.string("name"),
            )
            "screen" -> TriggerEvent.ScreenChanged(o.enum<ScreenEvent>("event") ?: return null)
            "battery" -> TriggerEvent.BatteryLevelChanged(o.int("previous") ?: return null, o.int("current") ?: return null)
            "audio_output" -> TriggerEvent.AudioOutputChanged(
                o.enum<AudioOutputKind>("kind") ?: return null,
                o.enum<ConnState>("state") ?: return null,
            )
            "mode" -> TriggerEvent.DeviceModeChanged(
                o.enum<ModeKind>("what") ?: return null,
                o.string("to") ?: return null,
                o.string("from"),
            )
            "media" -> TriggerEvent.MediaPlaybackChanged(o.enum<MediaPlayState>("state") ?: return null)
            "app_event" -> TriggerEvent.AppForegroundChanged(
                o.string("pkg") ?: return null,
                o.enum<AppEventKind>("event") ?: return null,
            )
            // Solo un `true` JSON (quello che scrive encode) rende verificata la scansione; assente o null = non
            // verificata; qualunque altro valore rifiuta l'evento. Chi conserva il testo lo tiene cifrato e
            // autenticato (AES-GCM, chiave del Keystore) nei file privati di Argus.
            "nfc" -> TriggerEvent.NfcTagScanned(
                o.string("tagId") ?: return null,
                verified = when (val flag = o["verified"]) {
                    null, JsonNull -> false
                    else -> (flag as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
                },
            )
            "app_usage" -> TriggerEvent.AppUsageChanged(
                o.string("pkg") ?: return null,
                o.int("previousMinutes") ?: return null,
                o.int("currentMinutes") ?: return null,
            )
            "user_mode" -> TriggerEvent.UserModeChanged(
                o.string("to") ?: return null,
                o.string("from"),
                chained = o.bool("chained") == true,
            )
            else -> null
        }
    }

    private fun JsonObject.string(name: String): String? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element.jsonPrimitive
        return if (primitive.isString) primitive.content else null
    }

    private fun JsonObject.int(name: String): Int? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element.jsonPrimitive
        return if (primitive.isString) null else primitive.intOrNull
    }

    private fun JsonObject.bool(name: String): Boolean? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element.jsonPrimitive
        return if (primitive.isString) null else primitive.booleanOrNull
    }

    private inline fun <reified E : Enum<E>> JsonObject.enum(name: String): E? =
        string(name)?.let { value -> enumValues<E>().firstOrNull { it.name == value } }
}
