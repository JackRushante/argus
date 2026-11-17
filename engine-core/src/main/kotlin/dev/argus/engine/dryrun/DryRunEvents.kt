package dev.argus.engine.dryrun

import dev.argus.engine.model.AudioOutputKind
import dev.argus.engine.model.Automation
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.SpanPhase
import dev.argus.engine.runtime.TriggerEvent

/**
 * The sample event of a dry run: which fields the form shows for a trigger, their defaults, and the event a real
 * source would deliver for what the user typed. The event goes through the real matcher and policy exactly like
 * a real one: its text is external data wherever the real event's is (the rule's bindings decide the labels).
 */
object DryRunEvents {
    /**
     * A notification key for the sample notification, so the checks that need one (reply, notification
     * action) see a notification that is still there, as a real one would be. It never reaches a gateway.
     */
    const val SAMPLE_NOTIFICATION_KEY = "argus_dry_run"

    /** The conversation of a sample message from someone other than the rule's chat. */
    const val OTHER_CONVERSATION = "argus_dry_run_other"

    /** The fields the user can edit for [trigger]; empty = the trigger has no payload to type (no form). */
    fun fields(trigger: Trigger): List<DryRunField> = when (trigger) {
        is Trigger.Notification -> buildList {
            add(DryRunField.SENDER)
            if (trigger.titleMatch != null) add(DryRunField.TITLE)
            add(DryRunField.TEXT)
        }
        is Trigger.PhoneState -> when (trigger.event) {
            PhoneEvent.SMS_RECEIVED -> listOf(DryRunField.NUMBER, DryRunField.TEXT)
            PhoneEvent.INCOMING_CALL, PhoneEvent.CALL_ENDED, PhoneEvent.MISSED_CALL -> listOf(DryRunField.NUMBER)
        }
        is Trigger.Connectivity -> listOf(DryRunField.NAME)
        is Trigger.CalendarEvent -> listOf(DryRunField.TITLE)
        is Trigger.UserModeChanged -> if (trigger.to == null) listOf(DryRunField.MODE) else emptyList()
        is Trigger.Time, Trigger.Immediate, is Trigger.Geofence, is Trigger.Sensor, is Trigger.Screen,
        is Trigger.Battery, is Trigger.AudioOutput, is Trigger.ModeChanged, is Trigger.Media, Trigger.Manual,
        is Trigger.AppEvent, is Trigger.NfcTag, is Trigger.NextAlarm, is Trigger.AppUsage,
        -> emptyList()
    }

    /** Defaults that match the trigger, so the first run shows the rule firing. */
    fun defaults(trigger: Trigger, conversationLabel: String? = null): DryRunSample = when (trigger) {
        is Trigger.Notification -> DryRunSample(
            sender = ruleSender(trigger, conversationLabel),
            title = trigger.titleMatch.orEmpty(),
            text = trigger.textMatch.orEmpty(),
        )
        is Trigger.PhoneState -> DryRunSample(number = trigger.number.orEmpty(), text = trigger.textMatch.orEmpty())
        is Trigger.Connectivity -> DryRunSample(name = trigger.match.orEmpty())
        is Trigger.CalendarEvent -> DryRunSample(title = trigger.titleMatch.orEmpty())
        else -> DryRunSample()
    }

    /** Whether [trigger] is an NFC tag: the form then offers «verified scan». */
    fun offersVerifiedScan(trigger: Trigger): Boolean = trigger is Trigger.NfcTag

