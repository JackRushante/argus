package dev.argus

import android.content.Intent
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.phone.SmsPart
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate device non distruttivo per il canale SMS: parte da un PDU 3GPP reale del CTS Android,
 * attraversa parser framework, grafo Hilt, ingress, matcher, Room, executor e journal.
 * Non tenta di forgiare il broadcast protetto SMS_RECEIVED: quella consegna resta un gate radio.
 */
@RunWith(AndroidJUnit4::class)
class ArgusPhoneIngressInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            instrumentation.targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun ctsPduFlowsThroughProductionSmsPipeline(): Unit = runBlocking {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(
            Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                putExtra("format", "3gpp")
                putExtra("pdus", arrayOf(hexToBytes(CTS_TEST_PDU)))
            },
        ).orEmpty().filterNotNull()

        assertEquals(1, messages.size)
        assertEquals(CTS_TEST_BODY, messages.single().messageBody)

        val store = services.automationStore()
        var automationId: AutomationId? = null
        try {
            val submission = services.approvalFlow().submit(
                CompileResult(
                    reply = "diagnostic",
                    draft = AutomationDraft(
                        name = "$E2E_AUTOMATION_PREFIX ${System.currentTimeMillis()}",
                        trigger = Trigger.PhoneState(
                            event = PhoneEvent.SMS_RECEIVED,
                            textMatch = CTS_TEST_BODY,
                        ),
                        actions = listOf(Action.CopyToClipboard("(Test)")),
                    ),
                    metaError = null,
                ),
            )
            assertTrue("Draft SMS non pronto: $submission", submission is DraftSubmissionResult.Ready)
            val ready = submission as DraftSubmissionResult.Ready
            assertTrue("Draft SMS non armabile: ${ready.review.draft.issues}", ready.review.canArm)

            val snapshot = ready.review.draft.snapshot
            val arm = services.approvalFlow().arm(
                snapshot.id,
                snapshot.revision,
                snapshot.fingerprint,
            )
            assertTrue("Arm SMS non riuscito: $arm", arm is FlowArmResult.Armed)
            automationId = (arm as FlowArmResult.Armed).automation.id

            services.phoneEventIngress().onSms(
                messages.map {
                    SmsPart(
                        sender = it.originatingAddress,
                        body = it.messageBody,
                        atMillis = it.timestampMillis,
                    )
                },
            )

            val fired = withTimeout(FIRE_TIMEOUT_MILLIS) {
                services.database().auditDao()
                    .observeLogForAutomation(automationId.value, 20)
                    .first { records -> records.any { it.kind == AuditKind.FIRED } }
                    .first { it.kind == AuditKind.FIRED }
            }
            val actions = services.database().executionJournalDao()
                .actions(requireNotNull(fired.executionId))
            assertEquals(1, actions.size)
            assertEquals("copy_to_clipboard", actions.single().actionType)
            assertEquals(ActionJournalOutcome.SUCCEEDED, actions.single().outcome)
        } finally {
            withContext(NonCancellable) {
                automationId?.let { store.delete(it) }
                assertTrue(
                    "Cleanup automazione diagnostica SMS non completato",
                    store.all().none { it.name.startsWith(E2E_AUTOMATION_PREFIX) },
                )
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        // AOSP CTS SmsMessageTest: mittente +14155551212, body "Test".
        const val CTS_TEST_PDU = "07916164260220F0040B914151245584F600006060605130308A04D4F29C0E"
        const val CTS_TEST_BODY = "Test"
        const val E2E_AUTOMATION_PREFIX = "Argus E2E SMS"
        const val FIRE_TIMEOUT_MILLIS = 20_000L
    }
}
