"""Execute the Pages release gate with offline GitHub API fixtures."""

import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'IvanSolis1989/Aerove'
RELEASE_SOURCE = 'd966bbc2357c25f815bed45a5fa5fe2b69b985d0'
BUILD_SOURCE = 'e5fa0dc36f3fb49d18c23a1a3bb88bfed3f55a1b'
TAG = 'v0.11.1-beta.1+2026102300'


class PagesReleaseTest(unittest.TestCase):
    def setUp(self):
        workflow = (ROOT / '.github/workflows/pages.yaml').read_text(encoding='utf-8')
        script = workflow.split("python3 - <<'PY'\n", 1)[1].split('\n          PY', 1)[0]
        self.script = compile(textwrap.dedent(script), 'pages-release-gate', 'exec')
        self.event = dict(name='Aerove Android', path='.github/workflows/xclash-android.yaml',
                          head_sha=RELEASE_SOURCE, head_branch='main', event='workflow_dispatch',
                          status='completed', conclusion='success', id=103, run_attempt=1,
                          repository=dict(full_name=REPOSITORY), head_repository=dict(full_name=REPOSITORY),
                          created_at='2026-10-31T00:00:00Z')
        self.current = copy.deepcopy(self.event)
        self.build = dict(self.event, id=100, head_sha=BUILD_SOURCE, head_branch='codex/build')
        self.info = dict(source=BUILD_SOURCE, workflow=f'https://github.com/{REPOSITORY}/actions/runs/100',
                         version=TAG[1:], applicationId='com.follow.clash.dev',
                         signing='persistent Android test key', appEnv='pre')
        self.tag_source = RELEASE_SOURCE
        self.job_changes = {}
        self.release = dict(tag_name=TAG, draft=False, prerelease=True,
                            published_at='2026-10-31T01:00:00Z')
        self.extra_releases = []
        self.asset_change = lambda assets: None

    def execute_gate(self):
        # Metadata hashes are rebuilt after intentional BUILD_INFO mutations so
        # source failures exercise provenance checks, rather than digest checks.
        info_text = json.dumps(self.info)
        info_hash = hashlib.sha256(info_text.encode()).hexdigest()
        apk_hash = 'a' * 64
        sums = f'{apk_hash}  Aerove-android-arm64.apk\n{info_hash}  BUILD_INFO.json\n'
        assets = [dict(id=index, name=name, state='uploaded', size=size, digest='sha256:' + digest)
                  for index, name, size, digest in (
                      (1, 'Aerove-android-arm64.apk', 1024, apk_hash),
                      (2, 'BUILD_INFO.json', len(info_text.encode()), info_hash),
                      (3, 'SHA256SUMS', len(sums.encode()), hashlib.sha256(sums.encode()).hexdigest()))]
        self.asset_change(assets)

        def github(command, **kwargs):
            endpoint = command[2].removeprefix(f'repos/{REPOSITORY}/')
            if endpoint == 'actions/runs/103':
                response = self.current
            elif endpoint == 'actions/runs/100':
                response = self.build
            elif endpoint.endswith('/jobs?per_page=100'):
                run = self.current if endpoint.startswith('actions/runs/103/') else self.build
                self.assertEqual(endpoint, f'actions/runs/{run["id"]}/attempts/{run["run_attempt"]}/jobs?per_page=100')
                names = ['release'] if run is self.current else ['verify', 'apk']
                jobs = [dict(id=index, name=name, status='completed', conclusion='success',
                             run_id=run['id'], head_sha=run['head_sha'], run_attempt=run['run_attempt'])
                        for index, name in enumerate(names, 1)]
                for job in jobs:
                    job.update(self.job_changes.get(job['name'], {}))
                response = [dict(total_count=len(jobs), jobs=jobs)]
            elif endpoint == 'releases?per_page=100':
                response = [[dict(self.release, assets=assets),
                             *(dict(release, assets=assets) for release in self.extra_releases)]]
            elif endpoint.startswith('git/ref/tags/'):
                response = dict(object=dict(type='commit', sha=self.tag_source))
            elif endpoint in ('releases/assets/2', 'releases/assets/3'):
                return subprocess.CompletedProcess(command, 0, info_text if endpoint.endswith('/2') else sums, '')
            else:
                self.fail(f'Unexpected GitHub API request: {endpoint}')
            return subprocess.CompletedProcess(command, 0, json.dumps(response), '')

        with tempfile.TemporaryDirectory() as temporary:
            event_path = Path(temporary) / 'event.json'
            output_path = Path(temporary) / 'output'
            event_path.write_text(json.dumps(dict(workflow_run=self.event)), encoding='utf-8')
            environment = dict(GITHUB_REPOSITORY=REPOSITORY, GITHUB_SERVER_URL='https://github.com',
                               GITHUB_EVENT_PATH=str(event_path), GITHUB_OUTPUT=str(output_path))
            with patch.dict(os.environ, environment), patch('subprocess.run', side_effect=github):
                exec(self.script, {})
            return output_path.read_text() if output_path.exists() else ''

    def test_separate_build_and_release_sources_publish(self):
        self.assertEqual(self.execute_gate(), f'released=true\nrelease_tag={TAG}\n')

    def test_same_source_still_publishes(self):
        self.info['source'] = self.build['head_sha'] = RELEASE_SOURCE
        self.assertEqual(self.execute_gate(), f'released=true\nrelease_tag={TAG}\n')

    def test_invalid_build_source_is_rejected(self):
        for source in ('', 'a' * 39, 'g' * 40, 'A' * 40, 100, None):
            with self.subTest(source=source):
                self.info['source'] = self.build['head_sha'] = source
                self.assertEqual(self.execute_gate(), '')

    def test_build_run_identity_and_success_remain_required(self):
        for changes in (dict(head_sha='b' * 40), dict(id=999), dict(run_attempt=0),
                        dict(repository=dict(full_name='other/repo')),
                        dict(head_repository=dict(full_name='other/repo')),
                        dict(path='.github/workflows/other.yaml'), dict(conclusion='failure')):
            with self.subTest(changes=changes):
                original = self.build
                self.build = dict(original, **changes)
                self.assertEqual(self.execute_gate(), '')
                self.build = original

    def test_build_info_package_signing_version_and_workflow_remain_required(self):
        for changes in (dict(applicationId='other.app'), dict(signing='untrusted key'),
                        dict(version='0.11.0'), dict(workflow='https://github.com/other/repo/actions/runs/100'),
                        dict(workflow=f'https://github.com/{REPOSITORY}/actions/runs/0')):
            with self.subTest(changes=changes):
                original = self.info
                self.info = dict(original, **changes)
                self.assertEqual(self.execute_gate(), '')
                self.info = original

    def test_failed_or_mismatched_jobs_are_rejected(self):
        for job, changes in (('verify', dict(conclusion='failure')), ('apk', dict(conclusion='failure')),
                             ('release', dict(conclusion='failure'))):
            with self.subTest(job=job):
                self.job_changes = {job: changes}
                self.assertEqual(self.execute_gate(), '')
        self.job_changes = {'apk': dict(head_sha=RELEASE_SOURCE)}
        with self.assertRaisesRegex(ValueError, 'Workflow job identity mismatch'):
            self.execute_gate()

    def test_tag_must_match_release_event_not_build(self):
        self.tag_source = BUILD_SOURCE
        self.assertEqual(self.execute_gate(), '')

    def test_untrusted_release_event_is_rejected(self):
        for changes in (dict(head_branch='codex/build'), dict(conclusion='failure'),
                        dict(status='in_progress'), dict(head_repository=dict(full_name='other/repo'))):
            with self.subTest(changes=changes):
                original = self.event
                self.event = dict(original, **changes)
                self.assertEqual(self.execute_gate(), '')
                self.event = original

    def test_release_run_readback_must_match_event(self):
        for changes in (dict(head_sha=BUILD_SOURCE), dict(id=999), dict(run_attempt=2)):
            with self.subTest(changes=changes):
                original = self.current
                self.current = dict(original, **changes)
                self.assertEqual(self.execute_gate(), '')
                self.current = original

    def test_asset_digest_and_uniqueness_remain_required(self):
        for change in (lambda assets: assets.append(dict(assets[0])),
                       lambda assets: assets[1].update(digest='sha256:' + '0' * 64),
                       lambda assets: assets[0].update(size=0)):
            with self.subTest(change=change):
                self.asset_change = change
                self.assertEqual(self.execute_gate(), '')

    def test_channel_and_publication_time_remain_required(self):
        self.info['appEnv'] = 'stable'
        self.assertEqual(self.execute_gate(), '')
        self.info['appEnv'] = 'pre'
        self.release['published_at'] = '2026-10-30T00:00:00Z'
        self.assertEqual(self.execute_gate(), '')

    def test_ambiguous_release_candidates_are_rejected(self):
        self.extra_releases = [dict(self.release)]
        with self.assertRaisesRegex(ValueError, 'Multiple releases match'):
            self.execute_gate()


if __name__ == '__main__':
    unittest.main()
