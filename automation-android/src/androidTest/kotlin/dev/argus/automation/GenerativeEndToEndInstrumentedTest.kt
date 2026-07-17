package dev.argus.automation

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.automation.notification.ActiveNotificationReplyRegistry
import dev.argus.automation.notification.AndroidNotificationReplyGateway
import dev.argus.automation.notification.AndroidNotificationSnapshotFactory
import dev.argus.automation.notification.NotificationIngress
import dev.argus.automation.notification.NotificationReplyHandleFactory
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAuditSink
import dev.argus.data.RoomAutomationStore
import dev.argus.data.RoomContactWhitelistStore
import dev.argus.data.RoomDeferredReplyStore
import dev.argus.data.RoomDraftRepository
import dev.argus.data.RoomExecutionJournal
import dev.argus.data.RoomObservedConversationStore
import dev.argus.device.DeviceController
import dev.argus.device.RingerMode
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.Trigger
import dev.argus.engine.notification.NotificationEventParser
import dev.argus.engine.runtime.ActionCapabilities
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.RevalidatingFirePolicy
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.NewDraft
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E sintetico P1-7 sul device reale (API 36): stessa pipeline del test host, ma con
 * PendingIntent/RemoteInput consegnati davvero dal sistema. Nessun grant globale richiesto
 * o modificato; Hermes non viene contattato (Brain locale).
 */
@RunWith(AndroidJUnit4::class)
class GenerativeEndToEndInstrumentedTest {
    private lateinit var context: Context
    private lateinit var db: ArgusDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var receiver: BroadcastReceiver
    private val delivered = CountDownLatch(1)

