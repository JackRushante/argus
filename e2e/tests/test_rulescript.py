import base64
import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import rulescript  # noqa: E402


def bundle(payload: dict) -> str:
    encoded = base64.b64encode(json.dumps(payload).encode()).decode()
    return f"Result: Bundle[{{r={encoded}}}]"


def output(**sections) -> str:
    return "\n".join(f"##{name}##\n{text}" for name, text in sections.items()) + "\n##END##\n"


def journal(status="SUCCEEDED", executions=1, actions=(), audit=()):
    return {
        "ok": True, "id": "x", "executions": executions,
        "last": {"status": status, "succeeded": 1, "failed": 0, "submitted": 0, "deferred": 0} if executions else None,
        "actions": [{"path": "1", "type": t, "outcome": o, "code": c} for t, o, c in actions],
        "audit": [{"kind": k, "detail": d} for k, d in audit],
    }


BASE_RECORD = {
    "id": "r1", "title": "t",
    "rule": {"name": "n", "trigger": {"type": "immediate"}, "actions": [{"type": "vibrate", "durationMs": 100}]},
    "expect": {"load": "ok", "run": "SUCCEEDED"},
}


class ParsingTest(unittest.TestCase):
    def test_sections_and_first_line_content(self):
        sections = rulescript.parse_sections("noise\n##ID##abc\n##LOAD##\nline1\nline2\n##END##\n")
        self.assertEqual("abc", sections["ID"])
        self.assertEqual("line1\nline2", sections["LOAD"])
        self.assertIn("END", sections)

    def test_decode_result_roundtrip_and_garbage(self):
        self.assertEqual({"ok": True}, rulescript.decode_result(bundle({"ok": True})))
        self.assertIsNone(rulescript.decode_result("Exception: SecurityException"))
        self.assertIsNone(rulescript.decode_result(None))
        self.assertIsNone(rulescript.decode_result("Result: Bundle[{r=!!!}]"))

    def test_hook_error_is_the_first_line(self):
        self.assertEqual("java.lang.SecurityException: e2e: uid 10123", rulescript.hook_error("java.lang.SecurityException: e2e: uid 10123\n at x"))
        self.assertEqual("nessuna risposta", rulescript.hook_error(""))


