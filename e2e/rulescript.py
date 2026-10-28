"""Script da eseguire sul telefono per UNA regola, e valutazione dell'output.

Per ogni regola e per ogni configurazione il runner fa un solo giro (ssh -> docker -> adb): un piccolo
script di shell scarica la regola nell'aggancio di debug, la innesca, aspetta, esegue i controlli,
legge il journal e disfa tutto. L'output ha sezioni `##NOME##` che `parse_sections` separa e `evaluate`
confronta con le attese. Tutto questo e' puro (nessun I/O): lo provano i test in `e2e/tests`.
"""
from __future__ import annotations

import base64
import json
import re
import shlex
from typing import Dict, List, Optional, Tuple

HOOK_URI = "content://dev.argus.e2e"
DEFAULT_WAIT_S = 1.5
REAL_SETTLE_S = 2.0

PRELUDE = r"""
H=content://dev.argus.e2e
b() { sed -n 's#.*r=\([A-Za-z0-9+/=]*\).*#\1#p'; }
call0() { content call --uri "$H" --method "$1" 2>&1; }
call() { content call --uri "$H" --method "$1" --arg "$2" 2>&1; }
enc() { base64 | tr -d '\n'; }
# Con `adb root` (configurazione Shizuku root) la shell di adb e' uid 0: alcuni comandi (es. `cmd notification
# post`) vengono rifiutati dal sistema se non arrivano dall'utente shell (uid 2000). Un solo argomento: la riga.
asshell() { if [ "$(id -u)" = 0 ]; then su 2000 sh -c "$1"; else sh -c "$1"; fi; }
"""

SECTION = re.compile(r"^##([A-Z0-9_]+)##(.*)$")


def substitute(rec: dict, values: Dict[str, str]) -> dict:
    """Sostituisce i segnaposto (es. `{{RUN}}`, unico per corsa e configurazione) in tutta la voce.

    Serve a titoli e testi delle notifiche: cosi' un controllo non puo' essere soddisfatto da un residuo
    della corsa precedente.
    """
    text = json.dumps(rec, ensure_ascii=False)
    for key, value in values.items():
        text = text.replace("{{" + key + "}}", value)
    return json.loads(text)


def q(text: str) -> str:
    return shlex.quote(text)


def _number(value: float) -> str:
    return ("%g" % value)


def check_snippet(index: int, check: dict) -> str:
    """Esegue il comando del controllo, riprovando ogni secondo fino a `wait_s` finche' l'attesa non e' vera."""
    wait = int(check.get("wait_s", 0))
    command = check["shell"]
    if "equals" in check:
        test = f'[ "$OUT" = {q(str(check["equals"]))} ] && break'
    elif "contains" in check:
        test = f'case "$OUT" in *{q(str(check["contains"]))}*) break;; esac'
    elif "not_contains" in check:
        test = f'case "$OUT" in *{q(str(check["not_contains"]))}*) ;; *) break;; esac'
    else:
        test = f'printf %s "$OUT" | grep -Eq {q(str(check["matches"]))} && break'
    return (
        f"echo '##CHK{index}##'\n"
        f"i=0\n"
        f"while :; do\n"
        f"  OUT=$( {{ {command} ; }} 2>&1 | grep -v '^Failed to write while dumping' )\n"
        f"  {test}\n"
        f"  i=$((i+1)); [ $i -gt {wait} ] && break\n"
        f"  sleep 1\n"
        f"done\n"
        f"printf '%s\\n' \"$OUT\"\n"
    )


