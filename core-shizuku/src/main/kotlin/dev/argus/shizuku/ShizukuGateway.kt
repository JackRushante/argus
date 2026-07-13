package dev.argus.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

enum class ShizukuGatewayStatus {
    NOT_INSTALLED,
    INSTALLED_NOT_RUNNING,
    RUNNING_NOT_AUTHORIZED,
    AUTHORIZED,
    UNSUPPORTED,
}

enum class ShizukuPermissionResult {
    GRANTED,
    DENIED,
    RATIONALE_REQUIRED,
    UNAVAILABLE,
}

internal interface ShizukuApi {
    fun managerInstalled(): Boolean
    fun binderAlive(): Boolean
    fun preV11(): Boolean
    fun permissionGranted(): Boolean
    fun shouldShowPermissionRationale(): Boolean
    fun requestPermission(requestCode: Int)
    fun addBinderReceivedListener(listener: () -> Unit)
    fun removeBinderReceivedListener(listener: () -> Unit)
    fun addBinderDeadListener(listener: () -> Unit)
    fun removeBinderDeadListener(listener: () -> Unit)
    fun addPermissionResultListener(listener: (Int, Int) -> Unit)
    fun removePermissionResultListener(listener: (Int, Int) -> Unit)
}

internal class AndroidShizukuApi(context: Context) : ShizukuApi {
    private val packageManager = context.applicationContext.packageManager
    private val binderListeners =
        ConcurrentHashMap<() -> Unit, Shizuku.OnBinderReceivedListener>()
    private val deadListeners = ConcurrentHashMap<() -> Unit, Shizuku.OnBinderDeadListener>()
    private val permissionListeners =
        ConcurrentHashMap<(Int, Int) -> Unit, Shizuku.OnRequestPermissionResultListener>()

    @Suppress("DEPRECATION")
    override fun managerInstalled(): Boolean = try {
        packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    override fun preV11(): Boolean = runCatching { Shizuku.isPreV11() }.getOrDefault(true)
    override fun permissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    override fun shouldShowPermissionRationale(): Boolean = runCatching {
        Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)
    override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)

    override fun addBinderReceivedListener(listener: () -> Unit) {
        val wrapped = Shizuku.OnBinderReceivedListener(listener)
        binderListeners[listener] = wrapped
        Shizuku.addBinderReceivedListenerSticky(wrapped)
    }

    override fun removeBinderReceivedListener(listener: () -> Unit) {
        binderListeners.remove(listener)?.let(Shizuku::removeBinderReceivedListener)
    }

    override fun addBinderDeadListener(listener: () -> Unit) {
        val wrapped = Shizuku.OnBinderDeadListener(listener)
        deadListeners[listener] = wrapped
        Shizuku.addBinderDeadListener(wrapped)
    }

    override fun removeBinderDeadListener(listener: () -> Unit) {
        deadListeners.remove(listener)?.let(Shizuku::removeBinderDeadListener)
    }

    override fun addPermissionResultListener(listener: (Int, Int) -> Unit) {
        val wrapped = Shizuku.OnRequestPermissionResultListener(listener)
        permissionListeners[listener] = wrapped
        Shizuku.addRequestPermissionResultListener(wrapped)
    }

    override fun removePermissionResultListener(listener: (Int, Int) -> Unit) {
        permissionListeners.remove(listener)?.let(Shizuku::removeRequestPermissionResultListener)
    }

    private companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }
}

class ShizukuGateway internal constructor(
    private val api: ShizukuApi,
) : AutoCloseable {
    constructor(context: Context) : this(AndroidShizukuApi(context))

    private val state = MutableStateFlow(resolveStatus())
    private val binderReceivedListener: () -> Unit = { refresh() }
    private val binderDeadListener: () -> Unit = { refresh() }
    private val permissionResultListener: (Int, Int) -> Unit = { _, _ -> refresh() }

    init {
        api.addBinderReceivedListener(binderReceivedListener)
        api.addBinderDeadListener(binderDeadListener)
        api.addPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun observeStatus(): StateFlow<ShizukuGatewayStatus> = state.asStateFlow()

    fun status(): ShizukuGatewayStatus = resolveStatus().also { state.value = it }

    suspend fun requestPermission(): ShizukuPermissionResult {
        when (status()) {
            ShizukuGatewayStatus.AUTHORIZED -> return ShizukuPermissionResult.GRANTED
            ShizukuGatewayStatus.NOT_INSTALLED,
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            ShizukuGatewayStatus.UNSUPPORTED,
            -> return ShizukuPermissionResult.UNAVAILABLE
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED -> Unit
        }
        if (api.shouldShowPermissionRationale()) {
            return ShizukuPermissionResult.RATIONALE_REQUIRED
        }
        return awaitPermissionResult()
    }

    private suspend fun awaitPermissionResult(): ShizukuPermissionResult =
        suspendCancellableCoroutine { continuation ->
            val requestCode = REQUEST_CODES.incrementAndGet()
            lateinit var listener: (Int, Int) -> Unit
            listener = { resultCode, grantResult ->
                if (resultCode == requestCode && continuation.isActive) {
                    api.removePermissionResultListener(listener)
                    refresh()
                    continuation.resume(
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            ShizukuPermissionResult.GRANTED
                        } else {
                            ShizukuPermissionResult.DENIED
                        },
                    )
                }
            }
            api.addPermissionResultListener(listener)
            continuation.invokeOnCancellation {
                api.removePermissionResultListener(listener)
            }
            try {
                api.requestPermission(requestCode)
            } catch (error: CancellationException) {
                api.removePermissionResultListener(listener)
                throw error
            } catch (_: Exception) {
                api.removePermissionResultListener(listener)
                if (continuation.isActive) continuation.resume(ShizukuPermissionResult.UNAVAILABLE)
            }
        }

    private fun refresh() {
        state.value = resolveStatus()
    }

    private fun resolveStatus(): ShizukuGatewayStatus {
        if (!api.binderAlive()) {
            return if (api.managerInstalled()) {
                ShizukuGatewayStatus.INSTALLED_NOT_RUNNING
            } else {
                ShizukuGatewayStatus.NOT_INSTALLED
            }
        }
        if (api.preV11()) return ShizukuGatewayStatus.UNSUPPORTED
        return if (api.permissionGranted()) {
            ShizukuGatewayStatus.AUTHORIZED
        } else {
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED
        }
    }

    override fun close() {
        api.removeBinderReceivedListener(binderReceivedListener)
        api.removeBinderDeadListener(binderDeadListener)
        api.removePermissionResultListener(permissionResultListener)
    }

    private companion object {
        val REQUEST_CODES = AtomicInteger(7_000)
    }
}
