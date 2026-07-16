package dev.argus.automation

import dev.argus.device.ParametricStateReader
import dev.argus.engine.model.Condition
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueCoercion
import dev.argus.engine.model.StateValueType
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CancellationException

enum class StateQueryProbeResult { AVAILABLE, UNAVAILABLE, TYPE_MISMATCH }

data class StateQueryProbeRequest(
    val query: StateQuery,
    val valueType: StateValueType,
)

fun interface StateQueryProbe {
    suspend fun probe(request: StateQueryProbeRequest): StateQueryProbeResult
}

/** Probe redatto: restituisce solo esito, mai il valore campione letto dal device. */
class AndroidStateQueryProbe internal constructor(
    private val shizukuStatus: () -> ShizukuGatewayStatus,
    private val readValues: suspend (StateQueryProbeRequest) -> String?,
) : StateQueryProbe {
    constructor(
        gateway: ShizukuGateway,
        reader: ParametricStateReader,
    ) : this(
        shizukuStatus = gateway::status,
        readValues = { request ->
            reader.read(setOf(request.query))[request.query.canonicalId]
        },
    )

    override suspend fun probe(request: StateQueryProbeRequest): StateQueryProbeResult {
        if (
            runCatching { shizukuStatus() == ShizukuGatewayStatus.AUTHORIZED }
                .getOrDefault(false).not()
        ) return StateQueryProbeResult.UNAVAILABLE
        val value = try {
            readValues(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return StateQueryProbeResult.UNAVAILABLE
        return if (StateValueCoercion.compatible(value, request.valueType)) {
            StateQueryProbeResult.AVAILABLE
        } else {
            StateQueryProbeResult.TYPE_MISMATCH
        }
    }
}

internal val FailClosedStateQueryProbe = StateQueryProbe {
    StateQueryProbeResult.UNAVAILABLE
}
