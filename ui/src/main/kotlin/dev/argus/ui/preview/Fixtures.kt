package dev.argus.ui.preview

import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.safety.Severity
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.AutomationDetailState
import dev.argus.ui.model.AutomationListState
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.BgLocationState
import dev.argus.ui.model.BudgetUi
import dev.argus.ui.model.ChatItem
import dev.argus.ui.model.ChatState
import dev.argus.ui.model.ContactRow
import dev.argus.ui.model.DraftCardStatus
import dev.argus.ui.model.EngineBanner
import dev.argus.ui.model.ExecutionLogState
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRow
import dev.argus.ui.model.OnboardingState
import dev.argus.ui.model.OnboardingStepState
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.SettingsState
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.model.StepKind
import dev.argus.ui.model.StepStatus
import dev.argus.ui.model.TransportUi
import dev.argus.ui.model.UiWarning
import dev.argus.ui.screens.shizukuOnboardingCopy

// =============================================================================
// Fixture centralizzate per i @Preview e i test UI; il runtime `app` usa dati reali.
//
// Fonte unica di dati finti REALISTICI per tutti e 6 gli schermi. Copre:
//  - i 3 esempi della spec: geofence Wi-Fi/BT in uscita (a1), DND 23:00 (a2),
//    risposta WhatsApp a "Moglie" (a3, generativa);
//  - regole plausibili aggiuntive: backup foto via shell (a5), ufficio disabilitata
//    (a4), promemoria farmaci NEEDS_REVIEW (a6), disinstalla-giochi generativa con
//    shell VIETATA → arm bloccato/ERROR (a7);
//  - TUTTI gli stati badge (ARMED/PENDING_APPROVAL/DISABLED/NEEDS_REVIEW),
//    generativa+cloud, shell integrale, arm-bloccato;
//  - TUTTI i LogOutcome (SUCCESS/PARTIAL/FAILED/SUBMITTED/DEFERRED) + i kind
//    (FIRED/SUPPRESSED_COOLDOWN/CONDITIONS_NOT_MET/ERROR).
//
// Puro layer-view: nessuna dipendenza Android, nessun engine reale — solo i
// contratti §6 (+ Severity/AuditKind da engine-core). L'app è una DEMO su dati
// finti: non esegue automazioni.
// =============================================================================

object Fixtures {

    // -------------------------------------------------------------------------
    // RuleRender — costruite dai tipi (deterministiche, direttiva §5.1).
    // -------------------------------------------------------------------------

    /** a1 · geofence in uscita → Wi-Fi off + Bluetooth on (spec esempio 1). */
    val geofenceExitRule = RuleRender(
        triggerLine = "Quando: esci dalla posizione attuale (±50 m)",
        triggerIconKey = "geofence",
        conditionLines = emptyList(),
        actions = listOf(
            ActionRow("wifi_off", "Disattiva Wi-Fi", null, false, null, false, false),
            ActionRow("bluetooth", "Attiva Bluetooth", null, false, null, false, false),
        ),
        isGenerative = false,
        privacyNote = null,
    )

    /** a2 · ogni sera alle 23:00 → suoneria silenziosa + Non disturbare (spec esempio 2). */
    val dndNightRule = RuleRender(
        triggerLine = "Ogni giorno alle 23:00 (Europe/Rome)",
        triggerIconKey = "time",
        conditionLines = emptyList(),
        actions = listOf(
            ActionRow("ringer", "Imposta suoneria (silenzioso)", null, false, null, false, false),
            ActionRow("dnd", "Attiva Non disturbare (totale)", null, false, null, false, false),
        ),
        isGenerative = false,
        privacyNote = null,
    )

    /** a3 · notifica WhatsApp da Moglie → risposta generata dall'AI (spec esempio 3, generativa). */
    val whatsappReplyRule = RuleRender(
        triggerLine = "Quando: notifica WhatsApp da \"Moglie\" (chat 1:1)",
        triggerIconKey = "notification",
        conditionLines = listOf("Solo tra le 18:00 e le 22:00 (Europe/Rome)"),
        actions = listOf(
            ActionRow(
                iconKey = "generative",
                label = "Rispondi con l'AI",
                detail = "Obiettivo: rispondere nel tono concordato.\nTool consentiti: whatsapp_reply\nDestinatario: vincolato al mittente (Moglie)",
                isShell = false,
                shellCommand = null,
                isGenerative = true,
                requiresLiveConfirm = false,
            ),
        ),
        isGenerative = true,
        privacyNote = "Il testo della notifica verrà inviato a Hermes e da lì al provider cloud per generare la risposta.",
    )

