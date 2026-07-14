package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class DndMode { OFF, PRIORITY, TOTAL }
enum class ActionTier { DETERMINISTIC, GENERATIVE }

/** Discriminatori wire stabili condivisi da JSON, manifest capability e journal. */
object ActionTypeIds {
    const val SET_WIFI = "set_wifi"
    const val SET_BLUETOOTH = "set_bluetooth"
    const val SET_DND = "set_dnd"
    const val SET_RINGER = "set_ringer"
    const val LAUNCH_APP = "launch_app"
    const val OPEN_URL = "open_url"
    const val SHOW_NOTIFICATION = "show_notification"
    const val TAP = "tap"
    const val INPUT_TEXT = "input_text"
    const val WHATSAPP_REPLY = "whatsapp_reply"
    const val RUN_SHELL = "run_shell"
    const val INVOKE_LLM = "invoke_llm"
}

@Serializable
sealed interface Action {
    val tier: ActionTier get() = if (this is InvokeLlm) ActionTier.GENERATIVE else ActionTier.DETERMINISTIC

    @Serializable @SerialName(ActionTypeIds.SET_WIFI) data class SetWifi(val on: Boolean) : Action
    @Serializable @SerialName(ActionTypeIds.SET_BLUETOOTH) data class SetBluetooth(val on: Boolean) : Action
    @Serializable @SerialName(ActionTypeIds.SET_DND) data class SetDnd(val mode: DndMode) : Action
    @Serializable @SerialName(ActionTypeIds.SET_RINGER) data class SetRinger(val mode: String) : Action
    @Serializable @SerialName(ActionTypeIds.LAUNCH_APP) data class LaunchApp(val pkg: String) : Action
    @Serializable @SerialName(ActionTypeIds.OPEN_URL) data class OpenUrl(val url: String) : Action
    @Serializable @SerialName(ActionTypeIds.SHOW_NOTIFICATION) data class ShowNotification(val title: String, val text: String) : Action
    @Serializable @SerialName(ActionTypeIds.TAP) data class Tap(val x: Int, val y: Int) : Action
    @Serializable @SerialName(ActionTypeIds.INPUT_TEXT) data class InputText(val text: String) : Action
    @Serializable @SerialName(ActionTypeIds.WHATSAPP_REPLY) data class WhatsAppReply(val text: String) : Action
    @Serializable @SerialName(ActionTypeIds.RUN_SHELL) data class RunShell(val cmd: String) : Action

    @Serializable @SerialName(ActionTypeIds.INVOKE_LLM)
    data class InvokeLlm(
        val goal: String,
        val contextSources: List<String>,
        val allowedTools: List<String>,   // MAI shell.run / automation.* (DraftValidator, spec §7)
        val replyTargetSender: Boolean,   // spec §10.4: destinatario vincolato al trigger.sender
        val timeoutMs: Long = 60_000,
    ) : Action
}
