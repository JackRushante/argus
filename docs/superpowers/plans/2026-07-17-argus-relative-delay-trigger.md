# Argus — Trigger a ritardo relativo (`Time.afterMs`) — 2026-07-17

## Problema (follow-up #61)
"Notificami **tra 2 minuti** il cambio" oggi compila in `Trigger.Time(at = ora+2min)`, un
istante **assoluto**. Disarmando e ri-armando la regola il conto NON riparte: `at` è nel
passato → nessuno scatto. Lorenzo vuole che "tra N" sia **ri-armabile** (il countdown riparte
ad ogni arm), mentre i ricorrenti (`ogni N` → cron) già ripartono.

## Decisione di design
NON un nuovo sottotipo sealed `Trigger.Delay` (costringerebbe a toccare tutti i `when`
esaustivi in DraftValidator / StaticShellSafety / CapabilityRequirements / TriggerMatcher /
RuntimeAdapters / RuleRenderMapper + un nuovo TriggerEvent + capability id).

Invece: **campo `afterMs: Long?` su `Trigger.Time`** = ritardo relativo one-shot.
- Riusa al 100% la macchina d'allarme (`TimeAlarmCoordinator`: reconcile, recovery boot,
  exact/inexact, reschedule su cambio fingerprint). Resta `Trigger.Time` ⇒ zero cast nuovi.
- Fira via `TriggerEvent.TimeFired`, capability `TRIGGER_TIME`. Nessun innesto `when` nuovo.

### Semantica dell'ancora (il punto delicato)
`afterMs` è **relativo**, ma va **ancorato una volta all'arm e poi congelato**, altrimenti
ogni reconcile (`APP_START`, `BOOT`) ricalcolerebbe `now + afterMs` facendo **slittare** lo
scatto in avanti all'infinito.

L'ancora vive nel `ScheduledTimeAlarm` persistito (Room), che è già "stato di scheduling"
NON parte del fingerprint di approvazione:
- 1° schedule dopo arm: `existing == null` ⇒ ancora = `now + afterMs`, persisti l'istante.
- reconcile successivi: `existing != null` ⇒ tieni `existing.eventAtMillis` (congelato) ⇒ UNCHANGED.
- scatto ⇒ one-shot ⇒ auto-disable ⇒ `cancelAndForget` cancella il `ScheduledTimeAlarm`.
- **re-arm**: `existing == null` (record cancellato al disable) ⇒ nuova ancora `now + afterMs`. ✅
- **reboot** durante il countdown: l'ancora è persistita in Room ⇒ sopravvive ⇒ scatta al
  target originale (come un timer/allarme, NON riparte dal boot). ✅

## Compat fingerprint (critico)
`ApprovalFingerprints.of` serializza l'intera Automation. `afterMs` DEVE essere
`@EncodeDefault(EncodeDefault.Mode.NEVER)`: quando `null` è omesso dal JSON ⇒ i Time esistenti
restano byte-identici ⇒ `V1FingerprintCompatibilityTest` (SHA pinnati) continua a passare.
Per un trigger relativo, `afterMs` è un campo costante ⇒ fingerprint stabile tra i re-arm
(nessuna ri-approvazione).

## Edit (produzione)
- `engine-core/model/Trigger.kt`: `@EncodeDefault(NEVER) val afterMs: Long? = null` su `Time`;
  KDoc "esattamente uno tra cron/at/afterMs"; extension `Trigger.Time.isOneShot() = at != null || afterMs != null`.
- `engine-core/runtime/CronSchedule.kt` (`TimeSpecs.nextFire`): ramo `afterMs != null -> after.plusMillis(afterMs)`.
- `engine-core/safety/DraftValidator.kt`: "esattamente uno tra {cron, at, afterMs}"; bound
  `afterMs in MIN_DELAY_MS..MAX_DELAY_MS` (1s..7g); codici `time_spec`, `after_ms_invalid`.
- `automation-android/TimeAlarmCoordinator.kt`: `TimeAlarmPlanner.next(automation, after, existing)`
  congela `afterMs` sull'ancora persistita; `scheduleLocked` passa `existing`; one-shot detection
  (righe ~182, ~284) usa `trigger.isOneShot()` invece di `.at != null`.
- `automation-android/GenerativeActionLane.kt`: `isOneShot` usa l'extension condivisa.
- `ui/RuleRenderMapper.kt`: `timeLine` ramo `afterMs` → "Una volta, tra <humanized>".
- `brain-android/AgentMessageSupport.kt` (rule 15): "tra N" → `time.afterMs` (ms, relativo);
  "ogni N" → cron; "subito" → immediate; "alle HH:MM"/data assoluta → `at`.
- `ops/hermes/bridge.py` (rule 13 + validate_action time): accetta/insegna `afterMs`. (orchestrator)

## Verifica
- TDD su ogni edit (engine-core, coordinator, mapper).
- Full gate `--rerun-tasks` verde.
- Live Hermes: "tra 2 minuti" → `{"type":"time","afterMs":120000,...}` (orchestrator).
- Runtime device: arma "tra 2 min" → scatta ~2min dopo; disarma/riarma → riparte. (Lorenzo)
