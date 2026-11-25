import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from uuid import uuid4
import zipfile

from consent_gate import CLASS, RESTART, failed_theme, persisted_restart
from run_vpn_diagnostic import verify_wire


class OriginalEvidence(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        archive = Path(os.environ.get("AEROVE_ORIGINAL_DIAGNOSTIC_ZIP", "official-373-diag.zip"))
        if not archive.is_file():
            self.skipTest("Original #373 diagnostic archive not supplied")
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(),
                         "56ec79e4d2e2b5d4c06af726074408b82fcf6153eb78ea8b8288f6af1ae6bac6")
        entries = ("BUILD_INFO.json", "consentNavigationTheme12/receipt.json", "consentNavigationTheme12.out")
        with zipfile.ZipFile(archive) as z:
            for name in entries:
                target = self.dir / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(name))
        self.info = json.loads((self.dir / entries[0]).read_text())
        self.receipt_path = self.dir / entries[1]
        self.row = json.loads(self.receipt_path.read_bytes())
        self.token = self.row["qaToken"]
        self.log = self.dir / entries[2]

    def check_first(self):
        return failed_theme(self.receipt_path.parent, self.log, self.token, self.info)

    def save(self):
        self.receipt_path.write_text(json.dumps(self.row))

    def test_exact_373_failure_retained(self):
        proof = self.check_first()
        self.assertEqual(proof["receiptSha256"], "2d16d3850c92ebbab312da175a89d7e468fb23bd8bbdac5f9a54f57903a0e308")
        self.assertTrue(proof["themeStillFailed"])

    def test_wrong_token(self):
        self.token = "qa-" + uuid4().hex
        with self.assertRaisesRegex(ValueError, "Wrong receipt identity"):
            self.check_first()

    def test_wrong_application_hash(self):
        self.info["apk_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "Wrong receipt identity"):
            self.check_first()

    def test_first_launch_failure_refused(self):
        self.row["stage"] = "first-launch"
        self.save()
        with self.assertRaisesRegex(ValueError, "later visual"):
            self.check_first()

    def test_explicitly_red_not_labelled_pass(self):
        self.row["scenarioPassed"] = True
        self.save()
        with self.assertRaisesRegex(ValueError, "later visual"):
            self.check_first()

    def test_action_not_observed(self):
        self.row["actions"] = [a for a in self.row["actions"] if a.get("detail") != "Got it"]
        self.save()
        with self.assertRaisesRegex(ValueError, "completed consent"):
            self.check_first()

    def test_crash_log_prevents_continuation(self):
        self.log.write_text(self.log.read_text() + "\nINSTRUMENTATION_ABORTED: Process crashed.\n")
        with self.assertRaisesRegex(ValueError, "crashed"):
            self.check_first()

    def test_unrelated_selector_prevents_continuation(self):
        self.log.write_text(self.log.read_text().replace("test=consentNavigationTheme12", "test=other"))
        with self.assertRaisesRegex(ValueError, "instrumentation identity"):
            self.check_first()

    def test_linked_receipt_refused(self):
        copied = self.dir / "linked"
        copied.symlink_to(self.receipt_path.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Invalid diagnostic evidence file"):
            failed_theme(copied, self.log, self.token, self.info)

    def restart_self(self):
        prior = self.check_first()
        restart_token = "qa-" + uuid4().hex
        restart_dir = self.dir / RESTART
        restart_dir.mkdir()
        hierarchy_file = restart_dir / "persisted-consent-hierarchy.json"
        hierarchy = [dict(package="dev.aerove.lite", visible=True, text="Dashboard")]
        hierarchy_file.write_text(json.dumps(hierarchy))
        receipt = {**self.row, "qaToken": restart_token, "status": "captured", "scenarioPassed": True,
                   "stage": "restart-consent-observed", "pid": prior["pid"] + 1,
                   "finishedElapsedRealtimeMs": prior["finished"] + 1,
                   "frames": [dict(label="persisted-consent", hierarchy=hierarchy_file.name,
                                   hierarchySha256=hashlib.sha256(hierarchy_file.read_bytes()).hexdigest())]}
        return restart_dir, restart_token, prior, receipt, hierarchy_file

    def test_restart_positive_control(self):
        directory, token, prior, receipt, _ = self.restart_self()
        (directory / "receipt.json").write_text(json.dumps(receipt))
        proof = persisted_restart(directory, token, self.info, prior, receipt)
        self.assertNotEqual(proof["pid"], prior["pid"])

    def test_restart_pid_reused(self):
        directory, token, prior, receipt, _ = self.restart_self()
        receipt["pid"] = prior["pid"]
        (directory / "receipt.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "fresh process"):
            persisted_restart(directory, token, self.info, prior, receipt)

    def test_restart_consent_visible(self):
        directory, token, prior, receipt, hierarchy = self.restart_self()
        nodes = json.loads(hierarchy.read_text()) + [dict(package="dev.aerove.lite", visible=True, text="Agree")]
        hierarchy.write_text(json.dumps(nodes))
        receipt["frames"][0]["hierarchySha256"] = hashlib.sha256(hierarchy.read_bytes()).hexdigest()
        (directory / "receipt.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "consent screen"):
            persisted_restart(directory, token, self.info, prior, receipt)

    def test_restart_hierarchy_tampering(self):
        directory, token, prior, receipt, hierarchy = self.restart_self()
        (directory / "receipt.json").write_text(json.dumps(receipt))
        hierarchy.write_text("{}")
        with self.assertRaisesRegex(ValueError, "hierarchy changed"):
            persisted_restart(directory, token, self.info, prior, receipt)

    def test_restart_requires_original_verifier_result(self):
        directory, token, prior, receipt, _ = self.restart_self()
        (directory / "receipt.json").write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "verifier-backed"):
            persisted_restart(directory, token, self.info, prior, dict(receipt, status="fake"))


class Wire(unittest.TestCase):
    def test_three_distinct_traffic_routes_require_live_fixture_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            request = [dict(stage=stage, requestToken=str(uuid4()), responseSha256="1" * 64)
                       for stage in ("vpn-active", "background-active", "resumed-active")]
            profile = str(uuid4())
            fixture_log = out / "fixture.err"
            lines = [f'127.0.0.1 - - [time] "GET /{r["requestToken"]} HTTP/1.0" 200 -'
                     for r in request]
            fixture_log.write_text("\n".join(lines) + "\n")
            events = out / "profile-events.jsonl"
            events.write_text(json.dumps({"token": profile, "event": "complete"}) + "\n")
            verify_wire(out, dict(networkRequests=request), profile)
            fixture_log.write_text("\n".join(lines[:-1]) + "\n")
            with self.assertRaisesRegex(ValueError, "matching socket requests"):
                verify_wire(out, dict(networkRequests=request), profile)
            fixture_log.write_text("\n".join(lines) + "\n")
            events.write_text(json.dumps({"token": profile, "event": "headers"}) + "\n")
            with self.assertRaisesRegex(ValueError, "completed profile fetch"):
                verify_wire(out, dict(networkRequests=request), profile)


if __name__ == "__main__":
    unittest.main()
