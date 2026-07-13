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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.io.File
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
    @Volatile private var service: IPrivilegedShellService? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var closed = false

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
            service = null
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
        check(!closed) { "PrivilegedShell chiusa" }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return@withLock it }
        check(gateway.status() == ShizukuGatewayStatus.AUTHORIZED) {
            "Shizuku non autorizzato"
        }
        bindUserService().also { service = it }
    }

    private suspend fun bindUserService(): IPrivilegedShellService =
        suspendCancellableCoroutine { continuation ->
            val callbackLock = Any()
            var completed = false
            var cancelled = false
            val candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                    if (binder == null || !binder.pingBinder()) {
                        synchronized(callbackLock) {
                            if (!completed && !cancelled && continuation.isActive) {
                                completed = true
                                continuation.resumeWithException(
                                    IllegalStateException("Binder UserService non valido"),
                                )
                            }
                        }
                        runCatching { Shizuku.unbindUserService(serviceArgs, this, false) }
                        return
                    }
                    val connected = IPrivilegedShellService.Stub.asInterface(binder)
                    val accepted = synchronized(callbackLock) {
                        if (completed || cancelled || closed || !continuation.isActive) {
                            false
                        } else {
                            completed = true
                            connection = this
                            service = connected
                            continuation.resume(connected)
                            true
                        }
                    }
                    if (!accepted) {
                        runCatching { Shizuku.unbindUserService(serviceArgs, this, false) }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (connection === this) {
                        connection = null
                        service = null
                    }
                }
            }
            continuation.invokeOnCancellation {
                synchronized(callbackLock) {
                    cancelled = true
                    if (connection === candidate) connection = null
                    service = null
                }
                runCatching { Shizuku.unbindUserService(serviceArgs, candidate, false) }
            }
            try {
                Shizuku.bindUserService(serviceArgs, candidate)
            } catch (_: Exception) {
                synchronized(callbackLock) {
                    if (!completed && !cancelled && continuation.isActive) {
                        completed = true
                        continuation.resumeWithException(
                            IllegalStateException("Bind UserService fallito"),
                        )
                    }
                }
            }
        }

    override fun close() {
        closed = true
        connection?.let { current ->
            runCatching { Shizuku.unbindUserService(serviceArgs, current, true) }
        }
        connection = null
        service = null
    }

    private companion object {
        const val USER_SERVICE_VERSION = 1
    }
}
