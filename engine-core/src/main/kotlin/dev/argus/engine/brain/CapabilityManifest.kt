package dev.argus.engine.brain
import dev.argus.engine.model.StateKeys
import dev.argus.engine.runtime.DeviceState

data class WhitelistedContact(val displayName: String, val id: String)

data class CapabilityManifest(
    val deviceModel: String,
    val androidVersion: Int,
    val shizukuAvailable: Boolean,
    val grantedPermissions: List<String>,
    val availableTools: List<String>,
    val unavailableTools: Map<String, String>,   // tool -> motivo
    val whitelistedContacts: List<WhitelistedContact>,
    val stateKeys: Map<String, String> = StateKeys.ALL,
) {
    fun render(): String = buildString {
        appendLine("DISPOSITIVO: $deviceModel, Android $androidVersion")
        appendLine("SHIZUKU: ${if (shizukuAvailable) "attivo (privilegi shell)" else "NON attivo — azioni shell in coda"}")
        appendLine("PERMESSI: ${grantedPermissions.joinToString().ifEmpty { "nessuno" }}")
        appendLine("TOOL DISPONIBILI: ${availableTools.joinToString()}")
        if (unavailableTools.isNotEmpty())
            appendLine("TOOL NON DISPONIBILI: " + unavailableTools.entries.joinToString { "${it.key} (${it.value})" })
        appendLine("CHIAVI STATO (le uniche ammesse in state_equals): " +
            stateKeys.entries.joinToString { "${it.key}=${it.value}" })
        appendLine("CONTATTI WHITELIST (usa l'id come conversationId nei trigger/reply): " +
            whitelistedContacts.joinToString { "${it.displayName} (id: ${it.id})" }.ifEmpty { "nessuno" })
    }
}

/** Impl Android in P0-B (sonda Shizuku/permessi/Android reali). */
interface CapabilityProbe {
    suspend fun probe(currentState: DeviceState): CapabilityManifest
}
