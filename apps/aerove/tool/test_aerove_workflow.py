import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / '.github/workflows'
FULL = (WORKFLOWS / 'xclash-android.yaml').read_text(encoding='utf-8')


def run_block(name):
    lines = FULL.splitlines()
    start = lines.index(f'      - name: {name}')
    start = next(index for index in range(start + 1, len(lines))
                 if lines[index] == '        run: |') + 1
    result = []
    for line in lines[start:]:
        if line and not line.startswith('          '):
            break
        result.append(line[10:])
    return '\n'.join(result)


class WorkflowPolicyTest(unittest.TestCase):
    def test_luminous_runner_prepares_the_pinned_core_source_for_dns_tests(self):
        render = re.search(r'(?ms)^  render:\n(.*?)(?=^  [a-z][a-z_-]*:|\Z)', FULL)[1]
        preparation = "      - name: Prepare official Core source for DNS contract tests\n" \
            "        if: ${{ matrix.suite == 'luminous' }}\n" \
            "        run: python3 tool/mihomo_source.py prepare"
        self.assertIn(preparation, render)
        self.assertLess(render.index(preparation),
                        render.index('Run preserved verification combination'))

    def test_generated_artifact_id_consumers_download_into_the_replay_directory(self):
        downloads = re.findall(
            r'artifact-ids: \$\{\{ needs.prep.outputs.generated_artifact_id \}\}\n'
            r'(.*?)(?=\n      - |\n  [a-z]|\Z)', FULL, re.DOTALL,
        )
        self.assertEqual(len(downloads), 4)
        for download in downloads:
            self.assertIn('merge-multiple: true', download)
            self.assertIn('path: ${{ runner.temp }}/xclash-generated', download)

    def test_composite_font_action_keeps_timeout_on_workflow_callers(self):
        font = (ROOT / '.github/actions/aerove-test-font/action.yaml').read_text(encoding='utf-8')
        self.assertNotIn('timeout-minutes:', font)
        callers = re.findall(
            r'- uses: \./\.github/actions/aerove-test-font\n(.*?)(?=\n      - |\n  [a-z]|\Z)',
            FULL, re.DOTALL,
        )
        self.assertEqual(len(callers), 4)
        for caller in callers:
            self.assertIn('timeout-minutes: 10', caller)

    def test_host_cache_miss_and_nonexact_match_stop_before_the_native_tests(self):
        git = Path(shutil.which('git')).resolve()
        bash = git.parent.parent / 'bin/bash.exe' if os.name == 'nt' else shutil.which('bash')
        script = run_block('Require exact producer host Rust cache')
        for hit in ['', 'false']:
            with self.subTest(cache_hit=hit):
                result = subprocess.run(
                    [str(bash), '-e', '-o', 'pipefail', '-c', script],
                    cwd=ROOT / 'apps/aerove', env={**os.environ, 'HOST_RUST_CACHE_HIT': hit},
                    capture_output=True, text=True,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Rebuild the native producer', result.stdout)

    def test_parallel_consumers_share_one_preparation_and_an_explicit_success_gate(self):
        def job(name):
            match = re.search(rf'(?ms)^  {name}:\n(.*?)(?=^  [a-z][a-z_-]*:|\Z)', FULL)
            self.assertIsNotNone(match, name)
            return match[1]

        self.assertEqual(FULL.count('dart run build_runner build'), 1)
        self.assertIn('needs: reuse', job('prep'))
        self.assertIn("needs.reuse.outputs.run_id == ''", job('prep'))
        self.assertIn('needs: prep', job('apk'))
        self.assertNotIn('needs: verify', job('apk'))
        self.assertIn("always() && needs.prep.result != 'skipped'", job('verify'))
        self.assertIn('needs: [prep, static, render, native, regressions]', job('verify'))
        self.assertIn('CI_VERIFY_NEEDS: ${{ toJSON(needs) }}', job('verify'))
        self.assertIn('python3 tool/ci_verify.py gate', job('verify'))
        self.assertIn('needs: [reuse, verify, apk]', job('release'))
        self.assertIn("needs.verify.result == 'success' && needs.apk.result == 'success'", job('release'))
        self.assertIn('needs.reuse.outputs.run_id || github.run_id', job('release'))
        self.assertIn('hooks_runner/shared/rust_api', job('native'))
        self.assertIn('hooks_runner/shared/rust_api', job('apk'))
        self.assertIn('tool/ci_verify.py host-assets --record', job('native'))
        native_consumers = job('regressions')
        self.assertIn('id: host_rust_cache', native_consumers)
        self.assertIn('fail-on-cache-miss: true', native_consumers)
        self.assertIn('steps.host_rust_cache.outputs.cache-hit', native_consumers)
        self.assertIn('tool/ci_verify.py host-assets', native_consumers)
        self.assertLess(native_consumers.index('Require exact producer host Rust cache'),
                        native_consumers.index('Run preserved verification combination'))
        self.assertIn('-aerove-rust-host-v2-', job('native'))
        self.assertIn('-aerove-rust-host-v2-', native_consumers)

    def test_matrix_consumers_restore_caches_and_keep_performance_on_its_own_runner(self):
        for name in ['render', 'regressions']:
            match = re.search(rf'(?ms)^  {name}:\n(.*?)(?=^  [a-z][a-z_-]*:|\Z)', FULL)
            contents = match[1]
            self.assertIn('runs-on: ubuntu-24.04', contents)
            self.assertIn('cache-read-only: true', contents)
            self.assertIn('fromJSON(needs.prep.outputs.', contents)
            self.assertIn('tool/ci_verify.py run', contents)
            self.assertNotIn('uses: actions/cache@', contents)
        action = (ROOT / '.github/actions/flutter/action.yaml').read_text(encoding='utf-8')
        self.assertIn("inputs.cache-read-only != 'true'", action)
        self.assertIn('uses: actions/cache/restore@', action)
        font = (ROOT / '.github/actions/aerove-test-font/action.yaml').read_text(encoding='utf-8')
        self.assertIn("inputs.save-cache == 'true'", font)
        self.assertIn('hashlib.sha256(path.read_bytes()).hexdigest() == expected', font)

    def test_all_active_actions_are_pinned_to_full_commits(self):
        paths = [*WORKFLOWS.glob('*.yaml'), *(ROOT / '.github/actions').rglob('action.yaml')]
        for path in paths:
            contents = path.read_text(encoding='utf-8')
            uses = re.findall(r'^\s+(?:- )?uses:\s+(\S+)', contents, re.MULTILINE)
            with self.subTest(path=path):
                self.assertTrue(uses)
                for action in uses:
                    if action.startswith('./'):
                        local = (ROOT / action[2:]).resolve()
                        self.assertTrue(local.is_relative_to(ROOT))
                        self.assertTrue((local / 'action.yaml').is_file())
                        continue
                    self.assertRegex(action, r'^[\w.-]+/[\w./-]+@[0-9a-f]{40}$')

    def test_official_source_and_real_go_resolution_precede_generated_tree_export(self):
        generate = run_block('Generate and format sources')
        prepare = generate.index('python3 tool/mihomo_source.py prepare')
        tidy = generate.index('(cd core && go mod tidy)')
        capture = generate.index('git -C "$GITHUB_WORKSPACE" diff --cached')
        self.assertLess(prepare, tidy)
        self.assertLess(tidy, capture)
        core = run_block('Verify Core background telemetry')
        self.assertIn('go test . -run UiTelemetry -count=2', core)
        self.assertIn('\ngo test .', core)
        self.assertEqual(FULL.count('flutter build apk '), 1)


class GeneratedPatchGitTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='aerove-workflow-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / 'repo'
        self.root.mkdir()
        self.product = self.root / 'apps/aerove'
        self.runner = Path(temporary.name) / 'runner'
        self.runner.mkdir()
        self.environment = {
            **os.environ, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
            'GIT_AUTHOR_NAME': 'Offline Test', 'GIT_COMMITTER_NAME': 'Offline Test',
            'GIT_AUTHOR_EMAIL': 'test@example.invalid',
            'GIT_COMMITTER_EMAIL': 'test@example.invalid',
            'GITHUB_WORKSPACE': self.root.as_posix(), 'RUNNER_TEMP': self.runner.as_posix(),
            'GIT_TERMINAL_PROMPT': '0',
        }
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        self.generated = (
            'lib/example.g.dart', 'test/example_test.dart', 'core/main.go',
            'core/go.mod', 'core/go.sum', 'pubspec.lock',
        )
        for relative in self.generated:
            path = self.product / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('before\n', encoding='utf-8', newline='\n')
        (self.root / 'shared-contract.txt').write_text('shared source\n', encoding='utf-8')
        self.git('add', '--all')
        self.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'workspace source')
        git = Path(shutil.which('git')).resolve()
        self.bash = git.parent.parent / 'bin/bash.exe' if os.name == 'nt' else shutil.which('bash')
        self.assertTrue(self.bash and Path(self.bash).is_file(), 'Bash is required for workflow replay')

    def git(self, *arguments):
        completed = subprocess.run(
            ['git', *arguments], cwd=self.root, env=self.environment,
            capture_output=True, text=True, encoding='utf-8',
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        return completed.stdout.strip()

    def shell(self, script, success=True):
        completed = subprocess.run(
            [str(self.bash), '-e', '-o', 'pipefail', '-c', script],
            cwd=self.product, env=self.environment, capture_output=True,
            text=True, encoding='utf-8',
        )
        if success:
            self.assertEqual(completed.returncode, 0, completed.stderr)
        else:
            self.assertNotEqual(completed.returncode, 0, completed.stdout)

    def capture(self):
        for relative in self.generated:
            (self.product / relative).write_text('after\n', encoding='utf-8', newline='\n')
        commands = '\n'.join(line for line in run_block('Generate and format sources').splitlines()
                             if line.startswith('git -C '))
        self.assertEqual(len(commands.splitlines()), 4)
        self.shell(commands)
        bundle = self.runner / 'xclash-generated'
        bundle.mkdir()
        for name in ('xclash-generated.patch', 'xclash-verified-tree.txt'):
            shutil.copyfile(self.runner / name, bundle / name)
        return bundle

    def test_generated_go_locks_and_code_replay_to_the_same_whole_repository_tree(self):
        bundle = self.capture()
        patch = (bundle / 'xclash-generated.patch').read_text(encoding='utf-8')
        for relative in self.generated:
            self.assertIn(f'diff --git a/apps/aerove/{relative} b/apps/aerove/{relative}', patch)
        self.assertEqual(self.git('diff', '--cached', '--name-only'), '')
        verified = (bundle / 'xclash-verified-tree.txt').read_text(encoding='ascii').strip()
        self.assertNotEqual(verified, self.git('rev-parse', 'HEAD^{tree}'))
        self.assertEqual(self.git('ls-tree', '--name-only', verified), 'apps\nshared-contract.txt')
        self.git('restore', '--worktree', '--', 'apps/aerove')
        self.shell(run_block('Apply verified generated sources'))
        self.assertEqual(self.git('diff', '--cached', '--name-only'), '')
        for relative in self.generated:
            self.assertEqual((self.product / relative).read_text(encoding='utf-8'), 'after\n')
        self.assertEqual((self.root / 'shared-contract.txt').read_text(encoding='utf-8'), 'shared source\n')

    def test_replay_rejects_a_different_shared_root_tree(self):
        self.capture()
        self.git('restore', '--worktree', '--', 'apps/aerove')
        (self.root / 'shared-contract.txt').write_text('different source\n', encoding='utf-8')
        self.git('add', 'shared-contract.txt')
        self.shell(run_block('Apply verified generated sources'), success=False)

    def test_replay_rejects_an_omitted_go_lock_change(self):
        bundle = self.capture()
        self.git('restore', '--worktree', '--', 'apps/aerove')
        patch = bundle / 'xclash-generated.patch'
        sections = re.split(r'(?=^diff --git )', patch.read_text(encoding='utf-8'), flags=re.MULTILINE)
        patch.write_text(''.join(section for section in sections
                                 if not section.startswith('diff --git a/apps/aerove/core/go.sum ')),
                         encoding='utf-8', newline='\n')
        self.shell(run_block('Apply verified generated sources'), success=False)


if __name__ == '__main__':
    unittest.main()
