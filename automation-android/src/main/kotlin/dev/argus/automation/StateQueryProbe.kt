package dev.argus.automation

import dev.argus.device.ParametricStateReader
import dev.argus.engine.model.Condition
import dev.argus.engine.model.StateValueCoercion
import dev.argus.shizuku.ShizukuGateway
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CancellationException

enum class StateQueryProbeResult { AVAILABLE, UNAVAILABLE, TYPE_MISMATCH }

fun interface StateQueryProbe {
    suspend fun probe(condition: Condition.StateCompare): StateQueryProbeResult
}

/** Probe redatto: restituisce solo esito, mai il valore campione letto dal device. */
class AndroidStateQueryProbe internal constructor(
    private val shizukuStatus: () -> ShizukuGatewayStatus,
    private val readValues: suspend (Condition.StateCompare) -> String?,
) : StateQueryProbe {
    constructor(
        gateway: ShizukuGateway,
        reader: ParametricStateReader,
    ) : this(
        shizukuStatus = gateway::status,
        readValues = { condition ->
            reader.read(setOf(condition.query))[condition.query.canonicalId]
        },
    )

    override suspend fun probe(condition: Condition.StateCompare): StateQueryProbeResult {
        if (
            runCatching { shizukuStatus() == ShizukuGatewayStatus.AUTHORIZED }
                .getOrDefault(false).not()
        ) return StateQueryProbeResult.UNAVAILABLE
        val value = try {
            readValues(condition)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return StateQueryProbeResult.UNAVAILABLE
        return if (StateValueCoercion.compatible(value, condition.valueType)) {
            StateQueryProbeResult.AVAILABLE
        } else {
            StateQueryProbeResult.TYPE_MISMATCH
        }
    }
}

internal val FailClosedStateQueryProbe = StateQueryProbe {
    StateQueryProbeResult.UNAVAILABLE
}
