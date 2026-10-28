#!/usr/bin/env python3
"""Runner della suite E2E di Argus 0.6.0 su un emulatore Android remoto.

Gira sul PC (l'host può non avere Python) e comanda l'emulatore via `ssh $ARGUS_E2E_HOST $ARGUS_E2E_EMULATOR adb ...`.
Per ciascuna delle tre configurazioni (Shizuku root, Shizuku shell, nessuno Shizuku) prepara l'emulatore,
esegue ogni regola della suite attraverso l'aggancio di debug dell'APK (`dev.argus.e2e`), ripristina lo
stato toccato e scrive un rapporto (markdown + JSON). Vedi `e2e/README.md`.
"""
from __future__ import annotations

import argparse
import os
import base64
import datetime as _dt
import json
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pairwise  # noqa: E402
from device import Emulator  # noqa: E402
from rulescript import (  # noqa: E402
    HOOK_URI,
    build_script,
    substitute,
    decode_result,
    encode_script_command,
    evaluate,
    hook_error,
)
from suite import effective, load_suite, validate  # noqa: E402

PACKAGE = "dev.argus"
MAIN_ACTIVITY = f"{PACKAGE}/.MainActivity"
# Cartella dell'host con gli APK di debug (solo da variabile d'ambiente; con --apk non serve).
BUILDS = os.environ.get("ARGUS_E2E_BUILDS", "")

# Permessi e accessi che un utente concederebbe da Impostazioni: uguali in tutte e tre le configurazioni.
# NON si concedono WRITE_SECURE_SETTINGS ne' la rimozione dell'ottimizzazione Shizuku: «senza Shizuku»
# deve restare senza.
BASE_GRANTS = [
    f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS",
    f"pm grant {PACKAGE} android.permission.ACCESS_FINE_LOCATION",
    f"pm grant {PACKAGE} android.permission.ACCESS_COARSE_LOCATION",
    f"pm grant {PACKAGE} android.permission.ACCESS_BACKGROUND_LOCATION",
    f"pm grant {PACKAGE} android.permission.RECEIVE_SMS",
    f"pm grant {PACKAGE} android.permission.READ_PHONE_STATE",
    f"pm grant {PACKAGE} android.permission.READ_CALL_LOG",
    f"pm grant {PACKAGE} android.permission.BLUETOOTH_CONNECT",
    f"pm grant {PACKAGE} android.permission.ACTIVITY_RECOGNITION",
    f"appops set {PACKAGE} SCHEDULE_EXACT_ALARM allow",
    f"appops set {PACKAGE} WRITE_SETTINGS allow",
    f"appops set {PACKAGE} GET_USAGE_STATS allow",
    f"cmd notification allow_dnd {PACKAGE}",
    f"cmd notification allow_listener {PACKAGE}/{PACKAGE}.automation.notification.ArgusNotificationListenerService",
    f"settings put secure enabled_accessibility_services {PACKAGE}/{PACKAGE}.automation.screen.ArgusAccessibilityService",
    "settings put secure accessibility_enabled 1",
    f"cmd deviceidle whitelist +{PACKAGE}",
    "settings put global stay_on_while_plugged_in 7",
]

# Stato di partenza noto, rimesso a fine sessione (e a inizio): cio' che le regole della suite possono toccare.
BASELINE = [
    "dumpsys battery reset",
    "svc wifi enable",
    "svc data enable",
    "cmd connectivity airplane-mode disable",
    "cmd location set-location-enabled true",
    "cmd power set-mode 0",
    "cmd uimode night no",
    "cmd notification set_dnd off",
    "settings put global mode_ringer 2",
    "settings put system accelerometer_rotation 1",
    "settings put system screen_brightness_mode 1",
    "settings put system screen_brightness 128",
    "cmd media_session volume --stream 3 --set 7",
    "cmd media_session volume --stream 2 --set 5",
    "cmd media_session volume --stream 4 --set 5",
    "cmd media_session volume --stream 5 --set 5",
    "settings delete global e2e_marker",
    "settings delete system e2e_marker",
    "settings delete secure e2e_marker",
    "rm -rf /sdcard/Pictures/Argus",
    "pm clear com.google.android.deskclock",  # timer e sveglie impostati dalle regole
    "input keyevent KEYCODE_WAKEUP",
    "wm dismiss-keyguard",
]

SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
SHIZUKU_ALLOW_LABELS = ["Allow all the time", "Allow", "ALLOW ALL THE TIME", "ALLOW", "Consenti sempre", "Consenti"]


@dataclass
class Result:
    id: str
    title: str
    config: str
    status: str  # pass | fail | skip
    problems: List[str] = field(default_factory=list)
    actions: List[str] = field(default_factory=list)
    seconds: float = 0.0
    note: str = ""
    details: Dict[str, object] = field(default_factory=dict)


# ---------------------------------------------------------------------------------------------------
# Aggancio


def hook(emu: Emulator, method: str, request: Optional[dict] = None, timeout: float = 120.0) -> Optional[dict]:
    command = f"content call --uri {HOOK_URI} --method {method}"
    if request is not None:
        arg = base64.b64encode(json.dumps(request).encode("utf-8")).decode("ascii")
        command += f" --arg {arg}"
    return decode_result(emu.sh(command, timeout=timeout))


# ---------------------------------------------------------------------------------------------------
# Preparazione dell'emulatore


def latest_debug_apk(emu: Emulator) -> str:
    listing = emu.ssh(f"ls -t {BUILDS}/*debug*.apk 2>/dev/null | head -1").stdout.strip()
    if not listing:
        raise RuntimeError(f"nessun APK di debug in {BUILDS!r}: compila :app:assembleDebug -PargusAbi=x86_64 o passa --apk")
    return listing


def install_app(emu: Emulator, apk: str) -> None:
    print(f"== installo {apk}")
    emu.adb("uninstall", PACKAGE, timeout=60)
    out = emu.install(apk)
    if "Success" not in out:
        raise RuntimeError(f"installazione fallita: {out[-300:]}")


def grant_base(emu: Emulator) -> None:
    script = "\n".join(f"{c} >/dev/null 2>&1" for c in BASE_GRANTS)
    emu.sh(f"echo {base64.b64encode(script.encode()).decode()} | base64 -d | sh", timeout=120)


def restore_baseline(emu: Emulator) -> None:
    script = "\n".join(f"{c} >/dev/null 2>&1" for c in BASELINE)
    emu.sh(f"echo {base64.b64encode(script.encode()).decode()} | base64 -d | sh", timeout=180)


def start_app(emu: Emulator) -> None:
    emu.sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n " + MAIN_ACTIVITY)
    time.sleep(3)


def stop_shizuku(emu: Emulator) -> None:
    emu.adb("root", timeout=60)
    time.sleep(3)
    emu.adb("wait-for-device", timeout=60)
    emu.sh("pkill -f shizuku_server")
    emu.adb("unroot", timeout=60)
    time.sleep(3)
    emu.adb("wait-for-device", timeout=60)


def grant_shizuku_permission(emu: Emulator, verbose: bool) -> bool:
    """Apre la richiesta di permesso di Shizuku dall'app e preme «Consenti» (uguale a un utente reale)."""
    hook(emu, "shizuku_request")
    for _ in range(12):
        time.sleep(2)
        labels = emu.ui()
        if verbose:
            print("    UI:", " | ".join(l.split(" ", 2)[-1] for l in labels)[:300])
        for wanted in SHIZUKU_ALLOW_LABELS:
            if any(line.split(" ", 2)[-1] == wanted for line in labels):
                emu.tap(wanted)
                time.sleep(2)
                status = hook(emu, "status") or {}
                if status.get("shizukuAuthorized"):
                    return True
        status = hook(emu, "status") or {}
        if status.get("shizukuAuthorized"):
            return True
    return False


