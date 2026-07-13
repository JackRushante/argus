package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class DndMode { OFF, PRIORITY, TOTAL }
enum class ActionTier { DETERMINISTIC, GENERATIVE }

@Serializable
sealed interface Action {
    val tier: ActionTier get() = if (this is InvokeLlm) ActionTier.GENERATIVE else ActionTier.DETERMINISTIC

    @Serializable @SerialName("set_wifi") data class SetWifi(val on: Boolean) : Action
    @Serializable @SerialName("set_bluetooth") data class SetBluetooth(val on: Boolean) : Action
    @Serializable @SerialName("set_dnd") data class SetDnd(val mode: DndMode) : Action
    @Serializable @SerialName("set_ringer") data class SetRinger(val mode: String) : Action
    @Serializable @SerialName("launch_app") data class LaunchApp(val pkg: String) : Action
    @Serializable @SerialName("open_url") data class OpenUrl(val url: String) : Action
    @Serializable @SerialName("show_notification") data class ShowNotification(val title: String, val text: String) : Action
    @Serializable @SerialName("tap") data class Tap(val x: Int, val y: Int) : Action
    @Serializable @SerialName("input_text") data class InputText(val text: String) : Action
    @Serializable @SerialName("whatsapp_reply") data class WhatsAppReply(val text: String) : Action
    @Serializable @SerialName("run_shell") data class RunShell(val cmd: String) : Action

    @Serializable @SerialName("invoke_llm")
    data class InvokeLlm(
        val goal: String,
        val contextSources: List<String>,
        val allowedTools: List<String>,   // MAI shell.run / automation.* (DraftValidator, spec §7)
        val replyTargetSender: Boolean,   // spec §10.4: destinatario vincolato al trigger.sender
        val timeoutMs: Long = 60_000,
    ) : Action
}
