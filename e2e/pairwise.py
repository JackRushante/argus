"""Copertura a coppie (pairwise) della suite E2E di Argus.

Il modello dichiara i parametri e i loro valori; ogni regola della suite occupa un punto del modello
(il suo trigger, le classi delle sue azioni, l'esito della condizione, le funzioni di flusso P4 che usa).
Una coppia (parametro A = valore, parametro B = valore) e' "coperta" se almeno una regola della suite
contiene entrambi i valori. Questo modulo:

* calcola le coppie richieste e quelle coperte da un insieme di regole (`missing_pairs`);
* genera un piano greedy di righe che copre tutte le coppie (`plan`), usato per scrivere le regole;
* calcola la copertura azione x configurazione di un rapporto di esecuzione (`config_matrix`).

Solo libreria standard: si prova con `python -m unittest discover -s e2e/tests`.
"""
from __future__ import annotations

import itertools
from typing import Dict, FrozenSet, Iterable, List, Set, Tuple

# Configurazioni dell'emulatore: Shizuku root, Shizuku shell, Shizuku assente.
CONFIGS = ["shizuku_root", "shizuku_shell", "none"]

TRIGGERS = [
    "time", "immediate", "manual", "geofence", "notification", "phone_state", "connectivity",
    "sensor", "screen", "battery", "audio_output", "mode_changed", "media", "app_event",
]

# Classi di azioni: raggruppano i tipi per natura dell'effetto e per tier. `wait`, `if` e `while` non
# sono una classe: sono le funzioni di flusso (FLOW) e si dichiarano a parte.
ACTION_CLASSES: Dict[str, List[str]] = {
    "local_ui": [
        "show_notification", "speak", "vibrate", "set_flashlight", "copy_text", "copy_to_clipboard",
        "media_control",
    ],
    "audio_mode": ["set_volume", "set_ringer", "set_dnd"],
    "display": ["set_brightness", "set_rotation", "set_dark_mode"],
    "launch": ["launch_app", "open_url", "open_settings_screen", "set_alarm", "set_timer"],
    "network_out": ["http_request"],
    "radios": [
        "set_wifi", "set_bluetooth", "set_mobile_data", "set_airplane", "set_location", "set_nfc",
        "set_battery_saver",
    ],
    "device_control": ["lock_screen", "screenshot", "app_control", "app_permission"],
    "shell": ["run_shell", "write_setting"],
    "screen_macro": ["ui_steps"],
}
CONTROL_FLOW = ["wait", "if", "while"]
ACTION_CLASS_OF: Dict[str, str] = {a: c for c, items in ACTION_CLASSES.items() for a in items}
ALL_ACTION_TYPES = sorted(ACTION_CLASS_OF) + CONTROL_FLOW

CONDITIONS = ["none", "true", "false"]

# Funzioni P4 derivate dalla regola: variabili (letterali/stato/payload/random), `clock`, cattura di
# output shell, `if`, `while`, `wait`.
FLOW = ["vars", "clock", "capture", "if", "while", "wait"]

Pair = FrozenSet[Tuple[str, str]]

# Coppie richieste: (parametro A, parametro B).
REQUIRED_AXES = [
    ("trigger", "klass"),
    ("trigger", "cond"),
    ("klass", "cond"),
    ("flow", "cond"),
    ("flow", "klass"),
]

DOMAIN = {
    "trigger": TRIGGERS,
    "klass": list(ACTION_CLASSES),
    "cond": CONDITIONS,
    "flow": FLOW,
}


def _walk_actions(actions: Iterable[dict]) -> Iterable[dict]:
    for action in actions:
        yield action
        if action.get("type") == "if":
            yield from _walk_actions(action.get("then", []))
            yield from _walk_actions(action.get("orElse", []))
        elif action.get("type") == "while":
            yield from _walk_actions(action.get("body", []))


def features(record: dict) -> Dict[str, Set[str]]:
    """Punto del modello occupato da una regola della suite (record = voce del file JSON)."""
    rule = record["rule"]
    actions = list(_walk_actions(rule.get("actions", [])))
    types = {a["type"] for a in actions}
    flow: Set[str] = set()
    vars_ = rule.get("vars", [])
    if vars_:
        flow.add("vars")
    if any(v.get("type") == "clock" for v in vars_):
        flow.add("clock")
    if any(a.get("type") == "run_shell" and a.get("captureAs") for a in actions):
        flow.add("capture")
    for name in CONTROL_FLOW:
        if name in types:
            flow.add(name)
    return {
        "trigger": {rule["trigger"]["type"]},
        "klass": {ACTION_CLASS_OF[t] for t in types if t in ACTION_CLASS_OF},
        "cond": {record.get("cond", "none")},
        "flow": flow,
        "action": {t for t in types},
    }


# Coppie impossibili sull'emulatore. L'immagine Android non ha il sensore di movimento significativo:
# una regola `sensor` non si arma mai (capability_unavailable), quindi la sua condizione non viene mai
# valutata. Le coppie (sensor x classe di azione) restano richieste e si coprono con il rifiuto all'arm.
INFEASIBLE: Set[Pair] = {
    frozenset({("trigger", "sensor"), ("cond", "true")}),
    frozenset({("trigger", "sensor"), ("cond", "false")}),
}


