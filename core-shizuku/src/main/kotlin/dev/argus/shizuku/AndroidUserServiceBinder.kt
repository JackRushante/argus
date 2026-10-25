package dev.argus.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Confine Android del collegamento al servizio privilegiato: tutto ciò che tocca
 * `Shizuku.bindUserService` sta qui, così il transport (timeout, interruttore, identità) si
 * prova su JVM con un binder finto.
 */
internal interface UserServiceBinder : AutoCloseable {
    /** Servizio collegato e ancora vivo, altrimenti `null`. */
    fun liveService(): IPrivilegedShellService?

    /** Scarta e scollega l'eventuale connessione precedente prima di un nuovo bind. */
    fun detach()

    /** Collega il servizio e attende il binder; lancia se il bind fallisce, si annulla con la coroutine. */
    suspend fun bind(): IPrivilegedShellService

    /** Una chiamata a [remote] è fallita: se è ancora il servizio corrente, lo invalida. */
    fun invalidate(remote: IPrivilegedShellService)
}

internal class AndroidUserServiceBinder(
    context: Context,
    /** Chiamata quando il servizio corrente si scollega (processo morto, unbind). */
    private val onServiceLost: () -> Unit,
) : UserServiceBinder {
    private val lifecycleLock = Any()
    @Volatile private var service: IPrivilegedShellService? = null
    @Volatile private var connection: ServiceConnection? = null
    private val closed = AtomicBoolean(false)

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedShellUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("argus_shell")
        .debuggable(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        .version(USER_SERVICE_VERSION)

    override fun liveService(): IPrivilegedShellService? =
        service?.takeIf { it.asBinder().pingBinder() }

    override fun detach() {
        detachConnection()?.let { stale -> unbind(stale, remove = false) }
    }

    override suspend fun bind(): IPrivilegedShellService =
        suspendCancellableCoroutine { continuation ->
            val callbackLock = Any()
            var settled = false
            val candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                    if (binder == null || !binder.pingBinder()) {
                        val shouldClean = synchronized(callbackLock) {
                            if (settled || !continuation.isActive) false else {
                                settled = true
                                continuation.resumeWithException(
                                    IllegalStateException("Binder UserService non valido"),
                                )
                                true
                            }
                        }
                        if (shouldClean) clearConnection(this)
                        unbind(this, remove = false)
                        return
                    }
                    val connected = IPrivilegedShellService.Stub.asInterface(binder)
                    val accepted = synchronized(callbackLock) {
                        if (settled || !continuation.isActive) false else {
                            val canAccept = synchronized(lifecycleLock) {
                                !closed.get() && connection === this
                            }
                            settled = true
                            if (canAccept) {
                                service = connected
                                continuation.resume(connected)
                                true
                            } else {
                                continuation.resumeWithException(
                                    IllegalStateException("Bind UserService annullato"),
                                )
                                false
                            }
                        }
                    }
                    if (!accepted) {
                        unbind(this, remove = false)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    clearConnection(this)
                }
            }

            val accepted = synchronized(lifecycleLock) {
                if (closed.get()) false else {
                    connection = candidate
                    true
                }
            }
            if (!accepted) {
                continuation.resumeWithException(IllegalStateException("PrivilegedShell chiusa"))
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                val shouldClean = synchronized(callbackLock) {
                    if (settled) false else {
                        settled = true
                        true
                    }
                }
                if (shouldClean) {
                    clearConnection(candidate)
                    unbind(candidate, remove = false)
                }
            }
            try {
                Shizuku.bindUserService(serviceArgs, candidate)
            } catch (error: Exception) {
                val shouldClean = synchronized(callbackLock) {
                    if (settled || !continuation.isActive) false else {
                        settled = true
                        continuation.resumeWithException(
                            IllegalStateException("Bind UserService fallito", error),
                        )
                        true
                    }
                }
                if (shouldClean) {
                    clearConnection(candidate)
                    unbind(candidate, remove = false)
                }
            }
        }

    override fun invalidate(remote: IPrivilegedShellService) {
        val stale = synchronized(lifecycleLock) {
            if (service !== remote) return
            service = null
            connection.also { connection = null }
        }
        stale?.let { unbind(it, remove = false) }
        onServiceLost()
    }

    private fun detachConnection(): ServiceConnection? = synchronized(lifecycleLock) {
        service = null
        connection.also { connection = null }
    }

    private fun clearConnection(candidate: ServiceConnection) {
        val cleared = synchronized(lifecycleLock) {
            if (connection === candidate) {
                connection = null
                service = null
                true
            } else {
                false
            }
        }
        if (cleared) onServiceLost()
    }

    private fun unbind(candidate: ServiceConnection, remove: Boolean) {
        runCatching { Shizuku.unbindUserService(serviceArgs, candidate, remove) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        detachConnection()?.let { current -> unbind(current, remove = true) }
        onServiceLost()
    }

    private companion object {
        const val USER_SERVICE_VERSION = 1
    }
}
