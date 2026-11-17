package dev.argus.engine.runtime

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Il giro di un `while` in cui sta una domanda: [effective] è il conteggio deciso all'ingresso nel ciclo. */
data class LoopPosition(val iteration: Int, val effective: Int)

/**
 * 0.8 `ask`: dove si è fermato un programma per una domanda e con che cosa, così una risposta arrivata
 * dopo la morte del processo lo fa proseguire da lì ([ProgramInterpreter.resume]). [variables] tiene i
 * valori con le loro label (un dato TAINTED resta TAINTED): chi conserva la continuazione la CIFRA, perché
 * può contenere il testo di un messaggio o un output SECRET. Mai in un log.
 */
class ProgramContinuation(
    val askPath: ActionPath,
    val variables: Map<String, VarValue>,
    /** I `while` attraversati dal percorso della domanda, per path del ciclo. */
    val loops: Map<String, LoopPosition>,
    val generativeCalls: Int,
    /** Inizio dell'esecuzione (orologio del programma): la scadenza di sei ore non riparte. */
    val startedAtMillis: Long,
) {
    override fun toString(): String =
        "ProgramContinuation(askPath=${askPath.value}, variables=${variables.keys}, loops=$loops, <redacted>)"
}

/**
 * Una domanda `ask` la cui risposta (o scadenza) è arrivata quando il programma che la poneva non c'era
 * più. Il motore la fa proseguire solo se la regola è ancora armata con la STESSA impronta.
 */
class ResumedQuestion(
    /** L'identità casuale della domanda: dà l'id evento dell'esecuzione ripresa (un doppio tocco è un duplicato). */
    val questionId: String,
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    /** L'evento che aveva fatto scattare la regola. */
    val envelope: TriggerEnvelope,
    val continuation: ProgramContinuation,
    /** [QuestionOutcome.Answered] o [QuestionOutcome.TimedOut]. */
    val outcome: QuestionOutcome,
) {
    init {
        require(ID.matches(questionId)) { "questionId non valido" }
    }

    val runEventId: TriggerEventId get() = TriggerEventId("$EVENT_PREFIX$questionId")

    override fun toString(): String =
        "ResumedQuestion(questionId=$questionId, automationId=${automationId.value}, <redacted>)"

    companion object {
        const val EVENT_PREFIX = "resumed:"
        val ID = Regex("^[a-z0-9]{16,64}$")
    }
}

/** Forma persistibile di una [ProgramContinuation]: decodifica stretta, mai una continuazione a metà. */
object ProgramContinuationCodec {
    private const val VERSION = 1
    private const val MAX_LOOPS = 8
    private const val MAX_VARIABLES = 16
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(continuation: ProgramContinuation): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "v" to JsonPrimitive(VERSION),
                "askPath" to JsonPrimitive(continuation.askPath.value),
                "variables" to JsonObject(
                    continuation.variables.mapValues { (_, value) ->
                        JsonObject(
                            mapOf(
                                "text" to JsonPrimitive(value.text),
                                "type" to JsonPrimitive(value.type.name),
                                "integrity" to JsonPrimitive(value.integrity.name),
                                "confidentiality" to JsonPrimitive(value.confidentiality.name),
                                "provenance" to JsonArray(value.provenance.map { JsonPrimitive(it.name) }),
                            ),
                        )
                    },
                ),
                "loops" to JsonObject(
                    continuation.loops.mapValues { (_, loop) ->
                        JsonObject(
                            mapOf(
                                "iteration" to JsonPrimitive(loop.iteration),
                                "effective" to JsonPrimitive(loop.effective),
                            ),
                        )
                    },
                ),
                "generativeCalls" to JsonPrimitive(continuation.generativeCalls),
                "startedAt" to JsonPrimitive(continuation.startedAtMillis),
            ),
        ),
    )

    fun decode(text: String): ProgramContinuation? = try {
        parse(json.parseToJsonElement(text).jsonObject)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun parse(o: JsonObject): ProgramContinuation? {
        if (o.long("v") != VERSION.toLong()) return null
        val askPath = o.string("askPath")?.let(::ActionPath) ?: return null
        val variablesJson = o["variables"]?.jsonObject ?: return null
        if (variablesJson.size > MAX_VARIABLES) return null
        val variables = linkedMapOf<String, VarValue>()
        for ((name, element) in variablesJson) {
            if (!VarBinding.NAME_REGEX.matches(name)) return null
            variables[name] = variable(element.jsonObject) ?: return null
        }
        val loopsJson = o["loops"]?.jsonObject ?: return null
        if (loopsJson.size > MAX_LOOPS) return null
        val loops = linkedMapOf<String, LoopPosition>()
        for ((path, element) in loopsJson) {
            val loopPath = ActionPath(path).value
            val loop = element.jsonObject
            val iteration = loop.int("iteration") ?: return null
            val effective = loop.int("effective") ?: return null
            if (iteration < 1 || effective < iteration) return null
            loops[loopPath] = LoopPosition(iteration, effective)
        }
        val generativeCalls = o.int("generativeCalls")?.takeIf { it >= 0 } ?: return null
        val startedAt = o.long("startedAt") ?: return null
        return ProgramContinuation(askPath, variables, loops, generativeCalls, startedAt)
    }

    private fun variable(o: JsonObject): VarValue? {
        val text = o.string("text") ?: return null
        if (text.length > VarScope.MAX_RUNTIME_VALUE_CHARS) return null
        val provenance = o["provenance"]?.jsonArray ?: return null
        return VarValue(
            text = text,
            type = o.enum<VarType>("type") ?: return null,
            integrity = o.enum<IntegrityLabel>("integrity") ?: return null,
            confidentiality = o.enum<ConfidentialityLabel>("confidentiality") ?: return null,
            provenance = provenance.mapTo(linkedSetOf()) { element ->
                element.primitiveString()?.let { name -> ValueProvenance.entries.firstOrNull { it.name == name } }
                    ?: return null
            },
        )
    }

    private fun JsonElement.primitiveString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.string(name: String): String? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        return element.primitiveString()
    }

    private fun JsonObject.int(name: String): Int? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive.isString || primitive is JsonNull) null else primitive.intOrNull
    }

    private fun JsonObject.long(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive.isString || primitive is JsonNull) null else primitive.longOrNull
    }

    private inline fun <reified E : Enum<E>> JsonObject.enum(name: String): E? =
        string(name)?.let { value -> enumValues<E>().firstOrNull { it.name == value } }
}