    /** a4 · geofence ufficio in entrata → suoneria vibrazione (regola disattivata). */
    val officeEnterRule = RuleRender(
        triggerLine = "Quando: entri nella posizione \"Ufficio\" (±120 m)",
        triggerIconKey = "geofence",
        conditionLines = emptyList(),
        actions = listOf(
            ActionRow("ringer", "Imposta suoneria (vibrazione)", null, false, null, false, false),
        ),
        isGenerative = false,
        privacyNote = null,
    )

    /** a5 · backup notturno foto via shell (shell integrale, monospace §5.4). */
    val backupShellRule = RuleRender(
        triggerLine = "Ogni giorno alle 03:00 (Europe/Rome)",
        triggerIconKey = "time",
        conditionLines = listOf("Solo se Wi-Fi connesso", "Solo se in carica"),
        actions = listOf(
            ActionRow(
                iconKey = "shell",
                label = "Esegui comando shell",
                detail = null,
                isShell = true,
                shellCommand = "rsync -a --delete /sdcard/DCIM/Camera/ /mnt/nas/immich/foto/",
                isGenerative = false,
                requiresLiveConfirm = true,
            ),
        ),
        isGenerative = false,
        privacyNote = null,
    )

    /** a7 · generativa che pretende shell.run/app.install → VIETATA (arm bloccato/ERROR). */
    val forbiddenShellRule = RuleRender(
        triggerLine = "Quando: ogni giorno alle 00:00 (Europe/Rome)",
        triggerIconKey = "time",
        conditionLines = emptyList(),
        actions = listOf(
            ActionRow(
                iconKey = "generative",
                label = "Decidi ed esegui con l'AI",
                detail = "Obiettivo: individuare i giochi e disinstallarli.\nTool richiesti: shell.run, app.install",
                isShell = false,
                shellCommand = null,
                isGenerative = true,
                requiresLiveConfirm = false,
            ),
        ),
        isGenerative = true,
        privacyNote = "Regola generativa con accesso alla shell.",
    )

    /** a6 · regola salvata con schema precedente, non più decodificabile (NEEDS_REVIEW). */
    val staleSchemaRule = RuleRender(
        triggerLine = "Regola salvata con uno schema precedente",
        triggerIconKey = "time",
        conditionLines = emptyList(),
        actions = emptyList(),
        isGenerative = false,
        privacyNote = null,
    )

    // -------------------------------------------------------------------------
    // Warning riusabili
    // -------------------------------------------------------------------------

    private val privacyGenerativeWarning = UiWarning(
        Severity.WARNING,
        "privacy_generative",
        "Il testo della notifica verrà inviato a Hermes e da lì al provider cloud per generare la risposta.",
    )
    private val readPlusReplyWarning = UiWarning(
        Severity.WARNING,
        "read_plus_reply",
        "Questa regola legge dati dal dispositivo (testo della notifica) e li può inviare al mittente. Possibile esfiltrazione di contesto.",
    )

    // -------------------------------------------------------------------------
    // Chat (§6.1) — parte vuota con suggerimenti; l'app appende una DraftCard
    // dopo una latenza simulata (one-shot §4).
    // -------------------------------------------------------------------------

    val chatEmpty = ChatState(
        items = emptyList(),
        input = "",
        sending = false,
        sendingElapsedSec = null,
        brainReachable = true,
        error = null,
    )

    /** Risposta canned dell'assistente dopo l'attesa one-shot (demo). */
    val cannedAssistantReply = ChatItem.AssistantMessage(
        text = "Ok. Ho preparato una regola: risponde solo a Moglie, solo nella fascia serale, generando il testo al momento. È generativa (il contenuto esce verso il cloud), quindi controllala e armala dal dettaglio.",
        timeLabel = "ora",
    )

    /** DraftCard canned proposta in chat → apre il Dettaglio a3 (onOpenDraft). */
    fun cannedDraft(): ChatItem.DraftCard = ChatItem.DraftCard(
        draftId = "a3",
        rule = whatsappReplyRule,
        issues = listOf(readPlusReplyWarning),
        status = DraftCardStatus.PROPOSED,
    )

