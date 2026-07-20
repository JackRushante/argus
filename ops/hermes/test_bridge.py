import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest import mock

import bridge


class BridgeTest(unittest.TestCase):
    def request(self):
        return {
            "schema_version": 1,
            "request_id": "req-test-1",
            "message": "dopo le 23 metti dnd",
            "manifest": {
                "device_model": "OnePlus",
                "android_api": 36,
                "shizuku_available": True,
                "granted_permissions": ["android.permission.INTERNET"],
                "available_tools": ["set_dnd"],
                "unavailable_tools": {},
                "whitelisted_contacts": [],
                "state_keys": {"ringer": "normal|vibrate|silent"},
            },
            "state": {
                "values": {"ringer": "normal"},
                "foreground_app": "dev.argus",
                "location_available": False,
            },
        }

    def test_request_is_strict_and_versioned(self):
        legacy = self.request()
        self.assertIs(legacy, bridge.validate_request(legacy, "req-test-1"))
        current = self.request_v2()
        self.assertIs(current, bridge.validate_request(current, "req-test-1"))
        current["schema_version"] = 3
        with self.assertRaises(bridge.RequestError):
            bridge.validate_request(current, "req-test-1")

    def test_v2_manifest_requires_exact_reader_policy_families_and_limits(self):
        missing = self.request_v2()
        del missing["manifest"]["state_readers"]
        with self.assertRaises(bridge.RequestError):
            bridge.validate_request(missing, "req-test-1")

        missing_triggers = self.request_v2()
        del missing_triggers["manifest"]["available_triggers"]
        with self.assertRaises(bridge.RequestError):
            bridge.validate_request(missing_triggers, "req-test-1")

        legacy_with_v2_field = self.request()
        legacy_with_v2_field["manifest"]["state_readers"] = self.state_readers()
        with self.assertRaises(bridge.RequestError):
            bridge.validate_request(legacy_with_v2_field, "req-test-1")

        for mutate in (
            lambda readers: readers.update(policy_version=2),
            lambda readers: readers.update(families=["dumpsys_field", "builtin"]),
            lambda readers: readers["limits"].update(max_output_bytes=999_999),
        ):
            invalid = self.request_v2()
            mutate(invalid["manifest"]["state_readers"])
            with self.assertRaises(bridge.RequestError):
                bridge.validate_request(invalid, "req-test-1")

    def test_untrusted_reader_shapes_fail_closed_without_type_errors(self):
        malformed_manifest = self.request_v2()
        malformed_manifest["manifest"]["state_readers"]["families"] = [["builtin"]]
        with self.assertRaises(bridge.RequestError):
            bridge.validate_request(malformed_manifest, "req-test-1")

        base = {
            "type": "state_compare",
            "query": {"type": "builtin", "key": "battery"},
            "valueType": "NUMBER",
            "op": "LT",
            "expected": "20",
            "policyVersion": bridge.STATE_QUERY_POLICY_VERSION,
        }
        malformed_conditions = []
        for path, replacement in (
            (("query", "key"), ["battery"]),
            (("valueType",), ["NUMBER"]),
            (("op",), ["LT"]),
        ):
            condition = json.loads(json.dumps(base))
            target = condition
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = replacement
            malformed_conditions.append(condition)

        setting = json.loads(json.dumps(base))
        setting["query"] = {
            "type": "setting",
            "namespace": ["SYSTEM"],
            "key": "screen_brightness",
        }
        malformed_conditions.append(setting)

        for condition in malformed_conditions:
            self.assertFalse(
                bridge.validate_condition(
                    condition,
                    0,
                    set(bridge.BUILTIN_STATE_VALUES),
                    set(bridge.STATE_QUERY_FAMILIES),
                )
            )

        self.assertFalse(bridge.validate_condition(
            {
                "type": "state_compare",
                "query": {"type": "system_property", "name": "ro.product.model"},
                "valueType": "TEXT",
                "op": "EQ",
                "expected": "😀" * 513,
                "policyVersion": bridge.STATE_QUERY_POLICY_VERSION,
            },
            0,
            set(bridge.BUILTIN_STATE_VALUES),
            {"system_property"},
        ))
        self.assertFalse(bridge.validate_state_query(
            {"type": "sysfs", "path": "/sys/" + "😀" * 126},
            set(bridge.BUILTIN_STATE_VALUES),
            {"sysfs"},
        ))
        self.assertFalse(bridge.validate_state_query(
            {"type": "sysfs", "path": "/sys/   /value"},
            set(bridge.BUILTIN_STATE_VALUES),
            {"sysfs"},
        ))

    def test_all_untrusted_enum_memberships_fail_closed_without_type_errors(self):
        act = {
            "schema_version": 1,
            "request_id": "act-test-1",
            "goal": "rispondi",
            "context_sources": [["notification"]],
            "allowed_tools": ["whatsapp_reply"],
            "context": {
                "notification": {
                    "package": "com.whatsapp",
                    "sender": None,
                    "title": None,
                    "text": "ciao",
                    "is_group": False,
                },
                "state": None,
            },
        }
        with self.assertRaises(bridge.RequestError):
            bridge.validate_act_request(act, "act-test-1")

        bad_package = json.loads(json.dumps(act))
        bad_package["context_sources"] = ["notification"]
        bad_package["context"]["notification"]["package"] = ["com.whatsapp"]
        with self.assertRaises(bridge.RequestError):
            bridge.validate_act_request(bad_package, "act-test-1")

        self.assertFalse(bridge.validate_trigger(
            {"type": "time", "cron": "0 8 * * *", "tz": "Europe/Rome", "precision": ["EXACT"]},
            set(),
        ))
        self.assertFalse(bridge.validate_trigger(
            {"type": "geofence", "radiusM": 100, "transition": ["ENTER"], "lat": 1, "lng": 1},
            set(),
        ))
        self.assertFalse(bridge.validate_condition(
            {"type": "state_equals", "key": ["battery"], "op": "GT", "value": "20"},
            0,
            {"battery"},
        ))
        self.assertFalse(bridge.validate_action(
            {"type": "set_dnd", "mode": ["TOTAL"]},
            {"set_dnd"},
        ))

    def test_strict_json_rejects_duplicate_keys_and_non_finite_numbers(self):
        for raw in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}'):
            with self.assertRaises(bridge.StrictJsonError):
                bridge.STRICT_JSON_DECODER.decode(raw)

        output = '@@META@@ {"draft":null,"draft":{},"error_code":null}'
        self.assertEqual(
            ("", None, "meta_invalid"),
            bridge.parse_model_output(output, set(), set(), set()),
        )
        with self.assertRaises(bridge.ModelProcessError):
            bridge.parse_act_model_output(
                '@@META@@ {"reply_text":"ciao","reply_text":"altro","error_code":null}'
            )

    def test_shared_state_query_v2_fixtures_match_python_validator(self):
        fixture_path = os.path.join(os.path.dirname(__file__), "state_query_contract_v2.json")
        with open(fixture_path, encoding="utf-8") as stream:
            fixture = json.load(stream)
        self.assertEqual(bridge.COMPILE_SCHEMA_VERSION, fixture["schema_version"])
        self.assertEqual(bridge.STATE_QUERY_POLICY_VERSION, fixture["policy_version"])
        self.assertEqual(bridge.STATE_READER_LIMITS, fixture["limits"])
        self.assertEqual(list(bridge.STATE_QUERY_FAMILIES), fixture["all_families"])
        for case in fixture["canonical_ids"]:
            self.assertEqual(case["id"], bridge.state_query_canonical_id(case["query"]))
        allowed_keys = set(bridge.BUILTIN_STATE_VALUES)
        for case in fixture["cases"]:
            families = set(case.get("available_families", fixture["all_families"]))
            actual = bridge.validate_condition(
                case["condition"], 0, allowed_keys, families
            )
            self.assertEqual(case["accepted"], actual, case["name"])

    def test_source_hash_normalizes_windows_and_linux_line_endings(self):
        with tempfile.TemporaryDirectory() as directory:
            lf = Path(directory) / "lf.py"
            crlf = Path(directory) / "crlf.py"
            lf.write_bytes(b"first\nsecond\n")
            crlf.write_bytes(b"first\r\nsecond\r\n")
            self.assertEqual(
                bridge.normalized_source_sha256(lf),
                bridge.normalized_source_sha256(crlf),
            )

    @staticmethod
    def state_readers():
        return {
            "policy_version": bridge.STATE_QUERY_POLICY_VERSION,
            "families": list(bridge.STATE_QUERY_FAMILIES),
            "limits": dict(bridge.STATE_READER_LIMITS),
        }

    def request_v2(self):
        request = self.request()
        request["schema_version"] = bridge.COMPILE_SCHEMA_VERSION
        request["manifest"]["available_triggers"] = ["time"]
        request["manifest"]["state_readers"] = self.state_readers()
        return request

    def test_copy_to_clipboard_action_requires_a_compilable_regex(self):
        tools = {"copy_to_clipboard"}
        ok = {
            "type": "copy_to_clipboard",
            "extractionRegex": r"(?:^|[^+0-9])([0-9]{4,8})(?:[^0-9]|$)",
        }
        self.assertTrue(bridge.validate_action(ok, tools))
        self.assertTrue(bridge.validate_action({
            "type": "copy_to_clipboard",
            "extractionRegex": r"(?<!\+)\b(\d{4,8})\b",
        }, tools))
        self.assertFalse(bridge.validate_action({
            "type": "copy_to_clipboard",
            "extractionRegex": r"(?<!foo)\d+",
        }, tools))
        self.assertFalse(bridge.validate_action({
            "type": "copy_to_clipboard",
            "extractionRegex": r"(\d+)\1",
        }, tools))
        self.assertTrue(bridge.validate_action({"type": "copy_to_clipboard"}, tools))
        self.assertFalse(
            bridge.validate_action({"type": "copy_to_clipboard", "extractionRegex": "(broken"}, tools)
        )
        self.assertFalse(
            bridge.validate_action({"type": "copy_to_clipboard", "extractionRegex": "x" * 600}, tools)
        )

    def test_invoke_llm_v2_requires_explicit_typed_and_classified_state(self):
        query = {"type": "dumpsys_field", "service": "battery", "field": "voltage"}
        action = {
            "type": "invoke_llm_v2",
            "goal": "rispondi considerando il voltaggio",
            "stateContext": [{
                "query": query,
                "valueType": "NUMBER",
                "policyVersion": bridge.STATE_QUERY_POLICY_VERSION,
                "integrity": "CLEAN",
                "confidentiality": "SECRET",
            }],
            "allowedTools": ["whatsapp_reply"],
            "replyTargetSender": True,
            "timeoutMs": 60_000,
        }
        arguments = (
            {"invoke_llm_v2"}, set(bridge.BUILTIN_STATE_VALUES),
            {"dumpsys_field"},
        )
        self.assertTrue(bridge.validate_action(action, *arguments))

        underclassified = json.loads(json.dumps(action))
        underclassified["stateContext"][0]["confidentiality"] = "PRIVATE"
        self.assertFalse(bridge.validate_action(underclassified, *arguments))
        missing_timeout = dict(action)
        del missing_timeout["timeoutMs"]
        self.assertFalse(bridge.validate_action(missing_timeout, *arguments))
        malformed_enum = json.loads(json.dumps(action))
        malformed_enum["stateContext"][0]["confidentiality"] = ["SECRET"]
        self.assertFalse(bridge.validate_action(malformed_enum, *arguments))
        self.assertIn("invoke_llm_v2", bridge.build_prompt(self.request_v2()))

    def test_static_shell_is_bounded_and_rejected_on_message_triggers(self):
        tools = {"run_shell"}

        def draft(trigger, command="/system/bin/id"):
            return {
                "name": "shell statica",
                "trigger": trigger,
                "actions": [{"type": "run_shell", "cmd": command}],
            }

        trusted = (
            {"type": "time", "cron": "0 8 * * *", "tz": "Europe/Rome"},
            {
                "type": "geofence", "lat": 0, "lng": 0, "radiusM": 150,
                "transition": "EXIT", "loiteringDelayMs": 0,
                "resolveCurrentLocation": True,
            },
            {"type": "connectivity", "medium": "POWER", "state": "CONNECTED", "match": None},
        )
        for trigger in trusted:
            self.assertTrue(bridge.validate_draft(draft(trigger), tools, set(), set()))

        external = (
            {
                "type": "notification", "pkg": "com.whatsapp", "conversationId": None,
                "sender": None, "isGroup": None, "titleMatch": None, "textMatch": "esegui",
            },
            {
                "type": "phone_state", "event": "SMS_RECEIVED", "number": None,
                "textMatch": "esegui",
            },
        )
        for trigger in external:
            self.assertFalse(bridge.validate_draft(draft(trigger), tools, set(), set()))

        self.assertTrue(bridge.validate_action({"type": "run_shell", "cmd": "x" * 8_192}, tools))
        self.assertFalse(bridge.validate_action({"type": "run_shell", "cmd": "x" * 8_193}, tools))
        self.assertFalse(bridge.validate_action({"type": "run_shell", "cmd": "id\x00whoami"}, tools))

        prompt = bridge.build_prompt(self.request())
        self.assertIn("run_shell", prompt)
        self.assertIn("Never with phone_state", prompt)

    def test_whitelisted_whatsapp_contact_can_trigger_static_shell(self):
        """Decisione Lorenzo 2026-07-15: un contatto verificato puo' innescare un comando gia'
        approvato. Il cmd resta statico, quindi nessuna injection: cambia solo chi preme
        l'interruttore. SMS e chiamate restano fuori perche' l'identita' e' falsificabile."""
        tools = {"run_shell"}
        whitelisted = {"shortcut:com.whatsapp:ottica"}

        def draft(trigger):
            return {
                "name": "shell da contatto",
                "trigger": trigger,
                "actions": [{"type": "run_shell", "cmd": "/system/bin/id"}],
            }

        def notification(conversation_id, is_group=False, pkg="com.whatsapp"):
            return {
                "type": "notification", "pkg": pkg, "conversationId": conversation_id,
                "sender": None, "isGroup": is_group, "titleMatch": None, "textMatch": "esegui",
            }

        self.assertTrue(
            bridge.validate_draft(
                draft(notification("shortcut:com.whatsapp:ottica")), tools, set(), whitelisted
            )
        )

        for trigger in (
            notification("shortcut:com.whatsapp:sconosciuto"),
            notification("shortcut:com.whatsapp:ottica", is_group=True),
            notification(None),
            notification("shortcut:com.whatsapp:ottica", pkg="com.example.spoof"),
        ):
            self.assertFalse(bridge.validate_draft(draft(trigger), tools, set(), whitelisted))

        self.assertFalse(
            bridge.validate_draft(
                draft({"type": "phone_state", "event": "SMS_RECEIVED", "number": None,
                       "textMatch": "esegui"}),
                tools, set(), whitelisted,
            )
        )

    def test_geofence_rejects_unimplemented_dwell_and_loitering(self):
        base = {
            "type": "geofence",
            "lat": 45.0,
            "lng": 9.0,
            "radiusM": 150,
            "transition": "EXIT",
            "loiteringDelayMs": 0,
            "resolveCurrentLocation": False,
        }
        self.assertTrue(bridge.validate_trigger(base, set()))
        self.assertFalse(bridge.validate_trigger({**base, "transition": "DWELL"}, set()))
        self.assertFalse(bridge.validate_trigger({**base, "loiteringDelayMs": 60_000}, set()))
        self.assertIn("only ENTER/EXIT", bridge.build_prompt(self.request()))

    def test_time_trigger_accepts_relative_after_ms_one_shot(self):
        tz = "Europe/Rome"
        seven_days = 7 * 24 * 60 * 60 * 1_000
        # afterMs valido: esattamente uno tra cron/at/afterMs, bound 1s..7g.
        self.assertTrue(bridge.validate_trigger({"type": "time", "afterMs": 120_000, "tz": tz}, set()))
        self.assertTrue(bridge.validate_trigger({"type": "time", "afterMs": 1_000, "tz": tz}, set()))
        self.assertTrue(bridge.validate_trigger({"type": "time", "afterMs": seven_days, "tz": tz}, set()))
        # bound violati.
        self.assertFalse(bridge.validate_trigger({"type": "time", "afterMs": 999, "tz": tz}, set()))
        self.assertFalse(bridge.validate_trigger({"type": "time", "afterMs": 0, "tz": tz}, set()))
        self.assertFalse(bridge.validate_trigger({"type": "time", "afterMs": seven_days + 1, "tz": tz}, set()))
        # non-int rifiutato senza TypeError (short-circuit su _is_int).
        self.assertFalse(bridge.validate_trigger({"type": "time", "afterMs": "120000", "tz": tz}, set()))
        # esattamente uno: combinazioni multiple rifiutate.
        self.assertFalse(bridge.validate_trigger(
            {"type": "time", "afterMs": 120_000, "at": "2026-07-17T14:30", "tz": tz}, set()))
        self.assertFalse(bridge.validate_trigger(
            {"type": "time", "afterMs": 120_000, "cron": "0 8 * * *", "tz": tz}, set()))
        # nessuno dei tre rifiutato.
        self.assertFalse(bridge.validate_trigger({"type": "time", "tz": tz}, set()))
        # il prompt insegna afterMs per "tra N".
        self.assertIn("afterMs", bridge.build_prompt(self.request()))

    def test_sensor_trigger_is_closed_bounded_and_requires_cooldown(self):
        significant = {
            "type": "sensor",
            "kind": "significant_motion",
            "minimumEventCount": 1,
            "samplingPeriodUs": None,
            "maxReportLatencyUs": None,
        }
        self.assertTrue(bridge.validate_trigger(significant, set()))
        self.assertFalse(bridge.validate_trigger({**significant, "minimumEventCount": 2}, set()))
        self.assertFalse(bridge.validate_trigger({**significant, "kind": "accelerometer_raw"}, set()))
        self.assertFalse(bridge.validate_trigger({**significant, "samplingPeriodUs": 1_000}, set()))

        steps = {"type": "sensor", "kind": "step_counter", "minimumEventCount": 100_000}
        self.assertTrue(bridge.validate_trigger(steps, set()))
        self.assertFalse(bridge.validate_trigger({**steps, "minimumEventCount": 100_001}, set()))

        draft = {
            "name": "Movimento",
            "trigger": significant,
            "actions": [{"type": "run_shell", "cmd": "/system/bin/true"}],
            "cooldownMs": 60_000,
        }
        self.assertTrue(
            bridge.validate_draft(
                draft, {"run_shell"}, set(), set(), {"sensor.significant_motion"}
            )
        )
        self.assertFalse(
            bridge.validate_draft(
                {**draft, "cooldownMs": 59_999},
                {"run_shell"}, set(), set(), {"sensor.significant_motion"},
            )
        )
        self.assertFalse(
            bridge.validate_draft(
                draft, {"run_shell"}, set(), set(), {"sensor.stationary_detect"}
            )
        )

    # --- P4 (schema v2 program) -------------------------------------------------------------
    P4_TIME_TRIGGER = {"type": "time", "cron": "0 8 * * *", "tz": "Europe/Rome"}

    def test_p4_valid_program_is_accepted(self):
        draft = {
            "name": "torcia lampeggiante",
            "trigger": self.P4_TIME_TRIGGER,
            "vars": [
                {"type": "literal", "name": "soglia", "value": "20",
                 "varType": "NUMBER", "confidentiality": "PUBLIC"},
            ],
            "actions": [
                {
                    "type": "if",
                    "condition": {"type": "var_compare", "varName": "soglia",
                                  "op": "GT", "expected": "10"},
                    "then": [
                        {"type": "run_shell", "cmd": "/system/bin/id", "captureAs": "uid"},
                        {"type": "wait", "durationMs": 500},
                        {"type": "show_notification", "title": "Argus", "text": "fatto"},
                    ],
                    "orElse": [{"type": "set_flashlight", "on": True}],
                },
                {
                    "type": "while",
                    "condition": {"type": "boolean_literal", "value": True},
                    "body": [{"type": "set_flashlight", "on": False}],
                    "maxIterations": 5,
                    "delayBetweenMs": 100,
                },
            ],
        }
        tools = {"run_shell", "show_notification", "set_flashlight"}
        self.assertTrue(bridge.validate_draft(draft, tools, set(), set()))

    def _nested_shell_draft(self, trigger):
        return {
            "name": "shell annidata",
            "trigger": trigger,
            "actions": [{
                "type": "if",
                "condition": {"type": "boolean_literal", "value": True},
                "then": [{"type": "run_shell", "cmd": "/system/bin/id"}],
            }],
        }

    def test_p4_shell_gate_recurses_into_nested_bodies(self):
        tools = {"run_shell"}
        # Trigger fidato (time): la shell annidata è ammessa.
        self.assertTrue(
            bridge.validate_draft(self._nested_shell_draft(self.P4_TIME_TRIGGER), tools, set(), set())
        )
        # Trigger phone_state SMS: la shell annidata DEVE essere trovata dal gate e rifiutata,
        # anche se non è al top-level (bug handoff §6).
        sms = {"type": "phone_state", "event": "SMS_RECEIVED", "number": None, "textMatch": None}
        self.assertFalse(bridge.validate_draft(self._nested_shell_draft(sms), tools, set(), set()))

    def test_p4_rejects_over_bounds(self):
        # 17 variabili -> oltre il tetto di 16.
        seventeen = {
            "name": "vars", "trigger": self.P4_TIME_TRIGGER,
            "vars": [{"type": "literal", "name": f"v{i}", "value": "1",
                      "varType": "TEXT", "confidentiality": "PUBLIC"} for i in range(17)],
            "actions": [{"type": "set_wifi", "on": True}],
        }
        self.assertFalse(bridge.validate_draft(seventeen, {"set_wifi"}, set(), set()))
        sixteen = json.loads(json.dumps(seventeen))
        sixteen["vars"] = sixteen["vars"][:16]
        self.assertTrue(bridge.validate_draft(sixteen, {"set_wifi"}, set(), set()))

        # Profondità di annidamento: 4 ok, 5 rifiutato.
        def nested_if(depth):
            node = {"type": "set_wifi", "on": True}
            for _ in range(depth):
                node = {"type": "if",
                        "condition": {"type": "boolean_literal", "value": True},
                        "then": [node]}
            return {"name": "n", "trigger": self.P4_TIME_TRIGGER, "actions": [node]}
        self.assertTrue(bridge.validate_draft(nested_if(4), {"set_wifi"}, set(), set()))
        self.assertFalse(bridge.validate_draft(nested_if(5), {"set_wifi"}, set(), set()))

        # maxIterations: 1000 ok, 1001 fuori intervallo.
        def while_iters(n):
            return {"name": "w", "trigger": self.P4_TIME_TRIGGER, "actions": [{
                "type": "while", "condition": {"type": "boolean_literal", "value": True},
                "body": [{"type": "set_wifi", "on": True}], "maxIterations": n}]}
        self.assertTrue(bridge.validate_draft(while_iters(1_000), {"set_wifi"}, set(), set()))
        self.assertFalse(bridge.validate_draft(while_iters(1_001), {"set_wifi"}, set(), set()))

        # Conteggio nodi albero: while + 63 corpo = 64 ok; + 64 = 65 rifiutato.
        def while_body(n):
            return {"name": "w", "trigger": self.P4_TIME_TRIGGER, "actions": [{
                "type": "while", "condition": {"type": "boolean_literal", "value": True},
                "body": [{"type": "set_wifi", "on": True} for _ in range(n)],
                "maxIterations": 1}]}
        self.assertTrue(bridge.validate_draft(while_body(63), {"set_wifi"}, set(), set()))
        self.assertFalse(bridge.validate_draft(while_body(64), {"set_wifi"}, set(), set()))

    def test_p4_v1_flat_draft_still_valid(self):
        flat = {
            "name": "dnd sera",
            "trigger": {"type": "time", "cron": "0 23 * * *", "tz": "Europe/Rome"},
            "actions": [{"type": "set_dnd", "mode": "PRIORITY"}],
        }
        self.assertTrue(bridge.validate_draft(flat, {"set_dnd"}, {"ringer"}, set()))

    def test_p4_capture_as_only_on_producers(self):
        # captureAs su un'azione non-produttrice -> rifiutato (chiave extra).
        bad = {
            "name": "x", "trigger": self.P4_TIME_TRIGGER,
            "actions": [{"type": "set_wifi", "on": True, "captureAs": "bad"}],
        }
        self.assertFalse(bridge.validate_draft(bad, {"set_wifi"}, set(), set()))
        # captureAs su run_shell -> ammesso.
        good = {
            "name": "x", "trigger": self.P4_TIME_TRIGGER,
            "actions": [{"type": "run_shell", "cmd": "/system/bin/id", "captureAs": "uid"}],
        }
        self.assertTrue(bridge.validate_draft(good, {"run_shell"}, set(), set()))
        # Nome captureAs non valido -> rifiutato.
        bad_name = json.loads(json.dumps(good))
        bad_name["actions"][0]["captureAs"] = "Bad-Name"
        self.assertFalse(bridge.validate_draft(bad_name, {"run_shell"}, set(), set()))

    def test_p4_flow_only_conditions_rejected_at_trigger_time(self):
        # var_compare/boolean_literal non sono ammesse come condizioni trigger-time.
        self.assertFalse(bridge.validate_condition(
            {"type": "var_compare", "varName": "x", "op": "EQ", "expected": "y"},
            0, set(),
        ))
        self.assertFalse(bridge.validate_condition(
            {"type": "boolean_literal", "value": True}, 0, set(),
        ))
        # Dentro un flusso (allow_flow) e con la var dichiarata -> ammesse.
        self.assertTrue(bridge.validate_condition(
            {"type": "boolean_literal", "value": True}, 0, set(),
            allow_flow=True,
        ))
        self.assertTrue(bridge.validate_condition(
            {"type": "var_compare", "varName": "x", "op": "EQ", "expected": "y"},
            0, set(), allow_flow=True, known_vars={"x"},
        ))

    def test_manifest_available_triggers_is_optional_and_bounded(self):
        # Client pre-P2: campo assente, accettato (retrocompatibilita').
        legacy = self.request()
        self.assertIs(legacy, bridge.validate_request(legacy, "req-test-1"))

        # Client P2: lista di wire name, accettata e usata dal prompt (REGOLA 10).
        current = self.request()
        current["manifest"]["available_triggers"] = [
            "time", "notification", "phone_state.sms", "sensor.significant_motion",
        ]
        self.assertIs(current, bridge.validate_request(current, "req-test-1"))
        prompt = bridge.build_prompt(current)
        self.assertIn("available_triggers", prompt)
        self.assertIn("phone_state.sms", prompt)
        self.assertIn("sensor.significant_motion", prompt)

        # Malformata o fuori bounds: rifiutata.
        for bad in (
            ["x" * 65],
            "not-a-list",
            ["time"] * 2,
            ["phone_state.sms", "time"],
        ):
            broken = self.request()
            broken["manifest"]["available_triggers"] = bad
            with self.assertRaises(bridge.RequestError):
                bridge.validate_request(broken, "req-test-1")

        # Chiavi sconosciute restano vietate: solo available_triggers e' opzionale.
        unknown = self.request()
        unknown["manifest"]["surprise"] = True
        with self.assertRaises(bridge.RequestError):
            bridge.validate_request(unknown, "req-test-1")

    def test_act_request_is_strict_minimal_and_reply_only(self):
        request = self.act_request()
        self.assertIs(request, bridge.validate_act_request(request, "act-test-1"))

        request["context"]["notification"]["conversation_id"] = "must-not-cross"
        with self.assertRaises(bridge.RequestError):
            bridge.validate_act_request(request, "act-test-1")

        for mutation in (
            lambda value: value.update(allowed_tools=["shell.run"]),
            lambda value: value.update(context_sources=["notification", "screen"]),
            lambda value: value["context"]["notification"].update(is_group=True),
            lambda value: value["context"].update(state=None),
        ):
            request = self.act_request()
            mutation(request)
            with self.assertRaises(bridge.RequestError):
                bridge.validate_act_request(request, "act-test-1")

    def test_act_output_accepts_only_bounded_text_without_target(self):
        output = '@@META@@ {"reply_text":"Ciao, a dopo.","error_code":null}'
        self.assertEqual(("Ciao, a dopo.", None), bridge.parse_act_model_output(output))

        for invalid in (
            '@@META@@ {"reply_text":"Ciao","target":"altro","error_code":null}',
            '@@META@@ {"reply_text":"","error_code":null}',
            '@@META@@ {"reply_text":null,"error_code":null}',
            '@@META@@ {"reply_text":"Ciao","error_code":"refused"}',
        ):
            with self.assertRaises(bridge.ModelProcessError):
                bridge.parse_act_model_output(invalid)

    def test_model_output_parses_only_available_actions(self):
        output = (
            'Va bene.\n@@META@@ {"draft":{"name":"DND sera",'
            '"trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome","precision":"EXACT"},'
            '"actions":[{"type":"set_dnd","mode":"PRIORITY"}]},"error_code":null}'
        )
        reply, draft, error = bridge.parse_model_output(output, {"set_dnd"}, {"ringer"}, set())
        self.assertEqual("Va bene.", reply)
        self.assertEqual("set_dnd", draft["actions"][0]["type"])
        self.assertEqual("EXACT", draft["trigger"]["precision"])
        self.assertIsNone(error)
        _, draft, error = bridge.parse_model_output(output, {"set_wifi"}, {"ringer"}, set())
        self.assertIsNone(draft)
        self.assertEqual("draft_invalid", error)

        invalid_precision = output.replace('"precision":"EXACT"', '"precision":"SECOND"')
        _, draft, error = bridge.parse_model_output(
            invalid_precision, {"set_dnd"}, {"ringer"}, set()
        )
        self.assertIsNone(draft)
        self.assertEqual("draft_invalid", error)

    def test_v2_model_output_accepts_only_advertised_typed_reader(self):
        output = (
            'Pronto.\n@@META@@ {"draft":{"name":"Voltaggio basso",'
            '"trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},'
            '"actions":[{"type":"show_notification","title":"Argus","text":"Batteria"}],'
            '"conditions":{"type":"state_compare","query":{"type":"dumpsys_field",'
            '"service":"battery","field":"voltage"},"valueType":"NUMBER","op":"LT",'
            '"expected":"3800","policyVersion":1}},"error_code":null}'
        )
        reply, draft, error = bridge.parse_model_output(
            output,
            {"show_notification"},
            set(bridge.BUILTIN_STATE_VALUES),
            set(),
            {"time"},
            {"dumpsys_field"},
        )
        self.assertEqual("Pronto.", reply)
        self.assertEqual("state_compare", draft["conditions"]["type"])
        self.assertIsNone(error)

        _, draft, error = bridge.parse_model_output(
            output,
            {"show_notification"},
            set(bridge.BUILTIN_STATE_VALUES),
            set(),
            {"time"},
            set(),
        )
        self.assertIsNone(draft)
        self.assertEqual("draft_invalid", error)
        prompt = bridge.build_prompt(self.request_v2())
        self.assertIn("state_compare", prompt)
        self.assertIn("policyVersion", prompt)
        self.assertIn('service "battery"', prompt)

    def test_model_output_enforces_granular_available_triggers(self):
        output = (
            'Pronto.\n@@META@@ {"draft":{"name":"Bluetooth auto",'
            '"trigger":{"type":"connectivity","medium":"BT","state":"CONNECTED"},'
            '"actions":[{"type":"run_shell","cmd":"/system/bin/true"}]},'
            '"error_code":null}'
        )
        _, draft, error = bridge.parse_model_output(
            output,
            {"run_shell"},
            set(),
            set(),
            {"connectivity.power"},
        )
        self.assertIsNone(draft)
        self.assertEqual("draft_invalid", error)

        _, draft, error = bridge.parse_model_output(
            output,
            {"run_shell"},
            set(),
            set(),
            {"connectivity.bt"},
        )
        self.assertEqual("BT", draft["trigger"]["medium"])
        self.assertIsNone(error)

        # Lista v2 vuota significa nessun trigger armabile.
        _, draft, error = bridge.parse_model_output(
            output, {"run_shell"}, set(), set(), set()
        )
        self.assertIsNone(draft)
        self.assertEqual("draft_invalid", error)

        # Solo il manifest v1 che omette il campo mantiene il comportamento legacy.
        _, legacy, error = bridge.parse_model_output(
            output, {"run_shell"}, set(), set()
        )
        self.assertIsNotNone(legacy)
        self.assertIsNone(error)

        wifi_named = output.replace(
            '"medium":"BT"',
            '"medium":"WIFI","match":"Casa"',
        )
        _, draft, error = bridge.parse_model_output(
            wifi_named,
            {"run_shell"},
            set(),
            set(),
            {"connectivity.wifi"},
        )
        self.assertIsNone(draft)
        self.assertEqual("draft_invalid", error)
        _, draft, error = bridge.parse_model_output(
            wifi_named,
            {"run_shell"},
            set(),
            set(),
            {"connectivity.wifi", "connectivity.wifi.identity"},
        )
        self.assertEqual("Casa", draft["trigger"]["match"])
        self.assertIsNone(error)

    def test_prompts_mirror_the_users_language(self):
        # Pinning della regola di MIRROR: entrambi i prompt (compile e act) devono istruire
        # esplicitamente il modello a scrivere il testo utente nella LINGUA DELL'UTENTE.
        self.assertIn("USER'S language", bridge.build_prompt(self.request()))
        self.assertIn("USER'S language", bridge.build_prompt(self.request_v2()))
        self.assertIn("USER'S language", bridge.build_act_prompt(self.act_request()))

    def test_missing_or_malformed_meta_fails_soft(self):
        self.assertEqual(
            ("testo", None, "draft_missing"),
            bridge.parse_model_output("testo", {"set_dnd"}, set(), set()),
        )
        self.assertEqual(
            "meta_invalid",
            bridge.parse_model_output("testo@@META@@ nope", {"set_dnd"}, set(), set())[2],
        )

    def test_idempotency_replays_once_and_rejects_conflicts(self):
        cache = bridge.IdempotencyCache()
        calls = 0
        lock = threading.Lock()

        def producer():
            nonlocal calls
            with lock:
                calls += 1
            time.sleep(0.05)
            return 200, {"ok": True}

        results = []
        threads = [
            threading.Thread(target=lambda: results.append(cache.execute("id", "same", producer)))
            for _ in range(2)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(1, calls)
        self.assertEqual({False, True}, {result[2] for result in results})
        with self.assertRaises(bridge.RequestError):
            cache.execute("id", "different", producer)

    @mock.patch("bridge.subprocess.run")
    def test_model_process_does_not_inherit_bridge_secret(self, run):
        run.return_value = mock.Mock(returncode=0, stdout="ok", stderr="")
        with mock.patch.dict(os.environ, {"ARGUS_BRIDGE_TOKEN": "never-forward"}, clear=False):
            bridge.run_gpt("prompt")
        self.assertNotIn("ARGUS_BRIDGE_TOKEN", run.call_args.kwargs["env"])

    @mock.patch("bridge.subprocess.run")
    def test_provider_quota_never_becomes_model_output(self, run):
        run.return_value = mock.Mock(
            returncode=0,
            stdout="Codex provider quota exhausted (429); retry later.",
            stderr="",
        )
        with self.assertRaises(bridge.ModelProcessError) as raised:
            bridge.run_gpt("prompt")
        self.assertEqual(503, raised.exception.status)
        self.assertEqual("provider_quota_exhausted", raised.exception.code)

    @mock.patch("bridge.subprocess.run")
    def test_quota_words_in_valid_protocol_output_are_not_misclassified(self, run):
        output = (
            "Quota exhausted è solo il nome richiesto.\n"
            '@@META@@ {"draft":null,"error_code":"clarification_required"}'
        )
        run.return_value = mock.Mock(returncode=0, stdout=output, stderr="")
        # subprocess mockato -> nessun usage-file scritto -> usage None (best-effort).
        self.assertEqual((output, None), bridge.run_gpt("prompt"))

    @mock.patch("bridge.run_gpt", return_value=("risposta senza metadati", None))
    def test_compile_rejects_missing_meta_as_upstream_failure(self, _run):
        with self.assertRaises(bridge.ModelProcessError) as raised:
            bridge.compile_request(self.request())
        self.assertEqual(502, raised.exception.status)
        self.assertEqual("model_invalid_output", raised.exception.code)

    def test_sanitize_usage_is_fail_closed(self):
        # Report reale della CLI hermes --usage-file (S15): il bridge tiene solo il subset chiuso.
        full = {
            "estimated_cost_usd": 0.0, "cost_status": "included", "cost_source": "none",
            "input_tokens": 2785, "output_tokens": 5, "cache_read_tokens": 0,
            "cache_write_tokens": 0, "reasoning_tokens": 0, "total_tokens": 2790,
            "api_calls": 1, "model": "gpt-5.5", "provider": "openai-codex",
            "session_id": "20260717_163952_e79991", "completed": True, "failed": False,
            "service_tier": None,
        }
        self.assertEqual(
            {
                "input_tokens": 2785, "output_tokens": 5, "total_tokens": 2790,
                "api_calls": 1, "model": "gpt-5.5", "provider": "openai-codex",
                "cost_status": "included", "estimated_cost_usd": 0.0,
            },
            bridge._sanitize_usage(full),
        )
        # Fail-closed: forme non valide -> None, mai TypeError.
        self.assertIsNone(bridge._sanitize_usage(None))
        self.assertIsNone(bridge._sanitize_usage([full]))
        self.assertIsNone(bridge._sanitize_usage({**full, "input_tokens": -1}))
        self.assertIsNone(bridge._sanitize_usage({**full, "input_tokens": bridge.MAX_USAGE_TOKENS + 1}))
        self.assertIsNone(bridge._sanitize_usage({**full, "output_tokens": "5"}))
        self.assertIsNone(bridge._sanitize_usage({**full, "api_calls": True}))
        self.assertIsNone(bridge._sanitize_usage({**full, "api_calls": bridge.MAX_USAGE_API_CALLS + 1}))
        self.assertIsNone(bridge._sanitize_usage({**full, "model": "x" * 65}))
        self.assertIsNone(bridge._sanitize_usage({**full, "estimated_cost_usd": float("inf")}))
        self.assertIsNone(bridge._sanitize_usage({**full, "estimated_cost_usd": -0.1}))
        # Senza i token minimi (input+output) il report non serve al budget: None.
        self.assertIsNone(bridge._sanitize_usage({"model": "gpt-5.5"}))

    @staticmethod
    def act_request():
        return {
            "schema_version": 1,
            "request_id": "act-test-1",
            "goal": "rispondi in modo cordiale",
            "context_sources": ["notification", "state"],
            "allowed_tools": ["whatsapp_reply"],
            "context": {
                "notification": {
                    "package": "com.whatsapp",
                    "sender": "Moglie",
                    "title": "Moglie",
                    "text": "ciao",
                    "is_group": False,
                },
                "state": {
                    "values": {"ringer": "normal"},
                    "foreground_app": "com.whatsapp",
                },
            },
        }


class BridgeHttpTest(unittest.TestCase):
    # Forma reale del report hermes --usage-file (S15).
    USAGE_REPORT = {
        "estimated_cost_usd": 0.0, "cost_status": "included", "cost_source": "none",
        "input_tokens": 2785, "output_tokens": 5, "cache_read_tokens": 0,
        "cache_write_tokens": 0, "reasoning_tokens": 0, "total_tokens": 2790,
        "api_calls": 1, "model": "gpt-5.5", "provider": "openai-codex",
        "session_id": "20260717_163952_e79991", "completed": True, "failed": False,
        "service_tier": None,
    }
    SANITIZED_USAGE = {
        "input_tokens": 2785, "output_tokens": 5, "total_tokens": 2790,
        "api_calls": 1, "model": "gpt-5.5", "provider": "openai-codex",
        "cost_status": "included", "estimated_cost_usd": 0.0,
    }

    def setUp(self):
        self.calls = 0

        # Stessa firma di run_gpt (il kwarg tools= è usato dal path /act): ritorna (output, usage).
        def runner(_prompt, tools="clarify"):
            self.calls += 1
            if "ONE-SHOT GENERATOR" in _prompt:
                return (
                    '@@META@@ {"reply_text":"Ciao, a dopo.","error_code":null}',
                    dict(self.USAGE_REPORT),
                )
            return (
                'Regola pronta.\n@@META@@ {"draft":{"name":"dnd sera",'
                '"trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},'
                '"actions":[{"type":"set_dnd","mode":"PRIORITY"}]},"error_code":null}',
                dict(self.USAGE_REPORT),
            )

        self.patchers = [
            mock.patch.object(bridge, "TOKEN", "test-token"),
            mock.patch.object(bridge, "run_gpt", runner),
            mock.patch.object(bridge, "IDEMPOTENCY", bridge.IdempotencyCache()),
            mock.patch.object(bridge, "MODEL_SLOTS", threading.BoundedSemaphore(1)),
        ]
        for patcher in self.patchers:
            patcher.start()
        self.server = bridge.ArgusBridgeServer(("127.0.0.1", 0), bridge.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        for patcher in reversed(self.patchers):
            patcher.stop()

    def request(self, path, body=None, token="test-token", request_id="req-http-1", content_type="application/json"):
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        request = urllib.request.Request(self.url + path, data=data)
        if token is not None:
            request.add_header("Authorization", f"Bearer {token}")
        if data is not None:
            request.add_header("Content-Type", content_type)
            request.add_header("Idempotency-Key", request_id)
        try:
            with urllib.request.urlopen(request, timeout=2) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def test_health_requires_authentication(self):
        self.assertEqual(401, self.request("/health", token=None)[0])
        status, body = self.request("/health")
        self.assertEqual(200, status)
        self.assertEqual({"status", "model", "schema_version"}, set(body))
        self.assertEqual(bridge.LEGACY_COMPILE_SCHEMA_VERSION, body["schema_version"])

        status, current = self.request("/health/v2")
        self.assertEqual(200, status)
        self.assertEqual(
            {
                "status", "model", "schema_version", "compile_schema_versions",
                "act_schema_versions", "source_sha256",
            },
            set(current),
        )
        self.assertEqual(bridge.HEALTH_SCHEMA_VERSION, current["schema_version"])
        self.assertEqual(list(bridge.SUPPORTED_COMPILE_SCHEMA_VERSIONS), current["compile_schema_versions"])
        self.assertEqual(
            list(bridge.SUPPORTED_ACT_SCHEMA_VERSIONS), current["act_schema_versions"]
        )
        self.assertEqual(64, len(current["source_sha256"]))

    def test_compile_is_strict_idempotent_and_conflict_safe(self):
        request = self.valid_request()
        status, first = self.request("/compile", request)
        self.assertEqual(200, status)
        self.assertEqual(bridge.COMPILE_SCHEMA_VERSION, first["schema_version"])
        self.assertEqual("dnd sera", first["meta"]["draft"]["name"])
        self.assertEqual(first, self.request("/compile", request)[1])
        self.assertEqual(1, self.calls)
        changed = self.valid_request(message="stessa chiave, altro contenuto")
        status, body = self.request("/compile", changed)
        self.assertEqual(409, status)
        self.assertEqual("idempotency_conflict", body["error"])

    def test_legacy_compile_stays_strict_during_v2_rollout(self):
        request = self.valid_request()
        request["schema_version"] = bridge.LEGACY_COMPILE_SCHEMA_VERSION
        del request["manifest"]["state_readers"]
        request["request_id"] = "req-http-legacy"

        status, body = self.request(
            "/compile", request, request_id=request["request_id"]
        )

        self.assertEqual(200, status)
        self.assertEqual(bridge.LEGACY_COMPILE_SCHEMA_VERSION, body["schema_version"])

    def test_act_is_strict_idempotent_and_never_returns_a_target(self):
        request = self.valid_act_request()
        status, first = self.request(
            "/act", request, request_id=request["request_id"]
        )
        self.assertEqual(200, status)
        self.assertEqual(
            {"schema_version", "request_id", "result", "error_code", "usage"}, set(first)
        )
        self.assertEqual({"text"}, set(first["result"]))
        self.assertEqual(
            first,
            self.request("/act", request, request_id=request["request_id"])[1],
        )
        self.assertEqual(1, self.calls)

        changed = self.valid_act_request(goal="stessa chiave, altro goal")
        status, body = self.request(
            "/act", changed, request_id=changed["request_id"]
        )
        self.assertEqual(409, status)
        self.assertEqual("idempotency_conflict", body["error"])

    def test_act_v2_accepts_only_exact_classified_reader_values(self):
        request = self.valid_act_v2_request()
        status, body = self.request(
            "/act", request, request_id=request["request_id"]
        )
        self.assertEqual(200, status)
        self.assertEqual(bridge.ACT_V2_SCHEMA_VERSION, body["schema_version"])
        self.assertEqual(1, self.calls)

        mutations = []
        underclassified = self.valid_act_v2_request("act-v2-underclassified")
        underclassified["context"]["state"][0]["confidentiality"] = "PRIVATE"
        mutations.append(underclassified)
        tainted = self.valid_act_v2_request("act-v2-tainted")
        tainted["context"]["state"][0]["integrity"] = "TAINTED"
        mutations.append(tainted)
        mismatched = self.valid_act_v2_request("act-v2-query-id")
        mismatched["context"]["state"][0]["query_id"] = "state.reader.fake"
        mutations.append(mismatched)
        unknown = self.valid_act_v2_request("act-v2-extra")
        unknown["context"]["state"][0]["extra"] = "no"
        mutations.append(unknown)
        malformed_unicode = self.valid_act_v2_request("act-v2-unicode")
        malformed_unicode["context"]["state"][0]["value"] = "\ud800"
        mutations.append(malformed_unicode)
        malformed_enum = self.valid_act_v2_request("act-v2-enum")
        malformed_enum["context"]["state"][0]["confidentiality"] = ["SECRET"]
        malformed_enum["context"]["state"][0]["value_type"] = ["NUMBER"]
        mutations.append(malformed_enum)

        for invalid in mutations:
            status, _ = self.request(
                "/act", invalid, request_id=invalid["request_id"]
            )
            self.assertEqual(400, status)
        self.assertEqual(1, self.calls)

    def test_responses_carry_sanitized_model_usage(self):
        # S15: /compile e /act allegano l'usage reale (subset chiuso) quando disponibile.
        status, body = self.request("/compile", self.valid_request())
        self.assertEqual(200, status)
        self.assertEqual(self.SANITIZED_USAGE, body["usage"])

        act = self.valid_act_request()
        status, act_body = self.request("/act", act, request_id=act["request_id"])
        self.assertEqual(200, status)
        self.assertEqual(self.SANITIZED_USAGE, act_body["usage"])

    def test_missing_usage_report_omits_the_field(self):
        # Senza report (o malformato) la risposta NON contiene "usage": il campo è opzionale
        # e le app con parser strict lo dichiarano nullable — mai un null esplicito.
        def runner_without_usage(_prompt, tools="clarify"):
            return (
                'Regola pronta.\n@@META@@ {"draft":{"name":"dnd sera",'
                '"trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},'
                '"actions":[{"type":"set_dnd","mode":"PRIORITY"}]},"error_code":null}',
                None,
            )

        with mock.patch.object(bridge, "run_gpt", runner_without_usage):
            request = self.valid_request(message="senza usage")
            request["request_id"] = "req-http-nousage"
            status, body = self.request(
                "/compile", request, request_id=request["request_id"]
            )
        self.assertEqual(200, status)
        self.assertNotIn("usage", body)

    def test_invalid_contract_never_calls_model(self):
        request = self.valid_request()
        request["surprise"] = True
        status, _ = self.request("/compile", request)
        self.assertEqual(400, status)
        self.assertEqual(0, self.calls)
        status, _ = self.request("/compile", self.valid_request(), content_type="text/plain")
        self.assertEqual(415, status)
        self.assertEqual(0, self.calls)

    def test_provider_quota_is_service_unavailable_and_idempotent(self):
        request = self.valid_request()
        request["request_id"] = "req-http-quota"
        failure = bridge.ModelProcessError(503, "provider_quota_exhausted")
        with mock.patch.object(bridge, "run_gpt", side_effect=failure) as run:
            status, body = self.request(
                "/compile", request, request_id="req-http-quota"
            )
            self.assertEqual(503, status)
            self.assertEqual({"error": "provider_quota_exhausted"}, body)
            status, body = self.request(
                "/compile", request, request_id="req-http-quota"
            )
        self.assertEqual(503, status)
        self.assertEqual({"error": "provider_quota_exhausted"}, body)
        run.assert_called_once()

    @staticmethod
    def valid_request(message="dopo le 23 metti dnd"):
        return {
            "schema_version": 2,
            "request_id": "req-http-1",
            "message": message,
            "manifest": {
                "device_model": "OnePlus",
                "android_api": 36,
                "shizuku_available": True,
                "granted_permissions": ["android.permission.INTERNET"],
                "available_tools": ["set_dnd"],
                "unavailable_tools": {},
                "whitelisted_contacts": [],
                "state_keys": {"dnd": "off|priority|total"},
                "available_triggers": ["time"],
                "state_readers": {
                    "policy_version": bridge.STATE_QUERY_POLICY_VERSION,
                    "families": list(bridge.STATE_QUERY_FAMILIES),
                    "limits": dict(bridge.STATE_READER_LIMITS),
                },
            },
            "state": {
                "values": {"dnd": "off"},
                "foreground_app": None,
                "location_available": False,
            },
        }

    @staticmethod
    def valid_act_request(goal="rispondi in modo cordiale"):
        return {
            "schema_version": 1,
            "request_id": "act-http-1",
            "goal": goal,
            "context_sources": ["notification"],
            "allowed_tools": ["whatsapp_reply"],
            "context": {
                "notification": {
                    "package": "com.whatsapp",
                    "sender": "Moglie",
                    "title": "Moglie",
                    "text": "ciao",
                    "is_group": False,
                },
                "state": None,
            },
        }

    @staticmethod
    def valid_act_v2_request(request_id="act-v2-http-1"):
        query = {"type": "dumpsys_field", "service": "battery", "field": "voltage"}
        return {
            "schema_version": bridge.ACT_V2_SCHEMA_VERSION,
            "request_id": request_id,
            "goal": "rispondi tenendo conto del voltaggio",
            "allowed_tools": ["whatsapp_reply"],
            "context": {
                "notification": {
                    "package": "com.whatsapp",
                    "sender": "Moglie",
                    "title": "Moglie",
                    "text": "ciao",
                    "is_group": False,
                },
                "state": [{
                    "query_id": bridge.state_query_canonical_id(query),
                    "query": query,
                    "value_type": "NUMBER",
                    "policy_version": bridge.STATE_QUERY_POLICY_VERSION,
                    "integrity": "CLEAN",
                    "confidentiality": "SECRET",
                    "value": "4200",
                }],
            },
        }


if __name__ == "__main__":
    unittest.main()
