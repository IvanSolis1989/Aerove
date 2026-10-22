import 'dart:io';

import 'package:test/test.dart';

final _textWidget = RegExp(
  r'\b(TextField|TextFormField|SelectableText(?:\.rich)?|SelectionArea|EditableText)\(',
);

const _requiredArguments = ['contextMenuBuilder', 'magnifierConfiguration'];

/// The text between the parentheses opening at [open], with everything nested
/// deeper than its own arguments blanked out, or null if it never closes.
String? _topLevelArguments(String source, int open) {
  final buffer = StringBuffer();
  var depth = 0;
  String? quote;
  for (var i = open; i < source.length; i++) {
    final char = source[i];
    if (quote != null) {
      if (char == r'\') {
        i++;
      } else if (char == quote) {
        quote = null;
      }
      continue;
    }
    if (char == "'" || char == '"') {
      quote = char;
      continue;
    }
    if (source.startsWith('//', i)) {
      i = source.indexOf('\n', i);
      if (i < 0) {
        return null;
      }
      continue;
    }
    if ('([{'.contains(char)) {
      depth++;
    } else if (')]}'.contains(char)) {
      depth--;
      if (depth == 0) {
        return buffer.toString();
      }
    } else if (depth == 1) {
      buffer.write(char);
    }
  }
  return null;
}

List<String> _offenders(String path, String source) {
  final offenders = <String>[];
  for (final match in _textWidget.allMatches(source)) {
    final lineStart = source.lastIndexOf('\n', match.start) + 1;
    final before = source.substring(lineStart, match.start).trimLeft();
    if (before.startsWith('//')) {
      continue;
    }
    final arguments = _topLevelArguments(source, match.end - 1) ?? '';
    final missing = [
      for (final name in _requiredArguments)
        if (!RegExp('\\b$name\\s*:').hasMatch(arguments)) name,
    ];
    if (missing.isEmpty) {
      continue;
    }
    final line = '\n'.allMatches(source.substring(0, match.start)).length + 1;
    offenders.add(
      '$path:$line — ${match[1]} needs ${missing.join(' and ')}. Pass '
      'glassEditableTextContextMenu (glassSelectableRegionContextMenu for a '
      'SelectionArea) and glassTextMagnifierConfiguration from '
      "lib/ui/text_context_menu.dart, or it shows Material's opaque toolbar "
      'with a glass pill behind every button and its rounded-rectangle loupe.',
    );
  }
  return offenders;
}

void main() {
  test('every text widget shows the glass selection menu', () {
    final offenders = <String>[];
    for (final entity in Directory('lib').listSync(recursive: true)) {
      final path = entity.path.replaceAll(r'\', '/');
      if (entity is! File ||
          !path.endsWith('.dart') ||
          path.contains('/generated/') ||
          path.contains('/l10n/intl/')) {
        continue;
      }
      offenders.addAll(_offenders(path, entity.readAsStringSync()));
    }
    expect(offenders, isEmpty, reason: offenders.join('\n'));
  });

  test('the scan reads only the arguments of the widget itself', () {
    const source = '''
SelectionArea(
  child: TextField(
    contextMenuBuilder: glassEditableTextContextMenu,
    magnifierConfiguration: glassTextMagnifierConfiguration,
  ),
);
FormRowTextField(controller: controller);
SelectableText.rich(
  TextSpan(text: 'a (b'),
  // contextMenuBuilder: in a comment
  contextMenuBuilder: glassEditableTextContextMenu,
  magnifierConfiguration: glassTextMagnifierConfiguration,
);
TextFormField(decoration: InputDecoration(hintText: ')'));
EditableText(contextMenuBuilder: glassEditableTextContextMenu);
''';
    expect(_offenders('sample.dart', source), [
      startsWith(
        'sample.dart:1 — SelectionArea needs contextMenuBuilder and '
        'magnifierConfiguration.',
      ),
      startsWith('sample.dart:14 — TextFormField needs contextMenuBuilder and'),
      startsWith('sample.dart:15 — EditableText needs magnifierConfiguration.'),
    ]);
  });
}