    /** Conversazione già popolata (per @Preview e stato "vivo" alternativo). */
    val chatConversation = ChatState(
        items = listOf(
            ChatItem.UserMessage(
                "Se Moglie mi scrive su WhatsApp tra le 18 e le 22, rispondile tu nel tono che abbiamo concordato.",
                "13:03",
            ),
            cannedAssistantReply.copy(timeLabel = "13:03"),
            cannedDraft(),
        ),
        input = "",
        sending = false,
        sendingElapsedSec = null,
        brainReachable = true,
        error = null,
    )

    // -------------------------------------------------------------------------
    // Automazioni · lista (§6.2) — volutamente in ordine "sbagliato": lo schermo
    // riordina per stato (PENDING → NEEDS_REVIEW → ARMED → DISABLED).
    // -------------------------------------------------------------------------

    val listRows: List<AutomationRow> = listOf(
        AutomationRow(
            id = "a1", name = "Casa · spegni Wi-Fi uscendo", triggerIconKey = "geofence",
            triggerSummary = "Geofence \"Casa\" · in uscita", status = StatusBadge.ARMED,
            enabled = true, isGenerative = false, hasWarnings = true,
            lastFiredLabel = "ieri 18:32", nextFireLabel = null,
        ),
        AutomationRow(
            id = "a2", name = "DND dopo le 23:00", triggerIconKey = "time",
            triggerSummary = "Ogni giorno 23:00", status = StatusBadge.ARMED,
            enabled = true, isGenerative = false, hasWarnings = false,
            lastFiredLabel = "oggi 23:00", nextFireLabel = "stasera 23:00",
        ),
        AutomationRow(
            id = "a5", name = "Backup foto notturno", triggerIconKey = "time",
            triggerSummary = "Ogni giorno 03:00 · shell", status = StatusBadge.ARMED,
            enabled = true, isGenerative = false, hasWarnings = false,
            lastFiredLabel = "oggi 03:00", nextFireLabel = "stanotte 03:00",
        ),
        AutomationRow(
            id = "a3", name = "Rispondi a Moglie su WhatsApp", triggerIconKey = "notification",
            triggerSummary = "Notifica WhatsApp · Moglie", status = StatusBadge.PENDING_APPROVAL,
            enabled = false, isGenerative = true, hasWarnings = true,
            lastFiredLabel = null, nextFireLabel = null,
        ),
        AutomationRow(
            id = "a7", name = "Disinstalla giochi dopo mezzanotte", triggerIconKey = "time",
            triggerSummary = "Ogni giorno 00:00 · generativa", status = StatusBadge.PENDING_APPROVAL,
            enabled = false, isGenerative = true, hasWarnings = true,
            lastFiredLabel = null, nextFireLabel = null,
        ),
        AutomationRow(
            id = "a6", name = "Promemoria farmaci", triggerIconKey = "time",
            triggerSummary = "Schema non compatibile", status = StatusBadge.NEEDS_REVIEW,
            enabled = false, isGenerative = false, hasWarnings = true,
            lastFiredLabel = null, nextFireLabel = null,
        ),
        AutomationRow(
            id = "a4", name = "Ufficio · silenzia entrando", triggerIconKey = "geofence",
            triggerSummary = "Geofence \"Ufficio\" · in entrata", status = StatusBadge.DISABLED,
            enabled = false, isGenerative = false, hasWarnings = false,
            lastFiredLabel = "mar 09:04", nextFireLabel = null,
        ),
    )

    val pendingCount: Int = listRows.count { it.status == StatusBadge.PENDING_APPROVAL }
    val needsReviewCount: Int = listRows.count { it.status == StatusBadge.NEEDS_REVIEW }

    val listState = AutomationListState(
        rows = listRows,
        filter = StatusFilter.ALL,
        banner = EngineBanner.NONE,
        loading = false,
    )

    // -------------------------------------------------------------------------
    // Automazione · dettaglio (§6.3) — uno stato per ogni riga della lista.
    // -------------------------------------------------------------------------

