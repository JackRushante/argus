<div align="center">

<img src="docs/assets/argus-banner.svg" alt="ARGUS" width="640">

### The all-seeing Android automation engine

[![Release](https://img.shields.io/github/v/release/JackRushante/argus?style=for-the-badge&labelColor=444&color=f5a623)](https://github.com/JackRushante/argus/releases/latest)
[![License](https://img.shields.io/badge/LICENSE-GPL--3.0-4c9141?style=for-the-badge&labelColor=444)](LICENSE)
[![Platform](https://img.shields.io/badge/ANDROID-11%2B-3ddc84?style=for-the-badge&labelColor=444&logo=android&logoColor=white)](#build--installation)

[![Kotlin](https://img.shields.io/badge/KOTLIN-100%25-7f52ff?style=for-the-badge&labelColor=444&logo=kotlin&logoColor=white)](#architecture)
[![Engine](https://img.shields.io/badge/RULES-LLM%20compiled%20·%20deterministic%20run-00b8d4?style=for-the-badge&labelColor=444)](#what-is-argus)
[![Bilingual](https://img.shields.io/badge/LANG-EN%20%7C%20IT-e05d44?style=for-the-badge&labelColor=444)](#project-status-and-roadmap)

</div>

**Natural-language automation for Android, compiled by an LLM, executed by a deterministic engine.**

Argus is a Tasker-class Android automation app where the LLM is the *compiler*, not the executor: you describe a rule in plain language ("every day at 9 send me the BTC price", "when I leave home turn off Wi-Fi"), the LLM compiles it into a structured `{trigger → conditions → actions}` rule, you review and approve a byte-stable fingerprint of the executable data, and from then on a deterministic engine runs it — no LLM in the execution loop, except for explicitly generative actions. Elevated privileges are optional via [Shizuku](https://shizuku.rikka.app/); a base tier works without it.

> The app UI is bilingual (English/Italian, follows the system language).

> **Privacy & license at a glance.** Argus operates **no project backend or account service** and includes no telemetry or analytics. It contacts only the LLM provider or self-hosted bridge that you explicitly configure. Rules, logs and API keys stay **on your device** (keys encrypted; the audit log records outcomes with no personal content). Argus is **free software under [GPL-3.0](LICENSE)**: you may study, modify and share it, but any derivative must stay open under the same license.

---

## Table of contents

1. [What is Argus](#what-is-argus)
2. [Architecture](#architecture)
3. [Trigger, condition and action catalog](#trigger-condition-and-action-catalog)
4. [LLM providers and the Hermes bridge](#llm-providers-and-the-hermes-bridge)
5. [Security & privacy](#security--privacy)
6. [Permissions and tiers](#permissions-and-tiers)
7. [Build & installation](#build--installation)
8. [For testers](#for-testers)
9. [Project status and roadmap](#project-status-and-roadmap)
10. [License](#license)

---

## What is Argus

Argus **is not a chatbot that "does things"**. It is an always-on automation engine (Tasker/MacroDroid style) in which the LLM has a single role: **compiling** natural language into a structured, typed rule. The lifecycle is:

1. **You describe** the rule in chat, in natural language.
2. **The LLM compiles** the request into a `{trigger, conditions, actions}` draft over a closed vocabulary of types.
3. **The validator** (`DraftValidator`) checks the draft: closed vocabularies, bounds on every field, security invariants. An error blocks approval.
4. **You approve** from the detail screen. The rule is shown **rendered from the types**, never from the LLM's paraphrase, and frozen with a **SHA-256 fingerprint** of the executable data only.
5. **The deterministic engine executes**: OS-managed alarms, system receivers, geofences, notification listener. The LLM is not consulted when the rule fires.

The only exception is the explicit generative action (`invoke_llm`): there the LLM comes back into play *at fire time*, but inside a rigid contract (closed allowed tools, bound recipient, timeout, budget) that you approved in advance.

Why this separation matters:

- **Predictability** — the rule that fires at 3 a.m. is exactly the one you approved, byte for byte.
- **Cost** — no LLM calls on every fire: you pay for (or self-host) the LLM only at compile time and in generative actions.
- **Security** — the executable program structure and its targets are fingerprinted before arming. In the current **Aggressive** policy, approved templates may also interpolate runtime data into authority fields; this is powerful and intentionally carries injection risk (see [Security & privacy](#security--privacy)).

The project's current philosophy is **Tasker-class power with explicit, reviewable risk**: the program tree is immutable after approval, bounds and capability checks remain enforced, while the optional use of untrusted runtime data in command/routing fields is permitted by the centralized Aggressive taint policy.

---

## Architecture

### Gradle modules

| Module | Type | Responsibility |
|---|---|---|
| `engine-core` | **Pure JVM** Kotlin (zero Android) | Domain models, deterministic flat/P4 runtime (`ProgramInterpreter`), variables and control flow, `TriggerMatcher`, `ConditionEvaluator`, `CronSchedule` (incl. DST), `DraftValidator`, approval fingerprints and safety policy. |
| `brain-android` | Android library | LLM transport: `CliBridgeTransport` (Hermes bridge), `OpenAICompatTransport` (OpenAI/Gemini/OpenRouter/custom), `AnthropicMessagesTransport`. Provider catalog, encrypted key store, usage normalization. |
| `automation-android` | Android library | Event-driven Android runtime: AlarmManager (exact/inexact), geofence, notification listener, SMS/telephony/connectivity/Bluetooth receivers, sensors, generative lane, budget/usage, ViewModel. |
| `data` | Android library | Room persistence: automations, audit (append-only, redacted), LLM usage. |
| `ui` | Android library | Jetpack Compose Material 3, 6 **stateless** screens (chat, list, detail/approval, log, system, onboarding) + previews. |
| `device-tools` | Android library | Typed capabilities on top of Shizuku (device states, toggles, screen tools). |
| `core-shizuku` | Android library | Single privileged gateway: shell UID via Shizuku, single-writer queue. |
| `app` | Android application | `dev.argus` — Hilt, navigation, wiring of the real runtimes. |

### Compile → approve → arm → fire flow

```
 user (chat, natural language)
        │
        ▼
 ┌─────────────┐   strict JSON draft    ┌────────────────┐
 │ Brain (LLM) │ ─────────────────────► │ DraftValidator │  ERROR ⇒ not armable
 │ compiles    │                        │ closed vocab   │  WARNING ⇒ shown
 └─────────────┘                        └───────┬────────┘
   Hermes bridge │ OpenAI │ Anthropic           │
   Gemini │ OpenRouter │ custom                 ▼
                              ┌──────────────────────────────┐
                              │ APPROVAL (Detail screen)     │
                              │ rule rendered FROM THE TYPES │
                              │ SHA-256 fingerprint of the   │
                              │ executable data only         │
                              └──────────────┬───────────────┘
                                             │ arm
                                             ▼
                              ┌──────────────────────────────┐
                              │ OS-MANAGED REGISTRARS        │
                              │ AlarmManager · geofence ·    │
                              │ NotificationListener ·       │
                              │ SMS/conn/BT receivers ·      │
                              │ sensors                      │
                              └──────────────┬───────────────┘
                                             │ event
                                             ▼
                              ┌──────────────────────────────┐
                              │ DETERMINISTIC ENGINE         │
                              │ trigger match → conditions → │
                              │ actions · cooldown · dedup · │
                              │ audit of every outcome       │
                              └──────┬───────────────┬───────┘
                                     │               │ generative actions only
                                     ▼               ▼
                              deterministic actions  invoke_llm lane
                              (no LLM)               (LLM under a closed contract)
```

---

## Trigger, condition and action catalog

The names below are the model's real wire discriminators (`engine-core`).

### Triggers

| Trigger | Main parameters | Notes |
|---|---|---|
| `time` | exactly one of `cron` (recurring), `at` (local ISO datetime, one-shot) and `afterMs` (relative delay, one-shot); `tz`; `precision` `FLEXIBLE\|EXACT` | Cron with DST handling in engine-core. `afterMs` ("in 2 minutes…") schedules exact by default. |
| `immediate` | — | Fires once when the rule is armed. Used for "right now" one-shots without racing the clock. |
| `notification` | `pkg`, `conversationId` (stable key, preferred), `sender` (spoofable fallback ⇒ WARNING), `isGroup`, `titleMatch`, `textMatch` | Used for WhatsApp; generative replies require 1:1 chats (`isGroup=false`) and `conversationId`. |
| `phone_state` | `event` `INCOMING_CALL\|CALL_ENDED\|SMS_RECEIVED`, `number`, `textMatch` (SMS only) | Sender/caller ID are considered **spoofable**: never allowed to trigger shell. |
| `connectivity` | `medium` `WIFI\|BT\|POWER`, `state` `CONNECTED\|DISCONNECTED`, `match` (e.g. SSID/device name) | POWER = power supply connected/disconnected. |
| `geofence` | `lat`/`lng`/`radiusM`, `transition` `ENTER\|EXIT`, `resolveCurrentLocation` | `DWELL`/loitering are represented by the model for compatibility but rejected by the current runtime. With `resolveCurrentLocation=true`, coordinates are resolved locally at arm time and never pass through the LLM. |
| `sensor` | `kind` `significant_motion\|stationary_detect\|motion_detect\|step_detector\|step_counter`, `minimumEventCount` | The domain reserves these low-rate families, but the production backend currently arms only `significant_motion` when the device supports it. Other kinds remain unavailable until they have dedicated backends. Minimum cooldown 60 s. |

### Conditions

A single composable tree with `and` / `or` / `not` (max depth 8, max 64 conditions).

| Condition | Parameters | Notes |
|---|---|---|
| `time_window` | `startLocal`, `endLocal`, `tz` | Time-of-day window. |
| `state_equals` | `key`, `op`, `value` | Over a **closed** registry of device keys: `ringer`, `wifi`, `bluetooth`, `dnd`, `battery`, `charging`, `airplane`, `screen`. |
| `state_compare` | `query` (families `builtin`, `setting`, `system_property`, `sysfs`, `dumpsys_field`), `valueType` `TEXT\|NUMBER\|BOOLEAN`, `op` `EQ\|NEQ\|GT\|LT\|CONTAINS`, `expected`, `policyVersion` | **Parametric** state readers (P3): closed families, validated and fingerprinted parameters, pre-arm probe. Read-only by construction. |
| `app_in_foreground` | `pkg` | Requires the privileged reader. |
| `location_in` | `lat`, `lng`, `radiusM` | Location within a radius. |
| `boolean_literal` / `var_compare` | literal boolean, or variable operands with `EQ\|NEQ\|GT\|LT\|CONTAINS\|IS_EVEN\|IS_ODD` | P4 flow conditions used inside `if` and bounded `while`. Numeric comparisons accept numeric text at runtime and fail closed when it is not coercible. |

Fail-closed semantics: an unreadable state is `UNKNOWN` and the condition fails closed, **even under `not`**.

### P4 variables and structured flow

Schema-v2 rules can bind up to 16 typed values from literals, trigger payloads, approved device-state
readers, engine-generated `random_int`, and captured shell/model output. They support nested `if`,
bounded `while` (literal or variable iteration count, clamped to 1–1000), and cooperative `wait`,
with at most 64 action nodes, flow depth 4 and a hard six-hour runtime budget. Values carry integrity,
confidentiality and provenance labels; only their rendered values change at runtime—the approved
program tree and fingerprint do not.

### Actions

| Action | What it does | Privilege |
|---|---|---|
| `set_wifi` / `set_bluetooth` / `set_mobile_data` | Radio/data toggles | Shizuku |
| `set_dnd` | DND `off\|priority\|total` | Base ("Do Not Disturb" access) |
| `set_ringer` | Ringer normal/vibrate/silent | Base |
| `set_volume` | Per-stream volume (`MEDIA\|RING\|ALARM\|NOTIFICATION`), percentage 0–100 | Base |
| `launch_app` / `open_url` | Launches an app / opens a URL | Base (reliable from the background only with Shizuku) |
| `open_settings_screen` | Opens a Settings screen from a **closed enum** (never arbitrary action strings) | Base |
| `show_notification` | Local Argus notification | Base |
| `set_alarm` / `set_timer` | **Real** clock-app alarm/timer via the `AlarmClock` Intent | Base |
| `copy_to_clipboard` / `copy_text` | Copies the trigger payload (optionally through a linear regex) or resolved literal/variable text. The OTP path is local; Argus schedules compare-and-clear of its unchanged clip after 60 seconds (process-local) | Base |
| `set_flashlight` / `vibrate` | Flashlight on/off, one-shot vibration | Base |
| `set_dark_mode` | Light/dark/automatic system theme; accepts the lowercase compiler wire and legacy persisted uppercase values | Shizuku |
| `whatsapp_reply` | WhatsApp reply via the notification's RemoteInput | Base (notification listener) |
| `run_shell` | Shell command template, shown in full at approval; P4 variables may be interpolated under the Aggressive policy | Shizuku |
| `write_setting` | Parametric `settings put` on `system\|secure\|global` (any validated key/value template) | Shizuku |
| `tap` / `input_text` | Reserved UI input primitives; rejected with `unsupported_phase` and never advertised as available | Not available |
| `wait` / `if` / `while` | Cooperative pause and structured, bounded control flow | Engine |
| `invoke_llm` | **Generative**: at fire time the LLM produces text for an explicit `WHATSAPP_REPLY`, `LOCAL_NOTIFICATION`, or P4 `CAPTURE_ONLY` sink. Hermes and direct providers support the resolved capture path | Base |
| `invoke_llm_v2` | P3 variant with explicit state context: every reader, type and classification enters the approved fingerprint. Flat delivery is supported; nesting it inside P4 is rejected before approval | Base |

---

## LLM providers and the Hermes bridge

The "Brain" is pluggable: the app always owns the loop, the LLM is a reasoning service behind a transport interface.

| Provider | Transport | Web search | Costs shown |
|---|---|---|---|
| **Hermes (self-hosted)** | dedicated bridge (see below) | yes (agent-side) | tokens only |
| **OpenAI** | Responses API (server-side `web_search`) | yes | $ estimate from price list |
| **Anthropic** | Messages API (`web_search` server tool) | yes | $ estimate from price list |
| **Google Gemini** | OpenAI-compat shim + native API for grounding (`google_search`) | yes | $ estimate from price list |
| **OpenRouter** | OpenAI-compat (web via the `:online` slug) | yes | tokens only |
| **Custom (OpenAI-compat)** | endpoint of your choice (Ollama, LiteLLM, z.ai, etc.) | no | n/a |

- **BYOK**: direct providers require **your own** API key, entered in-app and stored encrypted on the device. No Argus account, no project backend.
- **Budget**: per-turn usage tracking (tokens and, where the price list is known, micro-USD), configurable limits; a generative rule that exceeds its budget is suppressed and audited (`SUPPRESSED_BUDGET`).

### Hermes bridge (optional, self-hosted)

`ops/hermes/bridge.py` is a one-shot service meant for people who already run a self-hosted LLM agent on their own server: it exposes `POST /compile` (NL → rule draft) and strict versioned `POST /act` lanes, with bearer token, idempotent request-ids, body/output limits and fail-closed parsing. `/act` v1/v2 cover legacy and state-aware delivery; v3 carries P4 resolved runtime values in a strictly validated DATA envelope. Android requires v3 in `/health/v2`, so a stale bridge fails visibly before execution. The service binds to loopback only and should be published over HTTPS on a private network (e.g. a mesh VPN / Tailscale Serve). Example systemd unit and `.env` are included in `ops/hermes/`.

**The app works without the bridge**: just pick a direct provider with your own key. The bridge exists only for those who want to compile rules with their own self-hosted agent instead of a commercial API.

Full contract: `docs/design/hermes-bridge-contract.md`.

---

## Security & privacy

The main threat model is **prompt injection from external content** (SMS, notifications, web pages): the defense is structural, not filter-based.

- **Approval fingerprint** — SHA-256 (`argus-approval-v1`) of the canonical JSON of the **executable data only**; the LLM's prose is excluded from the hash. What fires is byte-for-byte what you approved; any change requires re-approval.
- **Rendering from the types** — the approval screen shows the rule reconstructed from the domain types, never the LLM's paraphrase. Shell commands are shown in full, in monospace, never truncated.
- **`DraftValidator`** — no draft enters the engine without validation: closed vocabularies (state keys, enums, tools), bounds on every field (lengths, ranges, condition-tree depth), hard invariants (e.g. `invoke_llm`'s `allowed_tools` can never contain `shell.run` or `automation.*` — re-checked at fire time as well).
- **Centralized taint and egress policy** — integrity labels remain monotonic, but the **Aggressive** policy deliberately permits a TAINTED trigger/capture value in approved authority templates such as commands, URLs, packages and settings. The program structure and capabilities remain fingerprinted, but data-driven authority creates a real injection surface. Remote egress is a separate decision: future credential-vault values are blocked from every remote Brain regardless of the taint posture, while generic `SECRET` data requires explicit review.
- **`StaticShellSafety`** — `run_shell` can only be triggered by triggers with a non-forgeable identity (time, immediate, geofence, connectivity, sensor) or by a **whitelisted** WhatsApp 1:1 chat identified by a stable `conversationId`. SMS and caller ID are excluded as a hard limit (spoofable), and the approved-trigger ↔ live-event binding is verified at runtime.
- **PII-free audit log** — every fire, suppression, error and lifecycle transition (arm/disable/delete/needs-review) is recorded in append-only Room with **closed-vocabulary reason codes**: never free text, never message content; event ids are hashed.
- **Minimization toward the Brain** — compile receives a capability manifest and a **redacted** device state (only keys from the approved registry); GPS coordinates never leave the phone (only `location_available` is sent). Generative replies are bound to the trigger's sender, whitelisted 1:1 chats only.
- **Fail-closed everywhere** — missing state = `UNKNOWN` = condition false; ambiguous metadata (e.g. unknown `isGroup`) = not authorized; Shizuku absent = the privileged action fails cleanly and is audited, never executed "later".
- **Keys and secrets** — API keys encrypted on-device, never in UI state, never in the repo, never in the APK; app backup disabled.

---

## Permissions and tiers

Argus degrades explicitly: the onboarding shows an honest list of what depends on what, and every extra permission is granted only when a rule uses it.

**Required to start**: Brain configuration (any provider) and privacy acknowledgment. Everything else is skippable.

| Permission / access | Needed for | When |
|---|---|---|
| Notifications (`POST_NOTIFICATIONS`) | outcomes, alerts, generative notification sink | recommended right away |
| **Alarms & reminders** (`SCHEDULE_EXACT_ALARM`) | punctuality of EXACT `time` triggers and "in N minutes" delays | when a rule asks for precision; inexact fallback if denied |
| Notification access (notification listener) | `notification` trigger, WhatsApp replies | notification rules |
| Location (fine + background) | `geofence`, `location_in` | location rules |
| SMS / phone state / call log | `phone_state` trigger | telephony rules (separate opt-in) |
| Nearby Bluetooth (`BLUETOOTH_CONNECT`) | BT connectivity trigger | BT rules |
| "Do Not Disturb" access | `set_dnd`, muting via volume | DND rules |
| Battery optimization exemption | reliability on aggressive OEMs | recommended |

### With and without Shizuku

| Tier | What it covers |
|---|---|
| **Base (no Shizuku)** | volume, ringer, DND, flashlight, vibration, notifications, clipboard/OTP, real alarms and timers, WhatsApp replies, generative actions, all triggers |
| **Degraded without Shizuku** | `launch_app`, `open_url`, `open_settings_screen`, alarm/timer **from a rule in the background** (Android restricts activity starts from the background: with Shizuku they go through `am start`, without it they only start while the app is in the foreground) |
| **Shizuku only** | Wi-Fi/Bluetooth/mobile-data toggles, dark mode, `run_shell`, `write_setting`, privileged state readers (`setting`, `system_property`, `sysfs`, `dumpsys_field`, foreground app) |

Note: on non-rooted devices Shizuku must be started via ADB and **does not survive a reboot**; Argus detects this and degrades fail-closed (the privileged action is never executed late), guiding you through recovery.

`tap` and `input_text` are present in the domain/tooling as foundations for future computer-use,
but the production executor rejects them and the capability manifest marks them unavailable.

---

## Build & installation

### Requirements

| What | Version |
|---|---|
| Gradle | wrapper **8.13** (included) |
| Android Gradle Plugin | **8.13.2** |
| Kotlin | **2.1.0** (KSP 2.1.0-1.0.29) |
| JDK | modules target **JVM 17** (explicit; no remote toolchain download); Gradle itself must run on JDK 17–21 (e.g. Android Studio's JBR) |
| Android SDK | compileSdk/targetSdk **36**, minSdk **30** (Android 11+) |
| Stack | Jetpack Compose (BOM 2025.05), Room 2.6.1, Hilt 2.57.1, Shizuku API 13.1.5 |

### Commands

```bash
# engine suite (pure JVM, fast — the primary verification)
./gradlew :engine-core:test

# debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# release APK (signed if a local keystore.properties exists — see below; unsigned otherwise)
./gradlew :app:assembleRelease
# → one APK per ABI: app/build/outputs/apk/release/app-<abi>-release.apk
# (build a single ABI with -PargusAbi=arm64-v8a)

# per-module tests
./gradlew :brain-android:test :automation-android:test :data:test :ui:test
```

To sign your own release: create `keystore.properties` in the root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) — the file and the keystores are gitignored and must never be committed. Official signed APKs are on the [GitHub Releases](https://github.com/JackRushante/argus/releases).

Installation: download the universal APK from the latest GitHub release and sideload it (`adb install`
or APK transfer). The F-Droid submission is still under review; do not assume it is available in the
official client until the metadata merge request is merged. There is no Play Store distribution.
On first launch the onboarding walks you through the LLM provider and permissions.

Create a `local.properties` file with `sdk.dir=<path to your Android SDK>` if Android Studio does not generate it by itself.

---

## For testers

Thanks for trying it. Useful things to know and to stress:

**What to try**

- **Compilation**: ask for rules in chat, from trivial to ambiguous ("in 2 minutes send me a notification with the EUR/USD rate", "when I leave home turn off Wi-Fi", "if my wife writes on WhatsApp after 11 pm reply for me"). Check that the draft rendered on the approval screen matches what you meant — it is the real rule, not the paraphrase.
- **Approval**: try to get "nasty" rules proposed (shell from SMS, disallowed tools, out-of-range values) and check that the validator blocks them with a clear reason.
- **Execution**: arm `time` rules (cron and one-shot), `immediate`, connectivity, geofence, notifications. Test the hostile cases: reboot, timezone change, DST, app killed by the OEM, Shizuku turned off midway.
- **Degradation**: deny permissions and turn off Shizuku, and check that the app honestly says what it cannot do instead of failing silently.
- **Budget** (generative rules): set a low limit and verify the audited suppression.

**Which logs to look at**

- **In-app "Log" tab**: every fire, suppression (cooldown/duplicate/budget), error and lifecycle event, with an expandable per-action detail. It is the source of truth: if a rule did not fire, the reason code is there.
- **"System" screen**: transport/provider status, Shizuku, permissions, battery exemption — useful to attach to a report.
- For crashes: `adb logcat` filtered on `dev.argus`.

**How to report**

Open an [Issue](https://github.com/JackRushante/argus/issues) with: what you asked in chat (exact text), the rule as shown on approval, what you expected, what happened, the relevant lines from the in-app Log, Android version and OEM, Shizuku status. The in-app log contains no message texts and no personal data: it is safe to paste.

---

## Project status and roadmap

**Active development.** Phases completed and verified on a real device: P0 (engine core + Android glue), P1 (notifications/WhatsApp generative replies), P2 (background triggers: SMS/OTP, calls, connectivity/power/BT, geofence), P3 (parametric state readers, sensor triggers, base tier without Shizuku, multi-provider with per-provider budgets and usage tracking), and P4 variables, deterministic structured flow, resolved model capture and nested delivery. Since **v0.2.0** the app is fully bilingual (English/Italian, follows the system language).

Short roadmap:

- **State-aware P4 generation** — design a resolved `invoke_llm_v2` lane with the same strict
  runtime-data framing as v3. Until that contract exists, the compiler and validator reject this
  combination before approval.
- **Broader device control** — complete and validate interactive screen→action execution, including a
  conservative accessibility/vision fallback for UI surfaces that expose insufficient semantics.
- **Cross-OEM hardening** — expand the real-device matrix beyond the current OnePlus/Android 16 test
  device, especially background starts, Shizuku recovery and notification metadata.

**Disclaimer**: this is a personal project, developed and tested mainly on a single device (OnePlus 15, Android 16, non-root). Expect rough edges on other OEMs — that is exactly the feedback we are looking for. No warranty: rules execute real actions on your phone, read what you approve.

---

## License

Argus is released under the [GNU General Public License v3.0](LICENSE) (GPL-3.0). You are free to use, study, modify and redistribute it; derivative works must remain open under the same license.
