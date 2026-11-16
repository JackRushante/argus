package dev.argus.engine.model

/**
 * Regole di [Action.NotificationAction], condivise da validator, interpolatore ed esecutore Android.
 *
 * L'azione agisce SOLO sulla notifica che ha fatto scattare la regola. CLICK preme un pulsante di
 * un'altra app al posto dell'utente, quindi:
 *  - il testo del pulsante è letterale (mai `${'$'}{…}`) e si confronta per intero, tolti gli spazi ai
 *    bordi e senza badare alle maiuscole ([labelMatches]);
 *  - mai sulle notifiche di Argus né delle app su cui una macro non può agire
 *    ([UiStepsPolicy.isForbiddenPackage]);
 *  - un pulsante che chiede di scrivere (RemoteInput) si rifiuta: per quello ci sono le risposte;
 *  - se il pulsante non c'è (o ce n'è più d'uno con quel testo) l'azione fallisce con un codice chiuso
 *    e non preme nient'altro.
 */
object NotificationActionPolicy {
    const val MIN_SNOOZE_MINUTES = 1
    const val MAX_SNOOZE_MINUTES = 1_440
    const val MAX_LABEL_CHARS = 100

    /** Codici chiusi dell'esecuzione (journal e log). */
    const val CODE_EVENT_UNVERIFIED = "notification_action_event_unverified"
    const val CODE_TARGET_FORBIDDEN = "notification_action_target_forbidden"
    const val CODE_NOTIFICATION_GONE = "notification_action_notification_gone"
    const val CODE_BUTTON_NOT_FOUND = "notification_action_button_not_found"
    const val CODE_BUTTON_AMBIGUOUS = "notification_action_button_ambiguous"
    const val CODE_BUTTON_NEEDS_INPUT = "notification_action_button_needs_input"
    const val CODE_UNAVAILABLE = "notification_action_unavailable"
    const val CODE_FAILED = "notification_action_failed"

    enum class Problem {
        LABEL_MISSING,
        LABEL_UNEXPECTED,
        LABEL_INVALID,
        LABEL_INTERPOLATED,
        SNOOZE_MISSING,
        SNOOZE_UNEXPECTED,
        SNOOZE_RANGE,
    }

    /** Problemi di forma dei campi, in ordine stabile. Vuoto = forma valida. */
    fun problems(action: Action.NotificationAction): List<Problem> = buildList {
        val label = action.label
        when (action.op) {
            NotificationActionOp.CLICK -> when {
                label == null -> add(Problem.LABEL_MISSING)
                InterpolationPolicy.containsInterpolation(label) -> add(Problem.LABEL_INTERPOLATED)
                !validLabel(label) -> add(Problem.LABEL_INVALID)
            }
            NotificationActionOp.DISMISS, NotificationActionOp.SNOOZE ->
                if (label != null) add(Problem.LABEL_UNEXPECTED)
        }
        val minutes = action.snoozeMinutes
        when (action.op) {
            NotificationActionOp.SNOOZE -> when {
                minutes == null -> add(Problem.SNOOZE_MISSING)
                minutes !in MIN_SNOOZE_MINUTES..MAX_SNOOZE_MINUTES -> add(Problem.SNOOZE_RANGE)
            }
            NotificationActionOp.DISMISS, NotificationActionOp.CLICK ->
                if (minutes != null) add(Problem.SNOOZE_UNEXPECTED)
        }
    }

    fun isShapeValid(action: Action.NotificationAction): Boolean = problems(action).isEmpty()

    /** Non vuota tolti gli spazi, entro [MAX_LABEL_CHARS], senza caratteri di controllo. */
    fun validLabel(label: String): Boolean =
        label.isNotBlank() && label.length <= MAX_LABEL_CHARS && label.none(Char::isISOControl)

    /** Il testo visibile del pulsante corrisponde all'etichetta approvata: intero, trim, maiuscole ignorate. */
    fun labelMatches(buttonTitle: String?, label: String): Boolean =
        buttonTitle != null && buttonTitle.trim().equals(label.trim(), ignoreCase = true)

    /** Notifiche su cui l'azione non agisce mai (CLICK): Argus e le app vietate alle macro. */
    fun isForbiddenTarget(pkg: String): Boolean = UiStepsPolicy.isForbiddenPackage(pkg)
}