def build_script(rec: dict) -> str:
    """Lo script per la voce `rec` (gia' risolta per la configurazione, vedi `suite.effective`)."""
    rule_json = json.dumps(rec["rule"], ensure_ascii=False, separators=(",", ":"))
    fire = rec.get("fire")
    lines: List[str] = [PRELUDE]
    if rec.get("foreground", True):
        # Argus in primo piano e schermo acceso: come un utente che ha l'app aperta (i servizi in primo piano
        # delle regole partono, le attivita' si aprono dal contesto giusto).
        lines.append("input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; am start -n dev.argus/.MainActivity >/dev/null 2>&1; sleep 0.3")
    for command in rec.get("pre", []):
        lines.append(f"{command} >/dev/null 2>&1")
    lines.append(f"LARG=$(printf %s {q(rule_json)} | enc)")
    lines.append("echo '##LOAD##'; L=$(call reset_load \"$LARG\"); echo \"$L\"")
    lines.append(
        "ID=$(printf %s \"$L\" | b | base64 -d 2>/dev/null | sed -n 's#.*\"id\":\"\\([^\"]*\\)\".*#\\1#p')"
    )
    lines.append("echo \"##ID##$ID\"")
    lines.append("if [ -n \"$ID\" ]; then")
    for command in rec.get("after_load", []):
        if isinstance(command, (int, float)):
            lines.append(f"  sleep {_number(command)}")
        else:
            lines.append(f"  {command} >/dev/null 2>&1")
    if rec.get("pause"):
        lines.append("  call pause \"$(printf '{\"on\":true}' | enc)\" >/dev/null")
    if fire and "payload" in fire:
        payload = json.dumps(fire["payload"], ensure_ascii=False, separators=(",", ":"))
        repeat = int(fire.get("repeat", 1))
        delay = fire.get("delay_s", 0)
        lines.append(f"  sleep {_number(fire.get('settle_s', 0.5))}")
        for n in range(repeat):
            lines.append(
                f"  FARG=$(printf '{{\"id\":\"%s\",\"payload\":%s,\"timeoutMs\":60000}}' \"$ID\" {q(payload)} | enc)"
            )
            lines.append(f"  echo '##FIRE{n}##'; call fire \"$FARG\"")
            if delay and n < repeat - 1:
                lines.append(f"  sleep {_number(delay)}")
    elif fire and "real" in fire:
        lines.append(f"  sleep {_number(fire.get('settle_s', REAL_SETTLE_S))}")
        lines.append("  echo '##REAL##'")
        for command in fire["real"]:
            if isinstance(command, (int, float)):
                lines.append(f"  sleep {_number(command)}")
            else:
                lines.append(f"  {command} 2>&1")
    run = rec.get("expect", {}).get("run")
    polled = isinstance(run, str) or (isinstance(run, dict) and "status" in run)
    spec = run if isinstance(run, dict) else {}
    needs_audit = any(k in spec for k in ("audit_has", "audit_lacks", "audit_detail", "code"))
    # Con l'attesa del termine dell'esecuzione (poll sotto) basta una pausa minima; senza, serve il tempo pieno.
    wait = rec.get("wait_s", DEFAULT_WAIT_S)
    lines.append(f"  sleep {_number(min(wait, 0.5) if polled else wait)}")
    if polled:
        # Un programma lungo (attese, macro sullo schermo) puo' non aver finito: si aspetta il termine.
        timeout = int(rec.get("run_timeout_s", 40))
        lines.append("  JARG=$(printf '{\"id\":\"%s\"}' \"$ID\" | enc)")
        lines.append("  n=0")
        lines.append("  while [ $n -lt %d ]; do" % timeout)
        lines.append("    JR=$(call journal \"$JARG\")")
        lines.append("    J=$(printf %s \"$JR\" | b | base64 -d 2>/dev/null)")
        lines.append("    case \"$J\" in *'\"last\":{\"status\":\"RUNNING\"'*) ;; *'\"last\":{'*) break;; esac")
        lines.append("    n=$((n+1)); sleep 1")
        lines.append("  done")
    for index, check in enumerate(rec.get("checks", [])):
        lines.append(check_snippet(index, check))
    if polled and not needs_audit:
        lines.append("  echo '##JOURNAL##'; echo \"$JR\"")
    else:
        lines.append(
            "  JARG=$(printf '{\"id\":\"%s\"}' \"$ID\" | enc); echo '##JOURNAL##'; call journal \"$JARG\""
        )
    lines.append("else")
    lines.append("  :")
    # Caricamento fallito: i controlli girano comunque (per le attese del tipo «non e' cambiato niente»).
    for index, check in enumerate(rec.get("checks", [])):
        lines.append(check_snippet(index, check))
    lines.append("fi")
    # Prima si disarma tutto, poi si ripristina: una regola ancora armata potrebbe scattare per i comandi
    # di ripristino stessi (schermo acceso, batteria, modalita') e rimettere in disordine il telefono.
    lines.append("echo '##RESET2##'; call0 reset")
    for command in rec.get("cleanup", []):
        lines.append(f"{command} >/dev/null 2>&1")
    lines.append("echo '##END##'")
    return "\n".join(lines) + "\n"


