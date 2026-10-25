package dev.argus.shizuku

/**
 * Fallimento della shell privilegiata causato da Shizuku stesso (non dal comando eseguito).
 * [code] è un codice stabile, privo di contenuti utente, che arriva fino al journal come
 * `ActionResult.Failure(code)`.
 */
class ShizukuShellException(
    val code: String,
    cause: Throwable? = null,
) : IllegalStateException(code, cause) {
    companion object {
        /** Shizuku non è in esecuzione, non è autorizzato o il servizio non si collega. */
        const val UNAVAILABLE = "shizuku_unavailable"

        /** La versione di Shizuku è troppo vecchia per il binder user service. */
        const val UNSUPPORTED = "shizuku_unsupported"

        /** Il servizio non ha risposto in tempo (bind scaduto o comando oltre il timeout). */
        const val TIMEOUT = "shizuku_timeout"
    }
}
