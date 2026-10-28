"""Caricamento e controllo statico della suite E2E (`e2e/suite/*.json`).

Ogni voce e' un dizionario con questi campi (vedi `e2e/README.md` per il dettaglio):

    id        identificativo unico (stringa corta, es. "T-screen-on")
    title     descrizione breve
    cond      "none" | "true" | "false": esito atteso delle condizioni della regola (serve alla copertura)
    configs   configurazioni in cui la regola gira (default: tutte e tre)
    rule      la bozza `AutomationDraft` in JSON (il nome riceve il prefisso `e2e/` dall'aggancio)
    needs     requisiti dell'ambiente ("internet", "package:<nome>"); se mancano la regola e' saltata
    pre       comandi shell sul telefono prima di caricare la regola
    fire      null | {"payload": {...}} | {"real": ["comando shell", ...]}
    wait_s    attesa dopo l'innesco prima dei controlli
    checks    [{"shell": "...", "equals"|"contains"|"not_contains"|"matches": "...", "wait_s": n}]
    expect    {"load": "ok" | {"fail": {"stage": "...", "code": "..."}}, "run": "STATO" | {...}}
    cleanup   comandi shell di ripristino
    per_config  {"none": {<campi che sostituiscono quelli della regola>, "skip": "motivo"}}
"""
from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Dict, Iterable, List

from pairwise import ALL_ACTION_TYPES, CONFIGS, CONDITIONS, TRIGGERS, _walk_actions

SUITE_DIR = Path(__file__).resolve().parent / "suite"

OVERRIDABLE = {"pre", "after_load", "fire", "wait_s", "checks", "expect", "cleanup", "needs", "rule", "skip", "pause"}
CHECK_OPERATORS = ("equals", "contains", "not_contains", "matches")
RUN_KEYS = {"executions", "status", "actions", "actions_contain", "audit_has", "audit_lacks", "audit_detail", "code"}
LOAD_STATUSES = {"ok"}
LOAD_STAGES = {"decode", "submit", "review", "arm"}


class SuiteError(ValueError):
    pass


def load_suite(directory: Path = SUITE_DIR) -> List[dict]:
    records: List[dict] = []
    for path in sorted(directory.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            raise SuiteError(f"{path.name}: attesa una lista di regole")
        for record in data:
            record["_file"] = path.name
            records.append(record)
    return records


def effective(record: dict, config: str) -> dict:
    """La voce con le sostituzioni di `per_config[config]` applicate."""
    merged = copy.deepcopy({k: v for k, v in record.items() if k != "per_config"})
    for key, value in record.get("per_config", {}).get(config, {}).items():
        merged[key] = copy.deepcopy(value)
    return merged


def _check_check(rid: str, check: dict, problems: List[str]) -> None:
    if "shell" not in check:
        problems.append(f"{rid}: controllo senza 'shell'")
    operators = [op for op in CHECK_OPERATORS if op in check]
    if len(operators) != 1:
        problems.append(f"{rid}: il controllo deve avere esattamente un operatore tra {CHECK_OPERATORS}")


def _check_expect(rid: str, expect: dict, problems: List[str]) -> None:
    load = expect.get("load", "ok")
    if load != "ok":
        fail = load.get("fail") if isinstance(load, dict) else None
        if not isinstance(fail, dict):
            problems.append(f"{rid}: expect.load deve essere 'ok' o {{'fail': {{...}}}}")
        else:
            if fail.get("stage") not in LOAD_STAGES:
                problems.append(f"{rid}: expect.load.fail.stage non valido: {fail.get('stage')!r}")
            if not fail.get("code"):
                problems.append(f"{rid}: expect.load.fail.code mancante")
    run = expect.get("run")
    if isinstance(run, dict):
        unknown = set(run) - RUN_KEYS
        if unknown:
            problems.append(f"{rid}: chiavi sconosciute in expect.run: {sorted(unknown)}")


def validate(records: Iterable[dict]) -> List[str]:
    """Elenco di problemi della suite (vuoto = a posto). Non serve il telefono."""
    problems: List[str] = []
    seen: Dict[str, str] = {}
    for record in records:
        rid = record.get("id", "<senza id>")
        if rid in seen:
            problems.append(f"{rid}: id duplicato ({seen[rid]} e {record.get('_file')})")
        seen[rid] = record.get("_file", "?")
        for field in ("id", "title", "rule"):
            if field not in record:
                problems.append(f"{rid}: manca '{field}'")
        if record.get("cond", "none") not in CONDITIONS:
            problems.append(f"{rid}: cond non valida: {record.get('cond')!r}")
        configs = record.get("configs", CONFIGS)
        if not configs or any(c not in CONFIGS for c in configs):
            problems.append(f"{rid}: configs non valide: {configs!r}")
        rule = record.get("rule", {})
        if "name" not in rule or "trigger" not in rule or "actions" not in rule:
            problems.append(f"{rid}: la regola deve avere name, trigger e actions")
            continue
        if rule["trigger"].get("type") not in TRIGGERS:
            problems.append(f"{rid}: trigger sconosciuto {rule['trigger'].get('type')!r}")
        for action in _walk_actions(rule.get("actions", [])):
            if action.get("type") not in ALL_ACTION_TYPES:
                problems.append(f"{rid}: tipo di azione non coperto dal modello: {action.get('type')!r}")
        unknown = set(record.get("per_config", {})) - set(CONFIGS)
        if unknown:
            problems.append(f"{rid}: per_config con configurazioni sconosciute {sorted(unknown)}")
        for config in CONFIGS:
            merged = effective(record, config)
            bad = set(record.get("per_config", {}).get(config, {})) - OVERRIDABLE
            if bad:
                problems.append(f"{rid}: per_config[{config}] sostituisce campi non ammessi {sorted(bad)}")
            for check in merged.get("checks", []):
                _check_check(rid, check, problems)
            _check_expect(rid, merged.get("expect", {}), problems)
            fire = merged.get("fire")
            if fire is not None and not ({"payload", "real"} & set(fire)):
                problems.append(f"{rid}: fire deve avere 'payload' o 'real'")
            if fire is not None and "payload" in fire and "real" in fire:
                problems.append(f"{rid}: fire non puo' avere insieme 'payload' e 'real'")
    return problems
