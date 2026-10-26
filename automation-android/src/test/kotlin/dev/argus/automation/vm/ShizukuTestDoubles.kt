package dev.argus.automation.vm

import dev.argus.shizuku.ShizukuAccess
import dev.argus.shizuku.ShizukuGatewayStatus
import dev.argus.shizuku.ShizukuIdentity
import dev.argus.shizuku.ShizukuIdentityProbe
import dev.argus.shizuku.ShizukuPermissionOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shizuku finto per i ViewModel: stato e identità sono flow che il test cambia a mano, come farebbe
 * il gateway vero quando l'utente risponde nella finestra di Shizuku o il servizio si collega.
 */
internal class FakeShizukuAccess(
    initialStatus: ShizukuGatewayStatus = ShizukuGatewayStatus.NOT_INSTALLED,
    initialIdentity: ShizukuIdentity? = null,
    var outcome: ShizukuPermissionOutcome = ShizukuPermissionOutcome.GRANTED,
) : ShizukuAccess {
    private val statusFlow = MutableStateFlow(initialStatus)
    private val identityFlow = MutableStateFlow(initialIdentity)

    /** Valori di `rationaleShown` con cui il ViewModel ha chiesto il permesso, in ordine. */
    val rationaleRequests = mutableListOf<Boolean>()

    fun setStatus(status: ShizukuGatewayStatus) {
        statusFlow.value = status
    }

    fun setIdentity(identity: ShizukuIdentity?) {
        identityFlow.value = identity
    }

    override fun status(): ShizukuGatewayStatus = statusFlow.value

    override fun observeStatus(): StateFlow<ShizukuGatewayStatus> = statusFlow.asStateFlow()

    override fun observeIdentity(): StateFlow<ShizukuIdentity?> = identityFlow.asStateFlow()

    override suspend fun requestPermissionOutcome(rationaleShown: Boolean): ShizukuPermissionOutcome {
        rationaleRequests += rationaleShown
        return outcome
    }
}

/** Sonda finta: conta le chiamate; [onProbe] può pubblicare l'identità o lanciare. */
internal class FakeShizukuIdentityProbe(
    private val onProbe: suspend () -> ShizukuIdentity? = { null },
) : ShizukuIdentityProbe {
    var calls = 0
        private set

    override suspend fun probe(): ShizukuIdentity? {
        calls++
        return onProbe()
    }
}