    val detailArmedGeofence = AutomationDetailState(
        id = "a1",
        name = "Casa · spegni Wi-Fi uscendo",
        status = StatusBadge.ARMED,
        rule = geofenceExitRule,
        rationale = "Ho messo un geofence sulla tua posizione attuale (50 m); appena esci spengo il Wi-Fi e accendo il Bluetooth per l'auto.",
        warnings = listOf(
            UiWarning(
                Severity.WARNING,
                "geofence_radius",
                "Raggio 50 m sotto i 100 m consigliati: lo scatto in uscita può arrivare con 2-15 min di ritardo (batching a schermo spento).",
            ),
        ),
        canArm = true,
        armBlockedReason = null,
        estimatedLlmCallsPerDay = null,
        recentRuns = listOf(
            LogRow("r1", "ieri 18:32", "Casa · spegni Wi-Fi uscendo", AuditKind.FIRED, LogOutcome.SUCCESS, "Wi-Fi off · Bluetooth on", null),
            LogRow("r2", "mar 08:10", "Casa · spegni Wi-Fi uscendo", AuditKind.CONDITIONS_NOT_MET, LogOutcome.SUCCESS, "già fuori zona", null),
        ),
        geofencePreviewLabel = "Posizione: quella attuale al momento dell'attivazione",
    )

    val detailDndNight = AutomationDetailState(
        id = "a2",
        name = "DND dopo le 23:00",
        status = StatusBadge.ARMED,
        rule = dndNightRule,
        rationale = "Ogni sera alle 23 metto il telefono in silenzioso e attivo Non disturbare totale, così non ti svegliano le notifiche.",
        warnings = emptyList(),
        canArm = true,
        armBlockedReason = null,
        estimatedLlmCallsPerDay = null,
        recentRuns = listOf(
            LogRow("r3", "oggi 23:00", "DND dopo le 23:00", AuditKind.FIRED, LogOutcome.SUCCESS, "Non disturbare attivato", null),
        ),
        geofencePreviewLabel = null,
    )

    val detailWhatsappPending = AutomationDetailState(
        id = "a3",
        name = "Rispondi a Moglie su WhatsApp",
        status = StatusBadge.PENDING_APPROVAL,
        rule = whatsappReplyRule,
        rationale = "Quando Moglie ti scrive su WhatsApp nella fascia serale, genero io una risposta nel tono che mi hai indicato e la invio.",
        warnings = listOf(privacyGenerativeWarning, readPlusReplyWarning),
        canArm = true,
        armBlockedReason = null,
        estimatedLlmCallsPerDay = "≈ 5 chiamate/giorno · cooldown minimo 60 s",
        recentRuns = emptyList(),
        geofencePreviewLabel = null,
    )

    val detailOfficeDisabled = AutomationDetailState(
        id = "a4",
        name = "Ufficio · silenzia entrando",
        status = StatusBadge.DISABLED,
        rule = officeEnterRule,
        rationale = "Quando entri in ufficio metto la suoneria in vibrazione.",
        warnings = emptyList(),
        canArm = true,
        armBlockedReason = null,
        estimatedLlmCallsPerDay = null,
        recentRuns = listOf(
            LogRow("r4", "mar 09:04", "Ufficio · silenzia entrando", AuditKind.FIRED, LogOutcome.SUCCESS, "suoneria in vibrazione", null),
        ),
        geofencePreviewLabel = null,
    )

    val detailBackupShell = AutomationDetailState(
        id = "a5",
        name = "Backup foto notturno",
        status = StatusBadge.ARMED,
        rule = backupShellRule,
        rationale = "Alle 3 di notte, se sei sotto Wi-Fi e in carica, sincronizzo le foto della fotocamera verso il NAS.",
        warnings = listOf(
            UiWarning(
                Severity.WARNING,
                "shell_requires_shizuku",
                "Il comando shell richiede Shizuku attivo: se spento dopo un riavvio, il backup resta in coda.",
            ),
        ),
        canArm = true,
        armBlockedReason = null,
        estimatedLlmCallsPerDay = null,
        recentRuns = listOf(
            LogRow("r5", "oggi 03:00", "Backup foto notturno", AuditKind.FIRED, LogOutcome.SUCCESS, "312 file sincronizzati", null),
            LogRow("r6", "ieri 03:00", "Backup foto notturno", AuditKind.FIRED, LogOutcome.PARTIAL, "sync ok · Wi-Fi non spento", null),
        ),
        geofencePreviewLabel = null,
    )

    val detailNeedsReview = AutomationDetailState(
        id = "a6",
        name = "Promemoria farmaci",
        status = StatusBadge.NEEDS_REVIEW,
        rule = staleSchemaRule,
        rationale = null,
        warnings = listOf(
            UiWarning(
                Severity.ERROR,
                "schema_migration",
                "La regola è stata creata con una versione precedente dello schema e non è più decodificabile automaticamente. Ri-chiedila in chat per ricrearla.",
            ),
        ),
        canArm = false,
        armBlockedReason = "schema incompatibile — ricrea la regola in chat",
        estimatedLlmCallsPerDay = null,
        recentRuns = emptyList(),
        geofencePreviewLabel = null,
    )

