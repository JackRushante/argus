# Argus end-to-end suite

About 160 rules (the number follows from the pairwise model below) run on an Android 16 x86_64 emulator, in three configurations:

| Configuration | Shizuku | What it proves |
|---|---|---|
| `shizuku_root` | started as root (uid 0) | every action works; `run_shell` runs as root |
| `shizuku_shell` | started from adb (uid 2000) | same, `run_shell` runs as shell |
| `none` | stopped | the base tier works; privileged actions and readers are refused at approval with `capability_unavailable` and nothing on the phone changes |

Nothing here uses an AI: every rule is JSON written in the suite, so there is no cost and the outcome is deterministic.

## Pieces

| Path | What |
|---|---|
| `app/src/debug/kotlin/dev/argus/e2e/` | The **debug-only hook**: a `ContentProvider` (`content://dev.argus.e2e`) that answers only to adb shell (uid 2000) and root (uid 0). It does not exist in a release build (manifest, classes and sources: `E2eReleaseExclusionTest`). |
| `e2e/suite/*.json` | The rules, with how to trigger them, the expected effects and the expected outcome per configuration. |
| `e2e/runner.py` | The runner (Python 3, standard library only). Runs on the PC and drives the emulator through `ssh $ARGUS_E2E_HOST $ARGUS_E2E_EMULATOR adb ...`. |
| `e2e/pairwise.py` | The coverage model and the generator of the pairwise plan. |
| `e2e/tests/` | Offline unit tests (no phone): `python -m unittest discover -s e2e/tests`. |

### The hook

Calls: `content call --uri content://dev.argus.e2e --method <m> --arg <base64 json>`; the answer is `Result: Bundle[{r=<base64 json>}]`. The argument travels as base64 because a command crosses three shells (PC, host, Android).

| Method | Does |
|---|---|
| `load` | Imports a rule (`AutomationDraft` JSON, **strict**: an unknown field is an error), approves it through the normal flow (`ApprovalFlow`: validator, fingerprint, capabilities, state probes) and arms it. Returns the id, or the validator issues (`stage`, `code`). |
| `reset_load` | `reset` then `load`, in one call. |
| `fire` | Delivers a synthetic `TriggerEvent` of the rule's trigger type to the engine, built with the same parsers as the real sources (same instant = same event id, so deduplication behaves as in production). `payload` can change the fields or, with `as`, the event type. `manual` rules go through the real `ManualRuleRunner`. |
| `journal` | Last execution of a rule: status, per-action outcome and error code, audit kinds and details. Closed-vocabulary codes only. |
| `reset` | Disarms (`AutomationEnablementCoordinator`) and deletes every rule and draft named `e2e/...`, and ends the pause. |
| `setup`, `status`, `pause`, `shizuku_request` | Environment helpers (privacy and onboarding flags, Shizuku state and capabilities, pause switch, open the Shizuku permission dialog). |

Where a simple real trigger exists, the suite uses it as well as the hook: `immediate`, `time` with `afterMs` (a real exact alarm), `manual`, the screen (`input keyevent KEYCODE_SLEEP/WAKEUP`), the battery (`dumpsys battery set level`), the charger (`dumpsys battery unplug`), ringer and Do Not Disturb (`cmd audio`, `cmd notification`), a posted notification (`cmd notification post`) and an app opened with `am start`.

## Running it

Requirements:

- a host reachable with `ssh` without a password, running an Android 16 x86_64 emulator behind a wrapper script with
  the subcommands `start`, `stop`, `status`, `adb <args>`, `install <apk>` and `shizuku shell|root`;
- the environment variables `ARGUS_E2E_HOST` (ssh host), `ARGUS_E2E_EMULATOR` (path of the wrapper on the host) and,
  optionally, `ARGUS_E2E_BUILDS` (folder of debug APKs on the host) and `ARGUS_E2E_AVD_DIR` (AVD folder, to clear
  stale locks);
- on Windows with Git Bash, `export MSYS2_ENV_CONV_EXCL='ARGUS_E2E_'`, or Git Bash rewrites the host paths;
- a **debug** x86_64 APK: `./gradlew :app:assembleDebug -PargusAbi=x86_64`, copied to the host (or pass `--apk`).

Then, from the repository root:

```
python e2e/runner.py                         # all three configurations, starts the emulator if needed
python e2e/runner.py --config none           # one configuration (repeatable)
python e2e/runner.py --only A01-notification,B01-wifi-off -v
python e2e/runner.py --skip-install          # keep the APK already installed
python e2e/runner.py --stop-emulator         # switch the emulator off at the end
python e2e/runner.py --list                  # the rules and their configurations
python e2e/runner.py --check-suite           # schema and pairwise coverage, no phone needed
```

The runner installs the newest `*debug*.apk` from `projects/android/artifacts/Argus/_builds` (uninstalling first, so it starts clean), grants what a user would grant from Settings (notifications, location, SMS and phone state, Do Not Disturb access, "Modify system settings", usage access, the notification listener, the Accessibility service, battery-optimization exemption; **not** `WRITE_SECURE_SETTINGS`, so "no Shizuku" stays without), switches the configuration (`android-emulator.sh shizuku root|shell`, or stops Shizuku; for Shizuku it also presses "Allow all the time" in the permission dialog), runs the rules and restores the touched state (Wi-Fi, mobile data, airplane mode, location, battery saver, dark mode, Do Not Disturb, ringer, volumes, brightness, rotation, battery overrides, test settings, screenshots). Exit code 0 means every rule passed.