class EvaluateTest(unittest.TestCase):
    def evaluate(self, record, **sections):
        return rulescript.evaluate(record, output(**sections))

    def test_a_clean_run_passes(self):
        status, problems, _ = self.evaluate(
            BASE_RECORD, LOAD=bundle({"ok": True, "id": "x"}),
            JOURNAL=bundle(journal(actions=[("vibrate", "SUCCEEDED", "")])))
        self.assertEqual(("pass", []), (status, problems))

    def test_wrong_status_fails_with_a_reason(self):
        status, problems, _ = self.evaluate(
            BASE_RECORD, LOAD=bundle({"ok": True, "id": "x"}), JOURNAL=bundle(journal(status="FAILED")))
        self.assertEqual("fail", status)
        self.assertIn("stato FAILED invece di SUCCEEDED", problems[0])

    def test_unexpected_rejection_is_reported_with_codes(self):
        status, problems, _ = self.evaluate(
            BASE_RECORD, LOAD=bundle({"ok": False, "stage": "review", "issues": [{"severity": "ERROR", "code": "capability_unavailable", "detail": "action.set_wifi"}]}))
        self.assertEqual("fail", status)
        self.assertIn("capability_unavailable", problems[0])

    def test_expected_rejection_passes_only_with_stage_and_code(self):
        record = dict(BASE_RECORD, expect={"load": {"fail": {"stage": "review", "code": "capability_unavailable", "detail": "action.set_wifi"}}})
        rejected = bundle({"ok": False, "stage": "review", "issues": [{"severity": "ERROR", "code": "capability_unavailable", "detail": "action.set_wifi"}]})
        self.assertEqual("pass", self.evaluate(record, LOAD=rejected)[0])
        wrong_code = bundle({"ok": False, "stage": "review", "issues": [{"severity": "ERROR", "code": "no_actions", "detail": ""}]})
        self.assertEqual("fail", self.evaluate(record, LOAD=wrong_code)[0])
        wrong_stage = bundle({"ok": False, "stage": "arm", "issues": [{"severity": "ERROR", "code": "capability_unavailable", "detail": "action.set_wifi"}]})
        self.assertEqual("fail", self.evaluate(record, LOAD=wrong_stage)[0])

    def test_an_armed_rule_that_should_have_been_rejected_fails(self):
        record = dict(BASE_RECORD, expect={"load": {"fail": {"stage": "review", "code": "shell_external_trigger"}}})
        status, problems, _ = self.evaluate(record, LOAD=bundle({"ok": True, "id": "x"}))
        self.assertEqual("fail", status)
        self.assertIn("armata", problems[0])

    def test_missing_hook_response_is_a_failure(self):
        status, problems, _ = self.evaluate(BASE_RECORD, LOAD="java.lang.SecurityException: nope")
        self.assertEqual("fail", status)
        self.assertIn("non risponde", problems[0])

    def test_script_without_end_marker_fails(self):
        status, problems, _ = rulescript.evaluate(BASE_RECORD, "##LOAD##\n" + bundle({"ok": True, "id": "x"}))
        self.assertEqual("fail", status)
        self.assertTrue(any("in fondo" in p for p in problems))

    def test_run_expectations(self):
        spec = {"executions": 1, "status": "FAILED", "actions": ["http_request:FAILED:http_status_404"], "audit_has": ["FIRED"], "audit_lacks": ["SUPPRESSED_COOLDOWN"], "audit_detail": {"BLOCKED_POLICY": "paused"}}
        j = journal(status="FAILED", actions=[("http_request", "FAILED", "http_status_404")], audit=[("FIRED", ""), ("BLOCKED_POLICY", "paused")])
        self.assertEqual([], rulescript.evaluate_run(spec, j))
        bad = journal(status="SUCCEEDED", actions=[("http_request", "SUCCEEDED", "")], audit=[("SUPPRESSED_COOLDOWN", "")])
        self.assertGreaterEqual(len(rulescript.evaluate_run(spec, bad)), 4)

    def test_zero_executions(self):
        self.assertEqual([], rulescript.evaluate_run({"executions": 0, "audit_has": ["CONDITIONS_NOT_MET"]}, journal(executions=0, audit=[("CONDITIONS_NOT_MET", "")])))
        self.assertTrue(rulescript.evaluate_run({"status": "SUCCEEDED"}, journal(executions=0)))

    def test_checks(self):
        self.assertIsNone(rulescript.evaluate_check({"shell": "x", "equals": "1"}, "1\n"))
        self.assertIsNotNone(rulescript.evaluate_check({"shell": "x", "equals": "1"}, "0"))
        self.assertIsNone(rulescript.evaluate_check({"shell": "x", "contains": "ab"}, "xaby"))
        self.assertIsNotNone(rulescript.evaluate_check({"shell": "x", "contains": "ab"}, "xy"))
        self.assertIsNone(rulescript.evaluate_check({"shell": "x", "not_contains": "ab"}, "xy"))
        self.assertIsNotNone(rulescript.evaluate_check({"shell": "x", "not_contains": "ab"}, "xaby"))
        self.assertIsNone(rulescript.evaluate_check({"shell": "x", "matches": "^[1-9]"}, "3"))
        self.assertIsNotNone(rulescript.evaluate_check({"shell": "x", "matches": "^[1-9]"}, "0"))

    def test_checks_run_even_when_the_load_is_rejected_as_expected(self):
        record = dict(BASE_RECORD, expect={"load": {"fail": {"stage": "review", "code": "x"}}}, checks=[{"shell": "echo 1", "equals": "1"}])
        rejected = bundle({"ok": False, "stage": "review", "issues": [{"severity": "ERROR", "code": "x", "detail": ""}]})
        self.assertEqual("pass", self.evaluate(record, LOAD=rejected, CHK0="1")[0])
        self.assertEqual("fail", self.evaluate(record, LOAD=rejected, CHK0="2")[0])
        self.assertEqual("fail", self.evaluate(record, LOAD=rejected)[0])


