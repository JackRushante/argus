package dev.argus.automation

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
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
import dev.argus.engine.safety.DraftArmResult
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E2E sintetico P1-7 (host): la pipeline REALE cucita come in produzione — notifica sintetica →
 * ingress → parser → Engine (claim Room) → lane generativa → Brain locale → gateway RemoteInput →
 * receiver di test → journal CAS — senza toccare Hermes né il device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerativeEndToEndTest {
    private lateinit var context: Context
    private lateinit var db: ArgusDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var receiver: BroadcastReceiver
    private val receivedTexts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val cipher = DeferredReplyCipher(
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> { key } },
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = ArgusDatabase.inMemory(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(RESULT_KEY)
                    ?.toString()
                    ?.let(receivedTexts::add)
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
    fun `synthetic example three sends the generated reply and converges the journal`() {
        val fixture = fixture()
        val posted = fixture.postAndArm()

        // L'Engine ritorna Submitted senza attendere il modello: il claim esiste subito.
        runBlocking(Dispatchers.Default) { fixture.ingress.persistAndDispatch(posted.parsed) }
        assertEquals(
            ExecutionStatus.SUBMITTED,
            awaitNotNull("claim journal") { fixture.executionStatus() },
        )

        awaitUntil("reply consegnata al receiver") { receivedTexts.isNotEmpty() }
        assertEquals(GENERATED_REPLY, receivedTexts.single())
        assertEquals(
            ExecutionStatus.SUCCEEDED,
            awaitNotNull("journal convergente") {
                fixture.executionStatus().takeIf { it != ExecutionStatus.SUBMITTED }
            },
        )

        // La conversazione osservata alimenta il picker whitelist.
        val observed = runBlocking {
            RoomObservedConversationStore(db.observedConversationDao()).recent(10)
        }
        assertEquals(listOf(posted.conversationId), observed.map { it.id })

        // Redelivery dello stesso evento: claim one-shot, nessun secondo invio.
        runBlocking(Dispatchers.Default) { fixture.ingress.persistAndDispatch(posted.parsed) }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, receivedTexts.size)
    }

    @Test
    fun `notification removed during generation defers the encrypted reply`() {
        val brainGate = CompletableDeferred<Unit>()
        val fixture = fixture(actDelay = brainGate)
        val posted = fixture.postAndArm()

        runBlocking(Dispatchers.Default) { fixture.ingress.persistAndDispatch(posted.parsed) }
        assertEquals(
            ExecutionStatus.SUBMITTED,
            awaitNotNull("claim journal") { fixture.executionStatus() },
        )

        // La notifica sparisce mentre il modello sta ancora generando (caso E13 più comune).
        fixture.ingress.remove(posted.notificationKey)
        brainGate.complete(Unit)

        assertEquals(
            ExecutionStatus.DEFERRED,
            awaitNotNull("journal deferred") {
                fixture.executionStatus().takeIf { it != ExecutionStatus.SUBMITTED }
            },
        )
        assertEquals(0, receivedTexts.size, "nessun invio senza canale verificato")

        // Il testo è persistito SOLO cifrato ed è decifrabile per la CTA "Invia ora".
        val row = awaitNotNull("riga deferred") {
            runBlocking {
                RoomDeferredReplyStore(db.deferredReplyDao())
                    .firstActionable(requireNotNull(fixture.lastExecutionId()).value, nowMillis = 2_000)
            }
        }
        assertTrue(GENERATED_REPLY !in row.payload)
        assertEquals(GENERATED_REPLY, cipher.decrypt(row.payload))
    }

    @Test
    fun `group conversations never fire the reply rule`() {
        val fixture = fixture()
        val posted = fixture.postAndArm()
        val groupParsed = assertNotNull(
            fixture.ingress.registerPosted(
                statusBarNotification(requestCode = 9, isGroup = true),
            ),
        )
        assertEquals(
            posted.conversationId,
            (groupParsed.envelope.event as TriggerEvent.NotificationPosted).conversationId,
            "stessa identità di conversazione, metadata gruppo",
        )

        runBlocking(Dispatchers.Default) { fixture.ingress.persistAndDispatch(groupParsed) }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, receivedTexts.size)
        assertNull(fixture.executionStatus(), "nessuna esecuzione per l'evento di gruppo")
    }

    // ------------------------------------------------------------------ fixture

    private inner class Fixture(
        val ingress: NotificationIngress,
        private val journal: RoomExecutionJournal,
        private val automations: RoomAutomationStore,
        private val drafts: RoomDraftRepository,
        private val whitelist: RoomContactWhitelistStore,
    ) {
        fun postAndArm(): PostedRule {
            val parsed = assertNotNull(ingress.registerPosted(statusBarNotification()))
            val event = parsed.envelope.event as TriggerEvent.NotificationPosted
            val conversationId = assertNotNull(event.conversationId)
            runBlocking(Dispatchers.Default) {
                whitelist.upsert(WhitelistedContact("Moglie", conversationId))
                val created = assertIs<DraftWriteResult.Saved>(
                    drafts.create(
                        NewDraft(
                            id = DraftId("draft-e2e"),
                            automationId = dev.argus.engine.model.AutomationId("rule-e2e"),
                            draft = AutomationDraft(
                                name = "Rispondi a Moglie",
                                trigger = Trigger.Notification(
                                    pkg = "com.whatsapp",
                                    conversationId = conversationId,
                                    isGroup = false,
                                ),
                                actions = listOf(
                                    Action.InvokeLlm(
                                        goal = "rispondi con tono cortese",
                                        contextSources = listOf("notification"),
                                        allowedTools = listOf("whatsapp_reply"),
                                        replyTargetSender = true,
                                    ),
                                ),
                                cooldownMs = 60_000,
                            ),
                            atMillis = 1,
                        ),
                    ),
                ).snapshot
                assertIs<DraftArmResult.Armed>(
                    drafts.arm(created.id, created.revision, created.fingerprint),
                )
            }
            return PostedRule(parsed, conversationId, requireNotNull(event.notificationKey))
        }

        fun executionStatus(): ExecutionStatus? = runBlocking {
            lastExecutionId()?.let { db.executionJournalDao().execution(it.value)?.status }
        }

        fun lastExecutionId(): ExecutionId? = runBlocking {
            db.auditDao().recent(20).firstNotNullOfOrNull { record ->
                record.executionId?.let(::ExecutionId)
            }
        }
    }

    private inner class PostedRule(
        val parsed: dev.argus.engine.notification.ParsedNotification,
        val conversationId: String,
        val notificationKey: String,
    )

    private fun fixture(actDelay: CompletableDeferred<Unit>? = null): Fixture {
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
        val brain = object : Brain {
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
            ): ActResult {
                actDelay?.await()
                return ActResult(GENERATED_REPLY, null)
            }
        }
        val lane = AndroidGenerativeLane(
            scope = scope,
            journal = journal,
            automations = automations,
            firePolicy = policy,
            brain = brain,
            replies = gateway,
            deferredReplies = PersistentDeferredReplySink(
                RoomDeferredReplyStore(db.deferredReplyDao()),
                cipher,
                ttlMillis = 60_000,
            ) { 1_500 },
            notifier = { _, _, _ -> },
        )
        val engine = Engine(
            store = automations,
            executor = ShizukuActionExecutor(
                tools = InertDeviceController(),
                notifier = { _, _, _ -> },
                generativeLane = lane,
                replies = gateway,
                clipboard = AndroidClipboardCopier(context),
            ),
            evaluator = ConditionEvaluator(java.time.Clock.systemDefaultZone()),
            matcher = TriggerMatcher(),
            firePolicy = policy,
            audit = RoomAuditSink(db.auditDao()),
            journal = journal,
            now = { 1_000 },
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
        return Fixture(ingress, journal, automations, RoomDraftRepository(db), whitelist)
    }

    private fun statusBarNotification(
        requestCode: Int = 1,
        isGroup: Boolean = false,
        message: String = "amore a che ora torni?",
    ): StatusBarNotification {
        val sender = Person.Builder().setName("Moglie").setUri("tel:+39000").build()
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setShortcutId("shortcut-e2e")
            .setStyle(
                Notification.MessagingStyle(Person.Builder().setName("Io").build())
                    .addMessage(message, 10L, sender),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Rispondi",
                    PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        Intent(REPLY_ACTION).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ),
                ).addRemoteInput(
                    RemoteInput.Builder(RESULT_KEY).setAllowFreeFormInput(true).build(),
                ).build(),
            )
            .build()
            .also { it.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, isGroup) }
        return StatusBarNotification(
            "com.whatsapp",
            "com.whatsapp",
            77,
            "tag",
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            UserHandle.getUserHandleForUid(Process.myUid()),
            10L,
        )
    }

    /** Attesa reale che pompa anche il main looper (i PendingIntent arrivano lì). */
    private fun awaitUntil(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timeout in attesa di: $label" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun <T : Any> awaitNotNull(label: String, supplier: () -> T?): T {
        var value: T? = null
        awaitUntil(label) {
            value = supplier()
            value != null
        }
        return requireNotNull(value)
    }

    private class InertDeviceController : DeviceController {
        override suspend fun setWifi(on: Boolean, executionId: ExecutionId, priority: Int) = Unit
        override suspend fun setBluetooth(on: Boolean, executionId: ExecutionId, priority: Int) =
            Unit

        override suspend fun setMobileData(on: Boolean, executionId: ExecutionId, priority: Int) =
            Unit

        override suspend fun setDnd(mode: DndMode, executionId: ExecutionId, priority: Int) = Unit
        override suspend fun setDarkMode(
            mode: dev.argus.engine.model.NightMode,
            executionId: ExecutionId,
            priority: Int,
        ) = Unit
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
        const val REPLY_ACTION = "dev.argus.test.E2E_REPLY"
        const val RESULT_KEY = "reply_text"
        const val GENERATED_REPLY = "arrivo per le 19:30, scaldo io la cena"
    }
}