Reports go to `e2e/reports/<timestamp>/` (git-ignored): `report.md` (summary per configuration, failures with the reason, pairwise coverage, action x configuration gaps) and `report.json` (everything, including the journal and the device output of failures).

One rule takes about 6 s (one ssh round trip; the rest is `content call` start-up on the emulator), so a full configuration is a quarter of an hour or more.

The emulator container is started with 10 GB (`MEM=10g`): with the default 6 GB qemu is killed by the out-of-memory killer after about half an hour. If it dies anyway, the runner removes the stale AVD lock files, starts it again, re-applies the configuration and repeats the rule that failed.

## Suite format

```json
{
  "id": "B01-wifi-off", "title": "Wi-Fi off", "cond": "true",
  "rule": { "name": "B01-wifi-off", "trigger": {"type": "immediate"}, "actions": [{"type": "set_wifi", "on": false}], "conditions": {"type": "state_equals", "key": "screen", "op": "EQ", "value": "on"} },
  "pre": ["svc wifi enable"],
  "fire": null,
  "wait_s": 3,
  "checks": [{"shell": "settings get global wifi_on", "equals": "0", "wait_s": 8}],
  "expect": {"load": "ok", "run": "SUCCEEDED"},
  "cleanup": ["svc wifi enable"],
  "per_config": {"none": {"expect": {"load": {"fail": {"stage": "review", "code": "capability_unavailable", "detail": "action.set_wifi"}}}, "checks": [], "cleanup": []}}
}
```

* `cond` (`none`, `true`, `false`) is the expected outcome of the rule's conditions; it feeds the coverage model.
* `fire`: `null` (the trigger fires on its own: `immediate`, `time`), `{"payload": {...}}` (the hook delivers the event; `repeat`, `delay_s`, `settle_s` are optional) or `{"real": ["shell command", seconds, ...]}` (commands run on the phone after the rule is armed, to cause the real trigger).
* `after_load`: commands run after arming and before the trigger (for example stopping Shizuku). `pause: true` turns the "Pause automations" switch on.
* `checks`: shell commands on the phone with `equals`, `contains`, `not_contains` or `matches`; `wait_s` retries once a second. `{{RUN}}` in titles and checks is replaced by an id unique to the run and the configuration, so a leftover notification can never satisfy a check.
* `expect.load`: `"ok"`, or `{"fail": {"stage": "decode|submit|review|arm", "code": "...", "detail": "..."}}`. `expect.run`: a status string, or `executions`, `status`, `actions` (`type:OUTCOME[:code]`), `actions_contain`, `audit_has`, `audit_lacks`, `audit_detail`, `code`.
* `per_config` replaces any of `pre, after_load, fire, wait_s, checks, expect, cleanup, needs, rule, skip, pause` for one configuration; `skip` gives a reason. `needs`: `internet`, `package:<name>`, `feature:<name>`; unmet needs skip the rule and the report says so.
* `restore_shizuku: true` restarts Shizuku after the rule (it stops it on purpose).
* `foreground: false` does not bring Argus to the front before the rule (the default does, like a user with the app open). `ui_steps` without `pkg` act on the current screen and refuse Argus itself (`ui_forbidden_screen`), so those rules put another app in front with `pre`.
* In `pre`, `after_load`, `fire.real` and `cleanup`, `asshell "<command line>"` runs the command as the shell user even when adb is root (the notification service rejects `cmd notification post` from uid 0).

## Pairwise coverage

`e2e/pairwise.py` declares the parameters and the pairs the suite must cover; `python e2e/runner.py --check-suite` fails if a pair is missing and `python e2e/pairwise.py` prints a plan of rows that would fill the gaps. The rules are the points of the model; `features()` derives them from each rule.

| Parameter | Values |
|---|---|
| trigger | `time`, `immediate`, `manual`, `geofence`, `notification`, `phone_state`, `connectivity`, `sensor`, `screen`, `battery`, `audio_output`, `mode_changed`, `media`, `app_event` |
| action class | `local_ui`, `audio_mode`, `display`, `launch`, `network_out`, `radios`, `device_control`, `shell`, `screen_macro` |
| condition | `none`, `true`, `false` |
| P4 feature | `vars`, `clock`, `capture`, `if`, `while`, `wait` |
| configuration | the three above: every rule runs in all three (or says why not) |

Required pairs: trigger x class, trigger x condition, class x condition, P4 feature x condition, P4 feature x class (265 in total). The `sensor` trigger cannot be armed on the emulator (no significant-motion sensor), so its two condition pairs are declared infeasible; its class pairs are covered by rules that must be refused with `capability_unavailable`. A shell or screen macro started by an SMS, a notification, the music or an app opening is covered by rules that must be refused (`shell_external_trigger`, `ui_steps_external_trigger`).

Action x configuration is covered by running every rule in every configuration; the report lists the pairs that did not run (skipped rules).

## Not in the suite

* `tap`, `input_text`: the engine refuses them (reserved).
* `invoke_llm`, `invoke_llm_v2`: they need an AI; the suite does not.
* `whatsapp_reply`: needs WhatsApp.
* Sunrise/sunset: needs a location fix at arm time (the rule is in the suite, skipped with the reason).
* Mobile data: the emulator has no observable effect for `svc data`; only the journal is checked.
* Phone and geofence triggers use synthetic events: the emulator console (`adb emu sms/gsm/geo`) is not reachable from the phone's shell.
