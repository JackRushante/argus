# Changelog

Release and engineering notes, newest first.

## v0.3.1 (2026-07-24) — P4/Hermes contract stabilization

### Fixed

- Clarification replies are now treated as normal conversation instead of malformed-rule errors.
  The next user answer carries the original request and clarification dialogue back into compilation.
- Friendly app names in rule requests are now resolved locally against launchable apps. Only
  matching candidates are sent to the compiler, so Argus can select packages such as Google
  Messages without asking the user or exposing the complete installed-app inventory.
- Added the missing Hermes `/act` schema v3 path for resolved runtime data, strict marker/data
  validation, capability negotiation in `/health/v2`, and a shared Kotlin/Python golden request.
- Made P4 `invoke_llm` capture and WhatsApp/local-notification delivery synchronous and explicit.
  The new `CAPTURE_ONLY` wire mode removes the ambiguous “capture implies no delivery” behavior;
  unsupported P4 `invoke_llm_v2` programs are rejected before approval.
- Moved compile retry into the metered boundary: every attempt is recorded and the HARD budget is
  checked again before a retry.
- Made `set_dark_mode` accept the lowercase compiler wire while remaining backward-compatible with
  previously persisted uppercase enum values.
- Removed phantom computer-use tools from the capability manifest and stopped pre-querying package
  visibility before launching apps, settings, Shizuku, alarms, or timers.

### Security and privacy

- Separated confidentiality labels from remote-egress policy. Values marked with credential-vault
  provenance can never be interpolated into a remote Brain request, even under Aggressive taint
  policy; generic `SECRET` data remains an explicit review decision.
- Framed runtime values as a strict JSON DATA block in Android and Hermes prompts and added hostile
  newline/delimiter tests plus shared prompt-semantics fixtures.
- Clipboard writes schedule a 60-second process-local expiry with compare-and-clear semantics:
  Argus removes only its own unchanged clip and never overwrites a newer user/app clip.

### Verification

- Hermes bridge tests pass locally and on the deployed service; production health announces
  `/compile` v1/v2 and `/act` v1/v2/v3.
- Real-device tests on OnePlus/Android 16 cover authenticated `/act` v3 and the complete
  `trigger → interpolation → Hermes → capture → branch → Android action` path.
- A live Hermes compile on the device produces lowercase `set_dark_mode=auto` and decodes it as
  `NightMode.AUTO`.

## v0.3.0 (2026-07-21) — P4 variables/control flow + Aggressive anti-injection

### Changed (security posture)

- The taint-based anti-injection posture is now **Aggressive**, centralized in a single policy point
  `TaintPolicy.allowTaintedInAuthority()` (engine-core `model/TaintPolicy.kt`). Consequence: a
  runtime/TAINTED value (a `trigger_payload` or any `captureAs` output) may now fill **authority /
  command fields** too — `run_shell.cmd`, `open_url.url`, `launch_app.pkg`, `input_text`, ringer
  mode, `write_setting` — and no longer only data sinks (notification/reply text, clipboard). Both the
  static gate (`DraftValidator.interpolation_tainted_authority`) and the dynamic gate
  (`TaintAwareInterpolator` `taint_blocked`) consult this policy; flipping one flag restores the
  previous "surgical" posture (tainted allowed only in sinks).
- This relaxes the **integrity** axis only. Deliberately kept intact:
  - **Shell trigger-gating** (`StaticShellSafety`): an approved `run_shell` is still triggerable only
    by whitelisted 1:1 WhatsApp contacts, never by SMS/phone or unknown senders.
  - **Confidentiality labels**: shell/LLM captures remain `TAINTED` + `SECRET`, stay redacted from
    logs, and produce an approval warning before disclosure to a Brain. These labels are metadata,
    not a non-exportability boundary.
  - **SYSTEM/DATA separation**: untrusted runtime data still travels in the `runtime_data` channel and
    never enters the model's system prompt at fire time.
- Brain prompt (`AgentMessageSupport`) softened accordingly so the model no longer refuses legitimate
  rules that interpolate captured/trigger values into command/URL/target fields, while still stating
  that `run_shell` is triggerable only by whitelisted contacts.

### P4 variables and structured control flow

#### Added

- Schema-v2 automation programs with typed variables, literal/device/trigger bindings,
  engine-generated `random_int`, `if`/`else`, bounded `while` (including a variable iteration
  count), and explicit cooperative `wait` actions.
- Per-value integrity, confidentiality, and provenance labels. Runtime values never alter the
  approved program structure; whether TAINTED values may enter authority templates is governed by
  the centralized policy described above.
- A deterministic program interpreter with definite assignment, typed conditions, hard limits,
  taint-aware interpolation, bounded execution time, redacted runtime values, and stable nested
  action paths such as `1.while[2].3`.
- Room schema 11 stores action paths and enforces one journal row per execution/path. The v10→v11
  migration backfills legacy one-based paths without losing historical rows.
- Android `run_shell.captureAs` support with a 30-second timeout and a 64 KiB stdout ceiling.
  Truncated, failed, or timed-out commands never expose partial captured output.
- P4 schema-v2 support in the app compiler prompt and Hermes bridge, including recursive
  validation, retry-on-invalid, bounded `limit_exceeded` errors, screen-state/parity conditions,
  `copy_text`, `set_mobile_data`, `set_dark_mode`, and the shell cookbook.
- An Android-side resolved `/act` contract that keeps runtime values in a separate DATA envelope
  and lets direct-provider transports return `invoke_llm.captureAs` output to the deterministic
  interpreter without also delivering a reply or notification.
- Recursive review rendering for variables, captures, `if` branches, bounded loops and waits.

#### Fixed and hardened

- Disabling, deleting, or changing the fingerprint of a running P4 rule now cancels only that
  program—even while it is waiting—without aborting other rules dispatched by the same event.
- Receiver, notification-listener, sensor, and immediate-trigger work now retain the shared
  foreground-service lease for the real execution lifetime. Immediate rules no longer keep the
  approval coroutine suspended and are consumed only after execution finishes.
- Static-shell policy is rechecked at execution time. Unexpected transport failures become bounded
  error codes while coroutine cancellation still propagates.
- Android ICU-compatible interpolation matching replaces the JVM-only regex construct that crashed
  on a real device.
- Provider usage accounting rejects malformed/negative values, preserves nullable token semantics,
  and blocks capped models whose cost cannot be priced instead of silently treating them as free.
- Sensor registration/unregistration, shared foreground demand, and one-shot consumption races are
  serialized and covered by regression tests.
- The app shell, runtime logs, generated notifications, onboarding/settings copy, capability labels,
  and diagnostics now honor English-default/Italian-system-locale rendering, including historical
  structured log rows.

#### Known limitations

- A non-capturing `invoke_llm` nested in a P4 program is not yet delivered synchronously and fails
  closed with `p4_generative_deliver_unavailable`. `invoke_llm_v2` capture/delivery in a P4 program
  is likewise unavailable. Flat generative delivery and `invoke_llm.captureAs` are supported.
- The checked-in Hermes bridge accepts `/act` schema 1/2 only, while the Android resolved capture
  client sends schema 3. Therefore `invoke_llm.captureAs` currently fails against Hermes with a
  protocol error; direct-provider transports are not affected. Health v2 does not currently expose
  this missing capability.
- `set_dark_mode` has a wire-enum mismatch: both compile prompts and the Python validator require
  lowercase `off|on|auto`, while Kotlin serializes `NightMode` as uppercase `OFF|ON|AUTO`.
  Natural-language compilation therefore produces a draft the Android decoder cannot accept.
- The real-device P4 gate proved shell capture and a complex random→branch→notification/flashlight
  program. A complete model-capture→later-action device E2E remains to be recorded.

#### Verification

- Full clean Gradle gate: all JVM/Robolectric suites across eight modules plus `:app:assembleDebug`
  (`226` tasks) passed again independently on 2026-07-24.
- Hermes bridge suite: `58/58` tests passed independently on 2026-07-24.
- Real OnePlus/Shizuku gate: literal shell success, non-zero failure, and bounded stdout capture all
  passed. Room migrations passed `10/10` on device, and the schema-11 APK upgraded existing app data
  and launched without Room or `AndroidRuntime` errors.
- The user confirmed correct real-device execution of the final complex random/branch program.

> **Privacy & license.** Argus operates no project backend or account service and includes no
> telemetry or analytics; it contacts only the provider/bridge configured by the user. Rules, logs
> and API keys stay on-device. It is GPL-3.0 free software. The current F-Droid submission uses
> reproducible developer-signed `Binaries`: F-Droid rebuilds and verifies each per-ABI APK before
> publishing it.

## docs — privacy & license clarified (2026-07-20)

Made the "no data collection" and GPL-3.0 copyleft guarantees explicit and prominent in the README
and the F-Droid (Fastlane) descriptions. Dropped the planned in-app Privacy & Licenses screen in
favour of clear documentation. No code change.

## `v0.2.4` — F-Droid ABI split + reproducible dex (2026-07-20)

Requested by F-Droid maintainer linsui on the packaging MR (fdroiddata!43234): the release now
ships one APK per ABI (armeabi-v7a, arm64-v8a, x86, x86_64) instead of a single universal APK, so
each device downloads only its own native code (the transitive `libandroidx.graphics.path.so`).
Per-ABI versionCodes follow F-Droid's `100 * %c + n` scheme (601/602/603/604).

Also drops the ART baseline profile from the release APK: F-Droid's clean rebuild produced a
different `assets/dexopt/baseline.prof` and profile-ordered `classes2.dex`, breaking the
reproducible-build byte comparison. Removing it makes the dex layout source-ordered and
deterministic across build hosts. Only effect is losing profile-guided startup optimisation; no
functional or behavioural change versus 0.2.2.

## Unreleased — privacy hardening (2026-07-18)

- Public builds no longer embed the maintainer's private Hermes/Tailscale endpoint in production
  defaults, previews, tests, or contributor notes.
- Hermes is unconfigured on a fresh install and must be set by the user. Existing installations
  retain their locally stored URL.

## `87c1e9502` — 2026-07-17

