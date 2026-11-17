package dev.argus.engine.directboot

import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Trigger

/**
 * 0.8, «Attiva anche prima dello sblocco»: which rules may also run after a restart, before the phone is unlocked
 * for the first time. Until then the database and the keys are in credential-encrypted storage, so such a rule
 * runs from a copy kept with weaker protection (device-encrypted storage) by a minimal path that has neither the
 * database, nor Shizuku, nor the network, nor the user.
 *
 * Allow-list, never a deny-list: every trigger, action and condition type is decided in an exhaustive `when`
 * without `else`, so a new type does not compile until somebody decides about it here. A rule qualifies only if
 * it holds no personal data and needs nothing the locked path does not have. The decision is about the rule's
 * content only; whether it is armed is the caller's business.
 */
object DirectBootPolicy {
    /** set_ringer modes a qualifying rule may name: literal values only, never a template. */
    val RINGER_MODES: Set<String> = setOf("normal", "vibrate", "silent")

    /** A qualifying rule is short: a handful of switches, run one after the other. */
    const val MAX_ACTIONS = 16

    /** Same depth bound as the validator. */
    private const val MAX_CONDITION_DEPTH = 8

    // Closed codes of why a rule does not qualify.
    const val NO_FINGERPRINT = "no_fingerprint"
    const val TRIGGER = "trigger"
    const val ACTIONS = "actions"
    const val CONDITIONS = "conditions"
    const val VARIABLES = "variables"
    const val CONFIRM = "confirm"
    const val SPAN = "span"
    const val COOLDOWN = "cooldown"
    const val PROGRAM = "program"

    fun qualifies(automation: Automation): Boolean = reasons(automation).isEmpty()

    /** Closed codes of why [automation] does not qualify; empty = it qualifies. */
    fun reasons(automation: Automation): Set<String> = buildSet {
        if (automation.approvalFingerprint == null) add(NO_FINGERPRINT)
        if (!allowsTrigger(automation.trigger)) add(TRIGGER)
        if (automation.actions.isEmpty() || automation.actions.size > MAX_ACTIONS ||
            !automation.actions.all(::allowsAction)
        ) {
            add(ACTIONS)
        }
        automation.conditions?.let { if (!allowsCondition(it)) add(CONDITIONS) }
        // Program values, «ask me first» and spans with restore need state the locked path does not have.
        if (automation.vars.isNotEmpty()) add(VARIABLES)
        if (automation.confirm != null) add(CONFIRM)
        if (automation.exit != null) add(SPAN)
        // The last fire before the restart is in the database: a pause the locked path could not keep.
        if (automation.cooldownMs != 0L) add(COOLDOWN)
        if (AutomationSchema.requiresP4(automation) || !AutomationSchema.isCompatible(automation)) add(PROGRAM)
    }

    /**
     * Only a repeating clock time: the locked path wakes up with AlarmManager and nothing else. Battery, power and
     * screen carry no personal data but need a running listener (a foreground service) before unlock: not in this
     * version. A one-time time is left out because its consumption lives in the database.
     */
    fun allowsTrigger(trigger: Trigger): Boolean = when (trigger) {
        is Trigger.Time -> trigger.cron != null && trigger.at == null && trigger.afterMs == null &&
            trigger.solar == null
        // Need a running listener before unlock (dynamic receivers): left out of this version.
        is Trigger.Battery,
        is Trigger.Screen,
        is Trigger.Connectivity,
        -> false
        // Location, messages, calls, notifications, apps, calendar, tags, sensors, modes: personal data, readers
        // in credential-encrypted storage or listeners the locked path does not have.
        is Trigger.Geofence,
        is Trigger.Notification,
        is Trigger.PhoneState,
        is Trigger.Sensor,
        is Trigger.AudioOutput,
        is Trigger.ModeChanged,
        is Trigger.Media,
        is Trigger.AppEvent,
        is Trigger.CalendarEvent,
        is Trigger.NfcTag,
        is Trigger.NextAlarm,
        is Trigger.AppUsage,
        is Trigger.UserModeChanged,
        -> false
        // Fire once on arming or by hand: nothing to do after a restart.
        is Trigger.Immediate,
        is Trigger.Manual,
        -> false
    }