    @Volatile
    private var receivedText: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = ArgusDatabase.inMemory(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                receivedText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(RESULT_KEY)
                    ?.toString()
                delivered.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(REPLY_ACTION), Context.RECEIVER_NOT_EXPORTED)
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
        scope.cancel()
        db.close()
    }

    @Test
    fun syntheticNotificationFlowsToRemoteInputAndJournalConverges() {
        val automations = RoomAutomationStore(db.automationDao())
        val journal = RoomExecutionJournal(db.executionJournalDao())
        val whitelist = RoomContactWhitelistStore(db.contactWhitelistDao())
        val registry = ActiveNotificationReplyRegistry()
        val gateway = AndroidNotificationReplyGateway(context, registry)
        val policy = RevalidatingFirePolicy(
            FirePolicySnapshotProvider {
                FirePolicySnapshot(
                    knownTools = AndroidCapabilityProbe.KNOWN_TOOLS,
                    availableCapabilities = setOf(
                        CapabilityIds.TRIGGER_NOTIFICATION,
                        ActionCapabilities.INVOKE_LLM,
                        GenerativeContract.TOOL_WHATSAPP_REPLY,
                    ),
                    whitelistedConversationIds = whitelist.all().mapTo(linkedSetOf()) { it.id },
                )
            },
        )
        val cipher = DeferredReplyCipher(
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> { key } },
        )
        val lane = AndroidGenerativeLane(
            scope = scope,
            journal = journal,
            automations = automations,
            firePolicy = policy,
            brain = LocalBrain(),
            replies = gateway,
            deferredReplies = PersistentDeferredReplySink(
                RoomDeferredReplyStore(db.deferredReplyDao()),
                cipher,
                ttlMillis = 60_000,
            ),
        )
        val engine = Engine(
            store = automations,
            executor = ShizukuActionExecutor(
                InertDeviceController(),
                { _, _, _ -> },
                lane,
                gateway,
                { _, _ -> dev.argus.engine.runtime.ActionResult.Success },
            ),
            evaluator = ConditionEvaluator(java.time.Clock.systemDefaultZone()),
            matcher = TriggerMatcher(),
            firePolicy = policy,
            audit = RoomAuditSink(db.auditDao()),
            journal = journal,
            now = System::currentTimeMillis,
        )
        val ingress = NotificationIngress(
            snapshotFactory = AndroidNotificationSnapshotFactory(),
            parser = NotificationEventParser(),
            handleFactory = NotificationReplyHandleFactory(),
            registry = registry,
            observedConversations = RoomObservedConversationStore(db.observedConversationDao()),
            dispatcher = { envelope -> engine.onTrigger(envelope) { DeviceState() } },
            privacyAccepted = { true },
        )

        val parsed = requireNotNull(ingress.registerPosted(statusBarNotification()))
        val event = parsed.envelope.event as TriggerEvent.NotificationPosted
        val conversationId = requireNotNull(event.conversationId)
        runBlocking {
            whitelist.upsert(WhitelistedContact("Fixture", conversationId))
            val drafts = RoomDraftRepository(db)
            val created =
                (drafts.create(
                    NewDraft(
                        id = DraftId("draft-e2e-device"),
                        automationId = AutomationId("rule-e2e-device"),
                        draft = AutomationDraft(
                            name = "Rispondi sintetico",
                            trigger = Trigger.Notification(
                                pkg = "com.whatsapp",
                                conversationId = conversationId,
                                isGroup = false,
                            ),
                            actions = listOf(
                                Action.InvokeLlm(
                                    goal = "rispondi",
                                    contextSources = listOf("notification"),
                                    allowedTools = listOf("whatsapp_reply"),
                                    replyTargetSender = true,
                                ),
                            ),
                            cooldownMs = 60_000,
                        ),
                        atMillis = 1,
                    ),
                ) as dev.argus.engine.safety.DraftWriteResult.Saved).snapshot
            drafts.arm(created.id, created.revision, created.fingerprint)
            ingress.persistAndDispatch(parsed)
        }

        assertTrue(
            "reply non consegnata dal sistema entro il timeout",
            delivered.await(15, TimeUnit.SECONDS),
        )
        assertEquals(GENERATED_REPLY, receivedText)

        val terminal = awaitTerminalStatus()
        assertEquals(ExecutionStatus.SUCCEEDED, terminal)
    }

    private fun awaitTerminalStatus(): ExecutionStatus? {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val status = runBlocking {
                db.auditDao().recent(10).firstNotNullOfOrNull { it.executionId }?.let {
                    db.executionJournalDao().execution(it)?.status
                }
            }
            if (status != null && status != ExecutionStatus.SUBMITTED &&
                status != ExecutionStatus.RUNNING
            ) {
                return status
            }
            Thread.sleep(50)
        }
        return null
    }

    private fun statusBarNotification(): StatusBarNotification {
        val sender = Person.Builder().setName("Fixture").setUri("tel:+39000").build()
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setShortcutId("shortcut-e2e-device")
            .setStyle(
                Notification.MessagingStyle(Person.Builder().setName("Io").build())
                    .addMessage("messaggio sintetico", 10L, sender),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Rispondi",
                    PendingIntent.getBroadcast(
                        context,
                        81,
                        Intent(REPLY_ACTION).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ),
                ).addRemoteInput(
                    RemoteInput.Builder(RESULT_KEY).setAllowFreeFormInput(true).build(),
                ).build(),
            )
            .build()
            .also { it.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) }
        return StatusBarNotification(
            "com.whatsapp",
            "com.whatsapp",
            88,
            "tag",
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            UserHandle.getUserHandleForUid(Process.myUid()),
            10L,
        )
    }

    private class LocalBrain : Brain {
        override suspend fun compile(
            nl: String,
            manifest: CapabilityManifest,
            state: DeviceState,
        ): CompileResult = error("non usato")

        override suspend fun act(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
        ): ActResult = ActResult(GENERATED_REPLY, null)
    }

    private class InertDeviceController : DeviceController {
        override suspend fun setWifi(on: Boolean, executionId: ExecutionId, priority: Int) = Unit
        override suspend fun setBluetooth(on: Boolean, executionId: ExecutionId, priority: Int) =
            Unit

        override suspend fun setDnd(mode: DndMode, executionId: ExecutionId, priority: Int) = Unit
        override suspend fun setRinger(mode: RingerMode, executionId: ExecutionId, priority: Int) =
            Unit

        override suspend fun launchApp(packageName: String, executionId: ExecutionId, priority: Int) =
            Unit

        override suspend fun openUrl(url: String, executionId: ExecutionId, priority: Int) = Unit
        override suspend fun setAlarm(
            hour: Int,
            minute: Int,
            label: String?,
            skipUi: Boolean,
            executionId: ExecutionId,
            priority: Int,
        ) = Unit
        override suspend fun setTimer(
            seconds: Int,
            label: String?,
            skipUi: Boolean,
            executionId: ExecutionId,
            priority: Int,
        ) = Unit
        override suspend fun openSettingsScreen(
            screen: dev.argus.engine.model.SettingsScreen,
            pkg: String?,
            executionId: ExecutionId,
            priority: Int,
        ) = Unit
        override suspend fun tap(x: Int, y: Int, executionId: ExecutionId, priority: Int) = Unit
        override suspend fun inputText(text: String, executionId: ExecutionId, priority: Int) = Unit
        override suspend fun writeSetting(
            namespace: dev.argus.engine.model.SettingNamespace,
            key: String,
            value: String,
            executionId: ExecutionId,
            priority: Int,
        ) = Unit
    }

    private companion object {
        const val REPLY_ACTION = "dev.argus.automation.test.E2E_REPLY"
        const val RESULT_KEY = "reply_text"
        const val GENERATED_REPLY = "risposta sintetica generata"
    }
}
