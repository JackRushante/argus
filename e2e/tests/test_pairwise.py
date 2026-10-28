import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pairwise  # noqa: E402


def record(trigger, actions, cond="none", vars_=None):
    rule = {"name": "x", "trigger": {"type": trigger}, "actions": actions}
    if vars_:
        rule["vars"] = vars_
    return {"id": "r", "cond": cond, "rule": rule}


class RequiredPairsTest(unittest.TestCase):
    def test_count_matches_the_model(self):
        t, k, c, f = (len(pairwise.DOMAIN[x]) for x in ("trigger", "klass", "cond", "flow"))
        self.assertEqual(t * k + t * c + k * c + f * c + f * k - len(pairwise.INFEASIBLE), len(pairwise.required_pairs()))

    def test_infeasible_pairs_are_never_required(self):
        self.assertTrue(pairwise.INFEASIBLE)
        self.assertFalse(pairwise.INFEASIBLE & pairwise.required_pairs())

    def test_every_action_type_belongs_to_exactly_one_class_or_control_flow(self):
        seen = {}
        for klass, items in pairwise.ACTION_CLASSES.items():
            for item in items:
                self.assertNotIn(item, seen, f"{item} in due classi")
                seen[item] = klass
        self.assertFalse(set(seen) & set(pairwise.CONTROL_FLOW))

    def test_pairs_are_unordered_and_cross_parameter(self):
        for pair in pairwise.required_pairs():
            self.assertEqual(2, len(pair))
            self.assertEqual(2, len({param for param, _ in pair}))


class FeaturesTest(unittest.TestCase):
    def test_flat_rule(self):
        point = pairwise.features(record("screen", [{"type": "set_volume"}, {"type": "set_wifi"}], cond="true"))
        self.assertEqual({"screen"}, point["trigger"])
        self.assertEqual({"audio_mode", "radios"}, point["klass"])
        self.assertEqual({"true"}, point["cond"])
        self.assertEqual(set(), point["flow"])

    def test_nested_flow_is_flattened(self):
        actions = [
            {"type": "if", "condition": {}, "then": [{"type": "wait"}, {"type": "vibrate"}],
             "orElse": [{"type": "while", "condition": {}, "body": [{"type": "run_shell", "cmd": "x", "captureAs": "o"}]}]},
        ]
        point = pairwise.features(record("manual", actions, vars_=[{"type": "clock", "name": "t"}]))
        self.assertEqual({"if", "wait", "while", "vars", "clock", "capture"}, point["flow"])
        self.assertEqual({"local_ui", "shell"}, point["klass"])

    def test_shell_without_capture_is_not_a_capture(self):
        point = pairwise.features(record("manual", [{"type": "run_shell", "cmd": "true"}]))
        self.assertNotIn("capture", point["flow"])


class CoverageTest(unittest.TestCase):
    def test_nothing_is_covered_by_an_empty_suite(self):
        self.assertEqual(len(pairwise.required_pairs()), len(pairwise.missing_pairs([])))

    def test_a_record_covers_its_own_pairs_only(self):
        records = [record("screen", [{"type": "set_volume"}], cond="true")]
        covered = pairwise.covered_pairs(records)
        self.assertIn(frozenset({("trigger", "screen"), ("klass", "audio_mode")}), covered)
        self.assertIn(frozenset({("trigger", "screen"), ("cond", "true")}), covered)
        self.assertIn(frozenset({("klass", "audio_mode"), ("cond", "true")}), covered)
        self.assertNotIn(frozenset({("trigger", "time"), ("klass", "audio_mode")}), covered)
        self.assertNotIn(frozenset({("trigger", "screen"), ("cond", "false")}), covered)

    def test_the_greedy_plan_covers_every_required_pair(self):
        rows = pairwise.plan([])
        covered = set()
        for row in rows:
            covered |= pairwise._row_pairs(row)
        self.assertEqual(pairwise.required_pairs(), covered)

    def test_the_plan_is_small(self):
        # 267 coppie con righe da tre classi: il piano resta molto sotto le ~100 regole della suite.
        self.assertLessEqual(len(pairwise.plan([])), 60)

    def test_the_plan_only_fills_the_gaps(self):
        first = {k: items[0] for k, items in pairwise.ACTION_CLASSES.items()}
        base = [record(t, [{"type": first[k]}]) for t in pairwise.TRIGGERS for k in pairwise.ACTION_CLASSES]
        remaining = pairwise.missing_pairs(base)
        rows = pairwise.plan(base)
        covered = set()
        for row in rows:
            covered |= pairwise._row_pairs(row)
        self.assertTrue(set(remaining) <= covered)
        self.assertLess(len(rows), len(pairwise.plan([])))

    def test_plan_terminates_when_everything_is_covered(self):
        rows = pairwise.plan([])
        records = []
        for row in rows:
            actions = [{"type": pairwise.ACTION_CLASSES[k][0]} for k in sorted(row["klass"])]
            for flow in sorted(row["flow"]):
                if flow in pairwise.CONTROL_FLOW:
                    actions.append({"type": flow})
            vars_ = []
            if "vars" in row["flow"]:
                vars_.append({"type": "literal", "name": "v"})
            if "clock" in row["flow"]:
                vars_.append({"type": "clock", "name": "c"})
            if "capture" in row["flow"]:
                actions.append({"type": "run_shell", "cmd": "x", "captureAs": "o"})
            records.append(record(sorted(row["trigger"])[0], actions, cond=sorted(row["cond"])[0], vars_=vars_))
        self.assertEqual([], pairwise.missing_pairs(records))
        self.assertEqual([], pairwise.plan(records))


class ConfigMatrixTest(unittest.TestCase):
    def test_counts_runs_per_config_and_skips_skipped(self):
        results = [
            {"actions": ["set_wifi"], "config": "shizuku_root", "status": "pass"},
            {"actions": ["set_wifi"], "config": "none", "status": "fail"},
            {"actions": ["set_wifi"], "config": "shizuku_shell", "status": "skip"},
            {"actions": ["vibrate", "set_wifi"], "config": "shizuku_root", "status": "pass"},
        ]
        matrix = pairwise.config_matrix(results)
        self.assertEqual({"shizuku_root": 2, "shizuku_shell": 0, "none": 1}, matrix["set_wifi"])
        self.assertEqual({"shizuku_root": 1, "shizuku_shell": 0, "none": 0}, matrix["vibrate"])

    def test_gaps_lists_missing_action_config_pairs(self):
        results = [{"actions": ["set_wifi"], "config": c, "status": "pass"} for c in ("shizuku_root", "none")]
        gaps = pairwise.action_config_gaps(results, ["set_wifi", "vibrate"])
        self.assertIn(("set_wifi", "shizuku_shell"), gaps)
        self.assertNotIn(("set_wifi", "none"), gaps)
        self.assertEqual(3, len([g for g in gaps if g[0] == "vibrate"]))


if __name__ == "__main__":
    unittest.main()
