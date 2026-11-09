import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


producer = module('candidate_identity')
verifier = module('verify_apk')


class CandidateIdentityTests(unittest.TestCase):
    def identity(self):
        return dict(schema=1, product='aerove-lite', source='a'*40, tree='b'*40,
                    runId='123', runNumber='350', attempt='2', abi='arm64-v8a')

    def verify(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                for name, content in rows:
                    archive.writestr(name, content)
            return verifier.verify_candidate_identity(apk, 'arm64-v8a', 'a'*40, 'b'*40,
                'https://github.com/IvanSolis1989/Aerove/actions/runs/123', 350, 2)

    def test_actual_asset_matches_original_workflow(self):
        self.assertEqual(self.identity(), self.verify([(producer.ASSET, json.dumps(self.identity()))]))

    def test_missing_oversized_and_duplicate_identity_rejected(self):
        for rows in ([], [(producer.ASSET, ' '*4097)], [(producer.ASSET, '{}'), (producer.ASSET, '{}')],
                     [(producer.ASSET, '{"schema":1,"schema":1}')]):
            with self.subTest(rows=str(rows)[:80]), self.assertRaises(ValueError):
                self.verify(rows)

    def test_every_identity_field_is_bound(self):
        for key, replacement in dict(schema=2, product='standard', source='c'*40, tree='d'*40,
                runId='124', runNumber='351', attempt='3', abi='x86_64').items():
            value = self.identity(); value[key] = replacement
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.verify([(producer.ASSET, json.dumps(value))])

    def test_no_ci_never_fabricates_identity(self):
        self.assertFalse(producer.generate(Path('.'), 'arm64-v8a', '', {})['identified'])

    def test_invalid_ci_never_falls_back(self):
        with self.assertRaises(ValueError):
            producer.generate(Path('.'), 'arm64-v8a', 'a'*40, {'GITHUB_ACTIONS': 'true'})

    def test_checkout_and_original_run_are_bound_without_workflow_ref_confusion(self):
        env = dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY='IvanSolis1989/Aerove',
                   GITHUB_SHA='c'*40, GITHUB_RUN_ID='123', GITHUB_RUN_NUMBER='350', GITHUB_RUN_ATTEMPT='2')
        with patch.object(subprocess, 'check_output', side_effect=['a'*40, 'b'*40]):
            self.assertEqual(self.identity(), producer.generate(Path('.'), 'arm64-v8a', 'a'*40, env))
        with patch.object(subprocess, 'check_output', return_value='c'*40), self.assertRaises(ValueError):
            producer.generate(Path('.'), 'arm64-v8a', 'a'*40, env)


if __name__ == '__main__':
    unittest.main()
