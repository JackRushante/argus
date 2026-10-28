import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pairwise  # noqa: E402
import suite  # noqa: E402


class SuiteFilesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.records = suite.load_suite()

    def test_the_suite_is_valid(self):
        self.assertEqual([], suite.validate(self.records))

    def test_size_is_in_the_expected_range(self):
        # «circa 100 automazioni»: ne servono abbastanza da coprire le coppie, non una per ogni combinazione.
        self.assertGreaterEqual(len(self.records), 90)
        self.assertLessEqual(len(self.records), 170)

    def test_every_required_pair_is_covered(self):
        self.assertEqual([], [pairwise.describe(p) for p in pairwise.missing_pairs(self.records)])

    def test_every_action_type_appears_somewhere(self):
        used = set()
        for record in self.records:
            used |= pairwise.features(record)["action"]
        # tap e input_text sono rifiutati dal motore di produzione; invoke_llm* richiede l'AI (fuori dalla suite);
        # whatsapp_reply richiede WhatsApp.
        not_testable = {"tap", "input_text", "invoke_llm", "invoke_llm_v2", "whatsapp_reply"}
        self.assertEqual([], sorted(set(pairwise.ALL_ACTION_TYPES) - used - not_testable))

    def test_every_trigger_type_appears_in_a_runnable_rule(self):
        runnable = {r["rule"]["trigger"]["type"] for r in self.records if r["expect"].get("load") == "ok"}
        # il sensore di movimento significativo non c'e' sull'emulatore: solo il rifiuto all'arm
        self.assertEqual([], sorted(set(pairwise.TRIGGERS) - runnable - {"sensor"}))

    def test_every_config_has_runnable_rules(self):
        for config in pairwise.CONFIGS:
            runnable = [r for r in self.records if config in r.get("configs", pairwise.CONFIGS)]
            self.assertGreater(len(runnable), 80, config)

    def test_privileged_rules_are_expected_to_be_blocked_without_shizuku(self):
        privileged = {"set_wifi", "set_bluetooth", "set_mobile_data", "set_dark_mode", "set_airplane", "set_location", "set_nfc",
                      "set_battery_saver", "lock_screen", "screenshot", "app_control", "app_permission", "run_shell", "write_setting"}
        for record in self.records:
            types = pairwise.features(record)["action"]
            if not (types & privileged) or record["expect"].get("load") != "ok":
                continue
            if "none" not in record.get("configs", pairwise.CONFIGS):
                continue
            effective = suite.effective(record, "none")
            load = effective["expect"]["load"]
            self.assertIsInstance(load, dict, f"{record['id']}: senza Shizuku deve essere rifiutata all'arm")
            self.assertEqual("capability_unavailable", load["fail"]["code"], record["id"])

    def test_real_trigger_paths_are_present(self):
        real = [r["id"] for r in self.records if (r.get("fire") or {}).get("real")]
        self.assertGreaterEqual(len(real), 6)
        timed = [r for r in self.records if r["rule"]["trigger"].get("afterMs")]
        self.assertTrue(timed)

    def test_effective_applies_per_config_overrides(self):
        record = {"id": "x", "title": "t", "rule": {"name": "n", "trigger": {"type": "immediate"}, "actions": [{"type": "vibrate", "durationMs": 1}]},
                  "checks": [{"shell": "a", "equals": "1"}], "per_config": {"none": {"checks": []}}}
        self.assertEqual([], suite.effective(record, "none")["checks"])
        self.assertEqual(1, len(suite.effective(record, "shizuku_root")["checks"]))
        self.assertNotIn("per_config", suite.effective(record, "none"))


class ValidationTest(unittest.TestCase):
    def valid(self):
        return {"id": "x", "title": "t", "rule": {"name": "n", "trigger": {"type": "immediate"}, "actions": [{"type": "vibrate", "durationMs": 1}]}}

    def test_detects_duplicates_and_unknown_things(self):
        a, b = self.valid(), self.valid()
        self.assertTrue(any("duplicato" in p for p in suite.validate([a, b])))
        bad = self.valid()
        bad["rule"]["trigger"] = {"type": "teleport"}
        self.assertTrue(any("trigger sconosciuto" in p for p in suite.validate([bad])))
        bad = self.valid()
        bad["rule"]["actions"] = [{"type": "teleport"}]
        self.assertTrue(any("tipo di azione" in p for p in suite.validate([bad])))
        bad = self.valid()
        bad["configs"] = ["moon"]
        self.assertTrue(suite.validate([bad]))

    def test_detects_malformed_checks_and_expectations(self):
        bad = self.valid()
        bad["checks"] = [{"shell": "x", "equals": "1", "contains": "2"}]
        self.assertTrue(suite.validate([bad]))
        bad = self.valid()
        bad["expect"] = {"load": {"fail": {"stage": "nowhere", "code": "c"}}}
        self.assertTrue(suite.validate([bad]))
        bad = self.valid()
        bad["expect"] = {"run": {"status": "SUCCEEDED", "bogus": 1}}
        self.assertTrue(suite.validate([bad]))
        bad = self.valid()
        bad["fire"] = {"payload": {}, "real": ["x"]}
        self.assertTrue(suite.validate([bad]))
        bad = self.valid()
        bad["per_config"] = {"none": {"rule": {}, "title": "x"}}
        self.assertTrue(any("non ammessi" in p for p in suite.validate([bad])))


if __name__ == "__main__":
    unittest.main()