    val detailForbiddenBlocked = AutomationDetailState(
        id = "a7",
        name = "Disinstalla giochi dopo mezzanotte",
        status = StatusBadge.PENDING_APPROVAL,
        rule = forbiddenShellRule,
        rationale = "A mezzanotte controllo quali app sono giochi e le rimuovo.",
        warnings = listOf(
            UiWarning(
                Severity.ERROR,
                "generative_forbidden_tool",
                "Una regola generativa non può usare shell.run né app.install: al fire-time l'LLM genererebbe comandi mai approvati.",
            ),
        ),
        canArm = false,
        armBlockedReason = "tool 'shell.run' e 'app.install' vietati nelle regole generative (invariante di sicurezza)",
        estimatedLlmCallsPerDay = "≈ 30 chiamate/giorno · alto rischio",
        recentRuns = emptyList(),
        geofencePreviewLabel = null,
    )

    /** Indice per id: la chat (onOpenDraft) e la lista (onOpen) aprono il Dettaglio da qui. */
    val detailsById: Map<String, AutomationDetailState> = listOf(
        detailArmedGeofence,
        detailDndNight,
        detailWhatsappPending,
        detailOfficeDisabled,
        detailBackupShell,
        detailNeedsReview,
        detailForbiddenBlocked,
    ).associateBy { it.id }

    // -------------------------------------------------------------------------
    // Log esecuzioni (§6.4) — timeline Oggi/Ieri, tutti gli esiti e i kind.
    // -------------------------------------------------------------------------

    val logEntries: List<LogRow> = listOf(
        LogRow(
            "l1", "oggi 23:00", "DND dopo le 23:00", AuditKind.FIRED, LogOutcome.SUCCESS,
            "Non disturbare attivato",
            listOf("SetRinger(silent) → ok", "SetDnd(total) → ok", "cooldown 6 h avviato"),
        ),
        LogRow(
            "l8", "oggi 19:40", "Rispondi a Moglie su WhatsApp", AuditKind.FIRED, LogOutcome.SUBMITTED,
            "Generazione risposta in corso…",
            listOf("GenerateReply(Hermes) → inviato, in attesa esito (≈ 12 s)"),
        ),
        LogRow(
            "l2", "oggi 19:14", "Rispondi a Moglie su WhatsApp", AuditKind.FIRED, LogOutcome.DEFERRED,
            "Risposta pronta — consegna manuale",
            listOf(
                "GenerateReply(Hermes) → ok (14 s)",
                "WhatsAppReply → non più rispondibile in automatico",
            ),
        ),
        LogRow(
            "l3", "oggi 18:32", "Casa · spegni Wi-Fi uscendo", AuditKind.FIRED, LogOutcome.SUCCESS,
            "2/2 azioni ok",
            listOf("SetWifi(off) → ok", "SetBluetooth(on) → ok"),
        ),
        LogRow(
            "l4", "oggi 12:05", "Backup foto notturno", AuditKind.FIRED, LogOutcome.PARTIAL,
            "1/2 azioni ok — 1 fallita", listOf("Sync(immich) → ok", "SetWifi(off) → errore permesso"),
        ),
        LogRow(
            "l5", "ieri 21:00", "DND dopo le 23:00", AuditKind.CONDITIONS_NOT_MET, LogOutcome.SUCCESS,
            "condizioni non soddisfatte · già in ritorno", null,
        ),
        LogRow(
            "l6", "ieri 20:05", "Casa · spegni Wi-Fi uscendo", AuditKind.SUPPRESSED_COOLDOWN, LogOutcome.SUCCESS,
            "soppressa (cooldown 60 s)", null,
        ),
        LogRow(
            "l7", "ieri 08:50", "Backup foto notturno", AuditKind.ERROR, LogOutcome.FAILED,
            "Shizuku non disponibile", listOf("shell.run → Shizuku binder assente"),
        ),
    )

    val logState = ExecutionLogState(
        entries = logEntries,
        filterAutomationName = null,
        loading = false,
    )

    // -------------------------------------------------------------------------
    // Sistema · settings (§6.5)
    // -------------------------------------------------------------------------

