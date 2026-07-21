package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.InterpolationPolicy
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarValue
import dev.argus.engine.model.WriteSettingPolicy
import dev.argus.engine.model.joinConfidentiality
import dev.argus.engine.model.joinIntegrity
import dev.argus.engine.safety.SafeExtractionRegex
import java.net.URI

/** Dato runtime non fidato da consegnare al Brain in un messaggio separato dal goal approvato. */
class RuntimeDataBinding internal constructor(
    val token: String,
    val variableName: String,
    val value: VarValue,
) {
    override fun toString(): String =
        "RuntimeDataBinding(token=$token, variableName=$variableName, value=<redacted>)"
}

/** Azione foglia risolta; [runtimeData] non deve mai essere concatenato a un prompt di sistema. */
class ResolvedProgramAction internal constructor(
    val action: Action,
    val runtimeData: List<RuntimeDataBinding>,
    val inputIntegrity: IntegrityLabel,
    val inputConfidentiality: ConfidentialityLabel,
    val inputProvenance: Set<ValueProvenance>,
) {
    override fun toString(): String =
        "ResolvedProgramAction(type=${action::class.simpleName}, runtimeData=${runtimeData.size}, values=<redacted>)"
}

sealed interface ActionResolution {
    data class Resolved(val value: ResolvedProgramAction) : ActionResolution
    data class Blocked(val code: String) : ActionResolution
}

/**
 * Interpolatore P4 a enforcement dinamico. Nei sink locali inserisce il dato; nei campi di
 * autorità rifiuta qualunque TAINTED. Il goal LLM è speciale: il testo TAINTED diventa un marker
 * stabile e viaggia in [RuntimeDataBinding], così non finisce nelle istruzioni di sistema.
 */
class TaintAwareInterpolator {
    fun resolve(action: Action, scope: VarScope): ActionResolution {
        val accumulator = Accumulator(action, scope)
        val resolved = try {
            when (action) {
                is Action.SetWifi,
                is Action.SetBluetooth,
                is Action.SetMobileData,
                is Action.SetDnd,
                is Action.Tap,
                is Action.SetVolume,
                is Action.SetFlashlight,
                is Action.Vibrate,
                is Action.Wait,
                -> action

                is Action.SetRinger -> action.copy(
                    mode = accumulator.field("mode", action.mode, MAX_SHORT_TEXT),
                )
                is Action.LaunchApp -> action.copy(
                    pkg = accumulator.field("pkg", action.pkg, MAX_PACKAGE_CHARS),
                )
                is Action.OpenUrl -> action.copy(
                    url = accumulator.field("url", action.url, MAX_TEXT_CHARS),
                )
                is Action.ShowNotification -> action.copy(
                    title = accumulator.field("title", action.title, MAX_TITLE_CHARS),
                    text = accumulator.field("text", action.text, MAX_TEXT_CHARS),
                )
                is Action.InputText -> action.copy(
                    text = accumulator.field("text", action.text, MAX_TEXT_CHARS),
                )
                is Action.WhatsAppReply -> action.copy(
                    text = accumulator.field("text", action.text, MAX_TEXT_CHARS),
                )
                is Action.RunShell -> action.copy(
                    cmd = accumulator.field("cmd", action.cmd, MAX_COMMAND_CHARS),
                )
                is Action.CopyToClipboard -> action.copy(
                    extractionRegex = action.extractionRegex?.let {
                        accumulator.field("extractionRegex", it, SafeExtractionRegex.MAX_PATTERN_CHARS)
                    },
                )
                is Action.CopyText -> action.copy(
                    text = accumulator.field("text", action.text, MAX_TEXT_CHARS),
                )
                is Action.SetAlarm -> action.copy(
                    label = action.label?.let { accumulator.field("label", it, MAX_TEXT_CHARS) },
                )
                is Action.SetTimer -> action.copy(
                    label = action.label?.let { accumulator.field("label", it, MAX_TEXT_CHARS) },
                )
                is Action.OpenSettingsScreen -> action.copy(
                    pkg = action.pkg?.let { accumulator.field("pkg", it, MAX_PACKAGE_CHARS) },
                )
                is Action.WriteSetting -> action.copy(
                    key = accumulator.field("key", action.key, WriteSettingPolicy.MAX_KEY_LENGTH),
                    value = accumulator.field("value", action.value, WriteSettingPolicy.MAX_VALUE_LENGTH),
                )
                is Action.InvokeLlm -> action.copy(
                    goal = accumulator.field("goal", action.goal, MAX_TEXT_CHARS, frameTaintedForBrain = true),
                    notificationTitle = action.notificationTitle?.let {
                        accumulator.field("notificationTitle", it, MAX_TITLE_CHARS)
                    },
                )
                is Action.InvokeLlmV2 -> action.copy(
                    goal = accumulator.field("goal", action.goal, MAX_TEXT_CHARS, frameTaintedForBrain = true),
                )

                // I contenitori non sono azioni foglia e non devono mai arrivare al runner.
                is Action.If, is Action.While -> throw ResolutionException("control_flow_not_leaf")
            }
        } catch (blocked: ResolutionException) {
            return ActionResolution.Blocked(blocked.code)
        }
        if (!validResolvedShape(resolved)) return ActionResolution.Blocked("resolved_action_invalid")
        return ActionResolution.Resolved(accumulator.result(resolved))
    }

