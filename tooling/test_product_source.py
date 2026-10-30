import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
import zipfile

import product_source as source


SHARED_STANDARD_INPUTS = (
    'apps/aerove/plugins/window/lib/window.dart',
    'apps/aerove/plugins/proxy/lib/proxy.dart',
    'apps/aerove/plugins/tray/lib/tray.dart',
    'apps/aerove/plugins/wifi_ssid/lib/wifi_ssid.dart',
    'apps/aerove/plugins/code_forge/lib/code_forge.dart',
    'apps/aerove/plugins/rust_api/rust/src/lib.rs',
    'apps/aerove/lint_options.yaml',
)


class ProductSourceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.env = {**os.environ, 'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_NOSYSTEM': '1',
            'GIT_AUTHOR_NAME': 'Projection test', 'GIT_COMMITTER_NAME': 'Projection test',
            'GIT_AUTHOR_EMAIL': 'test@example.invalid', 'GIT_COMMITTER_EMAIL': 'test@example.invalid'}
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        for path in ('apps/aerove/lib/app.dart', 'apps/aerove/test/app_test.dart',
                     'apps/aerove-lite/app/App.kt', 'apps/aerove-lite/app/src/test/Test.kt',
                     'apps/aerove/tool/aerove_release_notes.py',
                     'apps/aerove/tool/test_aerove_release_notes.py',
                     'docs/aerove/CHANGELOG.md', 'docs/shared/UI_CONTRACT.md',
                     '.github/actions/flutter/action.yaml', '.gitmodules', 'root-build.sh',
                     *SHARED_STANDARD_INPUTS):
            self.write(path, 'original\n')
        self.original = self.commit()

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, env=self.env,
            check=True, capture_output=True).stdout.decode().strip()

    def write(self, path, content):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding='utf-8')

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-q', '--allow-empty', '-m', 'test input')
        return self.git('rev-parse', 'HEAD')

    def compare(self, product='aerove'):
        return source.equivalent(root=self.root, product=product,
            build_source=self.original, release_source=self.git('rev-parse', 'HEAD'))

    def test_identical_tree_empty_commit_and_dirty_working_files_reuse_git_objects(self):
        self.commit()
        self.write('apps/aerove/lib/app.dart', 'uncommitted and ignored by object comparison')
        self.assertEqual(self.compare()['changed'], [])

    def test_other_product_and_explicit_fact_documents_are_allowed_both_directions(self):
        for product, changed in (('aerove', 'apps/aerove-lite/app/App.kt'),
                                 ('aerove-lite', 'apps/aerove/lib/app.dart')):
            with self.subTest(product=product):
                self.git('reset', '--hard', self.original)
                self.write(changed, 'other product change\n')
                self.write('docs/aerove/CHANGELOG.md', 'new release fact\n')
                self.write('docs/CHANGELOG_Aerove.md', 'generated compatibility release facts\n')
                self.commit()
                self.assertEqual({row['reason'] for row in self.compare(product)['changed']},
                                 {'otherProduct', 'factDocument'})

    def test_product_tests_shared_build_and_contract_unknown_root_are_rejected(self):
        for path in ('apps/aerove/lib/app.dart', 'apps/aerove/test/app_test.dart',
                     'docs/shared/UI_CONTRACT.md', '.github/actions/flutter/action.yaml',
                     '.gitmodules', 'unknown.sh', 'docs/development/VERSIONING.md',
                     'AGENTS.md', 'apps/aerove/AGENTS.md'):
            with self.subTest(path=path):
                self.git('reset', '--hard', self.original)
                self.write(path, 'changed\n')
                self.commit()
                with self.assertRaises(source.ProjectionError):
                    self.compare()

    def test_other_product_instructions_are_shared_governance_inputs(self):
        self.write('apps/aerove-lite/AGENTS.md', 'changed instructions\n')
        self.commit()
        with self.assertRaises(source.ProjectionError):
            self.compare()

    def test_lite_still_protects_the_standard_asn_fixture_it_reads_for_acceptance(self):
        self.write('apps/aerove/assets/data/ASN.mmdb', 'changed actual Android fixture\n')
        self.commit()
        with self.assertRaisesRegex(source.ProjectionError, 'Cross-product acceptance input'):
            self.compare('aerove-lite')

    def test_lite_rejects_changes_to_every_shared_standard_plugin_and_lint_input(self):
        baseline = source.equivalent(root=self.root, product='aerove-lite',
            build_source=self.original, release_source=self.original)
        for path in SHARED_STANDARD_INPUTS:
            with self.subTest(path=path):
                self.git('reset', '--hard', self.original)
                self.write(path, 'changed shared client dependency\n')
                changed = self.commit()
                with self.assertRaisesRegex(source.ProjectionError, 'Cross-product acceptance input'):
                    self.compare('aerove-lite')
                current = source.equivalent(root=self.root, product='aerove-lite',
                    build_source=changed, release_source=changed)
                self.assertNotEqual(current['protected_inputs_sha256'],
                                    baseline['protected_inputs_sha256'])

    def test_lite_protects_added_and_deleted_files_inside_shared_plugin_directories(self):
        for operation in ('add', 'delete'):
            with self.subTest(operation=operation):
                self.git('reset', '--hard', self.original)
                if operation == 'add':
                    self.write('apps/aerove/plugins/rust_api/rust/src/api/new.rs', 'new shared API\n')
                else:
                    (self.root / SHARED_STANDARD_INPUTS[0]).unlink()
                self.commit()
                with self.assertRaisesRegex(source.ProjectionError, 'Cross-product acceptance input'):
                    self.compare('aerove-lite')

    def test_lite_can_ignore_independent_standard_core_and_plugin_sibling_changes(self):
        baseline = self.compare('aerove-lite')
        paths = {'apps/aerove/core/main.go',
                 'apps/aerove/plugins/window_extra/lib/window.dart'}
        for path in paths:
            self.write(path, 'standard-only input\n')
        self.commit()
        projected = self.compare('aerove-lite')
        self.assertEqual({row['path'] for row in projected['changed']}, paths)
        self.assertEqual({row['reason'] for row in projected['changed']}, {'otherProduct'})
        self.assertEqual(projected['protected_inputs_sha256'], baseline['protected_inputs_sha256'])

    def test_shared_package_sources_remain_protected_for_both_products(self):
        for product in ('aerove', 'aerove-lite'):
            for path in ('packages/aerove_client/lib/application.dart',
                         'packages/aerove_client/pubspec.yaml'):
                with self.subTest(product=product, path=path):
                    self.git('reset', '--hard', self.original)
                    self.write(path, 'changed shared package\n')
                    self.commit()
                    with self.assertRaisesRegex(source.ProjectionError, 'Product/build/acceptance input'):
                        self.compare(product)

    def test_changed_modes_symlinks_and_submodule_entries_are_rejected_even_on_exceptions(self):
        path = 'docs/aerove/CHANGELOG.md'
        for mode in ('100755', '120000', '160000'):
            with self.subTest(mode=mode):
                self.git('reset', '--hard', self.original)
                blob = self.original if mode == '160000' else self.git('rev-parse', self.original + ':' + path)
                self.git('update-index', '--add', '--cacheinfo', mode, blob, path)
                self.git('commit', '-q', '-m', 'changed entry type')
                with self.assertRaises(source.ProjectionError):
                    self.compare()

    def test_release_notes_publisher_paths_are_exact_exceptions(self):
        paths = ('apps/aerove/tool/aerove_release_notes.py',
                 'apps/aerove/tool/test_aerove_release_notes.py')
        for path in paths:
            self.write(path, 'reviewed publisher maintenance\n')
        self.commit()
        projected = self.compare()
        baseline = source.equivalent(root=self.root, product='aerove',
            build_source=self.original, release_source=self.original)
        self.assertEqual(projected['protected_inputs_sha256'], baseline['protected_inputs_sha256'])
        changes = projected['changed']
        self.assertEqual({row['path'] for row in changes}, set(paths))
        self.assertEqual({row['reason'] for row in changes}, {'publisherBootstrap'})
        for path in ('apps/aerove/tool/aerove_version.py',
                     'apps/aerove/tool/ci_verify.py',
                     'apps/aerove/tool/unknown_publisher.py',
                     'apps/aerove/setup.dart',
                     'apps/aerove/android/app/build.gradle.kts',
                     'tooling/build.py'):
            with self.subTest(path=path):
                self.git('reset', '--hard', self.original)
                self.write(path, 'protected producer or acceptance gate\n')
                self.commit()
                with self.assertRaises(source.ProjectionError):
                    self.compare()

    def test_publisher_exception_is_exact_and_other_workflow_scripts_remain_protected(self):
        self.write('tooling/product_source.py', 'bootstrap\n')
        self.commit()
        self.assertEqual(self.compare()['changed'][0]['reason'], 'publisherBootstrap')
        self.write('tooling/build.py', 'unknown shared producer\n')
        self.commit()
        with self.assertRaises(source.ProjectionError):
            self.compare()