def switch_config(emu: Emulator, config: str, verbose: bool) -> Dict[str, object]:
    print(f"== configurazione {config}")
    if config == "shizuku_root":
        print("  ", emu.shizuku("root").splitlines()[-1:] or "")
    elif config == "shizuku_shell":
        print("  ", emu.shizuku("shell").splitlines()[-1:] or "")
    else:
        stop_shizuku(emu)
        # «Senza Shizuku» vero: fermo E senza il permesso. Con il permesso gia' concesso Argus tratta
        # Shizuku fermo come un'interruzione passeggera e arma comunque le regole privilegiate.
        emu.sh(f"pm revoke {PACKAGE} {SHIZUKU_PERMISSION}")
    emu.sh(f"am force-stop {PACKAGE}")
    # Fermare il processo spegne il servizio di Accessibilita': si riaccende come farebbe l'utente.
    grant_base(emu)
    start_app(emu)
    status = hook(emu, "setup", timeout=120) or {}
    if config != "none" and status.get("shizuku") == "RUNNING_NOT_AUTHORIZED":
        print("   Shizuku non autorizzato per Argus: chiedo il permesso")
        grant_shizuku_permission(emu, verbose)
        status = hook(emu, "status") or {}
    # Il binder di Shizuku puo' arrivare con qualche secondo di ritardo.
    for _ in range(10):
        authorized = bool(status.get("shizukuAuthorized"))
        if authorized == (config != "none"):
            break
        time.sleep(3)
        status = hook(emu, "status") or {}
    print(f"   Shizuku: {status.get('shizuku')}  capability: {len(status.get('capabilities', []))}")
    return status


# ---------------------------------------------------------------------------------------------------
# Requisiti dell'ambiente


class Needs:
    def __init__(self, emu: Emulator):
        self.emu = emu
        self.cache: Dict[str, bool] = {}

    def satisfied(self, need: str) -> bool:
        if need not in self.cache:
            self.cache[need] = self._probe(need)
        return self.cache[need]

    def _probe(self, need: str) -> bool:
        if need == "internet":
            out = self.emu.sh("ping -c1 -W3 example.com >/dev/null 2>&1 && echo ok", timeout=30)
            return out == "ok"
        if need.startswith("package:"):
            name = need.split(":", 1)[1]
            return f"package:{name}" in self.emu.sh(f"pm list packages {name}", timeout=30)
        if need.startswith("feature:"):
            name = need.split(":", 1)[1]
            return self.emu.sh(f"pm has-feature {name} && echo ok", timeout=30).endswith("ok")
        return False


# ---------------------------------------------------------------------------------------------------
# Esecuzione


def action_types(rec: dict) -> List[str]:
    return sorted({a["type"] for a in pairwise._walk_actions(rec["rule"].get("actions", []))})


def run_rule(emu: Emulator, rec: dict, config: str, needs: Needs, verbose: bool, run_id: str) -> Result:
    eff = substitute(effective(rec, config), {"RUN": run_id})
    base = dict(id=rec["id"], title=rec["title"], config=config, actions=action_types(rec))
    if eff.get("skip"):
        return Result(status="skip", note=str(eff["skip"]), **base)
    missing = [n for n in eff.get("needs", []) if not needs.satisfied(n)]
    if missing:
        return Result(status="skip", note="requisiti mancanti: " + ", ".join(missing), **base)
    script = build_script(eff)
    started = time.time()
    completed = emu.shell(encode_script_command(script), timeout=float(eff.get("timeout_s", 240)))
    elapsed = time.time() - started
    status, problems, details = evaluate(eff, completed.stdout)
    if completed.returncode == 124:
        status, problems = "fail", problems + [completed.stderr or "timeout"]
    if verbose or status == "fail":
        details["output"] = completed.stdout[-6000:]
    if eff.get("restore_shizuku") and config != "none":
        emu.shizuku("root" if config == "shizuku_root" else "shell")
        time.sleep(5)
        emu.sh(f"am force-stop {PACKAGE}")
        grant_base(emu)  # force-stop spegne il servizio di Accessibilita'
        start_app(emu)
        hook(emu, "setup", timeout=120)
    return Result(status=status, problems=problems, seconds=round(elapsed, 1), details=details, **base)


