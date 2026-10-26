package dev.argus.shizuku

/**
 * Sonda dell'identità con cui gira il servizio privilegiato di Shizuku.
 *
 * [ShizukuGateway.observeIdentity] resta `null` finché il servizio non è collegato, e il
 * collegamento avviene solo al primo comando privilegiato. La sonda lo collega apposta, con lo
 * stesso percorso dei comandi (circuito di blocco compreso), così Sistema può dire «Shizuku gira
 * come root» prima che una regola esegua qualcosa.
 *
 * [probe] non lancia eccezioni, salvo la cancellazione della coroutine: restituisce `null` se
 * Shizuku non è autorizzato (senza tentare il collegamento), se il collegamento fallisce o scade,
 * o se l'identità non è leggibile.
 */
fun interface ShizukuIdentityProbe {
    suspend fun probe(): ShizukuIdentity?
}
