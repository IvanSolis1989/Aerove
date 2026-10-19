import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tool import aerove_release_notes as notes


TAG = 'v0.9.1-X+2026102001'
INFO = {'source': 'a' * 40, 'version': TAG.removeprefix('v')}


class AeroveReleaseNotesTest(unittest.TestCase):
    def test_pending_notes_are_bound_to_the_exact_build(self):
        rendered = notes.render_release(
            '# Aerove\n\n## 待发布\n\n### 更新\n\n- 本轮更新记录\n\n'
            '## 0.9.1-X+2026101815 — 已发布\n\n- 上一版记录\n', TAG, INFO,
        )
        self.assertIn(f'<!-- aerove:changelog:tag {TAG} -->', rendered)
        self.assertIn('### 更新\n\n- 本轮更新记录', rendered)
        self.assertNotIn('上一版记录', rendered)
        self.assertLess(rendered.index(notes.BEGIN), rendered.index('本轮更新记录'))
        self.assertLess(rendered.index('本轮更新记录'), rendered.index(notes.END))

    def test_exact_section_wins_over_pending_and_other_builds(self):
        markdown = (
            '## 待发布\n- 未来内容\n'
            '## 0.9.1-X+2026102001 — 已发布\n- 目标版本\n'
            '## 0.9.1-X+20261020010 — 已发布\n- 不同构建\n'
        )
        self.assertEqual(notes.notes_for_release(markdown, TAG), '- 目标版本')

    def test_missing_empty_unchanged_or_ambiguous_notes_stop_publication(self):
        for markdown, previous in [
            ('## 0.9.1-X+2026101815 — 已发布\n- 上一版', ''),
            ('## 待发布\n稍后补充', ''),
            ('## 待发布\n- 旧记录', '## 待发布\n- 旧记录'),
            ('## 待发布\n- 一\n## 待发布\n- 二', ''),
            ('## 待发布\n- 一\n## Unreleased\n- 二', ''),
        ]:
            with self.subTest(markdown=markdown), self.assertRaises(ValueError):
                notes.render_release(markdown, TAG, INFO, previous)

    def test_rejects_wrong_build_identity_or_control_comments(self):
        for tag, info, markdown in [
            (TAG, {**INFO, 'version': '0.9.1-X+2026101815'}, '## 待发布\n- 新记录'),
            ('v0.9.1-X', INFO, '## 待发布\n- 新记录'),
            (TAG, {**INFO, 'source': 'main'}, '## 待发布\n- 新记录'),
            (TAG, INFO, '## 待发布\n- 新记录\n<!-- stale -->'),
        ]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                notes.render_release(markdown, tag, info)

    def test_first_release_and_historical_template_accept_real_pending_entries(self):
        for previous in ['', '# Aerove Android\n\n适用于 Android ARM64。']:
            rendered = notes.render_release('## 待发布\n- 本轮真实变更', TAG, INFO, previous)
            self.assertIn('本轮真实变更', rendered)

    def test_cli_reads_committed_source_and_rejects_reusing_previous_pending(self):
        script = Path(notes.__file__).resolve()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            environment = {**os.environ, 'GIT_AUTHOR_NAME': 'Test', 'GIT_COMMITTER_NAME': 'Test',
                           'GIT_AUTHOR_EMAIL': 'test@example.com', 'GIT_COMMITTER_EMAIL': 'test@example.com'}

            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, env=environment,
                                               text=True, encoding='utf-8').strip()

            git('init', '-q')
            changelog = root / notes.CHANGELOG_PATH
            changelog.parent.mkdir()
            changelog.write_text('## 待发布\n- 上一轮记录\n', encoding='utf-8')
            git('add', '.')
            git('commit', '-qm', 'previous')
            git('tag', 'v0.9.1-X+2026101815')
            source = git('rev-parse', 'HEAD')
            info_path = root / 'BUILD_INFO.json'
            info_path.write_text(json.dumps({**INFO, 'source': source}), encoding='utf-8')
            command = [sys.executable, str(script), '--tag', TAG, '--source', source,
                       '--previous-tag', 'v0.9.1-X+2026101815',
                       '--build-info', str(info_path), '--output', str(root / 'release.md')]
            rejected = subprocess.run(command, cwd=root, capture_output=True, text=True, encoding='utf-8')
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('unchanged', rejected.stderr)

            changelog.write_text('## 待发布\n- 本轮真实变更\n', encoding='utf-8')
            git('add', notes.CHANGELOG_PATH)
            git('commit', '-qm', 'current')
            source = git('rev-parse', 'HEAD')
            info_path.write_text(json.dumps({**INFO, 'source': source}), encoding='utf-8')
            command[command.index('--source') + 1] = source
            git('tag', 'v0.9.1-X+2026102000')
            changelog.write_text('## 待发布\n- 未提交的后续记录\n', encoding='utf-8')
            subprocess.run(command, cwd=root, check=True)
            rendered = (root / 'release.md').read_text(encoding='utf-8')
            self.assertIn('本轮真实变更', rendered)
            self.assertNotIn('未提交的后续记录', rendered)

            for previous_tag in ['', 'v0.9.1-X+2020010101-not-fetched']:
                command[command.index('--previous-tag') + 1] = previous_tag
                subprocess.run(command, cwd=root, check=True)
                self.assertIn('本轮真实变更', (root / 'release.md').read_text(encoding='utf-8'))

            git('checkout', '--orphan', 'unrelated-history')
            changelog.write_text('## 待发布\n- 其他历史分支的记录\n', encoding='utf-8')
            git('add', notes.CHANGELOG_PATH)
            git('commit', '-qm', 'unrelated release history')
            git('tag', 'v0.9.1-X+2026101800')
            command[command.index('--previous-tag') + 1] = 'v0.9.1-X+2026101800'
            subprocess.run(command, cwd=root, check=True)
            self.assertIn('本轮真实变更', (root / 'release.md').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
