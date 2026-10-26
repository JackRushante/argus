package dev.argus.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuPrivilegedShell(
    context: Context,
    private val gateway: ShizukuGateway,
    scope: CoroutineScope,
) : PrivilegedShell, ShizukuIdentityProbe, AutoCloseable {
    private val transport = ShizukuUserServiceTransport(
        binder = AndroidUserServiceBinder(
            context.applicationContext,
            onServiceLost = { gateway.publishIdentity(null) },
        ),
        gateway = gateway,
    )
    private val queue = PrioritizedPrivilegedShell(transport, scope)

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = queue.run(command, priority, timeoutMillis, maxOutputBytes, executionId)

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        executionId: String?,
    ): ShellResult = queue.runToFile(
        command,
        destination,
        priority,
        timeoutMillis,
        maxOutputBytes,
        executionId,
    )

    override suspend fun probe(): ShizukuIdentity? = withContext(Dispatchers.IO) {
        transport.probeIdentity()
    }

    override fun close() {
        queue.close()
        transport.close()
    }
}

/**
 * Trasporto verso il servizio privilegiato. I fallimenti causati da Shizuku (non dal comando)
 * sono [ShizukuShellException] con codice stabile:
 * - `shizuku_unavailable`: Shizuku non in esecuzione/non autorizzato, bind fallito, servizio caduto;
 * - `shizuku_unsupported`: versione di Shizuku troppo vecchia;
 * - `shizuku_timeout`: il servizio non ha risposto entro [bindTimeoutMillis].
 *
 * Dopo un bind fallito o scaduto un [BindCircuitBreaker] fa fallire subito le richieste per 60 s
 * (con `shizuku_unavailable`) invece di far aspettare a ognuna il timeout del bind.
 */
internal class ShizukuUserServiceTransport(
    private val binder: UserServiceBinder,
    private val gateway: ShizukuGateway,
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val bindTimeoutMillis: Long = USER_SERVICE_BIND_TIMEOUT_MILLIS,
    private val breaker: BindCircuitBreaker = BindCircuitBreaker(clock),
) : ShellTransport, AutoCloseable {
    private val connectMutex = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun execute(request: ShellRequest): ShellResult = withContext(Dispatchers.IO) {
        val remote = connectedService()
        val bundle = try {
            if (request.destination == null) {
                remote.execute(
                    request.command.toTypedArray(),
                    request.timeoutMillis,
                    request.maxOutputBytes,
                )
            } else {
                request.destination.parentFile?.mkdirs()
                ParcelFileDescriptor.open(
                    request.destination,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                ).use { descriptor ->
                    remote.executeToFile(
                        request.command.toTypedArray(),
                        descriptor,
                        request.timeoutMillis,
                        request.maxOutputBytes,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            binder.invalidate(remote)
            gateway.publishIdentity(null)
            throw ShizukuShellException(ShizukuShellException.UNAVAILABLE, error)
        }
        ShellResult(
            exitCode = bundle.getInt(PrivilegedShellUserService.KEY_EXIT_CODE, -127),
            stdout = bundle.getByteArray(PrivilegedShellUserService.KEY_STDOUT) ?: byteArrayOf(),
            stderr = bundle.getByteArray(PrivilegedShellUserService.KEY_STDERR) ?: byteArrayOf(),
            timedOut = bundle.getBoolean(PrivilegedShellUserService.KEY_TIMED_OUT, false),
            truncated = bundle.getBoolean(PrivilegedShellUserService.KEY_TRUNCATED, false),
            errorCode = bundle.getString(PrivilegedShellUserService.KEY_ERROR_CODE),
        )
    }

    /**
     * Collega il servizio se serve (stesso percorso dei comandi: [connectedService], circuito di
     * blocco compreso) e restituisce l'identità con cui gira. Con Shizuku non autorizzato non
     * tenta il collegamento. Qualsiasi fallimento, [ShizukuShellException] compresa, è `null`;
     * solo la cancellazione passa.
     */
    internal suspend fun probeIdentity(): ShizukuIdentity? {
        if (gateway.status() != ShizukuGatewayStatus.AUTHORIZED) return null
        return try {
            connectedService()
            gateway.observeIdentity().value
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun connectedService(): IPrivilegedShellService = connectMutex.withLock {
        check(!closed.get()) { "PrivilegedShell chiusa" }
        binder.liveService()?.let { return@withLock it }
        binder.detach()
        gateway.publishIdentity(null)
        when (gateway.status()) {
            ShizukuGatewayStatus.AUTHORIZED -> Unit
            ShizukuGatewayStatus.UNSUPPORTED ->
                throw ShizukuShellException(ShizukuShellException.UNSUPPORTED)
            ShizukuGatewayStatus.NOT_INSTALLED,
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED,
            -> throw ShizukuShellException(ShizukuShellException.UNAVAILABLE)
        }
        val epoch = gateway.statusEpoch()
        if (breaker.isOpen(epoch)) {
            throw ShizukuShellException(ShizukuShellException.UNAVAILABLE)
        }
        val service = try {
            withTimeoutOrNull(bindTimeoutMillis) { binder.bind() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            breaker.trip(epoch)
            throw ShizukuShellException(ShizukuShellException.UNAVAILABLE, error)
        }
        if (service == null) {
            breaker.trip(epoch)
            throw ShizukuShellException(ShizukuShellException.TIMEOUT)
        }
        breaker.reset()
        // Una lettura per sessione del servizio: l'uid non cambia finché il processo vive.
        gateway.publishIdentity(readIdentity(service))
        service
    }

    private fun readIdentity(service: IPrivilegedShellService): ShizukuIdentity? = try {
        service.uid().takeIf { it >= 0 }?.let { ShizukuIdentity(it) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        binder.close()
        gateway.publishIdentity(null)
    }

    internal companion object {
        const val USER_SERVICE_BIND_TIMEOUT_MILLIS = 15_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
