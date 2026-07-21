package dev.argus.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Tipo dichiarato di una variabile P4. */
@Serializable
enum class VarType { TEXT, NUMBER, BOOLEAN }

/** Integrità e riservatezza sono assi distinti (decision record P3 §4). */
@Serializable
enum class IntegrityLabel { CLEAN, TAINTED }

@Serializable
enum class ConfidentialityLabel { PUBLIC, PRIVATE, SECRET }

/** Vocabolario chiuso della provenienza persistibile in audit senza salvare il contenuto. */
@Serializable
enum class ValueProvenance { LITERAL, DEVICE_STATE, NOTIFICATION, SMS, PHONE, MODEL, SHELL, ENGINE }

fun joinIntegrity(a: IntegrityLabel, b: IntegrityLabel): IntegrityLabel =
    if (a == IntegrityLabel.TAINTED || b == IntegrityLabel.TAINTED) IntegrityLabel.TAINTED
    else IntegrityLabel.CLEAN

fun joinConfidentiality(
    a: ConfidentialityLabel,
    b: ConfidentialityLabel,
): ConfidentialityLabel = if (a.ordinal >= b.ordinal) a else b

/**
 * Valore runtime-only. Le trasformazioni propagano il join delle label e l'unione di provenance;
 * regex, parsing, escaping e output del modello non declassificano.
 */
data class VarValue(
    val text: String,
    val type: VarType,
    val integrity: IntegrityLabel,
    val confidentiality: ConfidentialityLabel,
    val provenance: Set<ValueProvenance>,
) {
    /** Il payload può contenere SMS, stato SECRET o output shell/model: mai in log accidentali. */
    override fun toString(): String =
        "VarValue(text=<redacted>, type=$type, integrity=$integrity, " +
            "confidentiality=$confidentiality, provenance=$provenance)"
}

/** Campo del payload del trigger catturabile in una variabile. */
@Serializable
enum class TriggerField {
    /** Corpo testuale del messaggio (SMS o notifica). */
    TEXT,

    /** Titolo della notifica. */
    TITLE,

    /** Solo display name della notifica; mai conversationId/capability token. */
    SENDER,

    /** Numero del chiamante o del mittente SMS. */
    NUMBER,
}

/** Sorgente dichiarata e fingerprintata di una variabile P4. */
@Serializable
sealed interface VarBinding {
    val name: String
    val confidentiality: ConfidentialityLabel

    /** Payload esterno: integrità sempre TAINTED, riservatezza almeno PRIVATE. */
    @Serializable
    @SerialName("trigger_payload")
    data class TriggerPayload(
        override val name: String,
        val field: TriggerField,
        /** RE2/J: primo gruppo non vuoto oppure intero match. */
        val extractionRegex: String? = null,
        override val confidentiality: ConfidentialityLabel,
    ) : VarBinding

    /** Reader locale approvato: integrità CLEAN, tipo/versione/floor P3 espliciti. */
    @Serializable
    @SerialName("state")
    data class State(
        override val name: String,
        val query: StateQuery,
        val valueType: StateValueType,
        val policyVersion: Int,
        override val confidentiality: ConfidentialityLabel,
    ) : VarBinding

    /** Letterale approvato byte-per-byte: integrità CLEAN. */
    @Serializable
    @SerialName("literal")
    data class Literal(
        override val name: String,
        val value: String,
        val varType: VarType,
        override val confidentiality: ConfidentialityLabel,
    ) : VarBinding

    /**
     * Intero casuale generato DAL MOTORE in [0, max): integrità CLEAN (non deriva da input esterno),
     * risolto UNA volta a inizio esecuzione → valore fisso per tutto il run. Non è un segreto, quindi
     * il default di riservatezza è il livello più basso (PUBLIC). Utile per branching (IS_EVEN/IS_ODD)
     * e conteggi. [max] deve essere >= 1 (garantito dal DraftValidator).
     */
    @Serializable
    @SerialName("random_int")
    data class RandomInt(
        override val name: String,
        val max: Int,
        override val confidentiality: ConfidentialityLabel = ConfidentialityLabel.PUBLIC,
    ) : VarBinding

    companion object {
        val NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}

val VarBinding.integrity: IntegrityLabel
    get() = when (this) {
        is VarBinding.Literal, is VarBinding.State, is VarBinding.RandomInt -> IntegrityLabel.CLEAN
        is VarBinding.TriggerPayload -> IntegrityLabel.TAINTED
    }

val VarBinding.declaredType: VarType
    get() = when (this) {
        is VarBinding.Literal -> varType
        is VarBinding.State -> valueType.toVarType()
        is VarBinding.RandomInt -> VarType.NUMBER
        is VarBinding.TriggerPayload -> VarType.TEXT
    }

fun StateValueType.toVarType(): VarType = when (this) {
    StateValueType.TEXT -> VarType.TEXT
    StateValueType.NUMBER -> VarType.NUMBER
    StateValueType.BOOLEAN -> VarType.BOOLEAN
}

fun VarBinding.provenance(trigger: Trigger): Set<ValueProvenance> = when (this) {
    is VarBinding.Literal -> setOf(ValueProvenance.LITERAL)
    is VarBinding.State -> setOf(ValueProvenance.DEVICE_STATE)
    is VarBinding.RandomInt -> setOf(ValueProvenance.ENGINE)
    is VarBinding.TriggerPayload -> when (trigger) {
        is Trigger.Notification -> setOf(ValueProvenance.NOTIFICATION)
        is Trigger.PhoneState -> when (trigger.event) {
            PhoneEvent.SMS_RECEIVED -> setOf(ValueProvenance.SMS)
            PhoneEvent.INCOMING_CALL, PhoneEvent.CALL_ENDED -> setOf(ValueProvenance.PHONE)
        }
        else -> emptySet() // DraftValidator impedisce questa combinazione.
    }
}