    /**
     * The event for [trigger] built from [sample]. [automation] must carry its approval fingerprint (the events a
     * source registers per rule carry it); [phase] is the part of a span rule the event is for.
     */
    fun event(
        automation: Automation,
        trigger: Trigger,
        phase: SpanPhase,
        sample: DryRunSample,
        conversationLabel: String? = null,
    ): TriggerEvent {
        val id = automation.id
        val fingerprint = requireNotNull(automation.approvalFingerprint) { "Regola simulata senza impronta" }
        return when (trigger) {
            is Trigger.Notification -> TriggerEvent.NotificationPosted(
                pkg = trigger.pkg,
                // Same sender as the rule's chat = the rule's conversation; anyone else = another conversation.
                conversationId = trigger.conversationId?.let { approved ->
                    if (sample.sender.trim() == ruleSender(trigger, conversationLabel).trim()) approved else OTHER_CONVERSATION
                },
                sender = sample.sender.ifEmpty { null },
                title = sample.title.ifEmpty { null },
                text = sample.text,
                isGroup = trigger.isGroup ?: false,
                notificationKey = SAMPLE_NOTIFICATION_KEY,
            )
            is Trigger.PhoneState -> TriggerEvent.PhoneStateChanged(
                event = trigger.event,
                number = sample.number.ifEmpty { null },
                smsText = if (trigger.event == PhoneEvent.SMS_RECEIVED) sample.text else null,
                sim = trigger.sim,
            )
            is Trigger.Connectivity -> TriggerEvent.ConnectivityChanged(
                trigger.medium,
                trigger.state,
                sample.name.ifEmpty { null },
            )
            is Trigger.Time -> TriggerEvent.TimeFired(id, fingerprint, phase)
            Trigger.Immediate -> TriggerEvent.ImmediateFired(id, fingerprint)
            is Trigger.Geofence -> TriggerEvent.GeofenceTransitioned(id, trigger.transition, fingerprint)
            is Trigger.Sensor -> TriggerEvent.SensorChanged(id, trigger.kind, fingerprint)
            is Trigger.Screen -> TriggerEvent.ScreenChanged(trigger.event)
            is Trigger.Battery -> batteryCrossing(trigger)
            is Trigger.AudioOutput -> TriggerEvent.AudioOutputChanged(
                if (trigger.kind == AudioOutputKind.ANY) AudioOutputKind.WIRED else trigger.kind,
                trigger.state,
            )
            is Trigger.ModeChanged -> TriggerEvent.DeviceModeChanged(
                trigger.what,
                trigger.to ?: trigger.what.allowedValues.sorted().first(),
                from = null,
            )
            is Trigger.Media -> TriggerEvent.MediaPlaybackChanged(trigger.state)
            Trigger.Manual -> TriggerEvent.ManualFired(id, fingerprint)
            is Trigger.AppEvent -> TriggerEvent.AppForegroundChanged(trigger.pkg, trigger.event)
            is Trigger.CalendarEvent -> TriggerEvent.CalendarEventFired(
                id,
                trigger.phase,
                fingerprint,
                title = sample.title.ifEmpty { null },
            )
            is Trigger.NfcTag -> TriggerEvent.NfcTagScanned(trigger.tagId, verified = sample.verifiedScan)
            is Trigger.NextAlarm -> TriggerEvent.NextAlarmFired(id, fingerprint, phase)
            is Trigger.AppUsage -> TriggerEvent.AppUsageChanged(
                trigger.pkg,
                previousMinutes = (trigger.minutes - 1).coerceAtLeast(0),
                currentMinutes = trigger.minutes,
            )
            is Trigger.UserModeChanged -> TriggerEvent.UserModeChanged(
                to = trigger.to ?: sample.mode,
                from = null,
            )
        }
    }

    private fun ruleSender(trigger: Trigger.Notification, conversationLabel: String?): String =
        conversationLabel ?: trigger.sender.orEmpty()

    /** A level that crosses the threshold, like the event that makes the rule fire. */
    private fun batteryCrossing(trigger: Trigger.Battery): TriggerEvent.BatteryLevelChanged {
        val below = trigger.below
        val above = trigger.above
        return when {
            below != null && above == null -> TriggerEvent.BatteryLevelChanged(below, below - 1)
            above != null && below == null -> TriggerEvent.BatteryLevelChanged(above, above + 1)
            // A malformed threshold never fires; the event says so to the matcher.
            else -> TriggerEvent.BatteryLevelChanged(0, 0)
        }
    }
}
