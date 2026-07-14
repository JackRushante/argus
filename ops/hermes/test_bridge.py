import json
import os
import threading
import time
import unittest
import urllib.error
import urllib.request
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
        request = self.request()
        self.assertIs(request, bridge.validate_request(request, "req-test-1"))
        request["schema_version"] = 2
        with self.assertRaises(bridge.RequestError):
            bridge.validate_request(request, "req-test-1")

    def test_copy_to_clipboard_action_requires_a_compilable_regex(self):
        tools = {"copy_to_clipboard"}
        ok = {"type": "copy_to_clipboard", "extractionRegex": r"(?<!\+)\b(\d{4,8})\b"}
        self.assertTrue(bridge.validate_action(ok, tools))
        self.assertTrue(bridge.validate_action({"type": "copy_to_clipboard"}, tools))
        self.assertFalse(
            bridge.validate_action({"type": "copy_to_clipboard", "extractionRegex": "(broken"}, tools)
        )
        self.assertFalse(
            bridge.validate_action({"type": "copy_to_clipboard", "extractionRegex": "x" * 600}, tools)
        )

    def test_manifest_available_triggers_is_optional_and_bounded(self):
        # Client pre-P2: campo assente, accettato (retrocompatibilita').
        legacy = self.request()
        self.assertIs(legacy, bridge.validate_request(legacy, "req-test-1"))

        # Client P2: lista di wire name, accettata e usata dal prompt (REGOLA 10).
        current = self.request()
        current["manifest"]["available_triggers"] = ["time", "notification", "phone_state.sms"]
        self.assertIs(current, bridge.validate_request(current, "req-test-1"))
        prompt = bridge.build_prompt(current)
        self.assertIn("available_triggers", prompt)
        self.assertIn("phone_state.sms", prompt)

        # Malformata o fuori bounds: rifiutata.
        for bad in (["x" * 65], "not-a-list", ["ok"] * 33):
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
        self.assertEqual(output, bridge.run_gpt("prompt"))

    @mock.patch("bridge.run_gpt", return_value="risposta senza metadati")
    def test_compile_rejects_missing_meta_as_upstream_failure(self, _run):
        with self.assertRaises(bridge.ModelProcessError) as raised:
            bridge.compile_request(self.request())
        self.assertEqual(502, raised.exception.status)
        self.assertEqual("model_invalid_output", raised.exception.code)

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
    def setUp(self):
        self.calls = 0

        def runner(_prompt):
            self.calls += 1
            if "GENERATORE ONE-SHOT" in _prompt:
                return '@@META@@ {"reply_text":"Ciao, a dopo.","error_code":null}'
            return (
                'Regola pronta.\n@@META@@ {"draft":{"name":"dnd sera",'
                '"trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},'
                '"actions":[{"type":"set_dnd","mode":"PRIORITY"}]},"error_code":null}'
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

    def test_compile_is_strict_idempotent_and_conflict_safe(self):
        request = self.valid_request()
        status, first = self.request("/compile", request)
        self.assertEqual(200, status)
        self.assertEqual("dnd sera", first["meta"]["draft"]["name"])
        self.assertEqual(first, self.request("/compile", request)[1])
        self.assertEqual(1, self.calls)
        changed = self.valid_request(message="stessa chiave, altro contenuto")
        status, body = self.request("/compile", changed)
        self.assertEqual(409, status)
        self.assertEqual("idempotency_conflict", body["error"])

    def test_act_is_strict_idempotent_and_never_returns_a_target(self):
        request = self.valid_act_request()
        status, first = self.request(
            "/act", request, request_id=request["request_id"]
        )
        self.assertEqual(200, status)
        self.assertEqual(
            {"schema_version", "request_id", "result", "error_code"}, set(first)
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
            "schema_version": 1,
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


if __name__ == "__main__":
    unittest.main()
