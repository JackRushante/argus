package dev.argus.automation.prompt

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.DeviceStateSnapshotProvider
import dev.argus.automation.DispatchFrame
import dev.argus.automation.R
import dev.argus.automation.foreground.launchReceiverWork
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.ConfirmationClosed
import dev.argus.engine.runtime.ConfirmedFire
import dev.argus.engine.runtime.Engine
import javax.inject.Provider

/**
 * Notifiche delle domande (0.8). Ogni pulsante è un PendingIntent IMMUTABILE ed ESPLICITO verso
 * [UserPromptReceiver], non esportato e senza intent-filter: nessun'altra app può spedirgli un intent.
 * Porta l'id della domanda (nell'URI, così ogni pulsante ha il suo PendingIntent) e il token casuale
 * della domanda, che il coordinatore confronta a tempo costante. Esegui chiede lo sblocco del telefono
 * (API 31+): dalla schermata di blocco nessuno lo tocca al posto dell'utente.
 *
 * Il testo mostrato è solo quello approvato: il nome della regola e la domanda risolta del suo `ask`.
 * Sulla schermata di blocco una versione pubblica senza testo della regola.
 */
class AndroidUserPromptSurface(context: Context) : UserPromptSurface {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)
    private val alarms = appContext.getSystemService(AlarmManager::class.java)

    override fun show(prompt: PendingPrompt, nowMillis: Long): Boolean {
        if (!canPost()) return false
        if (!ensureChannel()) return false
        val remaining = (prompt.expiresAtMillis - nowMillis).coerceAtLeast(1)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_argus_notification)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(false)
            .setTimeoutAfter(remaining)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion())
        when (prompt.kind) {
            PromptKind.CONFIRM -> {
                val text = appContext.getString(R.string.prompt08_confirm_text)
                builder
                    .setContentTitle(appContext.getString(R.string.prompt08_confirm_title, prompt.ruleName))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .addAction(
                        NotificationCompat.Action.Builder(0, appContext.getString(R.string.prompt08_run), answerIntent(prompt, RUN))
                            .setAuthenticationRequired(true)
                            .build(),
                    )
                    .addAction(0, appContext.getString(R.string.prompt08_cancel), answerIntent(prompt, CANCEL))
            }
            PromptKind.ASK -> {
                val question = prompt.prompt.orEmpty()
                builder
                    .setContentTitle(prompt.ruleName)
                    .setContentText(question)
                if (prompt.choices.size <= MAX_STANDARD_ACTIONS) {
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(question))
                    prompt.choices.forEachIndexed { index, choice ->
                        builder.addAction(0, choice, answerIntent(prompt, index.toString()))
                    }
                } else {
                    // Android mostra al massimo tre pulsanti: con quattro scelte la vista espansa li ha tutti.
                    builder
                        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                        .setCustomBigContentView(choicesView(prompt, question))
                }
            }
        }
        return try {
            manager.notify(notificationId(prompt.id), builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override fun dismiss(promptId: String) {
        manager.cancel(notificationId(promptId))
    }

    override fun scheduleExpiry(promptId: String, atMillis: Long) {
        try {
            alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, expiryIntent(promptId, PendingIntent.FLAG_UPDATE_CURRENT) ?: return)
        } catch (error: RuntimeException) {
            // Best effort: la scadenza si controlla comunque a ogni tocco e a ogni avvio.
            Log.w(TAG, "sveglia di scadenza non programmata: ${error::class.java.simpleName}")
        }
    }

    override fun cancelExpiry(promptId: String) {
        val operation = expiryIntent(promptId, PendingIntent.FLAG_NO_CREATE) ?: return
        alarms?.cancel(operation)
        operation.cancel()
    }

    private fun choicesView(prompt: PendingPrompt, question: String): RemoteViews =
        RemoteViews(appContext.packageName, R.layout.prompt08_ask_choices).apply {
            setTextViewText(R.id.prompt08_question, question)
            CHOICE_BUTTONS.forEachIndexed { index, buttonId ->
                val choice = prompt.choices.getOrNull(index)
                if (choice == null) {
                    setViewVisibility(buttonId, View.GONE)
                } else {
                    setViewVisibility(buttonId, View.VISIBLE)
                    setTextViewText(buttonId, choice)
                    setOnClickPendingIntent(buttonId, answerIntent(prompt, index.toString()))
                }
            }
        }

    private fun publicVersion() = NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_argus_notification)
        .setContentTitle(appContext.getString(R.string.prompt08_public_title))
        .build()

    private fun answerIntent(prompt: PendingPrompt, answer: String): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(ACTION_ANSWER)
            .setClass(appContext, UserPromptReceiver::class.java)
            .setPackage(appContext.packageName)
            .setData(promptUri(prompt.id, answer))
            .putExtra(EXTRA_TOKEN, prompt.token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun expiryIntent(promptId: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(ACTION_EXPIRE)
            .setClass(appContext, UserPromptReceiver::class.java)
            .setPackage(appContext.packageName)
            .setData(promptUri(promptId, EXPIRE)),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    /** false se il canale è stato spento dall'utente: la domanda non si vedrebbe. */
    private fun ensureChannel(): Boolean {
        val system = appContext.getSystemService(NotificationManager::class.java) ?: return false
        system.getNotificationChannel(CHANNEL_ID)?.let { existing ->
            return existing.importance > NotificationManager.IMPORTANCE_NONE
        }
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.prompt08_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = appContext.getString(R.string.prompt08_channel_description) },
        )
        return true
    }

    private fun canPost(): Boolean = (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) && manager.areNotificationsEnabled()

    companion object {
        const val CHANNEL_ID = "argus_user_prompts"
        internal const val ACTION_ANSWER = "dev.argus.automation.prompt.ANSWER"
        internal const val ACTION_EXPIRE = "dev.argus.automation.prompt.EXPIRE"
        internal const val EXTRA_TOKEN = "dev.argus.automation.prompt.TOKEN"
        internal const val RUN = "run"
        internal const val CANCEL = "cancel"
        private const val EXPIRE = "expire"
        private const val MAX_STANDARD_ACTIONS = 3
        private const val TAG = "ArgusUserPrompt"
        private val CHOICE_BUTTONS = listOf(
            R.id.prompt08_choice_1,
            R.id.prompt08_choice_2,
            R.id.prompt08_choice_3,
            R.id.prompt08_choice_4,
        )

        internal fun notificationId(promptId: String): Int = "prompt:$promptId".hashCode()

        internal fun promptUri(promptId: String, answer: String): Uri = Uri.Builder()
            .scheme("argus")
            .authority("prompt")
            .appendPath(promptId)
            .appendPath(answer)
            .build()

        /** La risposta di un intent del receiver, o null se l'intent non è uno dei nostri. */
        internal fun parse(action: String?, data: Uri?): Pair<String, PromptAnswer?>? {
            if (data == null || data.scheme != "argus" || data.host != "prompt") return null
            val segments = data.pathSegments
            if (segments.size != 2) return null
            val id = segments[0].takeIf(PendingPromptStore::wellFormedId) ?: return null
            return when (action) {
                ACTION_EXPIRE -> if (segments[1] == EXPIRE) id to null else null
                ACTION_ANSWER -> when (val answer = segments[1]) {
                    RUN -> id to PromptAnswer.Run
                    CANCEL -> id to PromptAnswer.Cancel
                    else -> answer.toIntOrNull()?.takeIf { it in 0..3 && answer.length == 1 }
                        ?.let { id to PromptAnswer.Choice(it) }
                }
                else -> null
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface UserPromptEntryPoint {
    fun userPromptCoordinator(): UserPromptCoordinator
}

/**
 * I tocchi sui pulsanti delle domande e la sveglia di scadenza. Non esportato, senza intent-filter: lo
 * raggiungono solo i PendingIntent espliciti di Argus. La risposta vale solo col token della domanda.
 */
class UserPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val parsed = AndroidUserPromptSurface.parse(intent?.action, intent?.data) ?: return
        val (promptId, answer) = parsed
        val token = intent?.getStringExtra(AndroidUserPromptSurface.EXTRA_TOKEN)
        val coordinator = try {
            EntryPointAccessors.fromApplication(context.applicationContext, UserPromptEntryPoint::class.java)
                .userPromptCoordinator()
        } catch (error: RuntimeException) {
            Log.w(TAG, "coordinatore domande non disponibile: ${error::class.java.simpleName}")
            return
        }
        launchReceiverWork(context, SOURCE) {
            if (answer == null) coordinator.sweep(repost = false) else coordinator.handle(promptId, token, answer)
        }
    }

    private companion object {
        const val SOURCE = "user_prompt"
        const val TAG = "ArgusUserPrompt"
    }
}

/** Esegui e chiusure verso il motore, nella stessa cornice di ogni dispatch (wakelock, manutenzione). */
class EngineConfirmedRuns(
    private val engine: Provider<Engine>,
    private val state: DeviceStateSnapshotProvider,
    private val frame: DispatchFrame,
) : ConfirmedRuns {
    override suspend fun run(confirmed: ConfirmedFire) {
        frame.run { engine.get().onConfirmed(confirmed) { request -> state.current(request) } }
    }

    override suspend fun closed(automationId: AutomationId, reason: ConfirmationClosed) {
        engine.get().onConfirmationClosed(automationId, reason)
    }
}
