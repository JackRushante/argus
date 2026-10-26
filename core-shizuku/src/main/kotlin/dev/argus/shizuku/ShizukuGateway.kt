package dev.argus.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

/**
 * Esito esteso della richiesta di permesso. A differenza di [ShizukuPermissionResult] distingue
 * [AWAITING_USER] (la finestra di Shizuku è ancora aperta e l'utente non ha risposto entro il
 * timeout) da [UNAVAILABLE] (Shizuku assente, spento o troppo vecchio).
 */
enum class ShizukuPermissionOutcome {
    GRANTED,
    DENIED,
    RATIONALE_REQUIRED,
    UNAVAILABLE,
    AWAITING_USER,
    ;

    /** Vista compatibile con i chiamanti che conoscono solo [ShizukuPermissionResult]. */
    fun toResult(): ShizukuPermissionResult = when (this) {
        GRANTED -> ShizukuPermissionResult.GRANTED
        DENIED -> ShizukuPermissionResult.DENIED
        RATIONALE_REQUIRED -> ShizukuPermissionResult.RATIONALE_REQUIRED
        UNAVAILABLE, AWAITING_USER -> ShizukuPermissionResult.UNAVAILABLE
    }
}

/**
 * Identità con cui gira il servizio privilegiato di Shizuku, letta una volta per sessione del
 * servizio. Con Shizuku avviato da adb l'uid è 2000 (shell); su un telefono con root Shizuku può
 * girare come root (uid 0) e allora i comandi delle regole girano come root.
 */
data class ShizukuIdentity(val uid: Int) {
    val runsAsRoot: Boolean get() = uid == ROOT_UID

    companion object {
        const val ROOT_UID = 0
    }
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

/**
 * Superficie di Shizuku che usano i ViewModel: stato, identità del servizio e richiesta del
 * permesso. [ShizukuGateway] è l'unica implementazione di produzione; un'interfaccia permette di
 * provare su JVM la risposta in attesa, il cambio di stato e l'identità senza un Shizuku vero.
 */
interface ShizukuAccess {
    fun status(): ShizukuGatewayStatus

    fun observeStatus(): StateFlow<ShizukuGatewayStatus>

    fun observeIdentity(): StateFlow<ShizukuIdentity?>

    suspend fun requestPermissionOutcome(
        rationaleShown: Boolean = false,
    ): ShizukuPermissionOutcome
}

class ShizukuGateway internal constructor(
    private val api: ShizukuApi,
) : ShizukuAccess, AutoCloseable {
    constructor(context: Context) : this(AndroidShizukuApi(context))

    private val state = MutableStateFlow(resolveStatus())
    private val identityState = MutableStateFlow<ShizukuIdentity?>(null)
    private val publishLock = Any()
    private val epoch = AtomicLong()
    private val binderReceivedListener: () -> Unit = { onBinderEvent() }
    private val binderDeadListener: () -> Unit = { onBinderEvent() }
    private val permissionResultListener: (Int, Int) -> Unit = { _, _ -> refresh() }
    private val permissionRequestMutex = Mutex()

    init {
        api.addBinderReceivedListener(binderReceivedListener)
        api.addBinderDeadListener(binderDeadListener)
        api.addPermissionResultListener(permissionResultListener)
        refresh()
    }

    override fun observeStatus(): StateFlow<ShizukuGatewayStatus> = state.asStateFlow()

    /**
     * Identità del servizio privilegiato collegato, `null` finché il servizio non è collegato
     * (Shizuku spento, non autorizzato, oppure nessuna richiesta privilegiata ha ancora aperto il
     * collegamento in questa sessione). Viene letta una volta per sessione del servizio, dopo il
     * bind, e azzerata quando il servizio o Shizuku si scollegano.
     * `identity.runsAsRoot` è vero quando Shizuku gira come root: i comandi delle regole girano
     * allora come root, non con i permessi shell (uid 2000).
     */
    override fun observeIdentity(): StateFlow<ShizukuIdentity?> = identityState.asStateFlow()

    override fun status(): ShizukuGatewayStatus = resolveStatus().also { publish(it) }

    /**
     * Contatore che avanza a ogni cambio di stato di Shizuku e a ogni evento del binder
     * (anche se lo stato letto resta uguale: un riavvio rapido è comunque un'altra istanza).
     * Chi memorizza un blocco legato a "lo stato di prima" lo azzera quando il contatore cambia.
     */
    internal fun statusEpoch(): Long = epoch.get()

    internal fun publishIdentity(identity: ShizukuIdentity?) {
        identityState.value = identity
    }

    suspend fun requestPermission(
        rationaleShown: Boolean = false,
    ): ShizukuPermissionResult = requestPermissionOutcome(rationaleShown).toResult()

    /**
     * Come [requestPermission] ma distingue il timeout della richiesta
     * ([ShizukuPermissionOutcome.AWAITING_USER]: la finestra di Shizuku può essere ancora aperta)
     * da Shizuku non disponibile. Se l'utente risponde dopo il timeout lo stato si aggiorna da
     * solo: il listener globale dei risultati di permesso resta registrato.
     */
    override suspend fun requestPermissionOutcome(
        rationaleShown: Boolean,
    ): ShizukuPermissionOutcome = permissionRequestMutex.withLock {
        when (status()) {
            ShizukuGatewayStatus.AUTHORIZED -> return@withLock ShizukuPermissionOutcome.GRANTED
            ShizukuGatewayStatus.NOT_INSTALLED,
            ShizukuGatewayStatus.INSTALLED_NOT_RUNNING,
            ShizukuGatewayStatus.UNSUPPORTED,
            -> return@withLock ShizukuPermissionOutcome.UNAVAILABLE
            ShizukuGatewayStatus.RUNNING_NOT_AUTHORIZED -> Unit
        }
        if (api.shouldShowPermissionRationale() && !rationaleShown) {
            return@withLock ShizukuPermissionOutcome.RATIONALE_REQUIRED
        }
        withTimeoutOrNull(PERMISSION_REQUEST_TIMEOUT_MILLIS) {
            awaitPermissionResult()
        } ?: ShizukuPermissionOutcome.AWAITING_USER
    }

    private suspend fun awaitPermissionResult(): ShizukuPermissionOutcome =
        suspendCancellableCoroutine { continuation ->
            val requestCode = REQUEST_CODES.incrementAndGet()
            lateinit var listener: (Int, Int) -> Unit
            listener = { resultCode, grantResult ->
                if (resultCode == requestCode && continuation.isActive) {
                    api.removePermissionResultListener(listener)
                    refresh()
                    continuation.resume(
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            ShizukuPermissionOutcome.GRANTED
                        } else {
                            ShizukuPermissionOutcome.DENIED
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
                if (continuation.isActive) continuation.resume(ShizukuPermissionOutcome.UNAVAILABLE)
            }
        }

    private fun refresh() {
        publish(resolveStatus())
    }

    private fun onBinderEvent() {
        epoch.incrementAndGet()
        identityState.value = null
        refresh()
    }

    private fun publish(next: ShizukuGatewayStatus) {
        synchronized(publishLock) {
            val previous = state.value
            state.value = next
            if (previous != next) {
                epoch.incrementAndGet()
                if (next != ShizukuGatewayStatus.AUTHORIZED) identityState.value = null
            }
        }
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
        const val PERMISSION_REQUEST_TIMEOUT_MILLIS = 30_000L
    }
}