    /**
     * Switches that work before unlock with the normal Android managers, with no text, no privileged backend and
     * no activity to start: Do Not Disturb, ringer, volume, flashlight, vibration.
     */
    fun allowsAction(action: Action): Boolean = when (action) {
        is Action.SetDnd,
        is Action.SetVolume,
        is Action.SetFlashlight,
        is Action.Vibrate,
        -> true
        is Action.SetRinger -> action.mode in RINGER_MODES
        // Privileged (Shizuku/root is not there before unlock).
        is Action.SetWifi,
        is Action.SetBluetooth,
        is Action.SetMobileData,
        is Action.SetDarkMode,
        is Action.RunShell,
        is Action.Tap,
        is Action.InputText,
        is Action.WriteSetting,
        is Action.AppControl,
        is Action.SetAirplane,
        is Action.SetLocation,
        is Action.SetNfc,
        is Action.SetBatterySaver,
        is Action.LockScreen,
        is Action.Screenshot,
        is Action.AppPermission,
        -> false
        // Text the user wrote, messages, calls, network, AI, secrets, stored values, modes, questions.
        is Action.ShowNotification,
        is Action.WhatsAppReply,
        is Action.CopyToClipboard,
        is Action.CopyText,
        is Action.Speak,
        is Action.HttpRequest,
        is Action.InvokeLlm,
        is Action.InvokeLlmV2,
        is Action.Ask,
        is Action.SetStored,
        is Action.SetUserMode,
        is Action.NotificationReply,
        is Action.NotificationAction,
        is Action.SendSms,
        is Action.CallNumber,
        is Action.SendIntent,
        is Action.ScreenCall,
        -> false
        // Activities cannot start from a locked background process; settings writes need a grant left out here.
        is Action.LaunchApp,
        is Action.OpenUrl,
        is Action.SetAlarm,
        is Action.SetTimer,
        is Action.OpenSettingsScreen,
        is Action.UiSteps,
        is Action.MediaControl,
        is Action.SetBrightness,
        is Action.SetRotation,
        -> false
        // Programs: waits and branches belong to the full runtime.
        is Action.Wait,
        is Action.If,
        is Action.While,
        -> false
    }

    /** Only the clock: a time window and the day of the week, combined with and/or/not. */
    fun allowsCondition(condition: Condition): Boolean = allowsCondition(condition, 1)

    private fun allowsCondition(condition: Condition, depth: Int): Boolean {
        if (depth > MAX_CONDITION_DEPTH) return false
        return when (condition) {
            is Condition.TimeWindow,
            is Condition.Weekday,
            -> true
            is Condition.And -> condition.all.isNotEmpty() && condition.all.all { allowsCondition(it, depth + 1) }
            is Condition.Or -> condition.any.isNotEmpty() && condition.any.all { allowsCondition(it, depth + 1) }
            is Condition.Not -> allowsCondition(condition.cond, depth + 1)
            // Device state, apps, location, connections, calendar, stored values, modes, program values.
            is Condition.StateEquals,
            is Condition.StateCompare,
            is Condition.AppInForeground,
            is Condition.LocationIn,
            is Condition.WifiConnected,
            is Condition.BluetoothConnected,
            is Condition.HeadsetConnected,
            is Condition.CalendarBusy,
            is Condition.StoredCompare,
            is Condition.UserModeIs,
            is Condition.VarCompare,
            is Condition.BooleanLiteral,
            -> false
        }
    }

    /** The Log's type of an action the locked path runs; null for any other action. */
    fun journalType(action: Action): String? = when (action) {
        is Action.SetDnd -> ActionTypeIds.SET_DND
        is Action.SetRinger -> ActionTypeIds.SET_RINGER
        is Action.SetVolume -> ActionTypeIds.SET_VOLUME
        is Action.SetFlashlight -> ActionTypeIds.SET_FLASHLIGHT
        is Action.Vibrate -> ActionTypeIds.VIBRATE
        else -> null
    }.takeIf { allowsAction(action) }
}

/** Closed codes the feature writes into the Log. */
object BeforeUnlockCodes {
    /** FIRED detail: the rule ran before the first unlock (imported into the Log after unlock). */
    const val FIRED = "before_unlock"

    /** BEFORE_UNLOCK_OFF details: why the option switched itself off. */
    const val OFF_RULE_CHANGED = "rule_changed"
    const val OFF_NOT_QUALIFYING = "not_qualifying"
    val OFF_DETAILS: Set<String> = setOf(OFF_RULE_CHANGED, OFF_NOT_QUALIFYING)
}