    private class Accumulator(
        private val action: Action,
        private val scope: VarScope,
    ) {
        private val inputs = mutableListOf<VarValue>()
        private val runtimeByVariable = linkedMapOf<String, RuntimeDataBinding>()

        fun field(
            label: String,
            template: String,
            maxChars: Int,
            frameTaintedForBrain: Boolean = false,
        ): String {
            val policyField = InterpolationPolicy.textFields(action).singleOrNull {
                it.label == label && it.value == template
            } ?: throw ResolutionException("interpolation_policy_missing")
            val parsed = InterpolationPolicy.parse(template)
            if (parsed.malformed) throw ResolutionException("interpolation_malformed")
            if (template.length > maxChars) throw ResolutionException("interpolation_too_long")
            if (parsed.refs.isEmpty()) return template

            val rendered = buildString {
                var cursor = 0
                REFERENCE.findAll(template).forEach { match ->
                    append(template, cursor, match.range.first)
                    val name = match.groupValues[1]
                    val value = scope[name] ?: throw ResolutionException("variable_unavailable")
                    if (policyField.cls == InterpolationPolicy.FieldClass.AUTHORITY &&
                        value.integrity != IntegrityLabel.CLEAN
                    ) {
                        throw ResolutionException("taint_blocked")
                    }
                    inputs += value
                    if (frameTaintedForBrain && value.integrity == IntegrityLabel.TAINTED) {
                        val binding = runtimeByVariable.getOrPut(name) {
                            val token = "ARGUS_RUNTIME_DATA_${runtimeByVariable.size + 1}"
                            RuntimeDataBinding(token, name, value)
                        }
                        append("{{").append(binding.token).append("}}")
                    } else {
                        append(value.text)
                    }
                    cursor = match.range.last + 1
                }
                append(template, cursor, template.length)
            }
            if (rendered.length > maxChars) throw ResolutionException("interpolation_too_long")
            return rendered
        }

        fun result(resolved: Action): ResolvedProgramAction {
            val integrity = inputs.fold(IntegrityLabel.CLEAN) { acc, value ->
                joinIntegrity(acc, value.integrity)
            }
            val confidentiality = inputs.fold(ConfidentialityLabel.PUBLIC) { acc, value ->
                joinConfidentiality(acc, value.confidentiality)
            }
            return ResolvedProgramAction(
                action = resolved,
                runtimeData = runtimeByVariable.values.toList(),
                inputIntegrity = integrity,
                inputConfidentiality = confidentiality,
                inputProvenance = inputs.flatMapTo(linkedSetOf()) { it.provenance },
            )
        }
    }

    private class ResolutionException(val code: String) : RuntimeException(null, null, false, false)

    private companion object {
        // Escape di entrambe le graffe: compatibile anche col regex engine ICU di Android.
        val REFERENCE = Regex("""\$\{([a-z][a-z0-9_]{0,31})\}""")
        const val MAX_SHORT_TEXT = 64
        const val MAX_PACKAGE_CHARS = 255
        const val MAX_TITLE_CHARS = 120
        const val MAX_TEXT_CHARS = 4_000
        const val MAX_COMMAND_CHARS = 8_192

        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun validResolvedShape(action: Action): Boolean = when (action) {
            is Action.SetRinger -> action.mode in setOf("normal", "vibrate", "silent")
            is Action.LaunchApp -> PACKAGE_NAME.matches(action.pkg)
            is Action.OpenUrl -> runCatching {
                val uri = URI(action.url)
                uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
            is Action.ShowNotification -> action.title.isNotBlank() &&
                action.title.length <= MAX_TITLE_CHARS && action.text.length <= MAX_TEXT_CHARS
            is Action.InputText -> action.text.isNotBlank() && action.text.length <= MAX_TEXT_CHARS
            is Action.WhatsAppReply -> action.text.isNotBlank() && action.text.length <= MAX_TEXT_CHARS
            is Action.RunShell -> action.cmd.isNotBlank() && action.cmd.length <= MAX_COMMAND_CHARS &&
                '\u0000' !in action.cmd
            is Action.SetAlarm -> action.label == null || action.label.isNotBlank()
            is Action.SetTimer -> action.label == null || action.label.isNotBlank()
            is Action.OpenSettingsScreen -> if (action.screen == SettingsScreen.APP_DETAILS) {
                action.pkg?.let(PACKAGE_NAME::matches) == true
            } else {
                action.pkg == null
            }
            is Action.WriteSetting -> WriteSettingPolicy.valid(action)
            is Action.InvokeLlm -> action.goal.isNotBlank() && action.goal.length <= MAX_TEXT_CHARS &&
                (action.notificationTitle == null || action.notificationTitle.isNotBlank())
            is Action.InvokeLlmV2 -> action.goal.isNotBlank() && action.goal.length <= MAX_TEXT_CHARS
            is Action.CopyToClipboard -> action.extractionRegex == null ||
                SafeExtractionRegex.isValid(action.extractionRegex)
            is Action.CopyText -> action.text.isNotBlank() && action.text.length <= MAX_TEXT_CHARS
            is Action.SetWifi,
            is Action.SetBluetooth,
            is Action.SetMobileData,
            is Action.SetDnd,
            is Action.Tap,
            is Action.SetVolume,
            is Action.SetFlashlight,
            is Action.Vibrate,
            is Action.Wait,
            -> true
            is Action.If, is Action.While -> false
        }
    }
}
