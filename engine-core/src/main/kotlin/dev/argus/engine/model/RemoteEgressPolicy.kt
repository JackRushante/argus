package dev.argus.engine.model

/**
 * Policy separata dall'integrità e dalla classificazione.
 *
 * La postura generale resta quella decisa per Argus P4: PRIVATE/SECRET possono raggiungere il
 * Brain dopo review esplicita. I segreti credential sono invece non esportabili per costruzione,
 * indipendentemente dalla postura Aggressive e dal provider configurato.
 */
object RemoteEgressPolicy {
    enum class Decision { ALLOW, REQUIRE_REVIEW, DENY }

    fun decide(value: VarValue): Decision = when {
        ValueProvenance.CREDENTIAL in value.provenance -> Decision.DENY
        value.confidentiality == ConfidentialityLabel.SECRET -> Decision.REQUIRE_REVIEW
        else -> Decision.ALLOW
    }

    fun allows(value: VarValue): Boolean = decide(value) != Decision.DENY
}
