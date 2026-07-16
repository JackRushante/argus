package dev.argus.automation

import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateValueType
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StateQueryProbeTest {
    private val condition = Condition.StateCompare(
        StateQuery.DumpsysField("battery", "voltage"),
        StateValueType.NUMBER,
        CmpOp.GT,
        "4000",
    )

    @Test
    fun `probe returns only redacted availability and type outcomes`() = runTest {
        val available = AndroidStateQueryProbe(
            { ShizukuGatewayStatus.AUTHORIZED },
            { "4200" },
        )
        val wrongType = AndroidStateQueryProbe(
            { ShizukuGatewayStatus.AUTHORIZED },
            { "not-a-number" },
        )
        val missing = AndroidStateQueryProbe(
            { ShizukuGatewayStatus.AUTHORIZED },
            { null },
        )

        assertEquals(StateQueryProbeResult.AVAILABLE, available.probe(condition))
        assertEquals(StateQueryProbeResult.TYPE_MISMATCH, wrongType.probe(condition))
        assertEquals(StateQueryProbeResult.UNAVAILABLE, missing.probe(condition))
    }

    @Test
    fun `unavailable Shizuku blocks before reading and cancellation propagates`() = runTest {
        var reads = 0
        val stopped = AndroidStateQueryProbe(
            { ShizukuGatewayStatus.INSTALLED_NOT_RUNNING },
            { reads++; "4200" },
        )
        assertEquals(StateQueryProbeResult.UNAVAILABLE, stopped.probe(condition))
        assertEquals(0, reads)

        val cancelled = AndroidStateQueryProbe(
            { ShizukuGatewayStatus.AUTHORIZED },
            { throw CancellationException("cancelled") },
        )
        assertFailsWith<CancellationException> { cancelled.probe(condition) }
    }
}
