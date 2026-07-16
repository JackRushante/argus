package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.runtime.DeviceState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Smoke LIVE opt-in (P3 #48 S8). Colpisce le API REALI dei provider e stampa l'esito per ciascuno.
 * Le chiavi arrivano SOLO da variabili d'ambiente (`ARGUS_LIVE_*_KEY`) e non compaiono mai nel
 * codice: senza env il test si auto-salta, quindi resta innocuo nel gate normale/CI. Va lanciato a
 * mano dal main loop con le chiavi come env var transitorie; l'esito si legge dal system-out del
 * report JUnit. Non asserisce credito/successo (dipende dall'account): fallisce solo se un transport
 * lancia un'eccezione NON tipizzata (un bug), altrimenti registra SUCCESS o TRANSPORT_ERROR<kind>.
 */
class LiveApiSmokeTest {

    private data class Case(val id: ProviderId, val envVar: String)

    private val cases = listOf(
        Case(ProviderId.OPENAI, "ARGUS_LIVE_OPENAI_KEY"),
        Case(ProviderId.GEMINI, "ARGUS_LIVE_GEMINI_KEY"),
        Case(ProviderId.OPENROUTER, "ARGUS_LIVE_OPENROUTER_KEY"),
        Case(ProviderId.ANTHROPIC, "ARGUS_LIVE_ANTHROPIC_KEY"),
    )

    private val manifest = CapabilityManifest(
        deviceModel = "smoke",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = false,
        grantedPermissions = emptyList(),
        availableTools = listOf("show_notification"),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
    )

    @Test
    fun liveSmoke() = runBlocking {
        val present = cases.filter { !System.getenv(it.envVar).isNullOrBlank() }
        if (present.isEmpty()) {
            println("LIVESMOKE: nessuna ARGUS_LIVE_*_KEY nell'ambiente — smoke saltato")
            return@runBlocking
        }
        for (c in present) {
            val key = System.getenv(c.envVar)
            val spec = ProviderCatalog.spec(c.id)
            // Modello piu' economico per non bruciare credito sui provider che ce l'hanno.
            val model = spec.defaultModels.lastOrNull()
            val config = ProviderConfig(c.id, requireNotNull(spec.defaultBaseUrl), model)
            val transport: AgentTransport = if (c.id == ProviderId.ANTHROPIC) {
                AnthropicMessagesTransport(spec, config, { key }, CliBridgeTransport.defaultClient())
            } else {
                OpenAICompatTransport(
                    providerId = c.id,
                    spec = spec,
                    config = config,
                    apiKey = { key },
                    client = CliBridgeTransport.defaultClient(),
                    allowCleartextForTests = false,
                )
            }
            val outcome = try {
                val r = transport.compile(
                    "crea una regola che ogni giorno alle 15 mi manda una notifica che dice ciao",
                    manifest,
                    DeviceState(),
                )
                "SUCCESS model=$model reply='${r.reply?.take(80)?.replace("\n", " ")}' " +
                    "draft=${r.draft != null} metaError=${r.metaError}"
            } catch (e: TransportException) {
                "TRANSPORT_ERROR kind=${e.kind} status=${e.statusCode} model=$model"
            }
            println("LIVESMOKE ${c.id}: $outcome")
        }
    }
}
