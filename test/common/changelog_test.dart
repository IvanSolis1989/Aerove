import 'dart:convert';

import 'package:fl_clash/common/changelog.dart';
import 'package:fl_clash/models/changelog.dart';
import 'package:flutter_test/flutter_test.dart';

String _body(String payload) =>
    '<!-- flclash:changelog:begin -->\n'
    '### Features\n'
    '- Override scripts\n'
    '<!-- flclash:changelog:end -->\n'
    '\n'
    '$releaseChangelogJsonMarker\n'
    '$payload\n'
    '-->\n'
    '\n'
    '| Platform | Download |\n';

const _payload =
    '{"schemaVersion":2,"versions":[{"version":"0.8.96","tag":"v0.8.96",'
    '"date":"2026-08-16","prerelease":false,"groups":[{"type":"feat",'
    '"entries":[{"id":"1a2b3c4","scope":"profiles","text":'
    '"Override scripts"}]}]}]}';

void main() {
  group('parseReleaseChangelog', () {
    test('reads the payload embedded in a release body', () {
      final version = parseReleaseChangelog(_body(_payload));

      expect(version?.tag, 'v0.8.96');
      expect(version?.date, '2026-08-16');
      final entry = version!.visibleGroups.single.entries.single;
      expect(version.visibleGroups.single.type, ChangelogType.feat);
      expect(entry.scope, 'profiles');
      expect(entry.text, 'Override scripts');
    });

    test('selects the exact target tag instead of the first payload version', () {
      final payload = jsonDecode(_payload) as Map<String, dynamic>;
      final versions = payload['versions'] as List<dynamic>;
      versions.insert(0, {
        'version': '0.8.95',
        'tag': 'v0.8.95',
        'groups': [],
      });
      expect(
        parseReleaseChangelog(_body(jsonEncode(payload)), tagName: 'v0.8.96')
            ?.version,
        '0.8.96',
      );
      expect(
        parseReleaseChangelog(_body(jsonEncode(payload)), tagName: 'v0.8.97'),
        isNull,
      );
    });

    test('does not confuse different builds of the same upstream version', () {
      const payload =
          '{"schemaVersion":2,"versions":[{"version":"0.9.1-X+2026101815",'
          '"tag":"v0.9.1-X+2026101815","groups":[]}]}';
      expect(
        parseReleaseChangelog(
          _body(payload),
          tagName: 'v0.9.1-X+2026102001',
        ),
        isNull,
      );
      expect(
        parseReleaseChangelog(
          _body(
            '{"schemaVersion":2,"versions":[{"version":"0.9.1",'
            '"tag":"v0.9.1","groups":[]}]}',
          ),
          tagName: 'v0.9.1-X+2026102001',
        ),
        isNull,
      );
    });

    test('decodes the escaped > the renderer writes for a `-->` entry', () {
      const payload =
          '{"schemaVersion":2,"versions":[{"version":"0.8.96",'
          '"tag":"v0.8.96","date":"","prerelease":false,"groups":'
          '[{"type":"fix","entries":[{"id":"1a2b3c4","text":'
          r'"Handle a --\u003e sequence"}]}]}]}';

      final version = parseReleaseChangelog(_body(payload));

      expect(
        version?.visibleGroups.single.entries.single.text,
        'Handle a --> sequence',
      );
    });

    test('returns null for a body without the payload', () {
      expect(parseReleaseChangelog('- Override scripts'), isNull);
    });

    test('returns null for a null body', () {
      expect(parseReleaseChangelog(null), isNull);
    });

    test('returns null when the comment is never closed', () {
      expect(
        parseReleaseChangelog('$releaseChangelogJsonMarker\n$_payload\n'),
        isNull,
      );
    });

    test('returns null for a payload from a newer schema', () {
      expect(
        parseReleaseChangelog(
          _body(
            '{"schemaVersion":3,"versions":[{"version":"0.9.0",'
            '"tag":"v0.9.0","date":"","prerelease":false,"groups":[]}]}',
          ),
        ),
        isNull,
      );
    });

    test('returns null for a payload that is not valid json', () {
      expect(parseReleaseChangelog(_body('{oops')), isNull);
    });

    test('returns null when the payload carries no version', () {
      expect(
        parseReleaseChangelog(_body('{"schemaVersion":2,"versions":[]}')),
        isNull,
      );
    });

    test('returns null for the retired schema 1 payload shape', () {
      expect(
        parseReleaseChangelog(
          _body(
            '{"schemaVersion":1,"versions":[{"version":"0.8.96",'
            '"tag":"v0.8.96","date":"","prerelease":false,"groups":'
            '[{"type":"feat","entries":[{"id":"1a2b3c4","text":'
            '{"en":"Override scripts"}}]}]}]}',
          ),
        ),
        isNull,
      );
    });
  });

  group('releaseNotesText', () {
    test('preserves paragraphs and headings inside the release notes scope', () {
      expect(
        releaseNotesText(
          '# Aerove v1\n<!-- flclash:changelog:begin -->\n'
          '<!-- aerove:changelog:tag v1 -->\n'
          '### 修复\n\n这是真实更新说明。\n- 可滚动查看\n'
          '<!-- flclash:changelog:end -->\n签名与下载信息',
          tagName: 'v1',
        ),
        '### 修复\n\n这是真实更新说明。\n- 可滚动查看',
      );
    });

    test('rejects mismatched bound notes and the legacy release template', () {
      expect(
        releaseNotesText(
          '<!-- flclash:changelog:begin -->\n'
          '<!-- aerove:changelog:tag v0.9.1-X+2026101815 -->\n- 旧记录\n'
          '<!-- flclash:changelog:end -->',
          tagName: 'v0.9.1-X+2026102001',
        ),
        isEmpty,
      );
      expect(releaseNotesText('# Aerove Android\n\n适用于 Android ARM64。'), isEmpty);
      expect(releaseNotesText(null), isEmpty);
      expect(releaseNotesText(_body(_payload)), isEmpty);
    });
  });

  test('fork changelog fallback selects one exact released build only', () {
    const markdown =
        '## 待发布\n- 未来内容\n'
        '## 0.9.1-X+2026101815 — 已发布\n\n### 修复\n- 目标内容\n'
        '## 0.9.1-X+2026101804 — 已发布\n- 旧版内容\n';
    expect(
      aeroveNotesForTag(markdown, 'v0.9.1-X+2026101815'),
      '### 修复\n- 目标内容',
    );
    expect(aeroveNotesForTag(markdown, 'v0.9.1-X+2026102001'), isNull);
    expect(aeroveNotesForTag(markdown, 'v0.9.1-X+202610181'), isNull);
  });
}
