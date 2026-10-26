package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** B14: il Log deve mostrare il vero motivo, ma solo se e' un codice chiuso e non testo libero. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomAuditSinkTest {
    private lateinit var db: ArgusDatabase
    private lateinit var sink: RoomAuditSink
    private var counter = 0

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        sink = RoomAuditSink(db.auditDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun stored(kind: AuditKind, detail: String): String {
        val id = AutomationId("sink-${counter++}")
        sink.record(AuditEvent(id, kind, 100, detail = detail))
        return db.auditDao().forAutomation(id.value).single().detail
    }

    private val reasonKinds = mapOf(
        AuditKind.ERROR to "execution_error",
        AuditKind.BLOCKED_POLICY to "policy_blocked",
        AuditKind.ARM_FAILED to "arm_failed",
        AuditKind.SCHEDULING_FAILED to "scheduling_failed",
        AuditKind.ENABLE_FAILED to "enable_failed",
    )

    @Test
    fun `reason code shaped details survive for every failure kind`() = runTest {
        val codes = listOf(
            "foreground_service_denied",
            "capability_requirements_mismatch",
            "condition_state_unavailable",
            "invalid_program",
            "shizuku_timeout",
            "a",
            "a" + "b".repeat(63), // 64 caratteri: ancora nella forma chiusa
        )
        for (kind in reasonKinds.keys) {
            for (code in codes) {
                assertEquals(code, stored(kind, code), "$kind $code")
            }
        }
    }

    @Test
    fun `details outside the closed form fall back to the generic code of the kind`() = runTest {
        val shapes = listOf(
            "john@example.org: token segreto",
            "Casa di Mario",
            "IllegalStateException",
            "9_starts_with_digit",
            "_starts_with_underscore",
            "with-dash",
            "has space",
            "http://leak.example/x",
            "+39 333 123 4567",
            "",
            "a" + "b".repeat(64), // 65 caratteri: troppo lungo
        )
        for ((kind, generic) in reasonKinds) {
            for (shape in shapes) {
                assertEquals(generic, stored(kind, shape), "$kind '$shape'")
            }
        }
    }

    @Test
    fun `conditions not met keeps only the unavailable state code`() = runTest {
        assertEquals(
            "condition_state_unavailable",
            stored(AuditKind.CONDITIONS_NOT_MET, "condition_state_unavailable"),
        )
        assertEquals("", stored(AuditKind.CONDITIONS_NOT_MET, ""))
        assertEquals("", stored(AuditKind.CONDITIONS_NOT_MET, "some_other_code"))
        assertEquals("", stored(AuditKind.CONDITIONS_NOT_MET, "stato di Mario non leggibile"))
    }

    @Test
    fun `budget keeps the unpriced model detail as MeteredBrain emits it`() = runTest {
        assertEquals("unpriced_model:openrouter", stored(AuditKind.SUPPRESSED_BUDGET, "unpriced_model:openrouter"))
        assertEquals(
            "unpriced_model:custom_openai_compat",
            stored(AuditKind.SUPPRESSED_BUDGET, "unpriced_model:custom_openai_compat"),
        )
        // I limiti di budget di oggi restano invariati.
        assertEquals("month_cost:openai", stored(AuditKind.SUPPRESSED_BUDGET, "month_cost:openai"))
        assertEquals("hour:global", stored(AuditKind.SUPPRESSED_BUDGET, "hour:global"))
    }

    @Test
    fun `budget still drops free text and malformed unpriced details`() = runTest {
        assertEquals("", stored(AuditKind.SUPPRESSED_BUDGET, "unpriced_model:"))
        assertEquals("", stored(AuditKind.SUPPRESSED_BUDGET, "unpriced_model:Mario Rossi"))
        assertEquals("", stored(AuditKind.SUPPRESSED_BUDGET, "unpriced_model:openrouter/gpt-x"))
        assertEquals("", stored(AuditKind.SUPPRESSED_BUDGET, "unpriced_model:" + "a".repeat(40)))
        assertEquals("", stored(AuditKind.SUPPRESSED_BUDGET, "http://leak.example/token?x=segreto"))
        assertEquals("", stored(AuditKind.SUPPRESSED_BUDGET, "budget esaurito di Mario"))
    }
}