def recover_emulator(emu: Emulator, config: str, verbose: bool) -> None:
    """L'emulatore e' morto a meta' corsa (OOM, kill): lo riaccende e rimette la configurazione."""
    print("   !! emulatore non raggiungibile: lo riavvio e ripristino la configurazione", flush=True)
    emu.start()
    emu.wait_for_boot()
    switch_config(emu, config, verbose)
    restore_baseline(emu)


def no_verdict(result: Result) -> bool:
    return any("non e' arrivato in fondo" in p or "l'aggancio non risponde" in p for p in result.problems)


def run_config(emu, records, config, only, verbose) -> List[Result]:
    needs = Needs(emu)
    results: List[Result] = []
    run_id = config[:3] + _dt.datetime.now().strftime("%H%M%S")
    selected = [r for r in records if config in r.get("configs", pairwise.CONFIGS) and (not only or r["id"] in only)]
    for index, rec in enumerate(selected, 1):
        result = run_rule(emu, rec, config, needs, verbose, run_id)
        if result.status == "fail" and no_verdict(result):
            # Lo script sul telefono non e' arrivato in fondo o l'aggancio non risponde: non c'e' un verdetto sulla
            # regola. Se l'emulatore e' morto lo si riavvia (puo' risultare ancora acceso per qualche secondo dopo
            # l'uccisione, per questo l'attesa); in ogni caso la regola si ripete una volta.
            time.sleep(10)
            if not (emu.is_running() and emu.booted()):
                recover_emulator(emu, config, verbose)
            result = run_rule(emu, rec, config, needs, verbose, run_id)
            result.note = "rieseguita: la prima volta non c'e' stato un verdetto (emulatore o shell interrotti)"
        results.append(result)
        mark = {"pass": "ok  ", "fail": "FAIL", "skip": "skip"}[result.status]
        extra = f"  {result.problems[0][:140]}" if result.problems else (f"  ({result.note})" if result.note else "")
        print(f"  [{index:3}/{len(selected)}] {mark} {rec['id']:<28} {result.seconds:5.1f}s{extra}", flush=True)
    return results


# ---------------------------------------------------------------------------------------------------
# Rapporto


def coverage_report(records: List[dict], results: List[Result]) -> List[str]:
    lines: List[str] = []
    gaps = pairwise.missing_pairs(records)
    required = pairwise.required_pairs()
    lines.append(f"- Coppie richieste: {len(required)}; coperte dalla suite: {len(required) - len(gaps)}; scoperte: {len(gaps)}")
    for gap in gaps[:40]:
        lines.append(f"  - scoperta: {pairwise.describe(gap)}")
    executed = [asdict(r) for r in results]
    all_actions = sorted({a for rec in records for a in action_types(rec)})
    config_gaps = pairwise.action_config_gaps(executed, all_actions)
    lines.append(f"- Tipi di azione nella suite: {len(all_actions)}; coppie azione x configurazione non eseguite: {len(config_gaps)}")
    for action, config in config_gaps[:40]:
        lines.append(f"  - non eseguita: {action} in {config}")
    return lines


def write_report(directory: Path, records, results: List[Result], statuses: Dict[str, dict], meta: dict) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "report.json").write_text(
        json.dumps({"meta": meta, "statuses": statuses, "results": [asdict(r) for r in results]}, indent=1, ensure_ascii=False),
        encoding="utf-8",
    )
    lines = [f"# Suite E2E Argus 0.6.0 — {meta['when']}", ""]
    lines.append(f"APK: `{meta.get('apk')}`  ·  regole nella suite: {len(records)}")
    lines.append("")
    lines.append("| Configurazione | Regole eseguite | Passate | Fallite | Saltate |")
    lines.append("|---|---|---|---|---|")
    for config in pairwise.CONFIGS:
        mine = [r for r in results if r.config == config]
        if not mine:
            continue
        passed = sum(r.status == "pass" for r in mine)
        failed = sum(r.status == "fail" for r in mine)
        skipped = sum(r.status == "skip" for r in mine)
        lines.append(f"| {config} | {len(mine) - skipped} | {passed} | {failed} | {skipped} |")
    lines.append("")
    lines.append("## Copertura")
    lines += coverage_report(records, results)
    for config in pairwise.CONFIGS:
        mine = [r for r in results if r.config == config]
        if not mine:
            continue
        lines += ["", f"## {config}"]
        status = statuses.get(config, {})
        lines.append(f"Shizuku: `{status.get('shizuku')}`; capability pubblicate: {len(status.get('capabilities', []))}")
        bad = [r for r in mine if r.status == "fail"]
        if bad:
            lines += ["", "### Fallite"]
            for r in bad:
                lines.append(f"- **{r.id}** — {r.title}")
                for problem in r.problems:
                    lines.append(f"  - {problem}")
        skipped = [r for r in mine if r.status == "skip"]
        if skipped:
            lines += ["", "### Saltate"]
            for r in skipped:
                lines.append(f"- {r.id}: {r.note}")
        lines += ["", "### Tutte", "", "| Regola | Esito | s |", "|---|---|---|"]
        for r in mine:
            lines.append(f"| {r.id} | {r.status} | {r.seconds} |")
    path = directory / "report.md"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


