import 'dart:io';
import 'dart:async';

import 'package:fl_clash/common/system_fonts.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

void main() {
  test(
    'catalog consumers share a read and invalidation refreshes it',
    () async {
      final reader = systemFontFamiliesReader;
      addTearDown(() => systemFontFamiliesReader = reader);
      final pending = Completer<List<String>>();
      var reads = 0;
      systemFontFamiliesReader = () {
        reads++;
        return pending.future;
      };
      final container = ProviderContainer();
      addTearDown(container.dispose);
      final first = container.read(installedSystemFontsProvider.future);
      final second = container.read(installedSystemFontsProvider.future);
      expect(reads, 1);
      pending.complete(['Installed CJK']);
      expect(await first, ['Installed CJK']);
      expect(await second, ['Installed CJK']);
      systemFontFamiliesReader = () async {
        reads++;
        return [];
      };
      container.invalidate(installedSystemFontsProvider);
      expect(
        await container.read(installedSystemFontsProvider.future),
        isEmpty,
      );
      expect(reads, 2);
    },
  );

  test('sorts families without case and drops duplicates and hidden ones', () {
    expect(
      sortFontFamilies([
        'Segoe UI',
        ' arial ',
        '.AppleSystemUIFont',
        '',
        'segoe ui',
        'Arial',
        'Microsoft YaHei',
      ]),
      ['arial', 'Microsoft YaHei', 'Segoe UI'],
    );
  });

  test('unavailable or unverified saved choices use the system default', () {
    expect(resolveSystemFontFamily(null, ['CJK']), isNull);
    expect(resolveSystemFontFamily('Old Latin', ['CJK']), isNull);
    expect(resolveSystemFontFamily('CJK', null), isNull);
    expect(resolveSystemFontFamily('CJK', ['CJK']), 'CJK');
    expect(resolveSystemFontFamily('CJK', []), isNull);
    expect(resolveSystemFontFamily('CJK', ['CJK']), 'CJK');
  });
  test('reads the host fonts sorted and unique, or nothing', () async {
    final families = await loadSystemFontFamilies();

    expect(families, sortFontFamilies(families));
  });

  test('reports a failed read as no fonts', () async {
    final reader = systemFontFamiliesReader;
    addTearDown(() => systemFontFamiliesReader = reader);
    systemFontFamiliesReader = () async => throw const OSError('dwrite', -1);

    expect(await loadSystemFontFamilies(), isEmpty);
  });
}
