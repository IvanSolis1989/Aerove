"""Synthetic one-method protocol/identity checks; no APK or frame execution."""
import copy
import json
import shlex
from pathlib import Path
import tempfile
import unittest
from unittest import mock

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

    def test_lite158_retains_failed_terminal_without_acceptance_or_passed_tests(self):
        spec = copy.deepcopy(self.spec)
        spec['samples'] = {'solid': spec['samples']['solid']}
        proof = dict(self.proof, runtimeTarget='lite158-frame-sample')
        source = dict(sample.context(spec, proof, 'solid', spec['samples']['solid']['selector']),
                      phase='original-sample', runtimeTarget='lite158-frame-sample', testShard=None)
        source['originalAppIdentity'] = spec['identity']
        selection, trace = self.frame_provenance(source)
        proof.update(harnessIdentity=source['harnessIdentity'], deviceSerial=source['deviceSerial'], rendererBackend=source['rendererBackend'])
        self.log.write_text(self.text(code=-2, summary=False))
        result = sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        self.assertTrue(result['selectionVerified'])
        self.assertEqual(('failed', -2, []), (result['originalTestStatus'], result['originalStatusCode'], result['passedTests']))
        self.assertFalse(result['accepted'])
        self.assertFalse(result['aggregateAcceptance'])
        source['originalAppIdentity'] = spec['identity']
        source_path, output = self.log.with_name('SOURCE.json'), self.log.with_name('SAMPLE-RESULT.json')
        source_path.write_text(json.dumps(source))
        with mock.patch('emulator_current.validate_renderer_proof', return_value=proof), \
                mock.patch('emulator_original84.runtime_spec', return_value=spec), \
                mock.patch.dict('os.environ', AEROVE_CURRENT_RENDERER_PROOF='synthetic-proof',
                    AEROVE_ORIGINAL_SAMPLE='solid', AEROVE_ORIGINAL_SAMPLE_SELECTOR=spec['samples']['solid']['selector']), \
                mock.patch('sys.argv', ['sample', '--source', str(source_path), '--log', str(self.log), '--output', str(output),
                    '--selection', str(selection), '--trace-receipt', str(trace)]):
            self.assertEqual(1, sample.main())
            self.assertEqual('failed', json.loads(output.read_text())['originalTestStatus'])
            self.log.write_text(self.text(code=-1, summary=False))
            self.assertEqual(1, sample.main())
            self.assertEqual('error', json.loads(output.read_text())['originalTestStatus'])
            self.assertFalse(json.loads(output.read_text())['accepted'])
            source_path.write_text(json.dumps(dict(source, runtimeTarget=sample.TARGET)))
            with self.assertRaisesRegex(ValueError, 'registry/proof'):
                sample.main()
        for value in (self.text('clear', code=-2, summary=False), self.text(code=-3, summary=False),
                      self.text(code=-2, summary=False) + self.text(code=-2, summary=False),
                      self.text(code=-2, summary=False).replace('INSTRUMENTATION_CODE: -1', '')):
            self.log.write_text(value)
            with self.subTest(value=value[:90]), self.assertRaises(ValueError):
                sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        self.log.write_text(self.text(summary=False))
        self.assertEqual([], sample.verify(source, self.log, discovery=True)['passedTests'])

    def test_lite158_cannot_label_the_original_log_only_protocol_as_execution(self):
        source = dict(self.source(), runtimeTarget=sample.FRAME_TARGET)
        self.log.write_text(self.text())
        with self.assertRaisesRegex(ValueError, 'provenance'):
            sample.verify(source, self.log)

    def frame_provenance(self, source):
        source.update(sourceRevision=self.spec['identity']['sourceRevision'], deviceSerial='emulator-5554',
            rendererBackend='SwANGLE (ANGLE + SwiftShader)', testTimeoutMillis=120000,
            harnessIdentity=dict(sourceRevision='d' * 40, workflowRun='900'), harnessRevision='d' * 40, harnessRun='900')
        discovery = self.log.with_name('sample-discovery.log')
        discovery.write_text(self.text())
        selection = self.log.with_name('SAMPLE-SELECTION.json')
        selection.write_text(json.dumps(sample.verify(source, discovery, discovery=True)))
        trace = self.log.parent / 'clear-trace/TRACE-RECEIPT.json'
        trace.parent.mkdir(exist_ok=True)
        command = ['adb', '-s', source['deviceSerial'], 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'debug', 'false', '-e', 'timeout_msec', '120000', '-e', 'class', shlex.quote(source['originalSample']['selector']),
            '-e', 'fixtureUrl', 'http://10.0.2.2:18080', '-e', 'sourceRevision', source['sourceRevision'],
            '-e', 'isolatedBackgroundPolicy', 'true', '-e', 'deviceKind', 'CI-emulator',
            '-e', 'rendererBackend', shlex.quote(source['rendererBackend']),
            'dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner']
        pid_command = ['adb', '-s', source['deviceSerial'], 'shell', 'pidof', 'dev.aerove.lite']
        trace.write_text(json.dumps(dict(schema=1, diagnosticOnly=True, accepted=False, performanceEvidence=False,
            deviceSerial=source['deviceSerial'], targetPackage='dev.aerove.lite', traceStarted=True, tracePulled=True,
            instrumentationCommand=command, instrumentationReturnCode=0, wrapperReturnCode=0, interruptSignals=[],
            instrumentationStartedHostMonotonicNanos=10, instrumentationFinishedHostMonotonicNanos=30, errors=[],
            commands=[dict(command=pid_command, hostMonotonicEndNanos=1),
                      dict(command=pid_command, hostMonotonicStartNanos=20, hostMonotonicEndNanos=21)])))
        return selection, trace

    def test_lite158_execution_rejects_discovery_reuse_and_cross_task_or_log_only_trace(self):
        source = dict(self.source(), runtimeTarget=sample.FRAME_TARGET)
        selection, trace = self.frame_provenance(source)
        original_trace = json.loads(trace.read_text())
        self.log.write_text(self.text())
        with self.assertRaisesRegex(ValueError, 'reused'):
            sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        self.log.write_text(self.text(code=-2, summary=False))
        for change in ({'deviceSerial': 'emulator-5556'}, {'traceStarted': False}, {'instrumentationSkipped': True},
                       {'instrumentationReturnCode': 124}, {'interruptSignals': [15]},
                       {'instrumentationFinishedHostMonotonicNanos': 0}):
            trace.write_text(json.dumps(dict(original_trace, **change)))
            with self.subTest(change=change), self.assertRaises(ValueError):
                sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        for extra in (['-e', 'log', 'true'], ['-e', 'logOnly', 'true'], ['-e', 'class', 'another.Test#method']):
            changed = copy.deepcopy(original_trace)
            changed['instrumentationCommand'][-1:-1] = extra
            trace.write_text(json.dumps(changed))
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        for key, value in (('command', ['adb', '-s', 'emulator-5556', 'shell', 'pidof', 'dev.aerove.lite']),
                           ('command', ['adb', '-s', source['deviceSerial'], 'shell', 'pidof', 'another.app']),
                           ('hostMonotonicStartNanos', 0)):
            changed = copy.deepcopy(original_trace)
            changed['commands'][1][key] = value
            trace.write_text(json.dumps(changed))
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        trace.write_text(json.dumps(original_trace))
        chosen = json.loads(selection.read_text())
        selection.write_text(json.dumps(dict(chosen, source=dict(source, harnessRun='another-run'))))
        with self.assertRaisesRegex(ValueError, 'source/hash'):
            sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        selection.write_text(json.dumps(dict(chosen, logSha256='0' * 64)))
        with self.assertRaisesRegex(ValueError, 'source/hash'):
            sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        selection.write_text(json.dumps(chosen))
        trace.write_text(json.dumps(dict(original_trace, tracePulled=False, errors=[{'stage': 'pull-trace', 'error': 'synthetic'}])))
        result = sample.verify(source, self.log, selection=selection, trace_receipt=trace)
        self.assertEqual('failed', result['originalTestStatus'])
        self.assertFalse(result['executionProvenance']['tracePulled'])

    def test_original_sample_host_budget_keeps_method_and_remaining_deadlines(self):
        script = Path(__file__).with_name("emulator_acceptance.sh").read_text()
        self.assertIn('if [[ "$phase" == original-sample ]]; then remaining_timeout=(timeout --signal=TERM --kill-after=20s 240s); fi', script)
        self.assertIn('remaining_timeout=(timeout --signal=TERM --kill-after=20s 1200s)', script)
        self.assertIn('test_timeout_msec=120000', script)
        self.assertIn('"deadlineSeconds":240,"timeoutSignal":"TERM","killAfterSeconds":20,"guestCancellationConfirmed":false', script)
        self.assertIn('"deadlineSeconds":1200,"guestCancellationConfirmed":false', script)


if __name__ == "__main__":
    unittest.main()