class SubstituteTest(unittest.TestCase):
    def test_placeholders_are_replaced_everywhere(self):
        record = {"rule": {"actions": [{"title": "e2e {{RUN}}"}]}, "checks": [{"shell": "grep '{{RUN}}'"}]}
        out = rulescript.substitute(record, {"RUN": "abc123"})
        self.assertEqual("e2e abc123", out["rule"]["actions"][0]["title"])
        self.assertIn("abc123", out["checks"][0]["shell"])
        self.assertNotIn("{{", json.dumps(out))


class BuildScriptTest(unittest.TestCase):
    TRICKY = dict(
        BASE_RECORD,
        rule={"name": "it's \"odd\" `x` $HOME", "trigger": {"type": "immediate"}, "actions": [{"type": "show_notification", "title": "a'b", "text": "c\"d"}]},
        pre=["settings put global e2e_marker 1"],
        fire={"payload": {"text": "it's \"q\" $x"}, "repeat": 2},
        checks=[{"shell": "echo 'a b' | tr a b", "equals": "b b", "wait_s": 2}, {"shell": "true", "matches": "^$"}, {"shell": "true", "contains": "it's"}, {"shell": "true", "not_contains": "x'y"}],
        cleanup=["settings delete global e2e_marker"],
        after_load=["true", 1],
        pause=True,
    )

    def test_the_script_is_valid_posix_shell(self):
        sh = shutil.which("sh")
        if not sh:
            self.skipTest("sh non disponibile")
        for record in (BASE_RECORD, self.TRICKY, dict(self.TRICKY, fire={"real": ["input keyevent KEYCODE_SLEEP", 2]}), dict(BASE_RECORD, fire=None, checks=[])):
            script = rulescript.build_script(record)
            proc = subprocess.run([sh, "-n"], input=script.encode(), capture_output=True)
            self.assertEqual(0, proc.returncode, proc.stderr.decode() + "\n" + script)

    def test_the_rule_json_travels_quoted_and_intact(self):
        script = rulescript.build_script(self.TRICKY)
        self.assertIn("call reset_load", script)
        sh = shutil.which("sh")
        if sh:
            line = next(l for l in script.splitlines() if l.startswith("LARG=$(printf %s "))
            command = line.split("LARG=$(printf %s ", 1)[1].rsplit(" | enc)", 1)[0]
            proc = subprocess.run([sh, "-c", f"printf %s {command}"], capture_output=True)
            self.assertEqual(self.TRICKY["rule"], json.loads(proc.stdout.decode()))

    def test_sections_present(self):
        script = rulescript.build_script(self.TRICKY)
        for marker in ("##LOAD##", "##ID##", "##FIRE0##", "##FIRE1##", "##CHK0##", "##CHK3##", "##JOURNAL##", "##RESET2##", "##END##"):
            self.assertIn(marker, script)

    def test_reset_runs_before_cleanup(self):
        script = rulescript.build_script(self.TRICKY)
        self.assertLess(script.index("##RESET2##"), script.index("settings delete global e2e_marker"))

    def test_polling_only_when_a_status_is_expected(self):
        self.assertIn("RUNNING", rulescript.build_script(BASE_RECORD))
        quiet = dict(BASE_RECORD, expect={"load": "ok", "run": {"executions": 0}})
        self.assertNotIn("RUNNING", rulescript.build_script(quiet))

    def test_encoded_command_is_shell_safe(self):
        command = rulescript.encode_script_command(rulescript.build_script(self.TRICKY))
        body = command.split(" | base64 -d")[0].replace("echo ", "")
        self.assertRegex(body, r"^[A-Za-z0-9+/=]+$")


if __name__ == "__main__":
    unittest.main()
