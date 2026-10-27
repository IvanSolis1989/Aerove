import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import instrumentation_results as result
import instrumentation_shards as shards


class ResultIndexTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.names = []
        for i in range(1000):
            name = f"case{i}(example.Tests)"
            if shards.shard_for(name) == 0:
                self.names.append(name)
            if len(self.names) == 40:
                break
        self.names.sort()

    def log(self, names, statuses=None, terminal=True):
        rows = []
        for i, name in enumerate(names, 1):
            method, cls = name.rsplit("(", 1)
            for code in (1, (statuses or {}).get(name, 0)):
                rows += [f"INSTRUMENTATION_STATUS: {k}={v}" for k, v in
                         dict(id="AndroidJUnitRunner", numtests=len(names), current=i, **{"class": cls[:-1], "test": method}).items()]
                if code in (-1, -2):
                    rows.append("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: original failure")
                rows.append(f"INSTRUMENTATION_STATUS_CODE: {code}")
        return "\n".join(rows) + ("\nINSTRUMENTATION_CODE: -1\n" if terminal else "\n")

    def inputs(self, names=None, statuses=None):
        names = names or self.names
        identity = dict(sourceRevision="a" * 40, sourceTree="b" * 40, workflowRun="141", runAttempt="1", runNumber="141")
        source = dict(identity, phase="remaining", appBuildRun="141", harnessRevision="c" * 40, harnessRun="142",
                      testShard=dict(numShards=8, shardIndex=shards.shard_for(names[0])))
        apk = {"app-release.apk": "d" * 64, "app-release-androidTest.apk": "e" * 64}
        self.discovery_log = self.root / "discovery.log"
        self.discovery_log.write_text("logOnly synthetic\n" + self.log(names))
        discovery = dict(numShards=8, shardIndex=source["testShard"]["shardIndex"], identity=identity,
                         discoveredDescriptions=sorted(names), discoveryFingerprint=shards.fingerprint(sorted(names)), discoveryCount=len(names),
                         apkSha256=apk, discoveryLogSha256=shards.sha(self.discovery_log), discoveryMode="logOnly-no-test-method-execution")
        self.source = self.root / "SOURCE.json"
        self.discovery = self.root / "TEST-DISCOVERY.json"
        self.sums = self.root / "APK_SHA256SUMS"
        self.ordinary = self.root / "instrumentation.log"
        self.source.write_text(json.dumps(source))
        self.discovery.write_text(json.dumps(discovery))
        self.sums.write_text("".join(f"{v}  apks/{k}\n" for k, v in apk.items()))
        self.ordinary.write_text(self.log([x for x in names if x != shards.SSID_DESCRIPTION], statuses))

    def index(self, isolated=None, host=1):
        return result.build_index(self.source, self.discovery, self.sums, self.ordinary, isolated, self.discovery_log, host)

    def test_failed_forty_keeps_35_pass_5_fail_and_never_accepts(self):
        self.inputs(statuses={x: -2 for x in self.names[:5]})
        index = self.index()
        self.assertEqual({"passed": 35, "failed": 5}, index["counts"])
        self.assertTrue(index["complete"])
        self.assertFalse(index["accepted"])
        self.assertFalse(index["aggregateAcceptance"])
        failures = [x for x in index["tests"] if x["status"] == "failed"]
        self.assertTrue(all(x["firstException"].endswith("original failure") and x["lineSpan"][0] < x["lineSpan"][1] for x in failures))
        self.assertTrue(all(x["durationSeconds"] is None for x in index["tests"]))

    def test_error_ignored_assumption_and_active_are_not_pass(self):
        self.inputs(statuses=dict(zip(self.names[:3], [-1, -3, -4])))
        index = self.index()
        self.assertEqual({"error": 1, "ignored": 1, "assumption": 1, "passed": 37}, index["counts"])
        text = self.ordinary.read_text()
        cut = text.rfind("INSTRUMENTATION_STATUS_CODE: 1") + len("INSTRUMENTATION_STATUS_CODE: 1")
        self.ordinary.write_text(text[:cut] + "\n")
        index = self.index(host=124)
        self.assertEqual(1, index["counts"]["active"])
        self.assertFalse(index["complete"])
        self.assertEqual(124, index["hostExitCode"])

    def test_synthetic_combined_and_exact_discovery_log_are_quarantined(self):
        self.inputs()
        self.ordinary.write_bytes(self.discovery_log.read_bytes())
        self.assertEqual({"unknown": 40}, self.index()["counts"])
        self.ordinary.write_text(self.log(self.names) + self.log(self.names))
        index = self.index()
        self.assertFalse(index["complete"])
        self.assertEqual({"unknown": 40}, index["counts"])

    def test_duplicate_malformed_and_mixed_identity_are_incomplete(self):
        self.inputs()
        self.ordinary.write_text(self.log(self.names).replace("INSTRUMENTATION_STATUS: id=", "INSTRUMENTATION_STATUS: id=bad\nINSTRUMENTATION_STATUS: id=", 1))
        self.assertFalse(self.index()["complete"])
        self.ordinary.write_text(self.log(self.names).replace("STATUS_CODE: 0", "STATUS_CODE: nonsense", 1))
        malformed = self.index()
        self.assertFalse(malformed["complete"])
        self.assertNotIn("passed", [x["status"] for x in malformed["tests"] if x["description"] == self.names[0]])
        state = json.loads(self.source.read_text())
        state["sourceRevision"] = "f" * 40
        self.source.write_text(json.dumps(state))
        self.assertIn("mismatch", self.index()["errors"][0])

    def test_ssid_requires_separate_invocation_and_ordinary_pass(self):
        names = sorted([shards.SSID_DESCRIPTION] + [f"normal{i}(example.Tests)" for i in range(50)
                        if shards.shard_for(f"normal{i}(example.Tests)") == shards.shard_for(shards.SSID_DESCRIPTION)])
        ordinary = next(x for x in names if x != shards.SSID_DESCRIPTION)
        self.inputs(names, {ordinary: -2})
        index = self.index()
        self.assertEqual("unstarted", next(x for x in index["tests"] if x["description"] == shards.SSID_DESCRIPTION)["status"])
        self.assertFalse(index["complete"])
        isolated = self.root / "ssid-device/instrumentation.log"
        isolated.parent.mkdir()
        isolated.write_text(self.log([shards.SSID_DESCRIPTION]))
        self.assertTrue(any("before ordinary" in x for x in self.index(isolated)["errors"]))
        self.inputs(names)
        complete = self.index(isolated)
        self.assertTrue(complete["complete"])
        scoped = next(x for x in complete["tests"] if x["description"] == shards.SSID_DESCRIPTION)
        self.assertTrue(scoped["log"].endswith("ssid-device/instrumentation.log"))
        self.assertEqual(shards.sha(isolated), scoped["logSha256"])
        self.assertFalse(self.index(isolated)["accepted"])

    def test_cli_writes_json_and_markdown_on_missing_source(self):
        script = Path(result.__file__)
        output = self.root / "index.json"
        import sys
        command = [sys.executable, str(script)]
        for key, value in dict(source=self.root / "missing", discovery=self.root / "missing", sums=self.root / "missing",
                               **{"ordinary-log": self.root / "missing", "discovery-log": self.root / "missing",
                                  "output": output, "markdown": self.root / "index.md"}).items():
            command += ["--" + key, str(value)]
        completed = subprocess.run(command, capture_output=True, check=True)
        index = json.loads(output.read_text())
        self.assertFalse(index["complete"])
        self.assertFalse(index["accepted"])
        self.assertTrue(index["errors"])
        self.assertIn(b"never acceptance", completed.stdout)

    def test_decoded_duration_is_observed_only_and_raw_missing_log_is_unknown(self):
        self.inputs()
        lines = self.ordinary.read_text().splitlines()
        seconds = 0
        stamped = []
        for line in lines:
            if line.startswith("INSTRUMENTATION_STATUS_CODE:"):
                seconds += 1
            stamped.append(f"2026-10-27T00:{seconds // 60:02}:{seconds % 60:02}Z " + line)
        self.ordinary.write_text("\n".join(stamped) + "\n")
        self.assertEqual(1, self.index()["slowest"][0]["durationSeconds"])
        self.ordinary.unlink()
        index = self.index()
        self.assertEqual({"unknown": 40}, index["counts"])
        self.assertFalse(index["complete"])

    def test_malformed_json_shapes_still_return_an_incomplete_index(self):
        self.inputs()
        original = self.source.read_text()
        self.source.write_text("[]")
        self.assertTrue(self.index()["errors"])
        state = json.loads(original)
        state["harnessIdentity"] = ["malformed"]
        self.source.write_text(json.dumps(state))
        index = self.index()
        self.assertFalse(index["complete"])
        self.assertFalse(index["accepted"])
        self.assertIn("must be an object", index["errors"][0])

    def test_actual_exit_hook_preserves_failure_even_if_indexer_fails(self):
        bash = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
                if os.name == "nt" else shutil.which("bash"))
        if not bash or not Path(bash).is_file():
            self.skipTest("bash unavailable")
        shell = Path(__file__).with_name("emulator_acceptance.sh").read_text()
        start = shell.index("write_result_index() {")
        hook = shell[start:shell.index("\ntest -f", start)]
        setup = 'phase=remaining; shard_count=8; diagnostics="' + self.root.as_posix() + '"; '
        setup += 'timeout() { shift; "$@"; }; python3() { return 7; };\n'
        for status in (0, 1, 124, 130, 143):
            completed = subprocess.run([bash, "-c", setup + hook + f"\nexit {status}"], capture_output=True)
            self.assertEqual(status, completed.returncode)


if __name__ == "__main__":
    unittest.main()