class WorkflowProjectionTest(unittest.TestCase):
    def workflow(self):
        return 'name: Product\non:\n  workflow_dispatch:\n    inputs:\n      build:\n        type: boolean\npermissions:\n  contents: read\njobs:\n  reuse:\n    run: old selector\n  build:\n    strategy:\n      matrix: [arm, x86]\n    run: compile\n  verify:\n    run: test\n  release:\n    run: old publisher\n'

    def test_only_new_input_and_reuse_release_jobs_can_change(self):
        before = self.workflow()
        after = before.replace('      build:', source.ACCEPTED_BUILD_INPUT + '      build:')
        after = after.replace('jobs:\n', 'jobs:\n' + source.PROMOTION_REQUEST_JOB)
        after = after.replace('old selector', 'explicit selector').replace('old publisher', 'original publisher')
        self.assertEqual(source.workflow_equivalent(before.encode(), after.encode()), ['build', 'verify'])
        for changed in (after.replace('compile', 'different compiler'),
                        after.replace('run: test', 'run: skip'),
                        after.replace('[arm, x86]', '[arm]'),
                        after.replace('contents: read', 'contents: write'),
                        after + '  another:\n    run: bypass\n'):
            with self.subTest(changed=changed), self.assertRaises(source.ProjectionError):
                source.workflow_equivalent(before.encode(), changed.encode())

    def test_standard_default_dispatch_builds_without_implicit_publication(self):
        before = self.workflow().replace('      build:\n', source.PUBLISH_DEFAULT + '      build:\n')
        after = before.replace('jobs:\n', 'jobs:\n' + source.PROMOTION_REQUEST_JOB)
        after = after.replace(source.PUBLISH_DEFAULT, source.PUBLISH_DEFAULT.replace('default: true', 'default: false'))
        source.workflow_equivalent(before.encode(), after.encode())
        with self.assertRaises(source.ProjectionError):
            source.workflow_equivalent(before.encode(), after.replace('default: false', 'default: unknown').encode())

    def test_lite_on_mapping_after_jobs_is_protected(self):
        before = self.workflow()
        header, jobs = before.split('jobs:\n')
        head, event = header.split('on:\n')
        late = head + 'jobs:\n' + jobs + 'on:\n' + event
        after = late.replace('old selector', 'explicit selector')
        after = after.replace('jobs:\n', 'jobs:\n' + source.PROMOTION_REQUEST_JOB)
        source.workflow_equivalent(late.encode(), after.encode())
        with self.assertRaises(source.ProjectionError):
            source.workflow_equivalent(late.encode(), after.replace('type: boolean', 'type: string').encode())

    def test_fixed_admission_prefix_preserves_every_original_business_gate(self):
        before = self.workflow()
        after = before.replace('jobs:\n', 'jobs:\n' + source.PROMOTION_REQUEST_JOB)
        after = after.replace('\n  build:\n', '\n  build:\n    if: ${{ ' + source.BUILD_MODE + ' }}\n')
        after = after.replace('\n  verify:\n', '\n  verify:\n    if: ${{ ' + source.BUILD_MODE + ' }}\n')
        source.workflow_equivalent(before.encode(), after.encode())
        with self.assertRaises(source.ProjectionError):
            source.workflow_equivalent(before.encode(), after.replace('&& (!inputs.publish_release', '|| (!inputs.publish_release').encode())

    def test_admission_valid_build_publish_and_recheck_and_invalid_mixed_requests(self):
        script = source.PROMOTION_REQUEST_JOB.split("python3 - <<'PY'\n", 1)[1].rsplit('          PY', 1)[0]
        script = textwrap.dedent(script)
        base = dict(ACCEPTED_BUILD_RUN='', PUBLISH_RELEASE='false', BUILD_APK='false',
                    GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/main')
        for changes, expected in (({}, 0),
                (dict(ACCEPTED_BUILD_RUN='123', PUBLISH_RELEASE='true'), 0),
                (dict(VERIFICATION_RECHECK='true', ORIGINAL_BUILD_RUN='40488925368'), 0),
                (dict(ACCEPTED_BUILD_RUN='123'), 1), (dict(PUBLISH_RELEASE='true'), 1),
                (dict(ACCEPTED_BUILD_RUN='123', PUBLISH_RELEASE='true', BUILD_APK='true'), 1),
                (dict(ACCEPTED_BUILD_RUN='123', PUBLISH_RELEASE='true', DIAGNOSTIC_TARGET='both'), 1),
                (dict(ACCEPTED_BUILD_RUN='123', PUBLISH_RELEASE='true', VERIFICATION_RECHECK='true'), 1)):
            with self.subTest(changes=changes):
                result = subprocess.run([sys.executable, '-B', '-c', script],
                    env={**os.environ, **base, **changes}, capture_output=True)
                self.assertEqual(bool(result.returncode), bool(expected), result.stderr)


class ArtifactBindingTest(unittest.TestCase):
    def test_original_zip_payload_bytes_and_metadata_are_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = root / 'payload'
            payload.mkdir()
            archive = root / 'original.zip'
            data = {'app.apk': b'original apk', 'BUILD_INFO.json': b'{"source":"original"}',
                    'SHA256SUMS': b'original sums'}
            with zipfile.ZipFile(archive, 'w') as package:
                for name, content in data.items():
                    package.writestr(name, content)
                    (payload / name).write_bytes(content)
            identity = dict(digest='sha256:' + hashlib.sha256(archive.read_bytes()).hexdigest(),
                            size_in_bytes=archive.stat().st_size)
            self.assertEqual(set(source.bind_archive(archive, payload, identity)), set(data))
            for name in data:
                with self.subTest(replaced=name):
                    (payload / name).write_bytes(b'replacement bytes')
                    with self.assertRaises(source.ProjectionError):
                        source.bind_archive(archive, payload, identity)
                    (payload / name).write_bytes(data[name])
            for changed in (dict(identity, digest='sha256:' + '0' * 64),
                            dict(identity, size_in_bytes=identity['size_in_bytes'] + 1)):
                with self.assertRaises(source.ProjectionError):
                    source.bind_archive(archive, payload, changed)


if __name__ == '__main__':
    unittest.main()
