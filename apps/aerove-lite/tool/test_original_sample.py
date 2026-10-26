"""Synthetic one-method protocol/identity checks; no APK or frame execution."""
import copy
from pathlib import Path
import tempfile
import unittest

import original_sample as sample


class OriginalSampleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.log = Path(self.temp.name) / "instrumentation.log"
        self.spec = dict(identity=dict(sourceRevision="a" * 40, workflowRun="125"), apks={"app": "b" * 64, "test": "c" * 64}, samples={})
        for key in ("glyph", "solid", "clear"):
            test = "case" + ("" if key == "glyph" else "[" + key + "]")
            self.spec["samples"][key] = dict(className="fixture.Test", testName=test, selector="fixture.Test#" + test,
                                                  description=test + "(fixture.Test)")
        self.proof = dict(runtimeTarget=sample.TARGET, diagnosticOriginalRuntime=True, promotionAccepted=False,
                          originalAppIdentity=self.spec["identity"], apks=self.spec["apks"])

    def source(self, key="solid"):
        return dict(sample.context(self.spec, self.proof, key, self.spec["samples"][key]["selector"]),
                    phase="original-sample", runtimeTarget=sample.TARGET, testShard=None)

    def text(self, key="solid", code=0, summary=True):
        value = self.spec["samples"][key]
        lines = []
        for status in (1, code):
            fields = dict(id="AndroidJUnitRunner", numtests=1, current=1,
                          **{"class": value["className"], "test": value["testName"]})
            lines.extend(f"INSTRUMENTATION_STATUS: {name}={item}" for name, item in fields.items())
            lines.append(f"INSTRUMENTATION_STATUS_CODE: {status}")
        lines += ["INSTRUMENTATION_CODE: -1"]
        if summary:
            lines += ["OK (1 test)"]
        return "\n".join(lines) + "\n"

    def test_registry_is_unique_selector_source_and_rejects_class_fallback_or_other_identity(self):
        for key in ("glyph", "solid", "clear"):
            self.assertEqual(self.spec["samples"][key]["selector"], self.source(key)["originalSample"]["selector"])
        for key in ("all", "", None):
            with self.assertRaises(ValueError):
                sample.selected(self.spec, key)
        for field, bad in (("runtimeTarget", "lite94"), ("apks", {}), ("promotionAccepted", True), ("originalAppIdentity", {})):
            with self.subTest(field=field), self.assertRaises(ValueError):
                sample.context(self.spec, dict(self.proof, **{field: bad}), "solid", self.spec["samples"]["solid"]["selector"])
        with self.assertRaises(ValueError):
            sample.context(self.spec, self.proof, "solid", "fixture.Test")

    def test_log_only_single_parameter_does_not_claim_execution_or_promotion(self):
        self.log.write_text(self.text(summary=False))
        receipt = sample.verify(self.source(), self.log, discovery=True)
        self.assertEqual([], receipt["passedTests"])
        self.assertEqual([self.spec["samples"]["solid"]["description"]], receipt["descriptions"])
        self.assertFalse(receipt["promotionAccepted"])
        self.assertTrue(receipt["diagnosticOnly"])

    def test_execution_requires_exact_parameter_summary_and_complete_passing_terminal(self):
        self.log.write_text(self.text())
        receipt = sample.verify(self.source(), self.log)
        self.assertEqual(1, len(receipt["passedTests"]))
        self.assertFalse(receipt["aggregateAcceptance"])
        for text in (self.text("clear"), self.text(code=-2), self.text(code=-3), self.text(code=-4),
                     self.text(summary=False), self.text().replace("OK (1 test)", "OK (2 tests)"),
                     self.text().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"), self.text() + self.text()):
            self.log.write_text(text)
            with self.subTest(text=text[:100]), self.assertRaises(ValueError):
                sample.verify(self.source(), self.log)

    def test_sample_receipt_cannot_enter_shard_or_acceptance_identity(self):
        self.log.write_text(self.text())
        for field, bad in (("phase", "remaining"), ("runtimeTarget", "beta14"), ("diagnosticOnly", False),
                           ("promotionAccepted", True), ("testShard", {"numShards": 4})):
            with self.subTest(field=field), self.assertRaises(ValueError):
                sample.verify(dict(self.source(), **{field: bad}), self.log)

    def test_original_sample_host_budget_keeps_method_and_remaining_deadlines(self):
        script = Path(__file__).with_name("emulator_acceptance.sh").read_text()
        self.assertIn('if [[ "$phase" == original-sample ]]; then remaining_timeout=(timeout --signal=TERM --kill-after=20s 240s); fi', script)
        self.assertIn('remaining_timeout=(timeout --signal=TERM --kill-after=20s 1200s)', script)
        self.assertIn('test_timeout_msec=120000', script)
        self.assertIn('"deadlineSeconds":240,"timeoutSignal":"TERM","killAfterSeconds":20,"guestCancellationConfirmed":false', script)
        self.assertIn('"deadlineSeconds":1200,"guestCancellationConfirmed":false', script)


if __name__ == "__main__":
    unittest.main()