**feat(#50 S15 + field fix): end-to-end Hermes usage + afterMs schedules EXACT by default**

Field fix (2026-07-17): a "tra 3 minuti" rule (afterMs, precision FLEXIBLE -> INEXACT) fired
with +2m33s of OEM batching on a OnePlus; the user assumed the rule was dead and deleted it.
An explicit relative delay is punctual by nature: TimeAlarmPlanner.effectivePrecision now
treats afterMs as EXACT (inexact fallback without the exact-alarm permission). A SCHEDULING
policy, not part of the trigger: fingerprint unchanged. deliverLocked's recoveryRecord aligned too.

S15 on the Kotlin side (closes the thread opened by commit 9c39863 on the bridge side):
- CompileResult.usage: TurnUsage? (engine-core, additive).
- CliBridgeTransport: optional BridgeUsageEnvelope on the /compile and /act envelopes (parser
  stays strict: the bridge sanitizes down to a closed key set), mapped fail-closed into TurnUsage.
  Usage also travels on outcomes without a draft: the turn cost something regardless.
- MeteredBrain.compile records result.usage (before: hardcoded null -> tokens/cost N/A in the budget UI).

Deploy order respected: the app declares the field BEFORE the bridge that sends it.

TDD (RED verified) + full gate --rerun-tasks, 154 tasks, 0 failures.

## `00d62e5e4` — 2026-07-17

**feat(#31-B): rule lifecycle audit — track who/when arms and removes an automation**

Motivation (Lorenzo): rules vanished from the inventory without a trace; and in the field the
log showed no "creata" entry when a rule was created. #31-A only covered failures; this
completes the SUCCESSFUL lifecycle.

New AuditKinds (engine-core) + CLOSED-vocabulary reasons (redaction in RoomAuditSink,
SCHEDULING_REASONS pattern — a reason outside the set -> generic token, never free text, zero PII):
- RULE_ARMED("approval") — single funnel: ApprovalFlow.finalizeRegistration, BEFORE the registrar
  to keep chronological order with a possible immediate disable.
- RULE_DISABLED("user" | "one_shot_consumed" | "expired") — EnablementCoordinator (manual),
  TimeAlarmCoordinator.deliverLocked + registerImmediate (one-shot post-fire), EXPIRED reconcile.
- RULE_ENABLED("user") — only on a successful final outcome (rollback keeps ENABLE_FAILED alone).
- RULE_DELETED("user") — AutomationDetailViewModel.onDelete (sole caller of store.delete).
- RULE_NEEDS_REVIEW("fire_policy" | "capability_lost" | "validation_failed" |
  "requirements_changed" | "planner_failed") — the 3 call sites of markNeedsReviewIfApproved,
  emitted ONLY if the transition actually happened.

All hooks use the standard non-fatal pattern (logging never changes the outcome).
UI: Italian labels in UiStateMappers + log/detail icons. DI: AuditSink injected into
CapabilityReconciler and AndroidArmedAutomationRegistrar.

Not tracked (product follow-up): the read-path quarantine in RoomAutomationStore.toDomain
(markNeedsReview on every read, no idempotency: it would spam one event per read).
Retention: already in place (AuditDao.trim, 2000 rows/30 days via RoomJournalMaintenance).

Independent --rerun-tasks gate: engine-core 230, data 68, automation-android 400, ui 36,
brain-android + app:compileDebugKotlin — BUILD SUCCESSFUL, 0 failures.

## `9c39863ec` — 2026-07-17

**feat(#50 S15): bridge attaches Hermes' real usage to /compile and /act + fail-closed hardening**

S15: run_gpt passes --usage-file to the hermes CLI and returns (output, usage). The 200 responses
of /compile and /act include an OPTIONAL "usage" field (never an explicit null: apps with strict
parsers will declare it nullable) carrying the sanitized closed subset of the real report:
input/output/total_tokens, api_calls, model, provider, cost_status, estimated_cost_usd.
Schema captured live from the CLI (cost_status="included" on the Codex plan -> cost 0).
_sanitize_usage is fail-closed: suspicious shape -> field omitted; usage is best-effort and can
never make a good response fail (temp file always cleaned up).

DEPLOY NOTE: NOT deployed to hermes. Mandatory order: app first (CliBridgeTransport has
ignoreUnknownKeys=false: an undeclared new field would break the parse), then the bridge.
Kotlin side (envelope + TurnUsage + MeteredBrain.compile) coming next.

Fixes picked up along the way:
- validate_act_request: element type-check BEFORE the set() dedup — a nested list in
  context_sources caused a TypeError instead of a clean 400 (fail-closed test now green).
- HTTP tests' mock runner: signature aligned to run_gpt(prompt, tools=...) — the 2 /act
  tests were failing with a spurious 502 since the tools (web) kwarg was added.
Bridge suite: 36/36 green (before: 29/32).

## `b644889b3` — 2026-07-17

**feat(#62): relative-delay trigger (time.afterMs) — re-armable "tra N"**

Follow-up to #61. "notificami tra 2 minuti" was compiled as an absolute time.at: re-arming
the rule did NOT restart the countdown (the instant had already passed). Now "tra N" ->
time.afterMs (RELATIVE delay in ms), anchored to the arm and therefore re-armable.

Design: an afterMs: Long? field on Trigger.Time (NOT a new sealed subtype), so it reuses
100% of the alarm machinery (reconcile/recovery/exact-inexact) without touching the exhaustive
whens elsewhere. @EncodeDefault(NEVER): when null it is OMITTED from the wire -> cron/at Time
triggers stay byte-identical and the pinned v1 fingerprints don't break. Constraint: exactly
one of {cron, at, afterMs}.

Frozen anchor: afterMs resolves to now+afterMs ONCE at arm time and is frozen onto the
persisted ScheduledTimeAlarm (TimeAlarmPlanner.next(..., existing)); without the freeze every
reconcile (APP_START/BOOT) would recompute now+afterMs, drifting forward. One-shot: fires
once and auto-disables (Trigger.Time.isOneShot() = at != null || afterMs != null);
a re-arm deletes the record and computes a new one -> the countdown restarts. Reboot: the
persisted anchor survives -> it fires at the original target (like a timer/alarm).

- engine-core: Trigger.afterMs + isOneShot(); TimeSpecs.nextFire afterMs branch; DraftValidator
  exactly-one + 1s..7d bounds (MIN/MAX_DELAY_MS).
- automation-android: TimeAlarmCoordinator anchor freeze + one-shot via isOneShot(); GenerativeActionLane.
- ui: RuleRenderMapper "Una volta, tra <umanizzato>".
- brain-android + ops/hermes/bridge.py: compile prompt "tra N"->afterMs; validate_trigger accepts
  afterMs (1s..7d bounds). Bridge deployed to hermes.

Verified: full 4-module --rerun-tasks gate green (incl. byte-stable V1FingerprintCompatibilityTest);
bridge suite +1 afterMs test. Live E2E against the deployed /compile: "tra 3 minuti"->afterMs=180000,
"ogni giorno alle 8"->cron "0 8 * * *", "subito"->immediate. APK assembled and installed.

## `c5bfd9a09` — 2026-07-17

**fix(#61): compile — disambiguate triggers (now/in N/every N) + enable recurring generatives**

Hermes compiled "notificami tra 2 minuti" as an "immediate" trigger (0 delay, fires at arm, not
re-armable). Verified from the device DB: trigger={"type":"immediate"} on a "tra 2 minuti" rule.

Compile prompt fix (app rule 15/16 + bridge 13/14): clear-cut trigger choice —
- immediate ONLY for "subito/adesso" (0 delay);
- time.at for "in N minutes/hours" (at = now+N, local ISO format WITHOUT offset/Z: the app's
  LocalDateTime.parse rejects "+02:00");
- time.cron for RECURRING ("ogni 24 ore", "ogni settimana") -> the sink re-generates on every fire.
Unblocks Lorenzo's real cases: "ogni 24 ore il prezzo BTC", "ogni settimana il risultato del Milan".

Verified live via Hermes: "tra 2 min"->time.at "2026-07-17T18:52" (no offset); "ogni 24 ore"->
time.cron "0 18 * * *"; "subito"->immediate. Bridge deployed + repo sync.

TODO follow-up (#61): relative-delay trigger for a re-armable "tra N" (today time.at is absolute,
re-arming does not restart the countdown; recurring cron rules do restart).

## `e42b7fc76` — 2026-07-17

**feat(#31): richer audit log — failed arm/scheduling/validation/enablement**

Until now the audit only recorded fire-time events (FIRED/SUPPRESSED_*/...); arm failures were not
persisted anywhere (it slowed us down while diagnosing the alarm that would not arm). Now they are.

- AuditSink: 4 new kinds VALIDATION_REJECTED, ARM_FAILED, SCHEDULING_FAILED, ENABLE_FAILED.
- Emission (AuditSink injected where the reason is known, no Boolean signature changes):
  ApprovalFlow -> VALIDATION_REJECTED (join of ValidationIssue.code) and ARM_FAILED (registrar_failed);
  TimeAlarmCoordinator -> SCHEDULING_FAILED (expired/dispatch_failed/reschedule_failed/scheduling_failed);
  AutomationEnablementCoordinator -> ENABLE_FAILED (review_required/scheduling_failed).
- Privacy: RoomAuditSink.redactedDetail extended to a CLOSED VOCABULARY for the new kinds (bounded
  snake_case code ^[a-z][a-z0-9_]{0,63}$ for validation; fixed sets for scheduling/enable). Free text
  (notifications/SMS/goal) NEVER in the detail. FIRED/SUPPRESSED unchanged.
- UI: UiStateMappers.auditSummary Italian labels + toLogOutcome -> FAILED; ExecutionLog/AutomationDetail
  screens -> red icon. The new reasons show up automatically in the Log tab.

Tests: RoomStore (redaction), ApprovalFlow (ARM/VALIDATION), EnablementCoordinator (ENABLE),
TimeAlarmCoordinator (SCHEDULING), UiStateMappers. 1318 test cases, full gate green.

## `cab3eb68d` — 2026-07-17

**fix(#41): health check by containment instead of exact schema-list equality**

A bridge redeploy that ADDS a schema version (e.g. announcing [1,2,3]) knocked old apps offline
when they pinned exactly [1,2] (Kotlin list equality = exact content+order).

Fix in CliBridgeTransport.health(): compatibility by CONTAINMENT — the bridge is compatible if it
ANNOUNCES the versions the app uses (COMPILE_SCHEMA_VERSION in compileSchemaVersions; ACT + ACT_V2 in
actSchemaVersions), not if the lists are identical. health.schemaVersion accepted if >= (not !=).
This way a redeploy that extends the contract does not break existing apps; a bridge that REMOVES a
version in use stays correctly incompatible. The SHA was already only format-checked (regex), no
change there.

Tests: [1,2,3] (superset) now ACCEPTED (new forward-compat test); [1] without the v2 in use, malformed
sha, unknown field remain rejected. hermes-bridge-contract doc updated. Bridge unchanged.

## `e63ea9a7a` — 2026-07-17

**fix(#60): notification sink actually executes — one-shot race + web timeout**

The sink compiled+armed+fired but the invoke_llm action failed (Log: "rule inactive", "act timeout").

BUG 2 (main) — generative one-shot race: a one-shot trigger (immediate, or time with `at`) disables
itself as soon as it is dispatched, but the generative lane processes async (a brain call takes
seconds); on re-validation it found status!=ARMED -> "rule_inactive". Fix in GenerativeActionLane.validate:
check fingerprint+action BEFORE the status, then status tolerant of a consumed one-shot (isOneShot =
Immediate or Time.at!=null). Safe: ApprovalFingerprints.of normalizes status/enabled -> a consumed
one-shot has the SAME fingerprint; a non-re-approved edit stays approval_changed; deleted stays
rule_missing. Reply (Notification) and disabled recurring cron rules stay rule_inactive (unchanged).

BUG 1 — "act timeout": web search via Hermes has variable latency. Fix: compile prompt sets
timeoutMs=120000 for invoke_llm with web.search; CliBridgeTransport.defaultClient 60s->125s (HTTP >=
max action timeout 120s); bridge MODEL_TIMEOUT 55->90s (deployed).

Tests: +5 lane (one-shot immediate/time-at complete while disabled; recurring cron stays inactive;
changed fingerprint->approval_changed; deleted->rule_missing) + CliBridge 125s timeout. Full gate green.

## `e27fbbf36` — 2026-07-17

**feat(#59 O4b): compile prompt + bridge — notification sink now SUPPORTED end-to-end**

Fourth wave (compile+bridge part): the model now compiles "mandami una notifica con X" instead of
rejecting it, and the /act bridge generates the text with no incoming notification.

- AgentMessageSupport (app compile prompt): rule 16 goes from "reject" to two deliver modes
  (WHATSAPP_REPLY | LOCAL_NOTIFICATION). DRAFT_SCHEMA_TEXT invoke_llm: + deliver + notificationTitle.
- bridge.py (deployed + repo sync):
  - _valid_notification_toolset (mirror of isNotificationToolset).
  - validate_act_request v1: reply vs sink branch. Sink (toolset without whatsapp_reply) accepts
    context_sources []/[state] (never notification) and context = {state} only (notification ABSENT);
    reply unchanged.
  - validate_action invoke_llm: deliver/notificationTitle fields allowed; LOCAL_NOTIFICATION validated
    (notification toolset, replyTargetSender false, title <=120, no "notification" in sources).
  - Compile rule 14: from rejection to two modes (mirror of app rule 16). invoke_llm schema updated.
  - build_act_prompt unchanged: produces {"reply_text":...} which the app maps onto the delivery (notification).

Bridge validator smoke 11/11 OK. Bridge live (source 215d519c757a). Full brain gate green.
Next: APK + live test via Hermes (arm a sink rule and verify the notification).

## `9a927ee44` — 2026-07-17

**feat(#59 O4a): act path WITHOUT a notification for the timer-driven sink (Kotlin)**

Fourth wave (Kotlin part): the notification sink on a time/immediate trigger has no incoming WhatsApp
notification. Made the notification context optional across the whole act path.

- AgentMessageSupport: requireGenerativeContextSources(sources, useReplyTool) — sink accepts [] or
  a subset of {state}, never "notification". New prompts WITHOUT WhatsApp framing: actSystemTextNotification
  (generates the text of a notification) + actUserTextNotification (state or "Genera ora..."). Reply unchanged.
- OpenAICompat/Anthropic act(): useReplyTool branch. Sink -> notification=null, no
  requireWhatsAppNotification, notification prompt. generate()/generateViaResponses/generateViaGeminiNative
  now accept a nullable notification. Reply unchanged.
- CliBridgeTransport act(): sink -> envelope with context.notification ABSENT (ActContextEnvelope.
  notification nullable @EncodeDefault(NEVER)), context_sources []/[state]. Reply envelope BYTE-INVARIANT
  (locked by test).

Sink /act wire contract (the O4b bridge implements it identically): context.notification omitted,
context.state null/object, context_sources []/[state], allowed_tools []/[web.search], no NotificationPosted requirement.

Tests: +sink on TimeFired (non-WhatsApp NOTIFICATION prompt, "notification" rejected); reply byte-identical. Full gate green.

## `5dd10108f` — 2026-07-17

**feat(#59 O3): plain-path transports for the notification sink (no reply tool)**

Third wave: when allowedTools does not contain whatsapp_reply (notification sink), the transports
produce PLAIN text instead of forcing the reply tool.

- AgentMessageSupport.requireGenerativeToolset: accepts isAllowedToolset || isNotificationToolset and
  returns useReplyTool = whatsapp_reply in allowedTools.
- OpenAICompatTransport / AnthropicMessagesTransport: generate() takes useReplyTool. false -> no
  reply tool, no forced tool_choice, system = actSystemTextPlain, text from the content. The dedicated
  web paths (OpenAI Responses / native Gemini) were already plain. true -> unchanged.
- CliBridgeTransport act/actV2: gate relaxed to isAllowedToolset || isNotificationToolset; the envelope
  forwards allowedTools verbatim (the bridge will produce plain when whatsapp_reply is missing).

Tests: +6 (OpenAICompat/Anthropic sink without reply tool + with web; CliBridge accepts [web.search]/[]).

NOTE (to close in O4): for the TIMER-driven sink (non-WhatsApp event, contextSources without notification)
the transports' act() still fails upstream on requireActContextSources/requireWhatsAppNotification —
the whole act path assumes a WhatsApp notification. O4 adds the notification-free path + bridge + compile.

## `a8d9ea11b` — 2026-07-17

**feat(#59 O2): the generative lane posts the local notification (LOCAL_NOTIFICATION)**

Second wave of the notification sink: GenerativeActionLane actually executes the LOCAL_NOTIFICATION deliver.

- AndroidGenerativeLane: new required param `notifier: AutomationNotifier`. validContract branches on
  deliver (LOCAL_NOTIFICATION = !replyTargetSender + isNotificationToolset + valid title + contextSources
  empty/subset of {state}, mirroring the DraftValidator). process(): after the text, if LOCAL_NOTIFICATION ->
  deliverNotification -> notifier.show(title=notificationTitle, text, context), SUCCEEDED; no
  NotificationPosted requirement (works from time/immediate triggers). notifier.show throwing -> FAILED
  "notify_failed". Reply path (WHATSAPP_REPLY + InvokeLlmV2) byte-invariant.
- DI: the lane receives the real notifier (AndroidAutomationNotifier, the same as ShizukuActionExecutor).
- Probe: NOT touched — the LOCAL_NOTIFICATION arm capability is ACTION_SHOW_NOTIFICATION, already
  published with notificationsGranted; invoke_llm+web.search already gated on generativeReady.

Tests: +4 lane (notification posted, from a non-notification trigger, throwing notifier, whatsapp_reply
rejected); 12 pre-existing reply tests green. Full gate green. O3 (transport plain path) and O4 (compile/bridge) remain.

## `6fb6ff7a5` — 2026-07-17

**feat(#59 O1): generative notification-sink contract (engine-core)**

First wave of the notification sink (#59): invoke_llm will be able to deliver the generated text as a
LOCAL NOTIFICATION (not just a WhatsApp reply). O1 = engine-core domain, inert until O2 (lane) + O4
(compile) wire it up — no LOCAL_NOTIFICATION rule is compilable yet.

- Action.InvokeLlm: + deliver: GenerativeDeliverMode {WHATSAPP_REPLY|LOCAL_NOTIFICATION} (default reply)
  + notificationTitle. @EncodeDefault(NEVER) on both fields -> already-approved reply rules keep the
  SHA-256 fingerprint byte-identical (V1FingerprintCompatibilityTest green). Enum without @SerialName
  -> UPPERCASE case-sensitive.
- GenerativeContract.isNotificationToolset: the sink's allowedTools = only optional web.search, NEVER
  whatsapp_reply (the sink is the notification, not a tool).
- DraftValidator.validateInvokeLlm: branches on deliver. LOCAL_NOTIFICATION = title non-blank/<=120/no
  control chars, isNotificationToolset, replyTargetSender=false, contextSources empty or subset of {state}
  (never notification). WHATSAPP_REPLY unchanged.
- CapabilityRequirements: LOCAL_NOTIFICATION requires ACTION_SHOW_NOTIFICATION (+web.search).
- RuleRenderMapper: "Genera e notifica" vs "Genera e rispondi".

Tests: serialization (UPPERCASE deliver + legacy backward-compat -> WHATSAPP_REPLY), validator (valid +
errors), capability, render. Full gate green across all modules (automation-android compiles; the lane
rejects LOCAL_NOTIFICATION at runtime -> O2).

## `55e4e1fc4` — 2026-07-17

**fix(#58): web.search armable (probe available set) + Hermes cleanly rejects the timer-generated notification**

Surfaced by Lorenzo's screenshot ("notifica cambio EUR/IDR tra 5 min" rule):
(a) F2 BUG: web.search was published in availableTools (compiler) but NOT in the `available` set
    (arm). CapabilityRequirements.InvokeLlm does addAll(allowedTools) -> web.search is REQUIRED
    at arm -> a reply+web rule compiled but did NOT arm ("capacita non disponibile:
    web.search"). Fix: AndroidCapabilityProbe adds TOOL_WEB_SEARCH to the `available` set when
    generativeReady (mirroring availableTools).
(b) UX: Hermes tried to compile "a notification generated by a timer" (P4 sink not built yet),
    producing an invalid invoke_llm (no notification context, show_notification as a tool). Now the
    compile prompt (app AgentMessageSupport rule 16 + bridge rule 14) enforces: generative delivery
    is ALWAYS a reply to an incoming notification; show_notification is NOT a generative tool;
    if the user asks for a notification GENERATED by a timer/schedule/immediate -> null draft +
    "unsupported_capability" with an explanation (lands with P4).

Tests: AndroidCapabilityProbeTest asserts web.search in availableCapabilities. Bridge deployed +
ops/hermes/bridge.py synced. Full gate green.

## `6801904c0` — 2026-07-17

**feat(#57): web search for OpenAI (Responses API) and Gemini (native API) — validated live**

OpenAI's and Gemini's web search does not go through /chat/completions. Added the two correct endpoints,
single-turn (the provider runs the web loop internally). Validated LIVE on 2026-07-17 with real keys:
- OpenAI Responses: real EUR/USD rate + exchange-rates.org/ECB citation.
- Gemini native: real EUR/USD rate + groundingMetadata.

- ProviderCatalog: WebSearchMechanism += OPENAI_RESPONSES, GEMINI_NATIVE (removed OPENAI_SEARCH,
  GEMINI_GROUNDING). OpenAI->OPENAI_RESPONSES, Gemini->GEMINI_NATIVE.
- OpenAICompatTransport.generate: when applyWeb, when(webSearch): OPENAI_RESPONSES->generateViaResponses
  (POST {base}/responses, tools:[{type:web_search}], input:[system,user], parse output->message->output_text,
  usage input/output_tokens); GEMINI_NATIVE->generateViaGeminiNative (POST {host}/v1beta/models/{model}:
  generateContent, x-goog-api-key header, tools:[{google_search:{}}], systemInstruction+contents, parse
  candidates[0].content.parts[].text, usage usageMetadata). OPENROUTER_ONLINE unchanged (:online).
- AgentMessageSupport.actSystemTextPlain: PLAIN system prompt for the web branch (no reply-tool: the
  model generates the text directly). Key redaction unchanged.

Web on direct providers now: OpenRouter + OpenAI + Gemini + Anthropic (needs credit) all covered. Full gate green.

## `6056762ce` — 2026-07-17

**chore(hermes): sync ops/hermes/bridge.py with the deployed version (web tool + immediate trigger)**

The bridge deployed on hermes had accumulated the web changes (F2: -t web, /act allowed_tools,
compile schema) and the immediate ones (validate_trigger, AVAILABLE_TRIGGER_IDS, StaticShellSafety
mirror, Rule 13). Aligning the versioned copy as the source of truth for the Codex relay.

## `fac7a3afd` — 2026-07-17

**feat(#54): onboarding — honest "what requires Shizuku" list**

In the SHIZUKU onboarding step the user sees what requires Shizuku and what degrades without it,
so they know what they cannot do. Derived from ActionPrivilege + fix #53 (activity launch from background).

- ui/UiContracts: ShizukuRequirement {REQUIRED, RECOMMENDED, NOT_REQUIRED}, ShizukuCapabilityRow
  (with real actionTypeIds), ShizukuCapabilityCatalog.rows() (invariant: every action in one row).
  OnboardingState.shizukuCapabilities.
- OnboardingViewModel: populates the list.
- OnboardingScreen: section inside the SHIZUKU step (not a new step), grouped by category.
- Categories: REQUIRED = set_wifi/set_bluetooth/write_setting/run_shell; RECOMMENDED = activity launch
  from background (alarm/timer/launch_app/open_url/open_settings, reliable only with Shizuku via am start);
  NOT_REQUIRED = volume/ringer/dnd/torch/vibration/notifications/clipboard + foreground alarm/timer.

Tests: ShizukuCapabilityCatalogTest (categories + uniqueness), OnboardingViewModelTest (state exposes the list).
Full gate green across all modules.

## `e0a4fcdeb` — 2026-07-17

**fix(web/F3): Gemini web = NONE (OpenAI-compat grounding shim unreachable) + live smoke**

Live smoke 2026-07-17 with real keys:
- OpenRouter `:online`: WORKS end-to-end (real EUR/USD rate + xe.com citation). Web validated live.
- Anthropic web_search_20250305: body accepted (only a "credit too low" error, not schema) -> format ok.
- Gemini: `extra_body.google.tools=[{google_search:{}}]` (documented format) returns 400 "Unknown name
  'tools' at 'extra_body.google'" even on gemini-3-flash-preview. Top-level OpenAI `tools` likewise.
  Google-side doc<->API mismatch: native web does not go through /chat/completions. -> Gemini webSearch=NONE
  (graceful degradation: no extra_body, no request that would 400).

Web on direct providers confirmed: OpenRouter (live) + Anthropic (format ok, needs credit). Gemini/
OpenAI = honest NONE; Hermes = bridge (F2). ProviderCatalog/OpenAICompat tests updated. Gate green.

## `e4bca2424` — 2026-07-17

**feat(web/F3): server-side web search on direct providers (Anthropic/OpenRouter/Gemini)**

Third phase of the web tool (#52). Enables web in invoke_llm on the direct cloud transports, single-turn
(the provider runs the loop internally, a single call; no client-side refactor).

- ProviderCatalog: enum WebSearchMechanism {NONE, ANTHROPIC_TOOL, OPENROUTER_ONLINE, OPENAI_SEARCH,
  GEMINI_GROUNDING} + per-provider ProviderQuirks.webSearch.
- OpenAICompatTransport: threads webRequested (web.search in allowedTools) through generate();
  OpenRouter -> model ":online"; Gemini -> extra_body.google.tools=[{google_search:{}}] (verified:
  the standard OpenAI tools is rejected by the compat layer); tool_choice=auto when web is active.
- AnthropicMessagesTransport: tools=[reply, {"type":"web_search_20250305","name":"web_search"}] +
  tool_choice auto when web is requested.
- OpenAI = NONE (honest): gpt-5.x web goes through the Responses API, not /chat/completions;
  web_search_options requires a -search-preview model. Graceful degradation (web ignored).
- AgentMessageSupport.requireReplyTool now uses isAllowedToolset (accepts [reply, web.search]).

Tests: OpenAICompat (openrouter :online, gemini extra_body, openai NONE degrades), Anthropic (server
tool), ProviderCatalog. Full gate green. Live smoke (Gemini has credit) = next step of the main loop.

## `82b6b8be2` — 2026-07-17

**fix(#55): set_volume.level is a 0-100 percentage mapped onto the stream's real max**

Field bug: `level` was used as the stream's absolute index (ALARM max ~7-16) then clamped,
so "volume at 35%" (level 35) became 100% (clamped to max). The LLM/user think in percentages.

- AndroidBaseActionExecutor.setVolume: valid `level` 0..100; maps `actual = if(level==0) 0 else
  max(1, round(level/100*maxStreamVolume))`. A percent>0 never silences (min 1); 100% = max.
- DraftValidator: range was already 0..100 (MAX_VOLUME_LEVEL=100), only the stale comment updated.
- AgentMessageSupport: the set_volume schema documents level as a 0-100 percentage.

Tests: AndroidBaseActionExecutorTest rewritten for the percentage semantics (50%@16->8, 100%->max,
1%@16->1 no-silence, 0->0, 101/-1 invalid); ShizukuActionExecutorTest routing tests adapted.
Full gate green across all modules. Bridge (set_volume description) = separate step.

## `124fe60c0` — 2026-07-17

**feat(#56): "immediate" trigger (run-once-at-arm) — root-cause fix for alarms**

A one-shot time trigger at "now" is never schedulable (the instant slips into the past between
draft->approve->schedule -> nextFire=null -> EXPIRED -> disableIfApproved -> "pianificazione
non riuscita" -> rule DISABLED). Diagnosis confirmed in the field with a screenshot: "imposti subito
una sveglia alle 12:15" -> Hermes generates trigger time=now, which fails scheduling.

Fix: new Trigger.Immediate = fires ONCE at arm time, no clock involved. The alarm/timer time lives
in the ACTION (set_alarm/set_timer), not in the trigger. No race against the clock.

engine-core (Wave A):
- Trigger.Immediate (data object, @SerialName "immediate"); CapabilityIds.TRIGGER_IMMEDIATE;
  TriggerEvent.ImmediateFired : Registered (mirror of TimeFired).
- TriggerMatcher: ImmediateFired matches ONLY an Immediate rule.
- StaticShellSafety: Immediate is a TRUSTED trigger (fires on-arm by user intent, no external
  actor) like Time/Geofence/Sensor/Connectivity.
- CapabilityRequirements/DraftValidator/RuleRenderMapper: Immediate branch.

automation-android (Wave B):
- ImmediateEventDispatcher + EngineImmediateEventDispatcher (mirror of time).
- ArmedAutomationRegistrar.registerImmediate: at arm it dispatches ImmediateFired (the engine runs
  the actions) then consumes the rule (disableIfApproved), like a one-shot time after the fire. Id
  carries a timestamp -> a re-arm re-fires.
- AndroidCapabilityProbe: trigger.immediate always available (no OS dependency) + in
  availableTriggers.
- AgentMessageSupport (compile prompt): rule 15 — one-shot "subito/adesso" commands and alarm/timer
  -> immediate trigger; time in the action, never a time trigger at an already-past instant.

Python bridge (immediate declare/map) = next step, then combined APK. Full gate green across all modules.

## `cff9a5d26` — 2026-07-17

**feat(web/F2-kotlin): probe publishes web.search, compile prompt declares it, CliBridgeTransport forwards it**

Second phase (Kotlin side) of the web tool (#52). Makes web declarable and routable towards Hermes.

- AndroidCapabilityProbe: web.search removed from PHASE_UNAVAILABLE_TOOLS (no longer always off) and
  published in available_tools when generativeReady (⇔ invoke_llm available); otherwise in
  unavailable with REASON_GENERATIVE_RUNTIME. Stays in KNOWN_TOOLS (known to the validator).
- AgentMessageSupport: compile rule 14 — invoke_llm.allowedTools may include web.search
  beyond whatsapp_reply ONLY if the goal needs online/live data (exchange rates, weather, prices, news).
- CliBridgeTransport: act/actV2 accept allowedTools via GenerativeContract.isAllowedToolset
  (no longer exact equality with [whatsapp_reply]); web.search travels in the envelope's
  `allowed_tools` field. ACT_REPLY_TOOL constant removed.

The Python bridge (run_gpt -t web,clarify + /act accepting web + compile schema) is still missing for
end-to-end web via Hermes: next step, then rebuild+install APK. Direct transports = F3.

Tests: AndroidCapabilityProbeTest (web available/unavailable per generativeReady), CliBridgeTransportTest
(act/actV2 forward [reply,web.search], reject [web.search] without reply). Full gate green.

## `7ab9f3a0d` — 2026-07-17

**feat(web/F1): generative contract accepts optional web.search alongside the reply**

First phase of the web tool (#52, light server-side plan). Relaxes the allowedTools contract
of the invoke_llm/invoke_llm_v2 actions to allow `web.search` (a read-only, server-side tool)
alongside the MANDATORY reply. No refactor: single-turn architecture intact.

- GenerativeContract: TOOL_WEB_SEARCH="web.search", OPTIONAL_TOOLS, isAllowedToolset()
  (reply present + no duplicates + the rest only read-only optional tools).
- DraftValidator: exact equality -> isAllowedToolset() in both paths (v1/v2). The per-tool
  checks (shell/automation.* forbidden, tool_unknown) remain defense in depth.
- GenerativeActionLane.validContract: same relaxation (v1 and v2).

Transports/probe/compile-prompt NOT touched (F2/F3). Next phases: Hermes bridge -t web,
CliBridgeTransport, per-provider web quirks. Design: docs/superpowers/plans/2026-07-17-argus-web-tool-server-side.md

Tests: +web cases in DraftValidatorTest (reply-only ok, reply+web ok, web-only/duplicate/shell
rejected) and GenerativeActionLaneTest (web contract accepted). Full gate green (all modules).

## `bae98fbdf` — 2026-07-17

**fix(actions): alarm/timer/settings/app reliable from background via Shizuku (BAL caveat)**

The activity-launch actions (set_alarm/set_timer/open_settings_screen/launch_app/open_url)
start from a startActivity Intent, which Android 14+/OEMs (OnePlus) block from a BACKGROUND
context. From an automation (time trigger -> receiver/AlarmManager) they failed: in the field
"Imposta sveglia 10:53" -> set_volume SUCCEEDED, set_alarm FAILED (action_failed, BAL exception
silently swallowed by guarded).

Fix (Shizuku routing, decided by Lorenzo):
- device-tools: DeviceController.setAlarm/setTimer/openSettingsScreen privileged via
  `am start` argv (shell identity uid 2000, exempt from the BAL block). Mirrors launchApp/openUrl.
- ShizukuActionExecutor: new shizukuReady signal; the 5 activity-launch actions prefer the
  privileged path when Shizuku is AUTHORIZED, otherwise BASE Intent (foreground). Manager
  actions (DND/ringer/volume/torch/vibration) stay BASE (already fine from background).
- base: ActivityStartBlockedException -> honest failure `activity_start_blocked` (in the journal)
  instead of the generic action_failed when the BASE Intent is blocked from background.
- DI: shizukuReady = gateway.status()==AUTHORIZED, re-read on every fire (revocation without restart).

Tests: +2 device-tools (am start argv + validation), +2 ShizukuActionExecutor (privileged
when ready / Managers stay base), +1 base (activity_start_blocked). Full gate green.

## `de8b17db7` — 2026-07-17

**fix(#51): UPPERCASE write_setting namespace in the compile prompt (was a case mismatch)**

SettingNamespace is an enum without @SerialName -> serializes UPPERCASE (SYSTEM),
and ArgusJson is case-sensitive (no decodeEnumsCaseInsensitive). The prompt
said lowercase 'system' -> a model following it produced JSON the app would
NOT deserialize. Aligned to UPPERCASE (consistent with the state.setting read).
Caught while verifying serialization before activating the Hermes bridge.

## `704507340` — 2026-07-17

**P3 #51 S4: BASE actions set_volume + set_flashlight + open_settings_screen + vibrate**

Fourth Android-commands slice, all BASE (no Shizuku), via Manager/Intent
not reachable through write_setting. Opus 4.8 TDD, main loop full gate
--rerun-tasks (394 green).

- set_volume(stream MEDIA|RING|ALARM|NOTIFICATION, level): AudioManager, clamped to
  getStreamMaxVolume, DND gate to silence ring/notif (volume_policy_unavailable).
- set_flashlight(on): CameraManager.setTorchMode (torch_unavailable if no flash).
- open_settings_screen(screen closed enum, pkg? for APP_DETAILS): Intent
  Settings.ACTION_* from a CLOSED enum (no sink routing), settings_screen_unresolved.
- vibrate(durationMs 1..10000): Vibrator/VibratorManager, normal VIBRATE permission.

Full compile-enforced wiring + new VolumeStream/SettingsScreen enums.
Published as ungated BASE in the probe. Client-side schema + reference updated.
Hermes bridge.py: batch activation from the main loop with Lorenzo.

## `d1deff692` — 2026-07-17

**P3 #51 S3: parametric write_setting action (PRIVILEGED, settings put)**

Absolute automation freedom: write ANY Android setting in system|secure|global
by key, the WRITE counterpart of the parametric readers.
Opus 4.8 TDD, verified by the main loop with the full --rerun-tasks gate (394 green).

DECISION (D0 wins over the design doc): PARAMETRIC with no allowlist. Invariant D2
satisfied like run_shell: CLEAN LITERAL namespace/key/value in the approved
fingerprint, never from the trigger. Guardrails = WriteSettingPolicy (QUERY_NAME-style
key regex, value <=1024 non-empty, reject NUL/newline/control chars) + human
pre-arm review (literal triple in RuleRenderMapper). No other limits.

DECISION (bug avoided): forAction derives ONLY the ACTION_WRITE_SETTING family
capability, not the per-key canonicalId - including it would brick every
rule in the stateless CapabilityReconciler (permanently structurally-missing);
the per-key binding lives in the fingerprint, like the parametric readers
(StateCompare/InvokeLlmV2 gate on family, not on canonicalId).

PRIVILEGED tier (Shizuku): argv /system/bin/settings put <ns> <key> <value>,
never sh -c. Probe publishes write_setting gated on Shizuku. Client-side schema +
reference with a run_shell-style note. Hermes bridge.py: the main loop updates it.

## `4cb96fc83` — 2026-07-17

**P3 #51 S1: set_alarm + set_timer actions (BASE, AlarmClock Intent)**

First slice of the Android interaction commands: sets Android's REAL ALARM/TIMER
via the AlarmClock Intent (not a notification), closing the gap reported by
Lorenzo. BASE tier: only the normal SET_ALARM permission, no Shizuku, no
runtime grant. Opus 4.8 TDD, verified by the main loop with the full
--rerun-tasks gate (360 green).

Full wiring (~13 spots, the exhaustive whens guarantee it): Action.kt
(ActionTypeIds+SetAlarm/SetTimer data classes+tier), ActionPrivilege (BASE),
CapabilityRequirements+ActionCapabilities, DeviceState.forAction, ExecutionJournal,
DraftValidator (hour/minute/seconds ranges+label), AndroidBaseActionExecutor+Surface
(AlarmClock Intent with EXTRAs, fail-clean alarm_app_unresolved), ShizukuActionExecutor
base-only dispatch, AndroidCapabilityProbe (always published BASE, ungated),
SET_ALARM manifest, RuleRenderMapper. Client-side schema (AgentMessageSupport +
reference) updated: direct providers generate them. Hermes bridge.py: the main
loop updates it separately.

Shizuku am-start fallback for the BAL caveat: future TODO. Physical verification
(real alarm) left to Lorenzo.

## `7815673ea` — 2026-07-17

**docs(P3): Android interaction command list + architecture + plan (#51)**

From subagent orchestration (3 Opus researchers + Fable synthesis): command list (alarm/timer BASE via Intent, brightness/dark-mode/write_setting PRIVILEGED, + toggle wave), hybrid architecture (parametric write_setting action as the WRITE counterpart of the state readers + curated typed actions), D0 security (sink authority like run_shell), Hermes coordination (bridge.py DRAFT_SCHEMA_TEXT + validator), TDD plan S1..S6. S1 = set_alarm+set_timer (BASE, closes the 'set an alarm -> only a notification' gap).

## `789d2e7f0` — 2026-07-17

**P3 #49 refinement: token-only budget for providers with no known price**

Decided by Lorenzo: dollar costs only make sense for providers with known
pricing; Hermes/OpenRouter/Custom are token-only. Implemented inline by Fable 5
xhigh in TDD, verified by the main loop with the full --rerun-tasks gate (360 green).

- ProviderSpec.costTracked: true ONLY for OpenAI/Anthropic/Gemini; Hermes/OpenRouter/
  Custom token-only (OpenRouter prices emptied: estimating from a static price list
  was fragile - 2 models out of hundreds - and we never read the real cost).
- CostEstimator: null for non-costTracked providers.
- UsageDao.tokensBetween: SUM(tokensIn)/SUM(tokensOut) aggregate per provider and
  window (null=n/a != 0).
- BudgetSettingsStore: maxTokensPerMonth (global + per-provider).
- BudgetPolicy: priced -> monthly cost cap; token-only -> monthly TOKEN cap
  (in+out); the global token cap sums ONLY the token-only providers; universal
  hourly/daily call limits unchanged; Engine cooldown intact. New LimitWindow.MONTH_TOKENS.
- UI: BudgetSection shows tokens in/out per provider+window as the primary
  metric, dollars (USD+EUR) ONLY for the priced ones; BudgetLimitsDialog with a
  "Token / mese" field. SUPPRESSED_BUDGET audit detail "month_tokens:<scope>".

Notes (from the risk reports, non-blocking): the global token cap counts only
token-only providers (priced ones stay on the cost cap); legacy OpenRouter rows
with a cost self-resolve at the month rollover. Compose visual check on device: Lorenzo.

## `4db609757` — 2026-07-17

**P3 #49 Wave 5 (budget policy + UI): S13 MeteredBrain/CostEstimator/BudgetPolicy + S14 BudgetUi**

Subagent orchestration (Fable build-book + Opus TDD), verified by the main loop
with the full --rerun-tasks --no-build-cache gate (360 tasks green).

S13 - MeteredBrain decorator registered in ArgusModule.brain() (wraps
ConfiguredBridgeBrain, the single compile/act/actV2 choke-point): pre-call BudgetPolicy
hard-block (ActResult metaError=budget_exceeded + BLOCKED_BUDGET usage_event,
no transport) or one-shot soft warning per window; post-call writes a
usage_event with usage + costMicros from CostEstimator (ProviderCatalog prices,
micro-USD, pricingVersion, unknown model->null). BudgetSettingsStore
(maxCallsPerHour/Day, maxCostPerMonthMicros, softThreshold; 0/null=unlimited).
Global + per-provider BudgetPolicy (HOUR->DAY->MONTH_COST, fail-open). New
AuditKind/ExecutionStatus.SUPPRESSED_BUDGET (engine-core) modeled on
SUPPRESSED_COOLDOWN, propagated by GenerativeActionLane and made visible in the
journal/UI (amber). The Engine's per-rule 60s cooldown UNCHANGED (the budget is
the cross-rule aggregate, separate).

S14 - Typed BudgetUi (usedHour/limitHour, usedDay/limitDay, costMonthMicros/
costLimitMicros, perProvider, softWarningActive) + real BudgetSection: hourly/daily/
MONTHLY cost progress, soft banner, per-provider breakdown, cost in USD and
EUR (fixed rate with an "estimate" disclaimer), BudgetLimitsDialog persisting the caps
to BudgetSettingsStore (onBudgetChange/Day/MonthlyCost, replacing the no-op placeholder).
SettingsViewModel reads the aggregates from UsageDao.

With this, #48 (multi-provider) and #49 (budget) are code-COMPLETE. Only
device/Hermes pieces remain: visual UI check on device, S15 (Hermes usage schema
bump), S16 (release E2E from a clean APK).

## `16fca18cd` — 2026-07-16

**P3 #49 Wave 4 (budget foundations): S11 Room usage_events + S12 ActResult.usage**

Subagent orchestration (Fable build-book + Opus TDD), verified by the main loop
with the full --rerun-tasks --no-build-cache gate (360 tasks green).

S11 - data module: append-only usage_events table (providerId, model, kind
COMPILE|ACT|ACT_V2, outcome OK|ERROR|BLOCKED_BUDGET, tokensIn/out?, costMicros?,
pricingVersion?) + timestamp index; UsageDao (insert + aggregateBetween with
nullable SUM so n/a != 0 + purgeBefore); zone/DST-safe UsageWindows (rolling hour,
day, current MONTH); versioned Room 9->10 migration with host tests
(Robolectric + MigrationTestHelper) and device; 35-day usage retention hooked into
RoomJournalMaintenance. KSP-generated 10.json schema included.

S12 - ActResult.usage: TurnUsage moves to engine-core (compat typealias in
brain-android); ActResult gains val usage: TurnUsage? = null as the last
defaulted param (text/metaError XOR intact, ActResult non-@Serializable,
proven v1 golden fingerprints unchanged); OpenAICompat/Anthropic populate
ActResult.usage from token parsing (the side-channel @Volatile lastUsage removed),
TransportBackedBrain propagates it. CliBridge/Hermes stays usage=null until S15.

NOT TESTED on device: extended MigrationTest (compiles on host, am instrument run
deferred). Next: Wave 5 = S13 MeteredBrain/CostEstimator/BudgetPolicy/
SUPPRESSED_BUDGET + S14 BudgetUi USD+EUR/monthly cap.

## `af0d3352d` — 2026-07-16

**P3 #48 Wave 3 (UI): S9 multi-provider Settings + S10 onboarding wizard**

Subagent orchestration (Fable 5 build-book + Opus 4.8 TDD), verified by the
main loop with the full --rerun-tasks --no-build-cache gate (360 tasks green).

S9 - Multi-provider Settings: TransportUi.DirectProvider + ProviderChoiceUi;
SettingsCallbacks.onSelectProvider/onSaveProviderConfig; SettingsViewModel emits
the transport branch from ProviderConfigStore's selectedProviderId() (Hermes->
CliBridge as before; direct->DirectProvider with authState from hasApiKey);
actionable TransportSection (provider selector, masked key+model editor,
provider-aware connection Test); ProviderConfigurationDialog (key never in
rememberSaveable, never echoed back, null apiKey keeps the existing one);
InArrivoChip removed from the provider branch. The API key NEVER enters the UiState.

S10 - "Scegli il cervello" onboarding: inside the existing BRAIN_CONFIG step
(no new StepKind) the user picks self-hosted Hermes or a BYOK provider
(OpenAI/Anthropic/Gemini/OpenRouter/custom); OnboardingViewModel on
ProviderConfigStore with selectProvider/saveProviderConfig (persists, re-reads
bearerToken, probes health, advances like saveBridge); onSkip keeps refusing
to skip the brain; canFinish unchanged. Key never in the onboarding state.

NOT TESTED: the Compose layer (selector chips, dialog, DirectProvider branches)
compiles (assembleDebug green) and previews render, but it must be VISUALLY
verified by Lorenzo on device. ViewModel tests: 9 (Settings) + 8 (Onboarding) green.

## `dd9414307` — 2026-07-16

**P3 #48 S8: opt-in live smoke harness (keys from env, auto-skip in CI)**

LiveApiSmokeTest hits the providers' real APIs ONLY when the ARGUS_LIVE_*_KEY
variables are in the environment; without them it auto-skips (no network, harmless
in the gate/CI). No key in the code. Smoke run by the main loop on 2026-07-16 with
Lorenzo's real keys (revoked afterwards): OpenAI (gpt-5-mini) and Gemini
(gemini-2.5-flash) real generation + @@META@@ sentinel parsed; Anthropic
(claude-sonnet-4-5) real generation; OpenRouter no-credit 402 -> TransportException
kind=BUDGET (error mapping validated). All 4 transports confirmed end-to-end.

## `db19ce8f7` — 2026-07-16

**P3 #48 Wave 2: concrete transports (S5 OpenAICompat, S6 compile, S7 Anthropic)**

Produced via subagent orchestration (Opus 4.8 TDD), verified by the main loop
with the full --rerun-tasks --no-build-cache gate (360 tasks green).

S5 - OpenAICompatTransport (a single Chat Completions adapter for OPENAI/GEMINI/
OPENROUTER/CUSTOM_OPENAI_COMPAT, parameterized by the ProviderCatalog quirks,
no if(provider==)): SINGLE-TURN act/actV2 (mirroring CliBridgeTransport),
whatsapp_reply tool + tool_choice/output-cap per quirks, usage parsing ->
TurnUsage (exposed via lastUsage pending ActResult.usage in S12), minimal
health(), error mapping 401->AUTH/402->BUDGET/429->RATE_LIMIT/4xx-5xx->HTTP/
timeout->TIMEOUT without leaking the key. Registers the 4 providers in the factory.

S6 - Client-side OpenAICompatTransport.compile: reproduces the Hermes prompt
(13 rules + draft schema) tool-less, parses the @@META@@ line with the existing
sentinel parser (CliBridgeParser) -> CompileResult; fail-soft on empty/sentinel-less/
malformed responses (typed metaError, never a crash). The key stays only in the
headers.

S7 - AnthropicMessagesTransport (Messages API): x-api-key + anthropic-version,
mandatory max_tokens, separate system, tool_use/tool_result, usage.input/
output_tokens->TurnUsage, no-credit 400/402->BUDGET. Extracted shared
AgentMessageSupport (prompt/validation/redaction) and refactored OpenAICompat
to delegate to it (behavior unchanged). Registers ANTHROPIC in the factory.

Main-loop fix: ConfiguredBridgeBrainTest - the "provider without a transport"
case (Wave 1: not_yet_implemented) is obsolete now that every provider has a
transport; rewritten around the real documented property (a factory throw
propagates outside TransportBackedBrain's try). This failure was caught ONLY
by the main loop's full gate, not by the agents' per-module gate.

Direct providers' compile()/act() stay SINGLE-TURN (the multi-turn computer-use
loop is P3-5). Next: S8 live smoke with real keys (main loop).

## `359f72f14` — 2026-07-16

**docs(P3): Hermes compile prompt reference for S6 (client-side compile)**

Extracted build_prompt + DRAFT_SCHEMA_TEXT + STATE_QUERY_SCHEMA_TEXT + SENTINEL from hermes:~/argus-bridge/bridge.py, to reuse the same compile contract on direct providers without diverging. No secrets (templates only).

## `a00ec0ff6` — 2026-07-16

**P3 #48 Wave 1: multi-provider transport foundations (S1-S4)**

Foundations with UNCHANGED behavior (Hermes continues bit-identical). Produced
via subagent orchestration (Fable 5 build-book + Opus 4.8 TDD), verified by
the main loop with the full --rerun-tasks --no-build-cache gate (360 tasks green).

S1 - transport contract: AgentTransport + TransportHealth, TransportException
(kind: CONFIGURATION/TIMEOUT/NETWORK/AUTH/HTTP/PROTOCOL/RATE_LIMIT/BUDGET) with
BridgeErrorKind/BridgeException typealiases (refactor without a rewrite),
TurnUsage (pure-data, not populated in Wave 1). CliBridgeTransport implements
AgentTransport (providerId=HERMES). HermesBrain -> TransportBackedBrain(transport)
via git mv, identical exception->metaError mapping. ChatViewModel handles the
two new kinds. NB: ActResult.usage POSTPONED to S12 (engine-core cannot depend
on brain-android where TurnUsage is born; ActResult is not @Serializable,
zero golden hash impact).

S2 - ProviderId {HERMES,OPENAI,ANTHROPIC,GEMINI,OPENROUTER,CUSTOM_OPENAI_COMPAT}
+ ProviderCatalog (authStyle, quirks forceToolChoiceAuto/outputCapParam/
extraBodyPassthrough, micro-USD prices with PRICING_VERSION). No secrets.
z.ai excluded (Lorenzo's decision). Prices from 2026 public price lists - TO REVIEW.

S3 - ProviderConfigStore generalizes BridgeConfigurationStore (selected + per
provider baseUrl/model; keys only via on-demand ProviderSecrets), reuses
AesGcmTokenCodec + Keystore (BridgeKeystore extracted), additive rollback-safe
migration from the legacy Hermes config, key anti-leak tests.

S4 - TransportFactory (HERMES->CliBridge; other providers -> TransportNotImplemented
until Wave 2) + ConfiguredBridgeBrain seam via the factory with a per-config cache
+ ArgusModule DI (brain() and the downstream graph unchanged).

NOT PROVEN (no device on the host gate): the brain-android instrumented tests
(ProviderConfigStoreInstrumentedTest, updated HermesBridgeInstrumentedTest)
compile but were not run - to verify on device before the release.

## `5c679836f` — 2026-07-16

**docs(P3): Lorenzo's decisions on multi-provider/budget (OpenRouter, no z.ai, USD+EUR, monthly cap)**

## `b1079901b` — 2026-07-16

**docs(P3): multi-provider design (#48) + budget/costs (#49) from subagent orchestration**

Plan produced by the argus-multiprovider-design workflow: 4 Opus 4.8 xhigh researchers (Brain/transport + budget codebase; OpenAI/Anthropic/Gemini/z.ai APIs) + Fable 5 xhigh synthesis. Anchored to real file:line references and 2026 API contracts. 16 TDD sub-slices with dependencies/gates + 7 open questions for Lorenzo. TO REVIEW before implementation.

## `aff245b35` — 2026-07-16

**test(harness): per-gate targeted cleanupGates (-e gate <substr>), no over-delete**

cleanupGates deleted ALL 'Argus GATE' rules: cleaning one gate also wiped another gate's still-armed ones (Lorenzo's commute geofences deleted during the sensor cleanup). Now with -e gate <substring> it removes only that gate; with no arg it cleans everything but says so on screen. Reconciles stay store-driven, so non-deleted rules stay registered.

## `966a489ae` — 2026-07-16

**P3-3: flip the Shizuku outage test -> base tier (DND keeps going, does not block)**

With the base tier active SetDnd is a BASE action (NotificationManager,
DND access implicit from the notification listener), so during a Shizuku outage
the rule MUST execute, not be blocked. ArgusShizukuOutageE2E now
verifies: FIRED (not BLOCKED_POLICY), no policy block, set_dnd journal
SUCCEEDED, DND really at TOTAL, rule still ARMED. cleanup restores DND
(it now really changes) via a restarted Shizuku. Compiles; the run stays host-driven
(prepare/verify/cleanup with the host stopping/restarting the daemon).

Closes P3-3: base tier without Shizuku complete (router, base executor, routing,
per-action probe, activation + DND gate on device, outage test updated).

## `79c3f1181` — 2026-07-16

**P3-3 B4: base-tier DND gate on device (implicit DND access via listener)**

ArgusBaseTierDndInstrumentedTest proves on the real OnePlus that, Argus being
an enabled notification listener, «Non disturbare» access is implicit
(isNotificationPolicyAccessGranted == true) and that AndroidBaseActionSurface
really changes the filter via NotificationManager (INTERRUPTION_FILTER_PRIORITY)
WITHOUT Shizuku, then restores the original state. OK (1 test).

Consequence: the «Accesso Non disturbare» system page shows
«impossibile abilitare perche' l'app ha accesso alle notifiche» because the
grant is already implicit; no DND grant row to add for the personal-full
tier. The explicit grant stays relevant only for a public profile without a
listener (P3-3 §4/§5).

## `38881e9da` — 2026-07-16

**P3-3 B4: activate the base tier (real adapter + DI + DND manifest)**

- AndroidBaseActionSurface: real NotificationManager/AudioManager/PackageManager/Intent adapter for DND/Ringer/LaunchApp/OpenUrl without Shizuku.
- Manifest: ACCESS_NOTIFICATION_POLICY ("Accesso Non disturbare" grant).
- DI: injects baseActions into the executor and baseTierActive=true into the probe, so base actions run via normal APIs and get published without Shizuku.

With Shizuku authorized the capabilities stay available (no armed rule goes into review); DND/Ringer execution now goes through NotificationManager and requires the "Non disturbare" grant, to be granted on the device. The E2E outage test flip remains (task #47).

## `a3cafd357` — 2026-07-16

**P3-3 B3: per-action capability probe (base tier, baseTierActive flag)**

Breaks up the single shizukuAvailable block: BASE capabilities are published
per-action when baseTierActive - LaunchApp/OpenUrl always, SetDnd/SetRinger
with the ACCESS_NOTIFICATION_POLICY grant (dndPolicyGranted) - while Wi-Fi/BT/
shell/readers stay gated on Shizuku. Default baseTierActive=false =
legacy behavior unchanged (existing tests green); the flip is activated by
the DI in B4, aligned with the executor that actually runs base actions without
privileges. New host tests cover the three cases (base active/grant, base
active/no-grant, base inactive).

## `25f275c6c` — 2026-07-16

**fix(settings): granted background location must not appear greyed out**

backgroundLocationState put NOT_NEEDED before the real grant: with
«sempre/precisa» granted but no geofence rule armed, the row stayed grey
and looked unapplied (reported by Lorenzo). Now a full grant (bg+fg) always
reads GRANTED; NOT_NEEDED only when truly not granted and not required.
Host test AndroidUiHealthTest (RED reproduced the bug).

## `9c895b9a4` — 2026-07-16

**docs(P3-3): A/B1/B2 landed status + B3/B4 roadmap (DND grant)**

## `e23c9dc26` — 2026-07-16

**P3-3: base/privileged routing in the executor (optional injection)**

ShizukuActionExecutor accepts an optional AndroidBaseActionExecutor: when
present, DND/Ringer/LaunchApp/OpenUrl go through the normal Android APIs and
NO longer through the Shizuku shell; the privileged ones (Wi-Fi/BT/shell/tap/input)
stay on tools. Default null = legacy behavior unchanged, so Lorenzo's
phone does not change until the DI activates the base tier (B4, which will
require the DND-access grant). Host routing tests + regressions green.

## `326043c68` — 2026-07-16

**P3-3: base executor without Shizuku (DND/Ringer/LaunchApp/OpenUrl)**

AndroidBaseActionExecutor runs the BASE actions with normal Android APIs
through an injectable seam (BaseActionSurface): mode mapping, DND grant
policy and package/URL validation are pure-JVM and host-tested; the real
NotificationManager/AudioManager/Intent adapter will be thin. Missing grant ->
typed failure (dnd_policy_unavailable/ringer_policy_unavailable),
never a crash nor a block. Not yet wired into the live executor.

## `a586556d4` — 2026-07-16

**P3-3: exhaustive ActionPrivilege router (BASE vs PRIVILEGED)**

Closed action->tier classifier in engine-core (decision record §7.3,
P3-3 plan §1): separates actions runnable with normal Android APIs (BASE)
from those requiring the Shizuku shell (PRIVILEGED). Exhaustive `when`
with no else: a new Action does not compile until its privilege is
declared. Foundation for the executor split and the per-action capability
probe of the upcoming commits; no consumer rewired yet.

## `bcbeacbeb` — 2026-07-16

**P3-2B: negative sensor gate (disable/delete/no-leak) on device**

Adds ArgusSensorNegativeInstrumentedTest (same process, production chain)
proving the properties complementary to the positive gate:
- disable -> onSensorTriggered produces no FIRED;
- delete  -> onSensorTriggered produces no FIRED;
- deleting the last rule of a kind -> the reconcile deregisters the
  physical sensor (no-leak): cleanupSucceeded, empty requiredBy, kind out
  of registeredKinds.

Run on the OnePlus: OK (3 tests). dumpsys sensorservice confirms 0 live
dev.argus connections at gate end (register at arm, unregister at
reconcile on every pid). Only the physical process-restart and the FGS
power/time measurement remain for Lorenzo.

## `73d03c133` — 2026-07-16

**docs(argus): record the physical sensor gate result and the method lesson**

Significant-motion physical gate passed in substance (2026-07-16 with Lorenzo):
effect+routing from the real notification seen while walking, callback+rearm from
the backend and framework logs, chain down to the journal from the synthetic test.
Records the lesson: reading the journal with am instrument kills the process and
the async write does not commit - not a bug, it is the reading method. The
disable/delete/process-restart observations and the FGS measurement remain.

## `39e6ae7e4` — 2026-07-16

**test(sensor): production-path synthetic proves the sensor pipeline end to end**

Calls sensorEventIngress().onSensorTriggered in the same process, with an armed
rule, and verifies FIRED + show_notification SUCCEEDED in the journal. Passes.

Clears up a false alarm from the physical gate: in the field the callback was there
("sensor trigger" backend log x2) and the sensor re-armed (framework re-registration,
same pid), but reportGates via `am instrument` showed an empty journal. Cause:
am instrument kills and recreates the app process, and the journal's async write
(scope.launch in the callback) did not manage to commit in time. The chain is
correct: the reading tool was destroying the data. The real effect was already
proven by the "movimento significativo" notification Lorenzo saw while walking.

## `bd9565395` — 2026-07-16

**test(sensor): stage the physical significant-motion gate harness**

armSensorGate arms a harmless significant-motion rule, ready for Lorenzo's
physical gate (move the phone -> one execution -> rearm). It must not be launched
until Lorenzo is ready: the FGS stays on until cleanupGates. Installed
but NOT armed.

## `d765c85e6` — 2026-07-16

**test(sensor): probe publishes significant-motion on real hardware**

Read-only instrumented test (touches no store or rules): on the OnePlus the probe
publishes sensor.significant_motion now that the backend is wired. Closes point 1
of the device gate without Lorenzo. availableTriggers contains sensor.significant_motion
= true; Lorenzo's rules intact after the reinstall.

## `c40e6a915` — 2026-07-16

**docs(argus): record P3-2B host-complete, ready for physical gate**

Significant-motion implementation closed on the host side in TDD; status READY FOR
PHYSICAL GATE (not COMPLETE) because movements, process restart and the
disable/delete negatives require Lorenzo. Maps the handoff's 20 tests to the real
coverage: what is in the new suite, what is covered by composition/construction,
what was already Codex's in P3-2A. Full gate 759/759.

## `555bef031` — 2026-07-16

**test(sensor): a corrupt detection record fails closed**

A wire name of a nonexistent kind in the prefs must make the pending null, not
crash: no redelivery on corrupt data.

## `204de74c4` — 2026-07-16

**feat(sensor): wire the significant-motion runtime and publish the capability**

Wires the sensor runtime into the lifecycle and ENABLES the capability in the same
slice as the real backend, as decision record 6.4 prescribes: the probe publishes
sensor.significant_motion only in the intersection of hardware + wired backend.

- registrar: Trigger.Sensor branch -> sensor.reconcile(), pre-check on the
  granular capability, excludes needsReview/failed;
- enablement and runtime controller: sensor reconcile on enable/disable/rollback and
  on APP_START/boot/package (one-shots do not survive process death). Pending
  recovery uses requiredBy as the authorized set, without handing the store
  to the controller;
- DI: a single SharedForegroundSentinel owns the FGS backend; connectivity and
  sensors take its demand view, so the connectivity coordinator stays
  unchanged. The backend->ingress->coordinator->backend cycle is broken by a
  Provider<SensorEventIngress> resolved only at the first callback;
- IMPLEMENTED_SENSOR_KINDS = {SIGNIFICANT_MOTION}.

Hilt graph green (no cycles/missing bindings), automation-android suite
unchanged, AndroidTest compiled.

## `b5ed5a09d` — 2026-07-16

**feat(sensor): android significant-motion backend and persisted detection store**

Backend via SensorManager.requestTriggerSensor (one-shot, not registerListener),
not Shizuku: the capability survives a Shizuku outage. The listener removes
itself on fire and delegates to the ingress; it never touches TriggerEvent.values.
The backend does not trust the probe-computed state: it rejects mode != ONE_SHOT
or wakeUp=false. Robolectric covers only the negative paths (wrong kind, missing
sensor, cancel no-op); the positive path is the physical gate.

Detection store on SharedPreferences with synchronous commit: the only persisted
sensor state. Corruption-safe (malformed fingerprint/kind/sequence -> pending
null, never a crash). Stable event id on redelivery, monotonic sequence for the
next detection, a pending from a superseded revision does not match.

## `af8e98c34` — 2026-07-16

**feat(sensor): share one foreground sentinel between connectivity and sensors**

P3 decision record 6.2 mandates a single FGS with shared demand reasons, not
one service per domain. SharedForegroundSentinel owns the single backend and
keeps it on as long as at least one domain demands it: removing Wi-Fi does not
turn off the sensor and vice versa.

demandBackend(reason) exposes the sentinel as a ConnectivitySentinelBackend for a
single demand, so the connectivity coordinator uses it WITHOUT changes (only the
wiring changes) and the sensor coordinator declares its own demand when it has
at least one registered kind. start/stop fire only on the empty<->non-empty
transition: no redundant FGS start or stop.

## `b2e9919e0` — 2026-07-16

**feat(sensor): add the significant-motion coordinator and ingress core**

Logical core of the P3-2B sensor runtime, JVM-tested (14 tests). Does not
publish the capability yet: the physical backend and the wiring land in the
next slices, and IMPLEMENTED_SENSOR_KINDS stays empty until the chain is
complete.

Coordinator: SHARED physical registration per kind (multiple rules of the same
kind = a single requestTriggerSensor, fan-out downstream). The registered state
lives ONLY in memory: after process recreation it is empty and the reconcile
re-registers from the desired demand, so a crash never leaves a false
"registered but dead". Structural unavailable -> NEEDS_REVIEW, transient failure
-> bounded retry.

Ingress: a one-shot sensor deactivates itself on fire, so the callback marks
consumed first of all and always re-arms in NonCancellable, without swallowing
the CancellationException (a non-rearmed one-shot is a sensor silently dead).
Stable event id on redelivery via the pending detection; the next detection
gets a new one. The old revision's fingerprint does not match after an edit.
Never actions from the listener: only envelopes towards the Engine.

## `bf487b460` — 2026-07-16

**docs(argus): hand P3 sensor runtime to Claude**

## `055499c78` — 2026-07-16

**feat(argus): define sensor trigger capabilities**

## `1ca0f14ca` — 2026-07-16

**test(argus): close the live act v2 gate**

## `0e6006f9c` — 2026-07-16

**feat(argus): add classified minimal state to act v2**

## `2e6926faa` — 2026-07-16

**test(argus): close the live bridge v2 gate**

## `462bc8362` — 2026-07-16

**feat(bridge): compile typed state readers with v2**

## `7db7a6fbb` — 2026-07-16

**feat(state): add typed parametric readers**

## `e3fa20293` — 2026-07-16

**feat(engine): read only state required by each rule**

## `450c7f550` — 2026-07-16

**docs(argus): lock P3 decisions and compatibility boundaries**

## `a9e243623` — 2026-07-16

**fix(engine): keep unavailable conditions fail closed**

## `6b307c597` — 2026-07-16

**fix(argus): harden geofence evidence and shell routing**

## `c6dee11a9` — 2026-07-16

**docs(argus): hand P2 back to Codex closed, with the reversals he doesn't know**

P2 closed on master with line-by-line evidence. The handoff puts up front what
Codex cannot deduce from the diffs: Lorenzo's decisions that OVERTURN things
written as final (shell from a whitelisted contact, B8 downgraded from a
barrier to a trade-off, sensors reopened) and the D0 principle with the
four-class taxonomy.

It also records my mistakes: the Wi-Fi feedback loop overestimated when the
contrary evidence was already in my own output, the cooldown 0 in the harness
and the cleanup that does not cancel OS registrations.

Plus a new trap on his point 9: the repo/host sha256 comparison lies on Windows
because of CRLF.

## `5b2efd8fe` — 2026-07-15

**fix(ui): round geofence coordinates and close the physical gate**

The review showed 15.266659599999999: Double.toString exposes the IEEE 754
representation to someone who only needs to recognize a place. Five decimals
are worth ~1 m, and on radii of hundreds of meters the rest is noise. Found by
Lorenzo on the device.

Also closes the last point of the P2 DoD. On 2026-07-15 at 20:03 and 20:08
Lorenzo crossed two real boundaries: FIRED once each, all actions succeeded.
The same rules, same radius, same place, two hours earlier fired twice in
three minutes with the device still: fix 4339244 holds in the field, not
just in the unit tests.

We make no latency promise: two edges within the minute are not a statistic and
the declared expectation remains E14's.

## `c0386041f` — 2026-07-15

**Merge branch feat/argus-p2-background: P2 background triggers**

Telephony (SMS + calls), OTP autocopy, Connectivity (BT/cable/Wi-Fi with an
on-demand FGS sentinel), durable Geofence via LocationManager, static shell from
trusted triggers, crash-consistency and linear-time OTP regex.

PHYSICAL gates passed with Lorenzo on 2026-07-15: real SMS/OTP, real call
(FIRED x1 + duplicate suppressed, i.e. fix dea6f79 confirmed in the field and not
just in the unit tests), cable, Bluetooth ACL. On-demand FGS observed appearing
after the arm and disappearing after the cleanup.

Example 1 geofence: passed with a SIMULATED position, with the phone's real
state verified (wifi_on=0, bluetooth_on=1). The PHYSICAL crossing stays a
post-merge observational gate: residual risk explicitly accepted by Lorenzo,
NOT declared a PASS.

The physical gate paid off immediately: it found a geofence flapping that no
synthetic test could show, because in the mock the position is decided by the
test (fix in 4339244).

By Lorenzo's decision, the shell can now also be triggered from a whitelisted
1:1 WhatsApp chat (f29e8fa): the previous ban was broader than his ethical
line. SMS and calls stay excluded because the identity is spoofable.

Pre-merge audit: 12 points, no blocking finding. Full gate 758/758 green after
every code change.

## `7024618b2` — 2026-07-15

**docs(argus): record the field flapping bug and the shell whitelist decision**

Plan: the geofence section now contains the PROVEN diagnosis of the flapping
(two consecutive EXITs are impossible by construction, so the intermediate ENTER
was certain even without seeing it) and the fix. It also records the correction
to a hasty analysis of ours: we had said Example 1 sabotages its own sensor by
turning off Wi-Fi, but "Wifi scanning is always available" was already in our
output and we had ignored it.

DoD: the physical Example 1 does NOT become a PASS because it's convenient. It
is recorded as a residual risk explicitly accepted by Lorenzo, which is a path
Codex's DoD anticipated.

Design: C2/E14 now say the framework can announce FALSE edges on a stationary
device, not just miss real ones. 10.2 reflects Lorenzo's revocation of the shell
ban, with the reason PhoneState stays out (spoofable identity, a real limit and
not caution). The header no longer declares P2 in progress.

## `4339244c9` — 2026-07-15

**fix(geofence): refuse a framework edge the real position contradicts**

Field bug 2026-07-15: device stationary at the center of a 200 m area, two EXITs
in three minutes, Wi-Fi turned off twice. Dedup couldn't notice: between the two
EXITs an equally spurious ENTER had arrived, and to the store they were real
edges. A repeated EXIT is impossible by construction, so the ping-pong was
certain even without seeing it (an EXIT-only rule doesn't match the ENTER and
doesn't log it).

The design promised hysteresis against noise but implemented it only in
post-crash recovery. Now the same defense, with the same 25 m margin, also
covers the normal path: an edge contradicted by the position does not advance
the sequence, otherwise dedup would treat it as if it really happened.

Deliberate fail-open: without a readable position the edge is accepted. Losing a
real crossing is worse than accepting a spurious one, and without a position we
have no basis to contradict the framework, which stays the primary signal. Near
the circumference, within the hysteresis, the last word stays with it.

The verifier depends on a trigger lookup, not on the whole
AutomationStore.

## `f29e8fa7c` — 2026-07-15

**feat(shell): let a whitelisted contact trigger an approved command**

Lorenzo's decision: he revokes his own previous ban. The rule was broader than
his ethical line — it also forbade STARTING a static command, not just choosing
it. Injection stays impossible by construction: the cmd is literal in the
fingerprint, the message is a switch. Only who can press it changes.

SMS and calls stay excluded, and not out of caution: sender and caller ID are
spoofable, so no whitelist can make them an identity. The WhatsApp
conversationId, on the other hand, is a stable key (E15).

The identity is verified three times in agreement (validator, FirePolicy,
executor) and on the REAL event, not just on the declared trigger. The executor
receives the whitelist with an empty default: whoever forgets to wire it gets
the closed behavior. The whitelist is re-read on every fire, so removing a
contact revokes its shell immediately.

The review gains shell_contact_trigger: the new risk is not what it runs but
that the contact chooses the moment.

Harness: the gates now arm with a 5 min cooldown instead of 0. The default 0
left geofence rules exposed to the position engine's flapping, against which the
design (C2) prescribes exactly a per-rule cooldown.

## `c59b3d42d` — 2026-07-15

**docs(argus): record the pre-merge audit with twelve verdicts**

Twelve points from the Codex 5 handoff, no blocking finding. The inherited lines
are marked as such: pending/ack persistence and lifecycle stay Codex evidence
not re-run, not my conclusions.

Two defenses deserve mention because they must not be forgotten: StaticShellSafety
is the single source with exhaustive when over the sealed types, so a new trigger
family doesn't compile until you decide whether the shell is allowed; and
PrefsCallStateStore prevents, with a runtime require, an SMS from entering the
persisted pending.

## `881e876d5` — 2026-07-15

**docs(argus): record the physical gate evidence and correct three misclassed limits**

P2 plan: call/cable/BT closed as physical E2E with journal evidence (the call
confirms dea6f79 in the field: second broadcast SUPPRESSED_DUPLICATE). Geofence
explicitly separates the PASS with a simulated position from the still-open
physical crossing, as the Codex 3.2 handoff requires. DoD is now a matrix with
the evidence class per row: only one point stays open.

Design: D0 records Lorenzo's directive (ethical-only limits) with the four-class
taxonomy. B8 downgraded from "insurmountable barrier" to a latency trade-off: for
unattended automation the free brain is enough, so the interactive loop becomes
two tiers and the paid one is optional. Sensors and DWELL brought back to their
real class.

## `7712afc2f` — 2026-07-15

**test(gates): add the physical trigger harness for P2 radio edges**

The physical gates can't inject the event: the harness arms the diagnostic rules
and lets the process die, so modem/cable/radio/framework really deliver. Hence
the three phases (arm* -> physical action -> report/cleanup), also necessary
because a walk for the geofence exceeds any timeout.

reportGates never prints the detail field and asserts it contains no run of
digits: printing it would be the PII leak the gate must exclude. reportInventory
counts the rules without revealing their content.

The address geocoding happens on the device: it doesn't enter the repo.

## `45f95c68b` — 2026-07-15

**docs(argus): prescribe Claude P2 closeout and P3 planning**

## `28d1e86cd` — 2026-07-15

**docs(argus): hand P2 back to Claude at quota stop**

## `e5f77a7c3` — 2026-07-15

**fix(action): bound extraction regex to linear time**

## `a7ee8b380` — 2026-07-15

**fix(background): recover durable trigger edges**

## `8215895b8` — 2026-07-15

**feat(geofence): add durable framework background triggers**

## `f2bca8ede` — 2026-07-15

**feat(connectivity): add on-device background triggers**

## `cfc0ef4ca` — 2026-07-15

**feat(shell): run approved static commands on trusted triggers**

## `dea6f7923` — 2026-07-15

**fix(phone): preserve caller identity across duplicate broadcasts**

## `04e67217d` — 2026-07-15

**test(phone): prove production SMS pipeline on device**

## `0c337e862` — 2026-07-15

**docs(argus): hand P2 back to Codex with proven state and open questions**

Reverse handoff Claude -> Codex: snapshot verified today (green gate on 02592b2,
deployed bridge identical to the repo, device permissions), the 13 P2 commits
with the intent behind them, the traps not deducible from the diffs (Hilt entry
points on the receivers, encodeDefaults and fingerprint, textMatch), Lorenzo's
explicit directives and the list of what is NOT proven.

Corrects the wrong diagnosis of the "Shizuku race" in ledger 23: the run_shell
refusal was structural, not a race.

## `02592b2a9` — 2026-07-14

**fix(phone): resolve receiver dependencies via explicit Hilt entry points**

The first P2-2 receivers used @AndroidEntryPoint field injection which
lives in the generated super.onReceive - never called (and not callable:
BroadcastReceiver.onReceive is abstract at compile time), so ingress
stayed uninitialized and no SMS rule ever fired (found live by Lorenzo).
EntryPointAccessors is safe here: Application.onCreate precedes every
manifest receiver delivery. Adds privacy-safe diagnostics (counts and
states only, TAG ArgusPhone).

## `9c3d2f190` — 2026-07-14

**feat(action): copy_to_clipboard with deterministic regex extraction (OTP)**

P2-3: new DETERMINISTIC action copies the textual trigger payload (SMS
or notification) to the clipboard, optionally reduced to the first
capture group of a draft-visible regex; the clip is marked sensitive
so codes never show in system previews and the text never leaves the
device. Honest failures leave the clipboard untouched (otp_not_found,
clipboard_source_missing); the validator rejects non-textual triggers
and uncompilable/oversized regexes; the review renders the regex
integrally. Probe publishes the tool unconditionally; bridge schema,
validator (re.compile, 512 max) and prompt updated and deployed
(17/17, unit active). Removes the P2-0 clipboard spike.

## `4f2e3b222` — 2026-07-14

**fix(engine): title-only notifications are armable**

The bridge accepts an empty notification body (a title-only Android
notification is legitimate) but the client validator demanded both
fields, blocking arm with "Campo obbligatorio" - found live by Lorenzo
on the first SMS rule. Title stays required, the body is bounded only.

## `b67337561` — 2026-07-14

**feat(engine): sms triggers can filter on message text**

Found live by Lorenzo: Hermes correctly refused "SMS containing X"
because Trigger.PhoneState had no text filter. textMatch (contains,
case-insensitive, valid only with SMS_RECEIVED - the validator rejects
it on calls) matches the volatile smsText, renders integrally in the
review line and is accepted by the bridge draft schema/validator.
Safe now: no phone_state rule exists yet, so approved fingerprints
are untouched. Deployed on hermes (16/16, unit active).

## `22cdfb926` — 2026-07-14

**fix(settings): every health row is tappable in any state**

Beyond the WARN "Correggi" and the opt-in "Attiva" buttons, the whole
row now opens its related panel/permission on tap (green rows too, for
inspection/revoke; the NOT_NEEDED location row can pre-grant ahead of
geofence rules). Found live by Lorenzo.

## `5de9853a8` — 2026-07-14

**fix(settings): opt-in health rows get an explicit Attiva button**

HealthRow wired onFix only to the WARN-state "Correggi" button, so the
NEUTRAL telephony rows were dead to the touch (found live by Lorenzo).
Opt-in rows now render an explicit action button via actionLabel.

## `f9d9bd31b` — 2026-07-14

**feat(brain): carry available triggers over the wire and enforce them in compile**

The manifest envelope gains available_triggers; the bridge accepts it
as an optional bounded list (legacy clients unchanged, unknown keys
still rejected) and RULE 10 makes the model compile ONLY listed
triggers, answering unsupported_capability with the exact Sistema row
that unlocks the missing grant. Deployed on hermes (backup
bridge.py.pre-triggers-rule-20260714, 16/16 tests, unit active).

## `ce3065064` — 2026-07-14

**feat(settings): one-tap telephony trigger grants in the system screen**

Two always-actionable health rows (SMS triggers, call triggers) with
NEUTRAL state while not granted (opt-in, not a health problem): the
tap launches the runtime permission request directly (calls also ask
READ_CALL_LOG, without which PHONE_STATE carries no number and
"call from X" rules cannot match). Status refreshes on grant result.

## `e3c5f4908` — 2026-07-14

**feat(phone): publish telephony capabilities and arm phone_state rules**

P2-2b part 2: the probe derives trigger.phone_state.sms/.call from the
real RECEIVE_SMS / READ_PHONE_STATE grants; the manifest gains an
availableTriggers list (rendered as "TRIGGER DISPONIBILI" only when
present, legacy manifests unchanged) so Hermes never proposes a dead
trigger; the registrar arms broadcast-backed phone_state rules against
their granular capability, everything else stays fail-closed.

## `b9bfd3f04` — 2026-07-14

**feat(phone): add the telephony event channel (SMS + call state)**

P2-2b part 1: manifest receivers (SMS_RECEIVED guarded by the system
BROADCAST_SMS permission, PHONE_STATE) delegate to a JVM-tested
PhoneEventIngress that recomposes multipart SMS per sender, treats
call-state TRANSITIONS (duplicate framework broadcasts are no-ops,
IDLE counts as call-ended only after RINGING/OFFHOOK via a persisted
last-state) and dispatches to the engine. Capability ids split per
event kind (trigger.phone_state.sms/.call) because the OS grants
differ. SMS text stays RAM-only end to end.

## `306f8fe75` — 2026-07-14

**feat(engine): add phone event parser with volatile sms text**

P2-2a: TriggerEvent.PhoneStateChanged gains smsText (RAM-only, needed
by the P2-3 clipboard action); PhoneEventParser produces fail-closed
envelopes where neither the number nor the text ever reach the event
id in cleartext (digest only), with the same bounds/control-char
policy as the notification parser.

## `e7e1dc57a` — 2026-07-14

**docs(argus): record the clipboard spike result and reorder P2 by value**

Background setPrimaryClip works on real Android 16 (verified by a real
paste): OTP autocopy proceeds with the clean design. Execution order
now puts PhoneState/SMS and OTP first.

## `0291216e3` — 2026-07-14

**docs(argus): add the P2 plan - background triggers, PhoneState/SMS, OTP autocopy, geofence**

Scoped from the design master (par. 15), the field backlog (handoff par. 23)
and Lorenzo's live mandate: shell stays unlimited-with-review, OTP autocopy
is the headline feature, sensor triggers are out for good.

## `0af4ac37a` — 2026-07-14

**docs(argus): record field observations and the P2 backlog from live feedback**

## `0751bfc5c` — 2026-07-14

**docs(argus): record the post-P1 UX polish round in the handoff ledger**

## `c7db6156e` — 2026-07-14

**Merge branch fix/p1-ux-polish: post-P1 UX feedback round**

Real overflow menu in chat (clear conversation, Hermes health check),
readable budget card copy, impersonal armable chat examples.
Verified on device (menu opens, examples shown, budget card legible).

## `8d252ed26` — 2026-07-14

**fix(ui): readable budget card and impersonal armable chat examples**

The budget section rendered a descriptive sentence inside the hourly
counter slot (monospace, squeezed, hyphenated to bits). With no active
hourly limit it now renders as plain full-width text, in user words
(no "P3" jargon); the counter+progress layout stays for when a real
limit ships. Chat suggestions no longer reference personal contacts
and only propose rules armable with current capabilities (time or
notification triggers - the geofence example returns with P2).

## `542f9794b` — 2026-07-14

**feat(chat): make the header overflow menu real**

The three-dot icon was decorative (no click target). It now opens a
menu with "Svuota conversazione" (clears messages/notices, keeps
pending draft cards - they are the approval channel, not history) and
"Verifica connessione Hermes" (re-runs the health check).

## `35c1da988` — 2026-07-14

**docs(argus): record the master merge in the handoff ledger**

## `8f283ca46` — 2026-07-14

**Merge branch feat/argus-p0b-dry: P0-B production runtime + P1 generative notifications**

All external gates green as of 2026-07-14 evening (with Lorenzo on the
physical device): reboot recovery + Android 16 LNP, degraded fail-closed,
live Example 3 (8.5s e2e generative WhatsApp reply), real WhatsApp
characterization (4 real bugs found and fixed, including structural
anti-echo), full no-cache gate (331 tests, lint clean) and read-only
smoke of the six screens on the production install.

## `e86520170` — 2026-07-14

**docs(argus): close P1-8 in the handoff with full gate and smoke evidence**

## `d3c93a744` — 2026-07-14

**docs(argus): close P0-B external gates and record P1 status across docs**

Audit conditions 3 (reboot/LNP) and 6 (live compile rerun) are done as
of the 2026-07-14 evening session; CLAUDE.md reflects P0-B gates all
green plus the P1 status; bridge contract documents the server-side
compile prompt rules including RULE 9 (whitelisted conversationId +
explicit isGroup=false for WhatsApp replies); commander replan checkbox
closed with evidence.

## `e359b4b92` — 2026-07-14

**docs(argus): record post-clean-install rerun findings from the P0-B session**

Preserves the concurrent Codex edits verbatim: production rerun after
clean install (Shizuku re-granted for the new UID, compile stopped by
provider quota as an honest 503, no draft/mutations), the UID/grant
caveat, and the follow-up condition to repeat the live compile when the
provider returns.

## `3e525998b` — 2026-07-14

**docs(argus): record real P1-7 completion and evening fixes in the handoff**

Section 21: live Example 3 green (8.5s e2e), four real bugs found by
characterization and fixed (base64 key, invoke_llm manifest, bridge
rule 9, reply echo), UX fixes, minor non-blocking residues. Status
table and commit ledger updated through 758f5e9.

## `758f5e9d6` — 2026-07-14

**feat(ui): review shows trusted whitelist names instead of conversation hashes**

The approval card rendered raw hashed conversation ids
("shortcut:com.whatsapp:62be..."). RuleRenderMapper now accepts a
conversationId -> displayName map resolved from ContactWhitelistStore
(the trusted store, never LLM prose) and renders
"da Ottica Marci (identita verificata, chat 1:1)".

Faithful by construction: TriggerMatcher gives conversationId exclusive
precedence over sender, so the whitelisted name IS the real match
criterion. Without a trusted label the full hash stays visible.

Chat draft cards, detail screen and list rows all resolve labels
reactively (whitelist changes re-render open cards).

## `b4457611e` — 2026-07-14

**fix(notification): never fire on reply echoes and key events by message time**

Second live P1-7 finding, spotted by Lorenzo: 8.5s after the generative
reply was delivered the rule fired again and only the 60s generative
cooldown suppressed it. WhatsApp reposts the conversation notification
with our own reply as the latest MessagingStyle message, and the event
id (digest over post time and title) treated that repost as a fresh
event. Two structural fixes, both TDD with a mutation check:

- The snapshot now carries the latest message timestamp and whether the
  latest message is authored by the user (null sender convention or
  match against EXTRA_MESSAGING_PERSON). The parser drops self-authored
  updates outright: "when X writes to me" never includes my own reply,
  so the anti-loop no longer depends on the cooldown alone.
- The event id is now keyed on the MessagingStyle message timestamp
  (post time only as fallback) and the title left the digest: cosmetic
  reposts of the same message become honest SUPPRESSED_DUPLICATE claims
  instead of new events.

Group negative also verified live: a message from the whitelisted
contact inside a group was observed as isGroup=1 and produced zero
fires.

## `a34b6d656` — 2026-07-14

**fix(notification): survive real WhatsApp metadata and advertise invoke_llm**

P1-7 real-device characterization findings, all reproduced then fixed
in TDD:

- WhatsApp notification tags are Base64.DEFAULT with a trailing
  newline, so the system notification key contains a control char. The
  parser and the reply gateway treated that as malformed and dropped
  every real conversation; the key is an opaque system identifier and
  is now accepted (length/blank checks stay), kept intact for registry
  and gateway matching, and still only ever hashed into event IDs.
- The Hermes compile prompt only offers action types listed in
  manifest.available_tools, and the probe never listed invoke_llm
  there: the model honestly fell back to a static reply. The manifest
  now advertises invoke_llm when the generative runtime is ready and
  explains the missing readiness otherwise.
- New binding rule 9 in the bridge compile prompt: WhatsApp replies
  require a whitelisted conversationId and an EXPLICIT isGroup=false
  (the validator already rejected the null tri-state, blocking Arm),
  and generated replies must use the exact P1 invoke_llm profile.
  Deployed to hermes (backup kept, unit restarted, 15/15 host tests).
- The ingress now logs privacy-safe outcomes only (package + booleans)
  after debugging a silent listener; chat input (and every screen) now
  lifts with the IME via padding+consumeWindowInsets+imePadding, since
  edge-to-edge on target 36 ignores adjustResize alone.

Live evidence on the OnePlus: real 1:1 message -> generative reply
delivered in 8.5s end-to-end (bridge act 7.4s, gpt-5.5), journal
SUCCEEDED, and a second message within 60s correctly
SUPPRESSED_COOLDOWN. Example 3 of the master spec is green.

## `df15f94ff` — 2026-07-14

**docs(argus): close the P0-B reboot and LNP gate in the handoff**

## `2ca4a10d6` — 2026-07-14

**test(app): add LNP wifi probe and network state permission**

Reboot gate 5.3.3-4 tooling: LocalNetworkProbeInstrumentedTest opens a
socket from the Wi-Fi Network SocketFactory (never a plain Socket that
Tailscale tun0 would absorb) against a caller-supplied LAN endpoint and
asserts the expected allowed/denied outcome, with Tailscale suspended
as the bridge contract prescribes. ACCESS_NETWORK_STATE (normal
permission) joins the manifest: ConnectivityManager callbacks require
it and the app does networking anyway.

Executed live on the OnePlus (Android 16): flag enabled at boot ->
LAN denied cleanly while Hermes over Tailscale stayed reachable from
the app (46 ms health), flag reset + reboot -> LAN baseline restored.

## `4b1151a20` — 2026-07-14

**docs(argus): record synthetic P1-7 completion in the handoff**

## `81750f2f5` — 2026-07-14

**test(automation): add synthetic generative end-to-end coverage**

P1-7 synthetic. GenerativeEndToEndTest (host, Robolectric) stitches the
real production components together the way DI does: synthetic WhatsApp
StatusBarNotification -> NotificationIngress/parser -> rule armed via
the real draft repository with the parser-derived conversation id ->
Engine claim on Room -> generative lane -> local Brain -> reply gateway
-> RemoteInput delivered to a test receiver -> journal CAS. Covers the
happy path (engine returns Submitted before the model responds, journal
converges to SUCCEEDED, duplicate redelivery suppressed, observed row
feeds the picker), the E13 path (notification removed mid-generation ->
DEFERRED with decryptable ciphertext only) and the group negative
(same conversation identity, zero fires). A wiring mutation check
(disconnected lane) fails both positive tests. The instrumented variant
ran on the OnePlus (Android 16): OK (1 test), with the system actually
delivering the RemoteInput broadcast. Also fixes a real flake in
ExecutionLogViewModelTest: viewModelScope must be cancelled and joined
before Dispatchers.resetMain.

## `5a72828a1` — 2026-07-14

**docs(argus): record P1-6 completion in the operational handoff**

Adds section 19 with the four P1-6 commits (encrypted deferred store,
actionable send-now + revocation coordinator, separated notification
health with real battery CTA and whitelist picker, observed retention),
the device migration evidence, the whitelist-retention decision, the
iphlpsvc/Tailscale infrastructure note for this machine and the exact
next steps (synthetic P1-7 now, real P1-7 + reboot gate with Lorenzo,
then P1-8).

## `e0dba9c46` — 2026-07-14

**feat(data): apply journal retention to observed conversation metadata**

P1-6 retention decision: locally observed display names now share the
journal max-age policy — a conversation not seen again within the
retention window leaves the picker store, on top of the existing
200-row bound and the delete-on-revoke purge. Maintenance reports the
trimmed rows.

## `1ec7b2bce` — 2026-07-14

**feat(ui): separate notification health, real battery cta and whitelist picker**

P1-6c. SettingsState now reports posting permission and notification
listener access as distinct health rows with their own fix CTAs, in the
mandated order: POST_NOTIFICATIONS first, then
ACTION_NOTIFICATION_LISTENER_SETTINGS; the onboarding step copy stops
promising "P1 later", completes only with both grants and drives the
same two-stage CTA. The battery fix now requests the package-specific
ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog (manifest permission
added, user gesture only) with the system list as fallback, guarded by
a manifest hardening regression test that also pins allowBackup=false
and the full data-extraction exclusions next to the new deferred table.
The whitelist picker lists recently observed WhatsApp 1:1 conversations
(groups, tri-state identities, foreign packages and already whitelisted
ids filtered out) with masked ids; the raw hash editor survives as the
advanced manual entry. The stale generative-budget copy now points to
the per-rule 60s cooldown and the P3 global budget.

## `262310a8f` — 2026-07-14

**feat(automation): make deferred replies actionable and centralize privacy revocation**

P1-6b. ExecutionLogViewModel resolves the E13 CTA: the audit row maps
to its execution, DeferredReplyManager decrypts the stored ciphertext
only after the tap, the text is copied to the clipboard flagged as
sensitive, WhatsApp is opened without any automatic send, and the row
is consumed with a one-shot CAS so the CTA cannot replay; the reply
text never enters UI messages, logs or state. PrivacyRevocationCoordinator
replaces the preference-only revocation: it closes the synchronous gate
first, then clears reply handles, observed conversations (new clear()
on the store) and encrypted deferred replies, reports partial purge
failures and stays idempotent for retry. The contact whitelist is
intentionally preserved: it is explicit user configuration and armed
generative rules are quarantined by reconcile anyway.

## `7d91fb40d` — 2026-07-14

**feat(data): add encrypted durable store for deferred replies**

P1-6a. Room v9 adds deferred_replies: one row per generative action,
AES-GCM ciphertext only (dedicated non-exportable Keystore key), TTL,
one-shot CAS consumption and FK CASCADE to the execution journal so
retention also kills orphaned ciphertext; maintenance purges expired
and consumed rows. PersistentDeferredReplySink replaces the unavailable
sink: the lane can mark DEFERRED only after the encrypted row is truly
persisted, and the defer-eligible set now includes
reply_channel_unavailable (notification removed/updated or registry
lost during Hermes latency) next to channel_expired; every other
gateway refusal stays FAILED. Migration 8->9 covered in MigrationTest
for every legacy version.

## `3f3702082` — 2026-07-14

**docs(argus): record P1-5 completion in the operational handoff**

Updates the Codex->Claude handoff after the P1-5 resume: executive
status table, commit ledger, resolved known issues, next-resume
checklist for P1-6 and a new section 18 with the implemented slices,
gate evidence (host + real device), in-plan decisions, explicit P1-6
leftovers, Windows/Gradle operational notes and the delivery state.

## `b5d9578dc` — 2026-07-14

**test(automation): gate probe capabilities on real device grants**

P1-5 device gate. The instrumented probe test now builds against the
readiness-aware constructor using the real bridge/preferences stores of
the instrumented package, and adds a targeted check that the actual
API 36 listener-access and battery-exemption reads fail closed for an
ungranted package: no notification trigger, no generative capability,
no raw or static reply, with the exact listener reason in the manifest.
Executed on the OnePlus (CPH2747, Android 16) via direct am instrument:
OK (2 tests); no global grant was modified and the test package was
uninstalled afterwards.

## `9a84c3bc1` — 2026-07-14

**feat(automation): generalize armed registrar for notification triggers**

P1-5d. TimeAlarmArmedAutomationRegistrar becomes
AndroidArmedAutomationRegistrar: Time keeps the exact AlarmManager
coordinator path, Notification verifies the global listener grant from
the capability snapshot plus the persisted ARMED/enabled row without
creating any per-rule OS subscription or persistent service, and every
unimplemented trigger stays fail-closed. Snapshot failures register as
false so arming rolls back honestly. Structural revocations after arm
were already quarantined by CapabilityReconciler through the updated
probe, and the privacy collector added in P1-5b reconciles while the
app stays foreground; fire-time policy remains the second defense.

## `8c857fd10` — 2026-07-14

**feat(automation): execute static WhatsApp reply through the reply gateway**

P1-5c. Action.WhatsAppReply leaves unsupported_phase: the executor now
sends the approved snapshot text through NotificationReplyGateway with
the package, notification key, conversation and event ID frozen from
the verified Notification trigger, so no text or target can come from
the model. Non-Notification events fail closed with
reply_event_unverified and gateway refusals (group, stale handle,
expired channel, untrusted package) surface as their typed codes with
one-shot handle consumption unchanged. With a real implementation path
the probe now publishes action.whatsapp_reply under the same listener
grant as the raw reply tool.

## `b7c25e76b` — 2026-07-14

**feat(automation): gate generative capabilities behind real readiness**

P1-5b. AndroidCapabilityProbe now derives availability from the actual
runtime instead of a static phase map: trigger.notification and the raw
whatsapp_reply tool follow the notification listener grant (with an
exact unavailability reason), action.invoke_llm requires the stored
bridge bearer, accepted privacy and battery exemption via the new
suspend GenerativeRuntimeReadiness boundary, and the Shizuku raw tools
now live in the same available/transient sets that CapabilityRequirements
persists. The static action.whatsapp_reply capability stays unpublished
until its executor exists.

NotificationIngress consults a synchronous privacy gate before creating
handles and re-checks it before persist/dispatch, so a revocation
between the listener callback and the coroutine cannot leak metadata;
reply handles and observed conversation rows are limited to trusted
WhatsApp packages while generic Notification triggers keep flowing.
ArgusRuntimeController now collects the preferences flow, clears the
reply handle registry and reconciles immediately on privacy revocation
instead of waiting for the next ON_START, and takes the Shizuku status
as a Flow so the behavior is testable.

## `e266fd9d8` — 2026-07-14

**feat(engine): align generative requirements and validator with P1 lane**

P1-5a. CapabilityRequirements now derives state.read when an InvokeLlm
context includes the state source, next to action.invoke_llm and the
exact approved raw tools. DraftValidator enforces the only InvokeLlm
profile the generative lane actually executes in P1: non-empty distinct
context sources limited to notification|state and including
notification, and allowed_tools exactly [whatsapp_reply] with no case
or alias normalization. The shared constants live in GenerativeContract
so validator, requirements and lane cannot drift silently.

## `b58c01a22` — 2026-07-14

**docs(argus): add detailed Claude handoff**

## `19bc2944e` — 2026-07-14

**feat(runtime): add guarded generative action lane**

## `069072369` — 2026-07-14

**fix(runtime): keep mixed async executions submitted**

## `d200b8c92` — 2026-07-14

**feat(notification): add guarded reply ingress**

## `bb2692717` — 2026-07-14

**feat(notification): add trusted notification parsing**

## `ce96c4cf8` — 2026-07-14

**feat(brain): add strict Hermes act endpoint**

## `72b69e4fa` — 2026-07-14

**feat(runtime): add async submission journal contract**

## `eb3f50638` — 2026-07-14

**docs(argus): add corrected P1 notification plan**

## `7af05aea2` — 2026-07-14

**test(app): add phased reboot recovery gate**

## `47d599ab5` — 2026-07-14

**test(app): make production E2E cleanup host-safe**

## `9f2861cf4` — 2026-07-14

**fix(bridge): surface provider quota failures safely**

## `a2a91227f` — 2026-07-14

**docs(argus): finalize P0-B audit and release state**

## `f48716842` — 2026-07-14

**fix(app): harden final production device gates**

## `82fa87fe8` — 2026-07-14

**fix(app): add proper themed launcher icon**

## `746668bc8` — 2026-07-14

**fix(brain): advertise compilable Android actions**

## `10bf5deb4` — 2026-07-14

**feat(app): complete production UI wiring**

## `50bcb9e30` — 2026-07-14

**fix(brain): enforce privacy consent before compile**

## `ee8a8d2f8` — 2026-07-14

**feat(app): wire production automation runtime**

## `dfb0782f4` — 2026-07-13

**feat(data): expose reactive execution log**

## `8ca0c4b78` — 2026-07-13

**feat(brain): apply bridge settings without restart**

## `a4479435f` — 2026-07-13

**feat(brain): protect bridge credentials with Android Keystore**

## `f33c0f822` — 2026-07-13

**build(app): add compatible Hilt wiring toolchain**

## `7dc848718` — 2026-07-13

**test(brain): require explicit Android API metadata**

## `5038c2b53` — 2026-07-13

**fix(brain): separate Android release from API level**

## `62f6f528c` — 2026-07-13

**feat(automation): orchestrate safe draft approval**

## `4d8b3899e` — 2026-07-13

**test(brain): isolate Android local network protection**

## `4d1cd31d7` — 2026-07-13

**feat(automation): probe and reconcile Android capabilities**

## `213b986c6` — 2026-07-13

**fix(runtime): quarantine only unchanged approvals**

## `251ad3478` — 2026-07-13

**feat(data): persist contact whitelist policy**

## `530c51578` — 2026-07-13

**docs(plan): record Shizuku device gate**

## `e3405b351` — 2026-07-13

**feat(shizuku): correlate privileged actions by execution**

## `c1463d437` — 2026-07-13

**fix(shizuku): harden permission and service lifecycles**

## `6e3b5a56a` — 2026-07-13

**fix(device-tools): parse Android 16 resumed activity**

## `c539190c3` — 2026-07-13

**fix(automation): keep unsupported actions fail closed**

## `e2d55e33e` — 2026-07-13

**feat(automation): add fail-closed Shizuku action executor**

## `0ce078e2c` — 2026-07-13

**fix(device-tools): dump multi-window UI trees**

## `26e08bf78` — 2026-07-13

**feat(device-tools): add typed Shizuku capabilities**

## `c107f2ba9` — 2026-07-13

**feat(shizuku): add prioritized UserService shell gateway**

## `02b9ee55b` — 2026-07-13

**fix(automation): preserve alarms across replacement races**

## `1c62c3640` — 2026-07-13

**docs(plan): record Android scheduler checkpoint**

## `019eada59` — 2026-07-13

**feat(automation): add resilient AlarmManager scheduler**

## `1e1577af7` — 2026-07-13

**feat(time): make exact alarm precision explicit**

## `27b20b47b` — 2026-07-13

**feat(policy): persist full capability requirements**

## `9aa0c93c0` — 2026-07-13

**build(android): target API 36**

## `81a567219` — 2026-07-13

**docs: align project context with Hermes bridge v1**

## `224a530de` — 2026-07-13

**feat(brain): secure versioned Hermes compile bridge**

## `530b146f7` — 2026-07-13

**fix(approval): bind edits to their approved base**

## `b0dedfe8b` — 2026-07-13

**feat(runtime): persist redacted execution journal**

## `7cdd914eb` — 2026-07-13

**feat(approval): make draft arm revision-bound and atomic**

## `d36d899b7` — 2026-07-13

**fix(data): quarantine invalid rules and expose reactive store**

## `c0a742c95` — 2026-07-13

**feat(data): expose observable store and persist quarantine**

## `c41e98464` — 2026-07-13

**fix(runtime): make trigger execution policy-bound and idempotent**

## `c5a066041` — 2026-07-13

**fix(engine): harden safety gates and runtime semantics**

## `44a260d29` — 2026-07-13

**fix(engine): schedule DST transitions deterministically**

## `866811c3f` — 2026-07-13

**fix(engine): fail closed on unknown notification group**

## `ec4bb0aa5` — 2026-07-13

**fix(engine): make draft validation fail closed**

## `a3eae2ac5` — 2026-07-13

**docs(plan): add P0-B hardening gate and Android 16 baseline**

## `8950bc1c2` — 2026-07-13

**feat(app): add Argus adaptive launcher icon**

## `5a993d132` — 2026-07-13

**feat(brain): HermesBrain via CliBridge transport (compile one-shot, MockWebServer tested)**

brain-android module: CliBridgeTransport (OkHttp -> Hermes bridge :8090) and
HermesBrain implementing engine-core's Brain. /compile preferred (structured
{reply, meta:{draft}, schema_version}); /chat fallback (default, live-bridge
today) delegates to engine-core CliBridgeParser for the @@META@@ sentinel.
Endpoint switch via useCompileEndpoint flag. 60s timeout. Transport throws
typed BridgeException (TIMEOUT/NETWORK/HTTP); HermesBrain maps it to
CompileResult.metaError so the ViewModel never crashes; CancellationException
propagates. 11 MockWebServer tests, all green.

Additive: settings.gradle.kts include("brain-android"). engine-core unmodified.

## `97236e5ec` — 2026-07-13

**feat(data): Room automation store + audit sink (JSON column, decode-fail→needs_review)**

New `data` module (com.android.library, dev.argus.data) implementing
engine-core's AutomationStore + AuditSink on Room 2.6.1 + KSP.

- AutomationEntity: flat columns for query/order + json column carrying the
  full polymorphic Automation via ArgusJson; lastFiredAt column for cooldown.
- AuditEntity: append-only audit log (indexed).
- RoomAutomationStore: entity<->domain map; flat columns authoritative on read;
  save preserves lastFiredAt. Decode-fail OR incompatible schemaVersion ->
  NEEDS_REVIEW placeholder (id/name preserved), never throws / never drops (E8).
- RoomAuditSink: suspend insert.
- 12 Robolectric tests (round-trip, armed() filter, recordFired/lastFiredAt,
  setStatus, corrupt-json->NEEDS_REVIEW, schemaVersion-incompat->NEEDS_REVIEW,
  audit ordering). MigrationTest v1 no-op scaffold (instrumented, P2).
- Version catalog: Room/KSP/serialization/coroutines/Robolectric/JUnit4.
- engine-core untouched.

## `54776bbd8` — 2026-07-13

**Merge branch 'feat/argus-engine-ui': Argus M1 (engine-core) + M2 (UI + demo APK)**

M1 — engine-core (pure Kotlin/JVM): domain models, Engine (lazy state,
last-writer-wins, error isolation, generative cooldown), CronSchedule/DST,
DraftValidator (safety invariants), CliBridgeParser, CapabilityManifest,
E2E of the 3 examples + security gate. 67 tests green.

M2 — ui (Compose M3 dark, Italian) + app: token-based theme, verbatim contracts,
RuleRenderMapper, shared components, 6 stateless screens, Fixtures + NavHost,
installable demo APK (inert, zero permissions). 14 ui tests green.

Agent-driven development (Opus 4.8) with per-unit review (10 units, all Approved),
final whole-branch review + fix-pass (all Approved). APK installed and
smoke-tested on oneplus. P0-B (real Android glue) planned for the next session.

## `644d4986b` — 2026-07-13

**test(engine): round-trip Action.SetWifi (complete schema DoD)**

## `4e0477d9a` — 2026-07-13

**docs(plan): P0-B carry-forward constraints from final review**

Append four carry-forward constraints to Global Constraints: privacyNote must
surface as a privacy UiWarning (E11), onSendNow carries a LogRow id (not an
automation id), Engine.onTrigger must rethrow CancellationException, and the
fire-time always-confirm catalog must join FORBIDDEN_IN_INVOKE_LLM as it grows.

## `3a3696afa` — 2026-07-13

**chore: scrub PII from fixtures/tests**

Replace the real personal phone number and Hermes Tailscale IP with clearly-fake
placeholders in fixtures/tests only (Fixtures.kt, SettingsScreen.kt previews,
TriggerMatcherTest.kt): +39 320 000 0000 (digits 393200000000) and 100.64.0.1.
TriggerMatcher assertion updated consistently (digit-suffix match) — stays green.
Docs keep the real values by design.

## `025e2211a` — 2026-07-13

**test(ui): RuleRenderMapper risky-branch coverage**

Add 4 tests flagged by final review (existing 3 kept):
- table-driven requiresLiveConfirm per action (InvokeLlm=false;
  WhatsAppReply/Tap/InputText=true);
- And/Or/Not condition tree flattens into the expected indented lines;
- non-humanizable cron falls back to the raw "Cron '...'" form;
- geofence transition=ENTER + resolveCurrentLocation=true line.

## `3b0973d9e` — 2026-07-13

**test(engine): DraftValidator hardening + full-schema round-trip**

- DraftValidator: normalize tool case before the forbidden-tool match and also
  forbid the bare "automation" prefix (no dot); allowlist backstop unchanged.
  New test asserts app.install, bare "automation", and mixed-case SHELL.RUN /
  Automation.Create all yield tool_forbidden.
- New SchemaRoundTripTest: table-driven decode(encode(x))==x for every subtype
  not covered elsewhere — Trigger.PhoneState/Connectivity, Condition.LocationIn,
  all remaining Actions (SetBluetooth/SetDnd/SetRinger/LaunchApp/OpenUrl/
  ShowNotification/Tap/InputText/WhatsAppReply/RunShell), and AutomationDraft.

## `c19656436` — 2026-07-13

**fix(app): wire nav affordances in demo host**

Now that the additive callbacks exist, cable them in the fake-data demo:
- List onEmptyCta → switch tab to Chat; onBannerTap → switch tab to Sistema
  (added a switchTab helper; surface a non-NONE list banner so the tap is
  demonstrable).
- Detail onBack → popBackStack().
- Log onSendNow → snackbar "Risposta inviata"; onOpenAutomation kept for its
  real (open-automation) purpose.

## `8e0b7dba2` — 2026-07-13

**fix(ui): wire list empty/banner nav + log send-now callbacks**

- AutomationListCallbacks: additive onEmptyCta()/onBannerTap() (default no-op);
  forwarded to EmptyState.onCta and EngineBannerBar.onClick so the empty-state
  "Vai in chat" button and health banner are no longer inert.
- ExecutionLogCallbacks: additive onSendNow(logId) (default no-op); "Invia ora"
  re-pointed from onOpenAutomation(row.id) to onSendNow(row.id) — row.id is a
  LOG-row id, not an automation id (E13).

## `0aab26150` — 2026-07-13

**fix(ui): detail badge opt-out + arm-blocked fallback + rationale case + onBack**

- RuleCard: add optional showGenerativeHeader (default true); Detail passes
  false so the generativa+cloud pair renders once (header badge row, §5.4).
- Detail: arm-blocked reason always shown when !canArm, with generic Italian
  fallback "Regola non armabile" when armBlockedReason is null (§5.2).
- Detail: rationale label lowercased to "descrizione del modello" (§5.1).
- AutomationDetailCallbacks.onBack additive default no-op; header back
  affordance now clickable (host wires the pop).

## `d149a3597` — 2026-07-13

**chore: M2 build green — engine+ui tests pass, demo APK assembles**

Task 13 verification (M2 Unit F):
- ./gradlew :app:assembleDebug → BUILD SUCCESSFUL, app-debug.apk (~55.5 MiB).
- ./gradlew test → engine-core (61 tests) + ui RuleRenderMapper (3) green.
- ./gradlew :ui:assembleDebug → OK.
- Lint (:app :ui lintDebug) non-blocking, no fatal issues.

Fix found during verification: drop redundant android:label on the launcher
activity (RedundantLabel) — the <application> label already applies.

## `b88d201c2` — 2026-07-13

**feat(app): demo NavHost + fixtures wiring all 6 screens (installable APK)**

Task 12 (M2 Unit F): centralized preview fixtures + `app` demo module.

- ui/preview/Fixtures.kt: single source of realistic fake data for all 6
  screens — the 3 spec examples (geofence Wi-Fi/BT exit, DND 23:00, WhatsApp
  reply "Moglie") + plausible rules (backup foto shell, promemoria farmaci
  NEEDS_REVIEW, forbidden-shell generative arm-blocked). Covers every badge
  state (armed/pending/disabled/needs_review), generative+cloud, integral
  shell, and every LogOutcome (SUCCESS/PARTIAL/FAILED/SUBMITTED/DEFERRED).
- app module: application `dev.argus` (minSdk 30, target/compile 35,
  versionName 0.1-demo), MainActivity (edge-to-edge, ArgusTheme + ArgusNavHost),
  manifest + strings/colors/themes.
- nav/ArgusNavHost.kt: Scaffold + bottom NavigationBar (Chat/Automazioni/Log/
  Sistema, §9 icons, pending/needs-review badges) + NavHost mounting each screen
  on Fixtures. Push Detail from chat draft / list row; Onboarding from Sistema;
  live demo state (chat send → canned DraftCard after simulated latency, filter
  chips, inline toggle, snackbars). Demo on fake data — no engine/network.

Screens/contracts/theme/engine-core unmodified.

## `f10943e25` — 2026-07-13

**feat(ui): Onboarding/permissions wizard (6 steps, Shizuku sub-states, consent)**

## `270928641` — 2026-07-13

**feat(ui): System/Settings screen (health, transport, whitelist, budget)**

## `0c0c50c6f` — 2026-07-13

**feat(ui): Execution log screen (per-day grouping, expandable, deferred send)**

## `77e979248` — 2026-07-13

**feat(ui): Automation list screen (fixed ordering, inline toggle, filter chips)**

## `397ad7b7b` — 2026-07-13

**feat(ui): Chat screen (one-shot latency indicator, draft cards, no streaming)**

## `ea7951f3c` — 2026-07-13

**feat(ui): AutomationDetail/Approval screen (warnings-above-fold, ERROR blocks arm)**

## `9be1085d0` — 2026-07-13

**feat(ui): shared components (badges, RuleCard+shell block, banners, latency, empty)**

Also adds the approved Unit B carry-over: 6 action icon keys (ringer,
launch_app, open_url, tap, input_text, whatsapp_reply) to iconFor so risky
actions no longer share the generic Bolt fallback with benign ones.

## `bc7e8dcbf` — 2026-07-13

**feat(ui): RuleRenderMapper (deterministic engine->view rule rendering) + tests**

## `e33c1e9b3` — 2026-07-13

**feat(ui): view-layer state contracts (handoff §6) + icon key mapping**

## `06cb138f7` — 2026-07-13

**feat(ui): typography scale + ArgusTheme (M3 dark/light, shapes, semantic provider)**

## `e8da46e9f` — 2026-07-13

**feat(ui): color tokens (dark+light) and semantic status colors**

## `aeaa0d979` — 2026-07-13

**chore(ui): scaffold Android Compose library module + version catalog**

## `a3d7f3a93` — 2026-07-13

**docs(plan): P0-B must always pass whitelist to DraftValidator (Unit D review)**

## `8f49d7afc` — 2026-07-13

**test(engine): end-to-end coverage of the 3 spec examples + security gate (engine-side)**

## `f3f08657d` — 2026-07-13

**feat(engine): DraftValidator (domain checks + hard security invariants on InvokeLlm)**

## `187ea0d5c` — 2026-07-13

**feat(engine): Brain interface + CliBridgeParser (balanced JSON extraction, explicit missing-draft error)**

## `08f627170` — 2026-07-13

**feat(engine): CapabilityManifest (contacts with ids, StateKeys registry) + CapabilityProbe**

## `19842d667` — 2026-07-13

**feat(engine): trigger-aware ConflictDetector (suppresses legitimate complementary pairs)**

## `8295cecd8` — 2026-07-13

**feat(engine): Engine (lazy state, ascending priority=last-writer-wins, error isolation, min generative cooldown, audit)**

## `8d51d4dbb` — 2026-07-13

**docs(plan): fix CronSchedule KDoc (*/s closed block comment) at source**

## `e260e9f95` — 2026-07-13

**feat(engine): CronSchedule + TimeSpecs (5-field cron, vixie OR, DST gap/overlap tested)**

## `d578c76c8` — 2026-07-13

**feat(engine): TriggerMatcher (conversationId precedence, normalized numbers, direction)**

## `383c585f2` — 2026-07-13

**feat(engine): ConditionEvaluator (time-window, state, geo, AND/OR/NOT)**

## `6f29564c5` — 2026-07-13

**feat(engine): runtime interfaces (executor+Submitted, store, audit, events) + fakes**

## `0df3d6791` — 2026-07-13

**feat(engine): Action + Automation models, tier classification, StateKeys registry**

## `3a7fbf832` — 2026-07-13

**feat(engine): Condition domain model (AND/OR/NOT tree)**

## `14c25d299` — 2026-07-13

**feat(engine): Trigger domain model + serialization (conversationId, at, direction)**

## `2cc759e1c` — 2026-07-13

**chore(engine): scaffold pure-JVM engine-core module**

## `842e76866` — 2026-07-13

**chore(engine): settings include engine-core, keep foojay resolver; ignore SDD scratch**

## `0d107b063` — 2026-07-13

**plan: M2 (UI Compose) + P0-B (Android glue)**

- plan-M2: ui module + demo app, 13 tasks, stateless screens from the handoff
  §6 contracts, theme from design §5, tested RuleRenderMapper, demo APK.
- plan-P0B: core-shizuku, device-tools, Room, brain-android (CliBridge),
  automation-android (executor/time-trigger/probe/approval/VM), minimal FGS.
  Points to verify on device marked 🔬. Executed next session.

## `5bb417f6c` — 2026-07-13

**chore(repo): .gitattributes (LF for gradlew, binaries marked)**

## `96cbdc57e` — 2026-07-13

**chore(repo): M0 setup — rev3 docs, design handoff, gradle 8.13 wrapper**

- spec updated to rev 3 + handoff-frontend in specs/
- P0-A plan updated to rev 2 (14 files, 13 TDD tasks)
- design handoff Claude Design (rev 1a approved) in docs/design/
- project CLAUDE.md, .gitignore, settings+properties, wrapper 8.13
- development moved to the negozio PC (C:\argus), history imported from oneplus

## `d6136377e` — 2026-07-12

**plan: P0-A engine-core (Kotlin puro, device-independent, TDD)**

Complete automation engine as a JVM library: serializable domain models,
ConditionEvaluator, TriggerMatcher, Engine (cooldown/priority/dispatch),
heuristic ConflictDetector, CliBridgeParser, CapabilityManifest. Shizuku/Room/
AlarmManager behind injected interfaces (impl in P0-B). 11 TDD tasks, the spec's
3 examples covered on the engine side.

## `4f58381e7` — 2026-07-12

**design rev2: re-analysis after Hermes verification**

- B5 resolved: guida-agent/bridge.py (HTTP reference impl) + hermes proxy verified
- Brain remodeled onto 2 transports (CliBridge codex-free one-shot / OpenAICompat fast)
- B8 new: free brain latency ~10-30s -> interactive loop moved to P3
- AccessibilityService downgraded to optional (uiautomator dump + input via Shizuku cover the core)
- D6 Shizuku-as-abstraction (shell UID default, root only for boot-persistence)
- containment injection: reply_target bound to trigger.sender
- privacy edge case (content leaves the homelab) + WhatsApp context limited to the notification
- background-location made explicit, "low" battery honestly rescaled
- phasing reordered: automations first (latency-tolerant), interactive screen later

## `522d8bb35` — 2026-07-12

**design: spec Argus — agente LLM automazione Android**

Design approved in brainstorming: an always-live Tasker-class engine with an
LLM compiling NL->rules, pluggable Brain (Hermes/OAuth), Shizuku,
approve-at-creation, + a section of edge cases/barriers/conflicts with solutions.
