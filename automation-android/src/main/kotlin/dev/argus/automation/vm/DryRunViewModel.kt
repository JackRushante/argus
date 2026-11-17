package dev.argus.automation.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.dryrun.DryRunRuleReader
import dev.argus.automation.dryrun.DryRunTarget
import dev.argus.engine.dryrun.DryRunAnswer
import dev.argus.engine.dryrun.DryRunEvents
import dev.argus.engine.dryrun.DryRunField
import dev.argus.engine.dryrun.DryRunReport
import dev.argus.engine.dryrun.DryRunRequest
import dev.argus.engine.dryrun.DryRunSample
import dev.argus.engine.dryrun.DryRunSimulator
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.SpanPhase
import dev.argus.ui.model.DryRunFieldUi
import dev.argus.ui.model.DryRunState
import dev.argus.ui.presentation.DryRunRenderMapper
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 0.8 «Prova a secco». Holds the sample event and the simulated answers IN MEMORY only (never the saved state
 * handle, never a store, never a log) and asks the [DryRunSimulator] for one simulated run. It has nothing to act
 * with: the simulator and the rule reader only read.
 */
@HiltViewModel
class DryRunViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val rules: DryRunRuleReader,
    private val simulator: DryRunSimulator,
    private val languageSource: RenderLanguageSource,
) : ViewModel() {
    private val routeId = requireNotNull(savedStateHandle.get<String>("id")) { "Prova a secco senza id" }
    private val mutableState = MutableStateFlow(DryRunState())
    val state: StateFlow<DryRunState> = mutableState.asStateFlow()

    private var target: DryRunTarget? = null
    private var sample = DryRunSample()
    private var answers: Map<String, DryRunAnswer> = emptyMap()
    private var simulateEnd = false
    private var run: Job? = null

    init {
        viewModelScope.launch {
            val loaded = try {
                rules.load(routeId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (loaded == null) {
                mutableState.value = DryRunState(loading = false, missing = true)
                return@launch
            }
            target = loaded
            sample = DryRunEvents.defaults(trigger(loaded), loaded.conversationLabel)
            mutableState.value = DryRunState(ruleName = loaded.automation.name, loading = false).withForm(loaded)
            simulate()
        }
    }

    /** The field changes on screen at once; the run repeats on «Try again», as in the approved design. */
    fun onFieldChange(field: DryRunField, value: String) {
        sample = sample.with(field, value)
        target?.let { loaded -> mutableState.update { it.withForm(loaded) } }
    }

    fun onVerifiedScanChange(verified: Boolean) {
        sample = sample.copy(verifiedScan = verified)
        simulate()
    }

    /** Start or end of a span: the other trigger, its own defaults and fresh answers. */
    fun onSimulateEndChange(end: Boolean) {
        val loaded = target ?: return
        if (end == simulateEnd || loaded.automation.exit == null) return
        simulateEnd = end
        sample = DryRunEvents.defaults(trigger(loaded), loaded.conversationLabel)
        answers = emptyMap()
        simulate()
    }

    fun onRun() = simulate()

    /** A picked answer runs again at once; a typed one shows at once and runs again after a short pause. */
    fun onAnswer(key: String, value: String) {
        answers = answers + (key to DryRunAnswer.Text(value))
        val typed = mutableState.value.steps.any { step -> step.choice?.let { it.key == key && it.freeText } == true }
        if (typed) {
            mutableState.update { current ->
                current.copy(
                    steps = current.steps.map { step ->
                        val choice = step.choice
                        if (choice?.key == key && choice.freeText) step.copy(choice = choice.copy(text = value)) else step
                    },
                )
            }
            simulate(delayMillis = TYPING_PAUSE_MILLIS)
        } else {
            simulate()
        }
    }

    fun onNoAnswer(key: String) {
        answers = answers + (key to DryRunAnswer.NoAnswer)
        simulate()
    }

    private fun simulate(delayMillis: Long = 0) {
        val loaded = target ?: return
        run?.cancel()
        run = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val language = languageSource.current
            val request = DryRunRequest(
                automation = loaded.automation,
                phase = if (simulateEnd) SpanPhase.EXIT else SpanPhase.START,
                sample = sample,
                answers = answers,
                defaultText = language.pick("sample text", "testo di prova"),
                conversationLabel = loaded.conversationLabel,
            )
            val report = try {
                simulator.simulate(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            mutableState.update { current ->
                if (report == null) current else current.withReport(loaded, report, language)
            }
        }
    }

    private fun trigger(loaded: DryRunTarget): Trigger {
        val exit = loaded.automation.exit
        return if (simulateEnd && exit != null) exit.trigger else loaded.automation.trigger
    }

    private fun DryRunState.withForm(loaded: DryRunTarget): DryRunState {
        val trigger = trigger(loaded)
        return copy(
            fields = DryRunEvents.fields(trigger).map { DryRunFieldUi(it, sample.valueOf(it)) },
            span = loaded.automation.exit != null,
            simulateEnd = simulateEnd,
            nfc = DryRunEvents.offersVerifiedScan(trigger),
            verifiedScan = sample.verifiedScan,
        )
    }

    private fun DryRunState.withReport(loaded: DryRunTarget, report: DryRunReport, language: RenderLanguage): DryRunState =
        dryRunState(loaded, report, language, nfcVerified = DryRunEvents.offersVerifiedScan(trigger(loaded)) && sample.verifiedScan)
            .withForm(loaded)
            .copy(ruleName = ruleName.ifEmpty { loaded.automation.name })

    private companion object {
        const val TYPING_PAUSE_MILLIS = 400L
    }
}

/** The screen state of a simulated run (pure: the ViewModel and its tests use it). */
internal fun dryRunState(
    target: DryRunTarget,
    report: DryRunReport,
    language: RenderLanguage,
    nfcVerified: Boolean,
): DryRunState = DryRunState(
    ruleName = target.automation.name,
    loading = false,
    notes = DryRunRenderMapper.notes(report, nfcVerified, language),
    steps = DryRunRenderMapper.steps(
        report,
        language,
        reason = { code -> LogReasonLabels.text(code, language) },
        conversationLabels = target.conversationLabels,
    ),
)

internal fun DryRunSample.valueOf(field: DryRunField): String = when (field) {
    DryRunField.SENDER -> sender
    DryRunField.TITLE -> title
    DryRunField.TEXT -> text
    DryRunField.NUMBER -> number
    DryRunField.NAME -> name
    DryRunField.MODE -> mode
}

internal fun DryRunSample.with(field: DryRunField, value: String): DryRunSample = when (field) {
    DryRunField.SENDER -> copy(sender = value)
    DryRunField.TITLE -> copy(title = value)
    DryRunField.TEXT -> copy(text = value)
    DryRunField.NUMBER -> copy(number = value)
    DryRunField.NAME -> copy(name = value)
    DryRunField.MODE -> copy(mode = value)
}
