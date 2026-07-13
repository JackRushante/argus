package dev.argus.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuPrivilegedShell(
    context: Context,
    private val gateway: ShizukuGateway,
    scope: CoroutineScope,
) : PrivilegedShell, AutoCloseable {
    private val transport = ShizukuUserServiceTransport(context.applicationContext, gateway)
    private val queue = PrioritizedPrivilegedShell(transport, scope)

    override suspend fun run(
        command: List<String>,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): ShellResult = queue.run(command, priority, timeoutMillis, maxOutputBytes)

    override suspend fun runToFile(
        command: List<String>,
        destination: File,
        priority: Int,
        timeoutMillis: Long,
        maxOutputBytes: Int,
    ): ShellResult = queue.runToFile(
        command,
        destination,
        priority,
        timeoutMillis,
        maxOutputBytes,
    )

    override fun close() {
        queue.close()
        transport.close()
    }
}

internal class ShizukuUserServiceTransport(
    private val context: Context,
    private val gateway: ShizukuGateway,
) : ShellTransport, AutoCloseable {
    private val connectMutex = Mutex()
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
        } catch (_: Exception) {
            invalidate(remote)
            throw IllegalStateException("UserService Shizuku disconnesso")
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

    private suspend fun connectedService(): IPrivilegedShellService = connectMutex.withLock {
        check(!closed.get()) { "PrivilegedShell chiusa" }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return@withLock it }
        detachConnection()?.let { stale -> unbind(stale, remove = false) }
        check(gateway.status() == ShizukuGatewayStatus.AUTHORIZED) {
            "Shizuku non autorizzato"
        }
        withTimeoutOrNull(USER_SERVICE_BIND_TIMEOUT_MILLIS) {
            bindUserService()
        } ?: throw IllegalStateException("Timeout bind UserService Shizuku")
    }

    private suspend fun bindUserService(): IPrivilegedShellService =
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

    private fun invalidate(remote: IPrivilegedShellService) {
        val stale = synchronized(lifecycleLock) {
            if (service !== remote) return
            service = null
            connection.also { connection = null }
        }
        stale?.let { unbind(it, remove = false) }
    }

    private fun detachConnection(): ServiceConnection? = synchronized(lifecycleLock) {
        service = null
        connection.also { connection = null }
    }

    private fun clearConnection(candidate: ServiceConnection) {
        synchronized(lifecycleLock) {
            if (connection === candidate) {
                connection = null
                service = null
            }
        }
    }

    private fun unbind(candidate: ServiceConnection, remove: Boolean) {
        runCatching { Shizuku.unbindUserService(serviceArgs, candidate, remove) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        detachConnection()?.let { current -> unbind(current, remove = true) }
    }

    private companion object {
        const val USER_SERVICE_VERSION = 1
        const val USER_SERVICE_BIND_TIMEOUT_MILLIS = 15_000L
    }
}