def encode_script_command(script: str, path: str = "/data/local/tmp/e2e_step.sh") -> str:
    """Comando per `adb shell`: scrive lo script (base64: nessuna virgoletta da proteggere) e lo esegue."""
    b64 = base64.b64encode(script.encode("utf-8")).decode("ascii")
    return f"echo {b64} | base64 -d > {path}; sh {path} </dev/null 2>&1"


# ---------------------------------------------------------------------------------------------------
# Output


def parse_sections(output: str) -> Dict[str, str]:
    """`##NOME##` apre una sezione; il resto della riga (se c'e') e' il suo primo contenuto."""
    sections: Dict[str, List[str]] = {}
    current: Optional[str] = None
    for line in output.replace("\r", "").split("\n"):
        match = SECTION.match(line)
        if match:
            current = match.group(1)
            sections.setdefault(current, [])
            if match.group(2):
                sections[current].append(match.group(2))
        elif current is not None:
            sections[current].append(line)
    return {name: "\n".join(lines).strip("\n") for name, lines in sections.items()}


def decode_result(section: Optional[str]) -> Optional[dict]:
    """Il JSON del risultato di una `content call` (chiave `r`, base64), o None se non c'e' o e' guasto."""
    if not section:
        return None
    match = re.search(r"r=([A-Za-z0-9+/=]+)", section)
    if not match:
        return None
    try:
        return json.loads(base64.b64decode(match.group(1)).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None


def hook_error(section: Optional[str]) -> str:
    """Perche' una chiamata all'aggancio non ha risposto (eccezione di sicurezza, provider assente...)."""
    text = (section or "").strip()
    if not text:
        return "nessuna risposta"
    return text.splitlines()[0][:200]


# ---------------------------------------------------------------------------------------------------
# Valutazione


def _issue_codes(result: dict) -> List[str]:
    codes = [issue.get("code", "") for issue in result.get("issues", [])]
    if result.get("code"):
        codes.append(result["code"])
    return codes


def _issue_details(result: dict) -> List[str]:
    return [issue.get("detail", "") for issue in result.get("issues", [])]


def evaluate_load(rec: dict, load: Optional[dict], raw: Optional[str]) -> Tuple[bool, List[str]]:
    """(la regola e' stata caricata?, problemi). Se il caricamento falliva come previsto, nessun problema."""
    expect = rec.get("expect", {}).get("load", "ok")
    if load is None:
        return False, [f"l'aggancio non risponde: {hook_error(raw)}"]
    if expect == "ok":
        if load.get("ok"):
            return True, []
        codes = ",".join(_issue_codes(load)) or load.get("stage", "?")
        return False, [f"caricamento rifiutato (stage={load.get('stage')}, codici={codes})"]
    fail = expect["fail"]
    if load.get("ok"):
        return True, [f"attesa un rifiuto ({fail['stage']}/{fail['code']}) ma la regola e' stata armata"]
    problems: List[str] = []
    if load.get("stage") != fail["stage"]:
        problems.append(f"stage {load.get('stage')!r} invece di {fail['stage']!r}")
    if fail["code"] not in _issue_codes(load):
        problems.append(f"codice {fail['code']!r} assente (ottenuti: {_issue_codes(load)})")
    if fail.get("detail") and fail["detail"] not in _issue_details(load):
        problems.append(f"detail {fail['detail']!r} assente (ottenuti: {_issue_details(load)})")
    return False, problems


def action_label(action: dict) -> str:
    label = f"{action['type']}:{action['outcome']}"
    return f"{label}:{action['code']}" if action.get("code") else label


def evaluate_run(run: object, journal: Optional[dict]) -> List[str]:
    if run is None:
        return []
    spec = {"status": run} if isinstance(run, str) else dict(run)
    if journal is None or not journal.get("ok"):
        return ["journal non disponibile"]
    problems: List[str] = []
    executions = journal.get("executions", 0)
    if "executions" in spec and executions != spec["executions"]:
        problems.append(f"esecuzioni {executions} invece di {spec['executions']}")
    if "status" in spec:
        last = journal.get("last") or {}
        if executions < 1:
            problems.append(f"nessuna esecuzione (attesa {spec['status']})")
        elif last.get("status") != spec["status"]:
            problems.append(f"stato {last.get('status')} invece di {spec['status']}")
    actions = [action_label(a) for a in journal.get("actions", [])]
    if "actions" in spec and actions != spec["actions"]:
        problems.append(f"azioni {actions} invece di {spec['actions']}")
    for wanted in spec.get("actions_contain", []):
        if wanted not in actions:
            problems.append(f"azione {wanted} assente (azioni: {actions})")
    audit = journal.get("audit", [])
    kinds = [a.get("kind") for a in audit]
    for kind in spec.get("audit_has", []):
        if kind not in kinds:
            problems.append(f"audit {kind} assente (audit: {kinds})")
    for kind in spec.get("audit_lacks", []):
        if kind in kinds:
            problems.append(f"audit {kind} presente")
    for kind, detail in spec.get("audit_detail", {}).items():
        details = [a.get("detail") for a in audit if a.get("kind") == kind]
        if detail not in details:
            problems.append(f"audit {kind} con detail {detail!r} assente (detail: {details})")
    if "code" in spec:
        codes = [a.get("code") for a in journal.get("actions", [])] + [a.get("detail") for a in audit]
        if spec["code"] not in codes:
            problems.append(f"codice {spec['code']!r} assente (codici: {[c for c in codes if c]})")
    return problems


def evaluate_check(check: dict, output: str) -> Optional[str]:
    text = output.strip()
    if "equals" in check:
        if text != str(check["equals"]).strip():
            return f"`{check['shell']}` -> {text!r}, atteso {str(check['equals'])!r}"
    elif "contains" in check:
        if str(check["contains"]) not in output:
            return f"`{check['shell']}` non contiene {check['contains']!r} (ottenuto {text[:200]!r})"
    elif "not_contains" in check:
        if str(check["not_contains"]) in output:
            return f"`{check['shell']}` contiene {check['not_contains']!r} (ottenuto {text[:200]!r})"
    elif "matches" in check:
        if not re.search(check["matches"], output):
            return f"`{check['shell']}` non corrisponde a /{check['matches']}/ (ottenuto {text[:200]!r})"
    return None


def evaluate(rec: dict, output: str) -> Tuple[str, List[str], Dict[str, object]]:
    """(esito 'pass'|'fail', problemi, dettagli per il rapporto)."""
    sections = parse_sections(output)
    load = decode_result(sections.get("LOAD"))
    loaded, problems = evaluate_load(rec, load, sections.get("LOAD"))
    details: Dict[str, object] = {"load": load}
    expected_ok = rec.get("expect", {}).get("load", "ok") == "ok"
    if "END" not in sections:
        problems.append("lo script sul telefono non e' arrivato in fondo (timeout o errore di shell)")
    journal = decode_result(sections.get("JOURNAL"))
    details["journal"] = journal
    if loaded and expected_ok and not problems:
        if rec.get("fire") and "payload" in rec["fire"]:
            for name in sorted(k for k in sections if k.startswith("FIRE")):
                fired = decode_result(sections[name])
                if fired is None or not fired.get("ok"):
                    problems.append(f"fire fallito: {hook_error(sections[name]) if fired is None else fired}")
                elif fired.get("timeout"):
                    problems.append("fire: il motore non ha finito entro il timeout")
        problems += evaluate_run(rec.get("expect", {}).get("run"), journal)
    # I controlli valgono anche quando il caricamento fallisce come previsto (nulla deve essere cambiato).
    outputs = {int(k[3:]): v for k, v in sections.items() if re.fullmatch(r"CHK\d+", k)}
    for index, check in enumerate(rec.get("checks", [])):
        if index not in outputs:
            problems.append(f"controllo {index} non eseguito")
            continue
        problem = evaluate_check(check, outputs[index])
        if problem:
            problems.append(problem)
    details["checks"] = {str(i): outputs[i] for i in sorted(outputs)}
    return ("fail" if problems else "pass"), problems, details
