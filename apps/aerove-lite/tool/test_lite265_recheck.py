import copy
import os
import unittest
from unittest.mock import patch

import lite265_recheck as recheck


class Original265BoundaryTests(unittest.TestCase):
    def api(self):
        identity = recheck.IDENTITY
        run = dict(id=int(identity['workflowRun']), run_attempt=1, head_sha=identity['sourceRevision'],
                   head_branch='codex/lite-shared-flutter-complete114', path='.github/workflows/aerove-lite.yaml',
                   status='completed', conclusion='failure', repository=dict(full_name='IvanSolis1989/Aerove'))
        jobs = dict(total_count=len(recheck.PRODUCERS), jobs=[dict(id=key, name=name, run_id=run['id'],
                    run_attempt=1, head_sha=run['head_sha'], status='completed', conclusion='success')
                    for key, name in recheck.PRODUCERS.items()])
        row = dict(recheck.ARCHIVE, expired=False, workflow_run=dict(id=run['id'], repository_id=1429390079,
                   head_repository_id=1429390079, head_branch=run['head_branch'], head_sha=run['head_sha']))
        return run, jobs, row

    def test_only_fixed_producer_can_be_rechecked(self):
        recheck.check_api(*self.api())
        for kind in ('attempt', 'producer', 'artifact', 'digest', 'repository'):
            run, jobs, row = copy.deepcopy(self.api())
            if kind == 'attempt':
                run['run_attempt'] = 2
            elif kind == 'producer':
                jobs['jobs'][0]['conclusion'] = 'failure'
            elif kind == 'artifact':
                row['id'] += 1
            elif kind == 'digest':
                row['digest'] = 'sha256:' + '0' * 64
            else:
                row['workflow_run']['repository_id'] = 1
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                recheck.check_api(run, jobs, row)

    def test_opt_in_does_not_change_normal_runtime_and_rejects_mixed_or_wrong_branch(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(recheck.enabled())
        env = dict(AEROVE_LITE265_RECHECK='1', GITHUB_REPOSITORY='IvanSolis1989/Aerove',
                   GITHUB_ACTOR='IvanSolis1989',
                   GITHUB_REF='refs/heads/codex/lite265-original-recheck',
                   GITHUB_EVENT_NAME='workflow_dispatch', AEROVE_GUEST_ANGLE='1')
        with patch.dict(os.environ, env, clear=True):
            self.assertTrue(recheck.enabled())
        surface = dict(env, GITHUB_REF='refs/heads/codex/lite37-startup-and-render', AEROVE_LITE265_SURFACE_PROBE='1')
        with patch.dict(os.environ, surface, clear=True):
            self.assertTrue(recheck.enabled())
        for invalid in (dict(surface, GITHUB_REF=env['GITHUB_REF']), dict(env, AEROVE_LITE265_SURFACE_PROBE='1'),
                        dict(env, GITHUB_REF=surface['GITHUB_REF'])):
            with patch.dict(os.environ, invalid, clear=True), self.assertRaises(ValueError):
                recheck.enabled()
        for change in (dict(GITHUB_REF='refs/heads/main'), dict(AEROVE_GUEST_ANGLE='0'),
                       dict(AEROVE_ORIGINAL_RUNTIME_PROOF='other.json'), dict(AEROVE_BETA14_PROFILE='1')):
            with patch.dict(os.environ, dict(env, **change), clear=True), self.assertRaises(ValueError):
                recheck.enabled()


if __name__ == '__main__':
    unittest.main()
