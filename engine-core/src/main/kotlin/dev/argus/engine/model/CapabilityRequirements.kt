package dev.argus.engine.model

/** Identificatori stabili persistiti insieme allo snapshot approvato. */
object CapabilityIds {
    const val TRIGGER_TIME = "trigger.time"
    const val TRIGGER_GEOFENCE = "trigger.geofence"
    const val TRIGGER_NOTIFICATION = "trigger.notification"
    const val TRIGGER_PHONE_STATE = "trigger.phone_state"
    const val TRIGGER_CONNECTIVITY = "trigger.connectivity"

    const val STATE_FOREGROUND_APP = "state.foreground_app"
    const val STATE_LOCATION = "state.location"

    const val ACTION_SET_WIFI = "action.set_wifi"
    const val ACTION_SET_BLUETOOTH = "action.set_bluetooth"
    const val ACTION_SET_DND = "action.set_dnd"
    const val ACTION_SET_RINGER = "action.set_ringer"
    const val ACTION_LAUNCH_APP = "action.launch_app"
    const val ACTION_OPEN_URL = "action.open_url"
    const val ACTION_SHOW_NOTIFICATION = "action.show_notification"
    const val ACTION_TAP = "action.tap"
    const val ACTION_INPUT_TEXT = "action.input_text"
    const val ACTION_WHATSAPP_REPLY = "action.whatsapp_reply"
    const val ACTION_RUN_SHELL = "action.run_shell"
    const val ACTION_INVOKE_LLM = "action.invoke_llm"

    fun state(key: String): String = "state.$key"
}

/**
 * Derivazione chiusa dei requisiti runtime. È parte del contratto approvato: se questa
 * mappa o i dati eseguibili cambiano, la regola deve essere rivalidata dall'utente.
 */
object CapabilityRequirements {
    fun derive(
        trigger: Trigger,
        actions: List<Action>,
        conditions: Condition? = null,
    ): Set<String> = buildSet {
        addAll(forTrigger(trigger))
        conditions?.let { addAll(forCondition(it)) }
        actions.forEach { addAll(forAction(it)) }
    }

    private fun forTrigger(trigger: Trigger): Set<String> = when (trigger) {
        is Trigger.Time -> setOf(CapabilityIds.TRIGGER_TIME)
        is Trigger.Geofence -> setOf(CapabilityIds.TRIGGER_GEOFENCE, CapabilityIds.STATE_LOCATION)
        is Trigger.Notification -> setOf(CapabilityIds.TRIGGER_NOTIFICATION)
        is Trigger.PhoneState -> setOf(CapabilityIds.TRIGGER_PHONE_STATE)
        is Trigger.Connectivity -> setOf(CapabilityIds.TRIGGER_CONNECTIVITY)
    }

    private fun forCondition(condition: Condition): Set<String> = when (condition) {
        is Condition.TimeWindow -> emptySet()
        is Condition.StateEquals -> setOf(CapabilityIds.state(condition.key))
        is Condition.AppInForeground -> setOf(CapabilityIds.STATE_FOREGROUND_APP)
        is Condition.LocationIn -> setOf(CapabilityIds.STATE_LOCATION)
        is Condition.And -> condition.all.flatMapTo(linkedSetOf(), ::forCondition)
        is Condition.Or -> condition.any.flatMapTo(linkedSetOf(), ::forCondition)
        is Condition.Not -> forCondition(condition.cond)
    }

    private fun forAction(action: Action): Set<String> = when (action) {
        is Action.SetWifi -> setOf(CapabilityIds.ACTION_SET_WIFI)
        is Action.SetBluetooth -> setOf(CapabilityIds.ACTION_SET_BLUETOOTH)
        is Action.SetDnd -> setOf(CapabilityIds.ACTION_SET_DND)
        is Action.SetRinger -> setOf(CapabilityIds.ACTION_SET_RINGER)
        is Action.LaunchApp -> setOf(CapabilityIds.ACTION_LAUNCH_APP)
        is Action.OpenUrl -> setOf(CapabilityIds.ACTION_OPEN_URL)
        is Action.ShowNotification -> setOf(CapabilityIds.ACTION_SHOW_NOTIFICATION)
        is Action.Tap -> setOf(CapabilityIds.ACTION_TAP)
        is Action.InputText -> setOf(CapabilityIds.ACTION_INPUT_TEXT)
        is Action.WhatsAppReply -> setOf(CapabilityIds.ACTION_WHATSAPP_REPLY)
        is Action.RunShell -> setOf(CapabilityIds.ACTION_RUN_SHELL)
        is Action.InvokeLlm -> buildSet {
            add(CapabilityIds.ACTION_INVOKE_LLM)
            addAll(action.allowedTools)
        }
    }
}
