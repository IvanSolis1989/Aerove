import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:test/test.dart';

final _plainController = RegExp(r'\bScrollController\(');

final _primaryOptOut = RegExp(r'\bprimary:\s*false\b');

const _allowed = {
  'lib/ui/inherited.dart',
  // A drag on the editor's completion overlay must never move a sheet.
  'lib/features/editor/completion_popup.dart',
  // Nor a drag on a popup menu, which stands over the sheet it opened from.
  'lib/ui/popup.dart',
  // Nor a drag along the proxies group chips, which run across the sheet.
  'lib/views/proxies/group_tabs.dart',
};

int _lineOf(String source, int offset) =>
    '\n'.allMatches(source.substring(0, offset)).length + 1;

bool _allowsPrimaryOptOut(String path, String source, Match match) {
  var depth = 0;
  var opening = match.start - 1;
  for (; opening >= 0; opening--) {
    final character = source[opening];
    if (character == ')') depth++;
    if (character == '(') {
      if (depth == 0) break;
      depth--;
    }
  }
  if (opening < 0 ||
      !RegExp(r'\bCustomScrollView$').hasMatch(source.substring(0, opening))) {
    return false;
  }
  final arguments = source.substring(opening + 1, match.start);
  final following = source.substring(match.end);
  if (arguments.trim().isEmpty &&
      RegExp(
        r'^\s*,\s*physics:\s*const NeverScrollableScrollPhysics\(\),',
      ).hasMatch(following)) {
    return true;
  }
  return path == 'lib/views/proxies/list.dart' &&
      RegExp(r'\bcontroller:\s*_scrollController,\s*$').hasMatch(arguments) &&
      source.contains("import 'group_scroll.dart';") &&
      RegExp(
        r'late final _scrollController\s*=\s*ProxyGroupScrollController\('
        r'\s*outer:\s*widget\.outerController,',
      ).hasMatch(source) &&
      RegExp(r'\bouterController:\s*_controller,').hasMatch(source) &&
      RegExp(
        r'\b_controller\s*=\s*sheetScrollController\(context\);',
      ).hasMatch(source);
}

void main() {
  test('scroll views in lib let a bottom sheet take their drags', () {
    final offenders = <String>[];

    for (final entity in Directory('lib').listSync(recursive: true)) {
      if (entity is! File ||
          !entity.path.endsWith('.dart') ||
          entity.path.contains('/generated/')) {
        continue;
      }
      final path = p.relative(entity.path).replaceAll(r'\', '/');
      if (_allowed.contains(path)) {
        continue;
      }
      final source = entity.readAsStringSync();
      for (final match in _plainController.allMatches(source)) {
        offenders.add(
          '$path:${_lineOf(source, match.start)} — a plain ScrollController '
          'never hands a drag to the bottom sheet around it; use '
          'sheetScrollController(context).',
        );
      }
      for (final match in _primaryOptOut.allMatches(source)) {
        if (_allowsPrimaryOptOut(path, source, match)) continue;
        offenders.add(
          '$path:${_lineOf(source, match.start)} — primary: false scrolls '
          'with a controller of its own, which never hands a drag to the '
          'bottom sheet around it; pass sheetScrollController(context) or '
          'a verified nested coordinator.',
        );
      }
    }

    expect(offenders, isEmpty, reason: offenders.join('\n'));
  });

  test(
    'primary opt-outs require a noninteractive view or a sheet coordinator',
    () {
      const path = 'lib/views/proxies/list.dart';
      const source = """
import 'group_scroll.dart';
_controller = sheetScrollController(context);
_GroupSliver(outerController: _controller,);
late final _scrollController = ProxyGroupScrollController(
  outer: widget.outerController,
  anchor: () => widget.groupOffset(),
);
CustomScrollView(
  key: PageStorageKey(('proxy-group', groupName)),
  controller: _scrollController,
  primary: false,
);
""";
      bool allowed(String candidate, {String candidatePath = path}) =>
          _allowsPrimaryOptOut(
            candidatePath,
            candidate,
            _primaryOptOut.firstMatch(candidate)!,
          );
      expect(allowed(source), isTrue);
      expect(allowed(source, candidatePath: 'lib/other.dart'), isFalse);
      for (final fragment in [
        "import 'group_scroll.dart';",
        '_controller = sheetScrollController(context);',
        'outerController: _controller,',
        'ProxyGroupScrollController',
        'outer: widget.outerController,',
      ]) {
        expect(allowed(source.replaceFirst(fragment, 'unverified')), isFalse);
      }
      expect(
        allowed(source.replaceFirst('controller: _scrollController,', '')),
        isFalse,
      );
      const unrelated = 'ListView(controller: other, primary: false)';
      final combined = '$source\n$unrelated';
      expect(
        _allowsPrimaryOptOut(
          path,
          combined,
          _primaryOptOut.allMatches(combined).last,
        ),
        isFalse,
      );
      expect(
        allowed(
          'CustomScrollView(primary: false, '
          'physics: const NeverScrollableScrollPhysics(),)',
        ),
        isTrue,
      );
      expect(allowed('CustomScrollView(primary: false,)'), isFalse);
      expect(
        allowed(
          'ListView(primary: false, '
          'physics: const NeverScrollableScrollPhysics(),)',
        ),
        isFalse,
      );
    },
  );
}
