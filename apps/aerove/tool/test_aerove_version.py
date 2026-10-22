from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from tool import aerove_version as version
from tool import xclash_version as upstream


SCRIPT = Path(__file__).with_name('aerove_version.py')


class ProductVersionTest(unittest.TestCase):
    def test_product_version_has_no_added_brand_suffix(self):
        self.assertEqual(
            version.resolve_product_version('version: 1.2.3+2026102201\n', 149),
            {
                'name': '1.2.3', 'number': 2026102349,
                'tag': 'v1.2.3+2026102349', 'app_env': 'stable',
            },
        )

    def test_valid_prereleases_map_to_the_only_preview_environment(self):
        for prerelease in ('pre.10', 'alpha', 'beta.2', 'rc.1', '0', 'alpha-beta.9'):
            with self.subTest(prerelease=prerelease):
                name = f'2.0.0-{prerelease}'
                plan = version.resolve_product_version(f'version: {name}+ci.009\n', 150)
                self.assertEqual(plan['name'], name)
                self.assertEqual(plan['app_env'], 'pre')
                self.assertEqual(plan['tag'], f'v{name}+2026102350')

    def test_quoted_version_comments_crlf_and_build_metadata_are_valid(self):
        for line in (
            'version: 1.2.3+009',
            "version: '1.2.3+build.009' # product build",
            'version: "1.2.3+sha-deadbeef.22"',
            'version : 1.2.3+ci.123 # build',
        ):
            with self.subTest(line=line):
                plan = version.resolve_product_version(f'name: aerove\r\n{line}\r\n', 150)
                self.assertEqual(plan['name'], '1.2.3')
                self.assertEqual(plan['number'], 2026102350)

    def test_build_metadata_is_optional_for_stable_and_preview_versions(self):
        for value, name, channel in (
            ('1.2.3', '1.2.3', 'stable'),
            ('1.2.3-beta.1', '1.2.3-beta.1', 'pre'),
            ('1.2.3+ci.1', '1.2.3', 'stable'),
            ('1.2.3-beta.1+ci.1', '1.2.3-beta.1', 'pre'),
        ):
            with self.subTest(value=value):
                plan = version.resolve_product_version(f'version: {value}\n', 150)
                self.assertEqual(plan['name'], name)
                self.assertEqual(plan['app_env'], channel)
                self.assertEqual(plan['number'], 2026102350)
                self.assertEqual(plan['tag'], f'v{name}+2026102350')

    def test_invalid_semver_build_metadata_or_duplicate_version_is_rejected(self):
        values = (
            'v1.2.3+1', '01.2.3+1', '1.02.3+1', '1.2.03+1',
            '1.2+1', '1.2.3.4+1', '1.2.3-01+1', '1.2.3-pre.01+1',
            '1.2.3-+1', '1.2.3-pre..1+1', '1.2.3-rc/1+1',
            '1.2.3+', '1.2.3+build..1', '1.2.3+build_1', '1.2.3+1+2',
            '1.2.3+中文', '１.2.3+1', '1.2.3+١', '1.2.3+1 trailing',
            "'1.2.3+1\"", '"1.2.3+1', 'true', 'null',
        )
        for value in values:
            with self.subTest(value=value):
                with self.assertRaises(version.VersionError):
                    version.resolve_product_version(f'version: {value}\n', 150)
        for contents in ('name: aerove\n', '  version: 1.2.3+1\n',
                         'version: 1.2.3+1\nversion: 2.0.0+2\n', None):
            with self.subTest(contents=contents):
                with self.assertRaises(version.VersionError):
                    version.resolve_product_version(contents, 150)

    def test_run_number_rejects_boolean_non_integer_non_positive_and_overflow(self):
        for number in (True, False, '150', 1.5, 0, -1, None,
                       upstream.ANDROID_VERSION_CODE_LIMIT - version.VERSION_CODE_BASE + 1):
            with self.subTest(number=number):
                with self.assertRaises(version.VersionError):
                    version.resolve_product_version('version: 1.2.3+1\n', number)

    def test_build_codes_keep_the_1849_upgrade_sequence_and_platform_limit(self):
        previous = upstream.FORK_VERSION_CODE_BASE + 149
        next_plan = version.resolve_product_version('version: 1.2.3+1\n', 1)
        self.assertEqual(previous, 2026101849)
        self.assertEqual(next_plan['number'], 2026102201)
        self.assertGreater(next_plan['number'], previous)
        maximum = upstream.ANDROID_VERSION_CODE_LIMIT - version.VERSION_CODE_BASE
        self.assertEqual(
            version.resolve_product_version('version: 1.2.3+1\n', maximum)['number'],
            upstream.ANDROID_VERSION_CODE_LIMIT,
        )


class ReleasePlanGitTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='aerove-version-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.environment = {
            **os.environ,
            'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
            'GIT_AUTHOR_NAME': 'Offline Test', 'GIT_AUTHOR_EMAIL': 'test@example.invalid',
            'GIT_COMMITTER_NAME': 'Offline Test', 'GIT_COMMITTER_EMAIL': 'test@example.invalid',
            'GIT_TERMINAL_PROMPT': '0', 'PYTHONDONTWRITEBYTECODE': '1',
        }
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'core.autocrlf', 'false')
        (self.repo / 'pubspec.yaml').write_text(
            'name: upstream\nversion: 0.9.1+2026101702\n', encoding='utf-8',
        )
        self.source = self.commit('historical upstream')
        self.git('tag', '-a', 'v0.9.1', '-m', 'baseline')
        self.git('update-ref', 'refs/upstream-tags/v0.9.1', 'refs/tags/v0.9.1')
        self.git('update-ref', 'refs/remotes/upstream/main', self.source)
        self.metadata = upstream.make_metadata(
            source_commit=self.source, tag_ref='refs/upstream-tags/v0.9.1',
            tag_commit=self.source, source_branches=['main'],
            pubspec_version='0.9.1+2026101702',
        )
        (self.repo / 'tool').mkdir()
        self.save_metadata()
        (self.repo / 'pubspec.yaml').write_text(
            'name: aerove\nversion: 2.3.4+2026102201\n', encoding='utf-8',
        )
        self.head = self.commit('independent product')

    def git(self, *arguments, input=None):
        completed = subprocess.run(
            ['git', *arguments], cwd=self.repo, env=self.environment,
            input=input, capture_output=True, text=True, encoding='utf-8', check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        return completed.stdout.strip()

    def commit(self, message):
        self.git('add', '--all')
        self.git('-c', 'commit.gpgsign=false', 'commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')

    def save_metadata(self):
        (self.repo / 'tool/upstream-version.json').write_text(
            json.dumps(self.metadata), encoding='utf-8',
        )

    def plan(self, **changes):
        arguments = {
            'repo': self.repo, 'head': self.head, 'run_number': 150,
            'metadata': Path('tool/upstream-version.json'), 'pubspec': Path('pubspec.yaml'),
        }
        return version.build_release_plan(**{**arguments, **changes})

    def cli(self, *extra):
        return subprocess.run(
            [sys.executable, '-B', str(SCRIPT), 'release-plan',
             '--repo', str(self.repo), '--metadata', 'tool/upstream-version.json',
             '--pubspec', 'pubspec.yaml', '--head', self.head, '--run-number', '150', *extra],
            cwd=self.repo.parent, env=self.environment, capture_output=True,
            text=True, encoding='utf-8', check=False,
        )

    def migrate_product(self):
        product = self.repo / 'apps/aerove'
        (product / 'tool').mkdir(parents=True)
        (self.repo / 'pubspec.yaml').rename(product / 'pubspec.yaml')
        (self.repo / 'tool/upstream-version.json').rename(product / 'tool/upstream-version.json')
        self.head = self.commit('move product into workspace')
        return product

    def test_monorepo_inputs_resolve_from_both_repository_and_product_roots(self):
        original = self.plan()
        product = self.migrate_product()
        self.assertEqual(self.plan(repo=product), original)
        self.assertEqual(self.plan(
            metadata=Path('apps/aerove/tool/upstream-version.json'),
            pubspec=Path('apps/aerove/pubspec.yaml'),
        ), original)
        self.assertEqual(self.plan(
            metadata=product / 'tool/upstream-version.json', pubspec=product / 'pubspec.yaml',
        ), original)
        self.assertEqual(original['upstream_pubspec_source_commit'], self.source)

    def test_monorepo_cli_reads_the_requested_head_from_an_unrelated_cwd(self):
        original = self.plan()
        product = self.migrate_product()
        (product / 'pubspec.yaml').write_text('version: 99.9.9\n', encoding='utf-8')
        (product / 'tool/upstream-version.json').write_text('{}', encoding='utf-8')
        completed = self.cli('--repo', str(product))
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout), original)
        completed = self.cli(
            '--metadata', 'apps/aerove/tool/upstream-version.json',
            '--pubspec', 'apps/aerove/pubspec.yaml',
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout), original)

    def test_monorepo_migration_does_not_hide_forged_historical_evidence(self):
        product = self.migrate_product()
        self.metadata['pubspec_version'] = '0.9.1+2026101703'
        (product / 'tool/upstream-version.json').write_text(
            json.dumps(self.metadata), encoding='utf-8',
        )
        self.head = self.commit('forged historical version after migration')
        with self.assertRaisesRegex(version.VersionError, 'does not match its source'):
            self.plan(repo=product)

    def test_independent_product_and_all_original_provenance_outputs(self):
        plan = self.plan()
        self.assertEqual(plan['name'], '2.3.4')
        self.assertEqual(plan['number'], 2026102350)
        self.assertEqual(plan['tag'], 'v2.3.4+2026102350')
        self.assertEqual(plan['app_env'], 'stable')
        mapping = {
            'upstream_repository': 'repository', 'upstream_source_commit': 'source_commit',
            'upstream_tag': 'tag', 'upstream_tag_commit': 'tag_commit',
            'upstream_source_branches': 'source_branches',
            'upstream_pubspec_version': 'pubspec_version',
            'upstream_pubspec_source_commit': 'pubspec_source_commit',
        }
        for output, field in mapping.items():
            self.assertEqual(plan[output], self.metadata[field])

    def test_newer_upstream_preview_and_stable_do_not_control_product_release(self):
        original = self.plan()
        for tag in ('v50.0.0-pre.1', 'v49.0.0'):
            self.git('update-ref', f'refs/upstream-tags/{tag}', self.head)
            self.git('update-ref', 'refs/remotes/upstream/next', self.head)
            with self.subTest(tag=tag):
                self.assertEqual(self.plan(), original)
        self.assertEqual(upstream.select_reachable_release(self.repo)['version'], '50.0.0-pre.1')

    def test_pinned_provenance_needs_no_mutable_upstream_refs_or_network(self):
        original = self.plan()
        for reference in ('refs/upstream-tags/v0.9.1', 'refs/remotes/upstream/main', 'refs/tags/v0.9.1'):
            self.git('update-ref', '-d', reference)
        self.assertEqual(self.plan(), original)

    def test_own_preview_tag_and_environment_ignore_stable_upstream(self):
        (self.repo / 'pubspec.yaml').write_text('version: 2.3.5-pre.1+4\n', encoding='utf-8')
        self.head = self.commit('product preview')
        self.assertEqual(self.plan()['app_env'], 'pre')
        self.assertEqual(self.plan()['tag'], 'v2.3.5-pre.1+2026102350')

    def test_cli_accepts_committed_stable_and_preview_without_build_metadata(self):
        for name, channel in (('1.2.3', 'stable'), ('1.2.3-beta.1', 'pre')):
            with self.subTest(name=name):
                (self.repo / 'pubspec.yaml').write_text(f'version: {name}\n', encoding='utf-8')
                self.head = self.commit('product version without build metadata')
                completed = self.cli()
                self.assertEqual(completed.returncode, 0, completed.stderr)
                plan = json.loads(completed.stdout)
                self.assertEqual(plan['name'], name)
                self.assertEqual(plan['number'], 2026102350)
                self.assertEqual(plan['app_env'], channel)
                self.assertEqual(plan['tag'], f'v{name}+2026102350')

    def test_head_pins_product_and_metadata_despite_dirty_or_newer_checkout(self):
        original = self.plan()
        (self.repo / 'pubspec.yaml').write_text('version: 9.9.9+1\n', encoding='utf-8')
        self.metadata['repository'] = 'attacker/repo'
        self.save_metadata()
        self.assertEqual(self.plan(), original)
        self.commit('different checkout inputs')
        self.assertEqual(self.plan(), original)
        completed = self.cli()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout), original)

    def test_each_forged_provenance_identity_is_rejected_after_commit(self):
        original = self.metadata.copy()
        changes = (
            {'schema_version': 0}, {'repository': 'attacker/repo'},
            {'tag_commit': 'f' * 40}, {'pubspec_source_commit': 'f' * 40},
            {'source_commit': 'f' * 40}, {'tag': 'v9.0.0'},
            {'pubspec_version': '0.9.1+0'}, {'source_branches': []},
            {'unexpected': 'value'},
        )
        for change in changes:
            with self.subTest(change=change):
                self.metadata = {**original, **change}
                self.save_metadata()
                self.head = self.commit('invalid provenance')
                with self.assertRaises(version.VersionError):
                    self.plan()

    def test_source_must_be_in_requested_head_history(self):
        tree = self.git('mktree', input='')
        unrelated = self.git('commit-tree', tree, '-m', 'unrelated source')
        self.metadata.update(
            source_commit=unrelated, tag_commit=unrelated, pubspec_source_commit=unrelated,
        )
        self.save_metadata()
        self.head = self.commit('record unrelated source')
        with self.assertRaisesRegex(version.VersionError, 'contained'):
            self.plan()

    def test_duplicate_json_fields_or_missing_committed_input_fail(self):
        path = self.repo / 'tool/upstream-version.json'
        path.write_text('{"schema_version":1,"schema_version":1}', encoding='utf-8')
        self.head = self.commit('ambiguous metadata')
        with self.assertRaisesRegex(version.VersionError, 'duplicate'):
            self.plan()
        with self.assertRaises(version.VersionError):
            self.plan(head=self.source)

    def test_invalid_json_non_object_and_boolean_schema_are_rejected(self):
        path = self.repo / 'tool/upstream-version.json'
        for contents in ('{', '[]', '{"schema_version":true}', '{"schema_version":NaN}'):
            with self.subTest(contents=contents):
                path.write_text(contents, encoding='utf-8')
                self.head = self.commit('invalid metadata encoding or schema')
                with self.assertRaises(version.VersionError):
                    self.plan()

    def test_paths_must_stay_inside_repo_and_head_must_be_a_full_commit_sha(self):
        for change in (
            {'head': 'HEAD'}, {'head': 'F' * 40}, {'head': 'f' * 40},
            {'head': self.git('rev-parse', 'HEAD^{tree}')},
            {'metadata': Path('../outside.json')}, {'pubspec': Path('../pubspec.yaml')},
            {'metadata': Path('.')},
        ):
            with self.subTest(change=change):
                with self.assertRaises(version.VersionError):
                    self.plan(**change)
        self.assertEqual(self.plan(pubspec=self.repo / 'pubspec.yaml'), self.plan())

    def test_cli_runs_offline_from_another_cwd_and_leaves_all_input_bytes_unchanged(self):
        files = [self.repo / 'pubspec.yaml', self.repo / 'tool/upstream-version.json']
        before = {path: path.read_bytes() for path in files}
        self.environment['APP_ENV'] = 'invalid-channel'
        completed = self.cli()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout), self.plan())
        self.assertEqual(before, {path: path.read_bytes() for path in files})

    def test_cli_errors_have_no_plan_stdout(self):
        for arguments in (
            ('--head', 'HEAD'), ('--run-number', 'True'), ('--run-number', '0'),
            ('--run-number', '1.5'), ('--run-number', str(upstream.ANDROID_VERSION_CODE_LIMIT)),
            ('--app-env', 'invalid-channel'), ('--metadata', '../outside.json'),
        ):
            with self.subTest(arguments=arguments):
                completed = self.cli(*arguments)
                self.assertNotEqual(completed.returncode, 0)
                self.assertEqual(completed.stdout, '')
                self.assertNotIn('Traceback', completed.stderr)

    def test_callable_cli_returns_the_plan_and_a_clean_error_status(self):
        arguments = [
            'release-plan', '--repo', str(self.repo),
            '--metadata', 'tool/upstream-version.json', '--pubspec', 'pubspec.yaml',
            '--head', self.head, '--run-number', '150',
        ]
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(version.main(arguments), 0)
        self.assertEqual(json.loads(output.getvalue()), self.plan())
        errors = io.StringIO()
        with redirect_stdout(io.StringIO()) as output, redirect_stderr(errors):
            self.assertEqual(version.main([*arguments, '--head', 'f' * 40]), 1)
        self.assertEqual(output.getvalue(), '')
        self.assertTrue(errors.getvalue().startswith('aerove_version: '))


if __name__ == '__main__':
    unittest.main()
