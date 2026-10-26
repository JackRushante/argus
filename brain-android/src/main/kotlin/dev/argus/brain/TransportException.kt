package dev.argus.brain

import java.io.IOException

/**
 * Categorie di errore di confine di un [AgentTransport]. Estende le sei categorie storiche del
 * bridge Hermes con RATE_LIMIT e BUDGET, richieste dai provider cloud di Wave 2/3.
 */
enum class TransportErrorKind {
    CONFIGURATION,
    TIMEOUT,
    NETWORK,
    AUTH,
    HTTP,
    PROTOCOL,
    RATE_LIMIT,
    BUDGET,
}

/**
 * Errore di confine con soli metadati sicuri: né body remoto né credenziali nel messaggio.
 * È una [IOException]: le implementazioni [dev.argus.engine.brain.Brain] catturano SOLO questa,
 * lasciando propagare [kotlinx.coroutines.CancellationException] e gli errori di programmazione.
 */
open class TransportException(
    val kind: TransportErrorKind,
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    /**
     * Codice d'errore CHIUSO letto dal bridge Hermes (`^[a-z][a-z0-9_]{0,63}$`), mai il body remoto.
     * Solo diagnostica: la UI lo mostra nel dettaglio tecnico.
     */
    val errorCode: String? = null,
    /**
     * Messaggio d'errore del PROVIDER (già ripulito: max 160 caratteri, niente controlli, chiave
     * mascherata), valorizzato SOLO dal test di connessione (`health()`) per spiegare all'utente cosa
     * correggere. Mai in `message`/`toString`, mai nei log, mai persistito: compare solo nel messaggio
     * di Sistema. compile e act non lo leggono (restano a «soli metadati sicuri»).
     */
    val providerMessage: String? = null,
) : IOException(message, cause)

/**
 * Provider a catalogo ma senza transport in questa build (Wave 1: solo HERMES è cablato).
 * Errore tipizzato, mai stringly: [providerId.wireName] è un identificatore pubblico, non un segreto;
 * né baseUrl né chiavi entrano nel messaggio.
 */
class TransportNotImplementedException(val providerId: ProviderId) : TransportException(
    kind = TransportErrorKind.CONFIGURATION,
    message = "transport not_yet_implemented: ${providerId.wireName}",
)

/** Compat: il codice Hermes esistente continua a compilare invariato. */
typealias BridgeErrorKind = TransportErrorKind
typealias BridgeException = TransportException
