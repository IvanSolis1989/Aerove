"""Pin the declared Flutter matrix to its strict aggregate admission."""
import ast
import json
import os
import tempfile
from unittest.mock import patch
import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / '.github/workflows/aerove-lite.yaml'
STEP = 'Execute subscription source transactions and affected shared client regressions'


class FlutterHostWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        jobs = yaml.safe_load(WORKFLOW.read_text())['jobs']
        cls.jobs = jobs
        cls.step = next(s for s in jobs['flutter_host_preflight']['steps'] if s.get('name') == STEP)
        script = cls.step['run']
        match = re.search(r'^suites=\(\n(.*?)^\)', script, re.M | re.S)
        cls.suites = [line.strip() for line in match[1].splitlines()]
        aggregate = script.rsplit("python3 - <<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
        tree = ast.parse(aggregate)
        cls.assertions = [n for n in tree.body if isinstance(n, ast.Assert)]
        cls.admission = compile(ast.Module(body=cls.assertions, type_ignores=[]), str(WORKFLOW), 'exec')
        count = cls.assertions[0].test.values[0]
        cls.expected_count = count.comparators[0].value

    def successful_results(self):
        return {path.replace('/', '_'): dict(testExit=0, logExit=0, allCasesPassed=True)
                for path in self.suites}

    def test_declared_files_and_strict_count_agree(self):
        self.assertEqual(len(self.suites), len(set(self.suites)))
        self.assertEqual(self.expected_count, len(self.suites))
        for path in self.suites:
            self.assertTrue((ROOT / 'apps/aerove/test' / path).is_file(), path)
        self.assertIn('features/custom_quick_edit_test.dart', self.suites)
        self.assertIn('widgets/edit_profile_focus_test.dart', self.suites)
        exec(self.admission, {'results': self.successful_results(), 'expected': set(self.successful_results())})

    def test_missing_or_extra_suite_cannot_pass(self):
        missing = self.successful_results()
        missing.pop(next(iter(missing)))
        extra = self.successful_results()
        extra['unrequested_test.dart'] = dict(testExit=0, logExit=0, allCasesPassed=True)
        for results in (missing, extra):
            with self.subTest(size=len(results)), self.assertRaises(AssertionError):
                exec(self.admission, {'results': results, 'expected': set(self.successful_results())})

    def test_failed_test_log_or_pixel_cannot_pass(self):
        for change in ({'testExit': 1}, {'logExit': 1}, {'allCasesPassed': False}, {'pixelReadback': False}):
            results = self.successful_results()
            results[next(iter(results))].update(change)
            with self.subTest(change=change), self.assertRaises(AssertionError):
                exec(self.admission, {'results': results, 'expected': set(self.successful_results())})

    def test_contract_runs_before_android_producers(self):
        checks = next(s for s in self.jobs['verify']['steps']
                      if s.get('name') == 'Source preparation and APK inspector regression tests')
        self.assertIn('python3 apps/aerove-lite/tool/test_flutter_host_workflow.py', checks['run'])
        for job in ('build_arm', 'build_x86'):
            self.assertEqual(self.jobs[job]['needs'], ['flutter_host_preflight', 'verify'])

    def test_same_count_wrong_suite_cannot_pass(self):
        results = self.successful_results()
        results['unrequested_test.dart'] = results.pop(next(iter(results)))
        with self.assertRaises(AssertionError):
            exec(self.admission, {'results': results, 'expected': set(self.successful_results())})

    def host_steps(self):
        return self.jobs['flutter_host_preflight']['steps']

    def test_collection_conditions_and_producer_dependencies(self):
        steps = {s['id']: s for s in self.host_steps() if 'id' in s}
        dependencies = {
            'rule_recovery': [], 'profile_recovery': [], 'kotlin_prefetch': [],
            'host_compile': ['kotlin_prefetch'], 'migration': ['host_compile'],
            'dns_geo': [], 'builder_fixture': ['host_compile'],
            'builder_consumer': ['builder_fixture'], 'memory_tests': [],
            'selection_policy': [], 'profile_cancel': [],
            'runner_graph': ['kotlin_prefetch'], 'initial_theme': [],
        }
        for name, producers in dependencies.items():
            required = ['host_collection_ready'] + producers
            expression = "${{ !cancelled() && " + ' && '.join(
                "steps." + dep + ".outcome == 'success'" for dep in required) + " }}"
            self.assertEqual(steps[name]['if'], expression, name)
            # Evaluate the actual constrained expression for unrelated failure,
            # cancellation and absent/unsuccessful prerequisite outcomes.
            def eligible(outcomes, cancelled=False):
                body = steps[name]['if'][3:-2].strip().replace('!cancelled()', str(not cancelled))
                body = re.sub(r"steps\.(\w+)\.outcome", lambda m: repr(outcomes.get(m[1], '')), body)
                return eval(body.replace('&&', 'and'), {'__builtins__': {}})
            success = dict.fromkeys(required, 'success')
            self.assertTrue(eligible(dict(success, subscription_tests='failure')), name)
            self.assertFalse(eligible(success, cancelled=True), name)
            for dependency in required:
                for state in ('failure', 'skipped', 'cancelled', None):
                    outcomes = dict(success)
                    if state is None: outcomes.pop(dependency)
                    else: outcomes[dependency] = state
                    self.assertFalse(eligible(outcomes), (name, dependency, state))
        self.assertNotIn('if', steps['migration_upload'])
        self.assertNotIn('if', steps['host_collection_ready'])
        self.assertEqual(steps['compile_scope']['if'], 'always()')
        self.assertIn('assert not apks', steps['compile_scope']['run'])
        self.assertIn('mkdir -p', steps['selection_policy']['run'])
        self.assertNotIn('continue-on-error', self.jobs['flutter_host_preflight'])
        for step in self.host_steps(): self.assertNotIn('continue-on-error', step)

    def test_final_gate_rejects_every_non_success_or_missing_outcome(self):
        gate = next(s for s in self.host_steps() if s.get('name') ==
                    'Require every Flutter host verification gate to succeed')
        self.assertEqual(gate['if'], 'always()')
        self.assertNotIn('toJSON(steps)', gate['env']['HOST_OUTCOMES'])
        self.assertNotIn('.outputs', gate['env']['HOST_OUTCOMES'])
        code = gate['run'].split("python3 - <<'PYHOST'\n", 1)[1].rsplit('\nPYHOST', 1)[0]
        tree = ast.parse(code)
        declaration = next(n for n in tree.body if isinstance(n, ast.Assign)
                           and isinstance(n.targets[0], ast.Name) and n.targets[0].id == 'required')
        required = declaration.value.func.value.value.split()
        self.assertEqual(set(required), {s['id'] for s in self.host_steps() if 'id' in s})
        self.assertEqual(len(required), len(set(required)))
        compiled = compile(code, str(WORKFLOW), 'exec')
        good = dict.fromkeys(required, 'success')
        self.assertEqual(json.loads(gate['env']['HOST_OUTCOMES']),
                         {name: '${{ steps.' + name + '.outcome }}' for name in required})
        with tempfile.TemporaryDirectory() as directory:
            environment = dict(RUNNER_TEMP=directory, GITHUB_SHA='a'*40,
                               GITHUB_RUN_ID='1', GITHUB_RUN_ATTEMPT='1')
            for name in required:
                for state in ('failure', 'skipped', 'cancelled', None):
                    rows = dict(good)
                    if state is None: rows.pop(name)
                    else: rows[name] = state
                    with patch.dict(os.environ, dict(environment, HOST_OUTCOMES=json.dumps(rows)), clear=True):
                        with self.assertRaises(AssertionError): exec(compiled, {})
                    receipt = json.loads((Path(directory)/'lite-flutter-host/required-gates.json').read_text())
                    self.assertFalse(receipt['passed'])
            with patch.dict(os.environ, dict(environment, HOST_OUTCOMES=json.dumps(good)), clear=True):
                exec(compiled, {})
            self.assertTrue(json.loads((Path(directory)/'lite-flutter-host/required-gates.json').read_text())['passed'])

    def test_downstream_admission_and_budget_are_unchanged(self):
        self.assertEqual(self.jobs['flutter_host_preflight']['timeout-minutes'], 50)
        for name in ('build_arm', 'build_x86'):
            job = self.jobs[name]
            self.assertEqual(job['needs'], ['flutter_host_preflight', 'verify'])
            self.assertNotRegex(job['if'], r'(always|failure|cancelled|success)\s*\(')
            self.assertNotIn('continue-on-error', job)
        for name in ('flutter_ui_preview', 'acceptance'):
            self.assertEqual(self.jobs[name]['needs'], ['build_x86'])
            self.assertNotRegex(self.jobs[name]['if'], r'(always|failure|cancelled|success)\s*\(')
        candidate = self.jobs['apk_only_candidate']
        self.assertIn('flutter_host_preflight', candidate['needs'])
        self.assertNotRegex(candidate['if'], r'(always|failure|cancelled|success)\s*\(')
        self.assertIn("all(row['result'] == 'success'", candidate['steps'][0]['run'])


if __name__ == '__main__':
    unittest.main()
