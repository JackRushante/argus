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


class BridgeHttpTest(unittest.TestCase):
    def setUp(self):
        self.calls = 0

        def runner(_prompt):
            self.calls += 1
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

    def test_invalid_contract_never_calls_model(self):
        request = self.valid_request()
        request["surprise"] = True
        status, _ = self.request("/compile", request)
        self.assertEqual(400, status)
        self.assertEqual(0, self.calls)
        status, _ = self.request("/compile", self.valid_request(), content_type="text/plain")
        self.assertEqual(415, status)
        self.assertEqual(0, self.calls)

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


if __name__ == "__main__":
    unittest.main()
