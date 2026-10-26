package dev.argus.engine.brain
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQueryFamily
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.runtime.DeviceState
import kotlinx.coroutines.flow.Flow

data class WhitelistedContact(val displayName: String, val id: String) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 120) {
            "Nome contatto whitelist non valido"
        }
        require(id.isNotBlank() && id.length <= 512 && id.none(Char::isISOControl)) {
            "Conversation ID whitelist non valido"
        }
    }
}

/** Fonte master unica per manifest, approvazione e impostazioni UI. */
interface ContactWhitelistStore {
    suspend fun all(): List<WhitelistedContact>
    fun observeAll(): Flow<List<WhitelistedContact>>
    suspend fun upsert(contact: WhitelistedContact)
    suspend fun remove(conversationId: String)
}

/** Limiti effettivi della policy read-only, pubblicati al compilatore senza inventario di chiavi. */
data class StateReaderLimits(
    val maxQueryNameLength: Int = StateQueryPolicy.MAX_QUERY_NAME_LENGTH,
    val maxSysfsPathLength: Int = StateQueryPolicy.MAX_SYSFS_PATH_LENGTH,
    val maxExpectedLength: Int = StateQueryPolicy.MAX_EXPECTED_LENGTH,
    val timeoutMillis: Long = StateQueryPolicy.QUERY_TIMEOUT_MILLIS,
    val maxOutputBytes: Int = StateQueryPolicy.MAX_QUERY_OUTPUT_BYTES,
    val maxScalarChars: Int = StateQueryPolicy.MAX_SCALAR_CHARS,
)

data class StateReaderManifest(
    val policyVersion: Int = StateQueryPolicy.VERSION,
    val families: List<StateQueryFamily> = emptyList(),
    val limits: StateReaderLimits = StateReaderLimits(),
) {
    init {
        require(policyVersion == StateQueryPolicy.VERSION) { "Policy reader non compatibile" }
        require(families.distinct().size == families.size) { "Famiglie reader duplicate" }
        require(families == StateQueryFamily.entries.filter { it in families }) {
            "Ordine famiglie reader non canonico"
        }
        require(limits == StateReaderLimits()) { "Limiti reader non compatibili" }
    }
}

data class CapabilityManifest(
    val deviceModel: String,
    /** Versione commerciale mostrata all'utente (es. Android 16). */
    val androidVersion: Int,
    /** API level inviato al bridge (es. 36); separato dalla release commerciale. */
    val androidApi: Int,
    val shizukuAvailable: Boolean,
    val grantedPermissions: List<String>,
    val availableTools: List<String>,
    val unavailableTools: Map<String, String>,   // tool -> motivo
    val whitelistedContacts: List<WhitelistedContact>,
    val stateKeys: Map<String, String> = StateKeys.ALL,
    /** Trigger realmente armabili ora (wire name, es. "time", "phone_state.sms").
     *  Vuota nei manifest legacy: la riga non viene emessa. */
    val availableTriggers: List<String> = emptyList(),
    /** Famiglie parametriche realmente disponibili; i parametri restano aperti ma bounded. */
    val stateReaders: StateReaderManifest = StateReaderManifest(),
    /**
     * Lingua dei testi che il compilatore scrive PER l'utente (risposta, nome regola, notifiche,
     * goal): la decide l'app dalla lingua dell'interfaccia, non il modello dagli indizi del
     * contesto. Solo `en` o `it`. Entra nel prompt di compile; la busta del bridge Hermes non la porta.
     */
    val replyLanguage: String = REPLY_LANGUAGE_EN,
) {
    init {
        require(replyLanguage == REPLY_LANGUAGE_EN || replyLanguage == REPLY_LANGUAGE_IT) {
            "Lingua di risposta non valida"
        }
    }

    companion object {
        const val REPLY_LANGUAGE_EN = "en"
        const val REPLY_LANGUAGE_IT = "it"
    }

    /** Solo diagnostica/test: il prompt reale lo costruisce `AgentMessageSupport` in brain-android. */
    fun render(): String = buildString {
        appendLine("DISPOSITIVO: $deviceModel, Android $androidVersion")
        appendLine("SHIZUKU: ${if (shizukuAvailable) "attivo (privilegi shell)" else "NON attivo — le azioni che richiedono privilegi shell non sono disponibili"}")
        appendLine("PERMESSI: ${grantedPermissions.joinToString().ifEmpty { "nessuno" }}")
        if (availableTriggers.isNotEmpty())
            appendLine("TRIGGER DISPONIBILI (usa SOLO questi): ${availableTriggers.joinToString()}")
        appendLine("TOOL DISPONIBILI: ${availableTools.joinToString()}")
        if (unavailableTools.isNotEmpty())
            appendLine("TOOL NON DISPONIBILI: " + unavailableTools.entries.joinToString { "${it.key} (${it.value})" })
        appendLine("CHIAVI STATO (le uniche ammesse in state_equals): " +
            stateKeys.entries.joinToString { "${it.key}=${it.value}" })
        appendLine(
            "READER STATO PARAMETRICI: " + stateReaders.families
                .joinToString { it.wireName }
                .ifEmpty { "nessuno" },
        )
        appendLine("CONTATTI WHITELIST (usa l'id come conversationId nei trigger/reply): " +
            whitelistedContacts.joinToString { "${it.displayName} (id: ${it.id})" }.ifEmpty { "nessuno" })
    }
}

/** Impl Android in P0-B (sonda Shizuku/permessi/Android reali). */
interface CapabilityProbe {
    suspend fun probe(currentState: DeviceState): CapabilityManifest
}
