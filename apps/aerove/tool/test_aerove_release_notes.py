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
    def test_original_std74_candidate_preamble_is_removed_without_changing_build_identity(self):
        info = {'source': '806558b3c397ebd6b3d4ea4d558a442828c6d285',
                'version': '0.11.0-beta.14+2026102274'}
        status = '本候选尚未构建、验收或发行，安装编号由下一次实际构建生成。'
        entries = ('- 主题色选项“普通主题色”更名为“经典单色”，同步七种语言。\n'
                   '- 融合网络控制卡显式开启局域网代理后，成功提示并复制局域网 IP 与端口；失败、取消或过时结果不触发复制，卡片保持原布局。')
        markdown = '## 0.11.0-beta.14 — 开发候选\n\n' + status + '\n\n' + entries
        rendered = notes.render_release(markdown, 'v' + info['version'], info)
        self.assertEqual(notes.notes_for_release(markdown, 'v' + info['version']), entries)
        self.assertIn(f'版本：`{info["version"]}`', rendered)
        self.assertIn(f'源码：`{info["source"]}`', rendered)
        self.assertNotIn(status, rendered)
        for invalid in (status + '后续说明', status.replace('尚未构建', '已经构建'),
                        status + '\n\n其他状态', '<!-- control -->' + status):
            with self.subTest(status=invalid), self.assertRaises(ValueError):
                notes.render_release(markdown.replace(status, invalid), 'v' + info['version'], info)

    def test_independent_stable_and_preview_tags_keep_exact_build_binding(self):
        for tag in ['v1.0.0+2026102002', 'v1.1.0-beta.1+2026102003']:
            info = {**INFO, 'version': tag.removeprefix('v')}
            rendered = notes.render_release('## 待发布\n- 独立产品更新', tag, info)
            self.assertIn(f'<!-- aerove:changelog:tag {tag} -->', rendered)
            with self.assertRaises(ValueError):
                notes.render_release('## 待发布\n- 独立产品更新', tag, INFO)
        for tag in ['v1.0.0+0', 'v01.0.0+42', 'v1.0.0-beta.01+42',
                    'v1.0.0+42+extra', 'v1.0.0+42/other']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                notes.notes_for_release('## 待发布\n- 独立产品更新', tag)

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

    def test_unique_same_version_development_candidate_keeps_build_and_source_binding(self):
        tag = 'v0.11.0-beta.11+2026102264'
        info = {**INFO, 'version': tag.removeprefix('v')}
        for title in ['0.11.0-beta.11 — 开发候选，待构建和验收',
                      'v0.11.0-beta.11 — 开发候选']:
            with self.subTest(title=title):
                markdown = f'## {title}\n本候选未接受、未发行。\n\n- 本次修改\n'
                rendered = notes.render_release(markdown, tag, info)
                self.assertNotIn('本候选未接受、未发行。', rendered)
                self.assertIn(f'版本：`{info["version"]}`', rendered)
                self.assertIn(f'源码：`{info["source"]}`', rendered)
                with self.assertRaises(ValueError):
                    notes.render_release(markdown, tag, INFO)
                with self.assertRaises(ValueError):
                    notes.render_release(markdown, tag, {**info, 'source': 'main'})

    def test_candidate_entries_keep_continuations_and_links_without_unaccepted_status(self):
        body = ('新原包、完整 CI、Android 功能与用户验收仍待完成，本候选未接受、未发行。\n\n'
                '- 修复实际功能，参见 [来源](https://example.invalid/source)。\n'
                '  保留条目的连续说明。\n\n'
                '- 第二项修改\n'
                '  [回归证据](https://example.invalid/test)')
        markdown = '## 0.9.1-X — 开发候选，待构建和验收\n' + body
        rendered = notes.render_release(markdown, TAG, INFO)
        self.assertNotIn('仍待完成', rendered)
        self.assertNotIn('未接受、未发行', rendered)
        self.assertIn(body.split('\n\n', 1)[1], rendered)
        exact = f'## {TAG} — 已发布\n' + body
        self.assertEqual(notes.notes_for_release(exact, TAG), body)

    def test_candidate_body_structure_fails_closed(self):
        for body in ['尚待构建。\n\n- 修改',
                     '<!-- control -->本候选未接受、未发行。\n\n- 修改',
                     '### 本候选未接受、未发行。\n\n- 修改',
                     '本候选未接受、未发行。\n- 修改',
                     '本候选未接受、未发行。\n\n第二段状态\n\n- 修改',
                     '本候选未接受、未发行。\n\n### 更新\n- 修改',
                     '- 修改\n未缩进的额外段落',
                     '- 修改\n\n本候选未接受、未发行。']:
            markdown = '## 0.9.1-X — 开发候选\n' + body
            with self.subTest(body=body), self.assertRaises(ValueError):
                notes.render_release(markdown, TAG, INFO)

    def test_exact_build_section_wins_over_candidate_and_pending(self):
        markdown = ('## 0.9.1-X — 开发候选\n- 候选内容\n'
                    '## 待发布\n- 后续内容\n'
                    f'## {TAG} — 已发布\n- 精确构建内容\n')
        self.assertEqual(notes.notes_for_release(markdown, TAG), '- 精确构建内容')

    def test_candidate_fallback_rejects_ambiguity_wrong_versions_and_invalid_entries(self):
        for markdown in [
            '## 0.9.1-X — 开发候选\n- 一\n## v0.9.1-X — 开发候选，待验收\n- 二',
            '## 0.9.1-X — 开发候选\n- 一\n## 0.9.1-X — 已发布\n- 二',
            '## 0.9.1-X — 开发候选\n- 一\n## 0.9.1-X — 开发候选\n- 二',
            '## 0.9.1-X — 已发布\n- 旧记录',
            '## 0.9.1-X\n- 无候选身份',
            '## 0.9.1-X-extra — 开发候选\n- 其他版本',
            '## 0.9.1-X+2026102002 — 开发候选\n- 其他构建',
            '## 0.9.1-X — 开发候选\n稍后补充',
            '## 0.9.1-X — 开发候选\n- 更新\n<!-- control -->',
            f'## {TAG} — 已发布\n- 一\n## {TAG.removeprefix("v")}\n- 二',
        ]:
            with self.subTest(markdown=markdown), self.assertRaises(ValueError):
                notes.render_release(markdown, TAG, INFO)

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
            changelog.parent.mkdir(parents=True)
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

    def test_migrated_cli_keeps_old_tag_notes_and_reads_new_path_at_requested_source(self):
        script = Path(notes.__file__).resolve()
        with tempfile.TemporaryDirectory(prefix='aerove-notes-monorepo-') as directory:
            root = Path(directory)
            environment = {
                **os.environ, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                'GIT_AUTHOR_NAME': 'Test', 'GIT_COMMITTER_NAME': 'Test',
                'GIT_AUTHOR_EMAIL': 'test@example.invalid',
                'GIT_COMMITTER_EMAIL': 'test@example.invalid',
                'PYTHONDONTWRITEBYTECODE': '1',
            }

            def git(*arguments):
                return subprocess.check_output(
                    ['git', *arguments], cwd=root, env=environment,
                    text=True, encoding='utf-8',
                ).strip()

            def commit(message):
                git('add', 'docs')
                git('-c', 'commit.gpgsign=false', 'commit', '-qm', message)
                return git('rev-parse', 'HEAD')

            git('init', '-q')
            git('config', 'core.autocrlf', 'false')
            old_path = root / notes.LEGACY_CHANGELOG_PATH
            old_path.parent.mkdir(parents=True)
            old_path.write_text('## 待发布\n- 上次发行内容\n', encoding='utf-8')
            commit('historical release notes')
            previous = 'v0.9.1-X+2026101815'
            git('tag', previous)
            new_path = root / notes.CHANGELOG_PATH
            new_path.parent.mkdir(parents=True)
            old_path.rename(new_path)
            source = commit('move notes without changing entries')
            product = root / 'apps/aerove'
            product.mkdir(parents=True)
            info_path = root / 'BUILD_INFO.json'
            output = root / 'release.md'

            def cli(source):
                info_path.write_text(json.dumps({**INFO, 'source': source}), encoding='utf-8')
                return subprocess.run(
                    [sys.executable, '-B', str(script), '--repo', str(product),
                     '--tag', TAG, '--source', source, '--previous-tag', previous,
                     '--build-info', str(info_path), '--output', str(output)],
                    cwd=root.parent, env=environment, capture_output=True,
                    text=True, encoding='utf-8',
                )

            rejected = cli(source)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('unchanged', rejected.stderr)
            new_path.write_text('## 待发布\n- 本轮独立更新\n', encoding='utf-8')
            old_path.write_text('## 待发布\n- 失效的历史镜像\n', encoding='utf-8')
            source = commit('new notes in the canonical workspace path')
            new_path.write_text('## 待发布\n- 工作区后续修改\n', encoding='utf-8')
            accepted = cli(source)
            self.assertEqual(accepted.returncode, 0, accepted.stderr)
            rendered = output.read_text(encoding='utf-8')
            self.assertIn('本轮独立更新', rendered)
            self.assertNotIn('失效的历史镜像', rendered)
            self.assertNotIn('工作区后续修改', rendered)
            self.assertEqual(
                notes.previous_source_changelog(previous, repo=product),
                '## 待发布\n- 上次发行内容\n',
            )


if __name__ == '__main__':
    unittest.main()
