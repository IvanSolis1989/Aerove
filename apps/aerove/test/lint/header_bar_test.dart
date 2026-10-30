import 'dart:io';

import 'package:test/test.dart';

import '../helpers/client_sources.dart';

final _bar = RegExp(r'\b(?:Sliver)?AppBar\(|\bappBar:');

const _allowed = {
  'lib/ui/scaffold.dart',
  // The camera runs under the whole bar, so its buttons keep their frame.
  'lib/pages/scan.dart',
};

int _lineOf(String source, int offset) =>
    '\n'.allMatches(source.substring(0, offset)).length + 1;

void main() {
  test('pages take their top bar from CommonScaffold', () {
    final offenders = <String>[];

    for (final entity in clientSourceDirectory(
      'lib',
    ).listSync(recursive: true)) {
      if (entity is! File || !entity.path.endsWith('.dart')) {
        continue;
      }
      final path = clientSourcePath(entity.path).replaceAll(r'\', '/');
      if (path.contains('/generated/') || _allowed.contains(path)) {
        continue;
      }
      final source = entity.readAsStringSync();
      for (final match in _bar.allMatches(source)) {
        offenders.add(
          '$path:${_lineOf(source, match.start)} — a bar of its own frames '
          'its buttons whether or not content runs under it; build the page '
          'on CommonScaffold, which frames them only once it does.',
        );
      }
    }

    expect(offenders, isEmpty, reason: offenders.join('\n'));
  });
}