    val settingsAllGreen = SettingsState(
        transport = TransportUi.CliBridge(
            url = "https://hermes.tail04462d.ts.net",
            reachable = true,
            lastLatencyLabel = "14 s · normale per Hermes",
        ),
        shizuku = ShizukuStatus.AUTHORIZED,
        batteryExempt = true,
        notificationAccess = true,
        backgroundLocation = BgLocationState.GRANTED,
        whitelist = listOf(ContactRow(displayName = "Moglie", conversationId = "wa::393200000000::c1a9")),
        budget = BudgetUi(maxCallsPerHour = 20, usedThisHourLabel = "3 / 20 quest'ora"),
        privacyAccepted = true,
        appVersionLabel = "Argus v0.1.0 · MVP (sideload)",
    )

    val settingsDegraded = SettingsState(
        transport = TransportUi.CliBridge(url = "https://hermes.tail04462d.ts.net", reachable = false, lastLatencyLabel = null),
        shizuku = ShizukuStatus.DEGRADED_AFTER_REBOOT,
        batteryExempt = false,
        notificationAccess = true,
        backgroundLocation = BgLocationState.DENIED,
        whitelist = emptyList(),
        budget = BudgetUi(maxCallsPerHour = 20, usedThisHourLabel = "17 / 20 quest'ora"),
        privacyAccepted = true,
        appVersionLabel = "Argus v0.1.0 · MVP (sideload)",
    )

    // -------------------------------------------------------------------------
    // Onboarding / permessi (§6.6) — copy Shizuku VERBATIM da §9.
    // -------------------------------------------------------------------------

    /**
     * Costruisce i 6 step. Lo step Shizuku prende body+cta VERBATIM da
     * [shizukuOnboardingCopy] (sorgente unica §9). `current` marca lo step in corso.
     */
    fun onboardingSteps(
        current: StepKind = StepKind.WELCOME_PRIVACY,
        shizuku: ShizukuStatus = ShizukuStatus.RUNNING_NOT_AUTHORIZED,
    ): List<OnboardingStepState> {
        val (shizukuBody, shizukuCta) = shizukuOnboardingCopy(shizuku)
        fun statusFor(kind: StepKind): StepStatus =
            if (kind == current) StepStatus.IN_PROGRESS else StepStatus.TODO
        return listOf(
            OnboardingStepState(
                StepKind.WELCOME_PRIVACY, statusFor(StepKind.WELCOME_PRIVACY), "Privacy e consenso",
                "Il testo delle notifiche e ciò che chiedi in chat viaggia verso Hermes (il tuo server) e da lì verso provider cloud (OpenAI/Nous/…). Nulla esce senza una regola che approvi.",
                ctaLabel = "Ho capito, acconsento", blockedReason = null,
            ),
            OnboardingStepState(
                StepKind.BRAIN_CONFIG, statusFor(StepKind.BRAIN_CONFIG), "Collega Hermes",
                "Indirizzo del bridge Hermes sul tuo tailnet. Precompilato: puoi testarlo subito.",
                ctaLabel = "Test connessione", blockedReason = null,
            ),
            OnboardingStepState(
                StepKind.SHIZUKU, statusFor(StepKind.SHIZUKU),
                "Autorizza Shizuku", shizukuBody, ctaLabel = shizukuCta, blockedReason = null,
            ),
            OnboardingStepState(
                StepKind.NOTIFICATION_ACCESS, statusFor(StepKind.NOTIFICATION_ACCESS), "Accesso alle notifiche",
                "Argus legge le notifiche per far scattare le regole (es. WhatsApp da un contatto in whitelist).",
                ctaLabel = "Concedi", blockedReason = null,
            ),
            OnboardingStepState(
                StepKind.BATTERY_OEM, statusFor(StepKind.BATTERY_OEM),
                "Escludi dall'ottimizzazione batteria",
                "OxygenOS può sospendere Argus in background. Escludilo per far girare le azioni pianificate e le risposte AI.",
                ctaLabel = "Apri impostazioni", blockedReason = null,
            ),
            OnboardingStepState(
                StepKind.BACKGROUND_LOCATION, statusFor(StepKind.BACKGROUND_LOCATION),
                "Posizione in background",
                "Serve solo se creerai regole geofence. Puoi concederla più avanti, quando la prima regola di posizione lo richiede.",
                ctaLabel = "Concedi", blockedReason = null,
            ),
        )
    }

    val onboardingState = OnboardingState(
        steps = onboardingSteps(current = StepKind.WELCOME_PRIVACY),
        currentIndex = 0,
        canFinish = false,
    )
}