# ---------------------------------------------------------------------------------------------------


def main(argv: Optional[List[str]] = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # la console di Windows e' cp1252
        except (AttributeError, ValueError):
            pass
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", action="append", choices=pairwise.CONFIGS, help="configurazione da eseguire (ripetibile; default: tutte)")
    parser.add_argument("--only", help="id delle regole, separati da virgola")
    parser.add_argument("--apk", help="APK di debug sull'host (default: l'ultimo in $ARGUS_E2E_BUILDS)")
    parser.add_argument("--skip-install", action="store_true", help="non reinstalla l'APK (usa quello presente)")
    parser.add_argument("--no-start", action="store_true", help="non avvia l'emulatore se e' spento")
    parser.add_argument("--stop-emulator", action="store_true", help="spegne l'emulatore a fine corsa")
    parser.add_argument("--report-dir", default=str(Path(__file__).resolve().parent / "reports" / _dt.datetime.now().strftime("%Y%m%d-%H%M%S")))
    parser.add_argument("--list", action="store_true", help="elenca le regole e esce")
    parser.add_argument("--check-suite", action="store_true", help="controlla la suite (schema e copertura) senza il telefono")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args(argv)

    records = load_suite()
    problems = validate(records)
    gaps = pairwise.missing_pairs(records)
    if args.list:
        for rec in records:
            print(f"{rec['id']:<28} {','.join(rec.get('configs', pairwise.CONFIGS)):<40} {rec['title']}")
        return 0
    if problems:
        print("Suite non valida:")
        for problem in problems:
            print("  -", problem)
        return 2
    if args.check_suite:
        print(f"suite valida: {len(records)} regole, {len(gaps)} coppie scoperte")
        for gap in gaps:
            print("  scoperta:", pairwise.describe(gap))
        return 1 if gaps else 0

    only = set(args.only.split(",")) if args.only else None
    configs = args.config or pairwise.CONFIGS
    emu = Emulator(verbose=args.verbose)
    if not args.no_start:
        emu.start()
    elif not emu.is_running():
        print("emulatore spento")
        return 2
    apk = args.apk or latest_debug_apk(emu)
    if not args.skip_install:
        install_app(emu, apk)
    grant_base(emu)

    results: List[Result] = []
    statuses: Dict[str, dict] = {}

    def meta() -> dict:
        return {"when": _dt.datetime.now().isoformat(timespec="seconds"), "apk": apk, "configs": configs, "ssh_calls": emu.calls}

    try:
        for config in configs:
            statuses[config] = switch_config(emu, config, args.verbose)
            restore_baseline(emu)
            results += run_config(emu, records, config, only, args.verbose)
            restore_baseline(emu)
            # Rapporto parziale dopo ogni configurazione: una corsa interrotta non perde quelle gia' finite.
            write_report(Path(args.report_dir), records, results, statuses, meta())
    finally:
        hook(emu, "reset")
        restore_baseline(emu)
        if args.stop_emulator:
            emu.stop()
    path = write_report(Path(args.report_dir), records, results, statuses, meta())
    failed = sum(r.status == "fail" for r in results)
    print(f"\nrapporto: {path}\nfallite: {failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