def required_pairs() -> Set[Pair]:
    pairs: Set[Pair] = set()
    for a, b in REQUIRED_AXES:
        for va, vb in itertools.product(DOMAIN[a], DOMAIN[b]):
            pairs.add(frozenset({(a, va), (b, vb)}))
    return pairs - INFEASIBLE


def covered_pairs(records: Iterable[dict]) -> Set[Pair]:
    covered: Set[Pair] = set()
    for record in records:
        point = features(record)
        for a, b in REQUIRED_AXES:
            for va, vb in itertools.product(point[a], point[b]):
                covered.add(frozenset({(a, va), (b, vb)}))
    return covered


def missing_pairs(records: Iterable[dict]) -> List[Pair]:
    return sorted(required_pairs() - covered_pairs(records), key=lambda p: sorted(p))


def describe(pair: Pair) -> str:
    return " x ".join(f"{k}={v}" for k, v in sorted(pair))


# ---------------------------------------------------------------------------------------------------
# Piano greedy


def _row_pairs(row: Dict[str, Set[str]]) -> Set[Pair]:
    covered: Set[Pair] = set()
    for a, b in REQUIRED_AXES:
        for va, vb in itertools.product(row[a], row[b]):
            covered.add(frozenset({(a, va), (b, vb)}))
    return covered


def plan(already: Iterable[dict] = (), max_classes: int = 3, max_flow: int = 2) -> List[Dict[str, Set[str]]]:
    """Righe (trigger, classi, condizione, flusso) che coprono le coppie ancora mancanti.

    Greedy: ogni riga nasce da una coppia ancora scoperta e si estende, un valore alla volta, con quello
    che copre piu' coppie nuove. Trigger e condizione hanno un solo valore per riga; le classi di azione
    al massimo `max_classes`, le funzioni di flusso al massimo `max_flow`.
    """
    remaining = required_pairs() - covered_pairs(already)
    rows: List[Dict[str, Set[str]]] = []
    while remaining:
        seed = sorted(remaining, key=lambda p: sorted(p))[0]
        row: Dict[str, Set[str]] = {"trigger": set(), "klass": set(), "cond": set(), "flow": set()}
        for param, value in seed:
            row[param].add(value)

        def new_pairs(candidate: Dict[str, Set[str]]) -> int:
            return len(_row_pairs(candidate) & remaining)

        while True:
            best = None
            best_gain = 0
            base = new_pairs(row)
            for param, values in DOMAIN.items():
                if param in ("trigger", "cond") and row[param]:
                    continue
                if param == "klass" and len(row[param]) >= max_classes:
                    continue
                if param == "flow" and len(row[param]) >= max_flow:
                    continue
                for value in values:
                    if value in row[param]:
                        continue
                    trial = {k: set(v) for k, v in row.items()}
                    trial[param].add(value)
                    gain = new_pairs(trial) - base
                    if gain > best_gain:
                        best, best_gain = (param, value), gain
            if best is None:
                break
            row[best[0]].add(best[1])
        if not row["trigger"]:
            row["trigger"].add(TRIGGERS[0])
        if not row["cond"]:
            row["cond"].add("none")
        newly = _row_pairs(row) & remaining
        if not newly:  # sicurezza: il seme e' sempre coperto, quindi non succede
            raise RuntimeError("il piano non progredisce")
        remaining -= newly
        rows.append(row)
    return rows


# ---------------------------------------------------------------------------------------------------
# Copertura azione x configurazione di un'esecuzione


def config_matrix(results: Iterable[dict]) -> Dict[str, Dict[str, int]]:
    """Per ogni tipo di azione, in quante regole ha girato in ciascuna configurazione.

    `results` = elenco di {"actions": [...], "config": "...", "status": "pass|fail|skip"}: contano le
    esecuzioni non saltate (un'esecuzione fallita e' comunque un'esecuzione: serve ai rapporti).
    """
    matrix: Dict[str, Dict[str, int]] = {}
    for result in results:
        if result.get("status") == "skip":
            continue
        for action in set(result.get("actions", [])):
            matrix.setdefault(action, {c: 0 for c in CONFIGS})[result["config"]] += 1
    return matrix


def action_config_gaps(results: Iterable[dict], actions: Iterable[str]) -> List[Tuple[str, str]]:
    matrix = config_matrix(results)
    return [
        (action, config)
        for action in sorted(set(actions))
        for config in CONFIGS
        if matrix.get(action, {}).get(config, 0) == 0
    ]


if __name__ == "__main__":  # piano per le coppie ancora scoperte: `python e2e/pairwise.py`
    import json
    import sys
    from pathlib import Path

    suite_dir = Path(__file__).parent / "suite"
    records = []
    for path in sorted(suite_dir.glob("*.json")):
        records.extend(json.loads(path.read_text(encoding="utf-8")))
    gaps = missing_pairs(records)
    print(f"regole: {len(records)}  coppie richieste: {len(required_pairs())}  scoperte: {len(gaps)}")
    for pair in gaps:
        print("  ", describe(pair))
    if gaps:
        print("\npiano suggerito:")
        for row in plan(records):
            print("  ", {k: sorted(v) for k, v in row.items()})
    sys.exit(1 if gaps else 0)
