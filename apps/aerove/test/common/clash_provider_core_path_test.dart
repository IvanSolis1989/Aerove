import 'dart:io';

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/app_paths.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory home;
  setUpAll(() async {
    home = await Directory.systemTemp.createTemp('aerove-rule-core-path-');
    await useAppPaths(home);
  });
  tearDownAll(() async {
    await home.delete(recursive: true);
  });

  test(
    'the source format selects a real core input without product globals',
    () async {
      for (final format in [RuleProviderFormat.yaml, RuleProviderFormat.text]) {
        final provider = ClashProvider(
          id: 1,
          label: 'domains',
          behavior: RuleProviderBehavior.domain,
          format: format,
        );
        final raw = await provider.path;
        expect(provider.isCompiled('mihomo'), isTrue);
        expect(provider.isCompiled('meow-rs'), isFalse);
        expect(await provider.corePath('mihomo'), '$raw.mrs');
        expect(await provider.corePath('meow-rs'), raw);
        expect(
          provider.definition(raw, coreId: 'meow-rs')['format'],
          format.name,
        );
        expect(
          provider.definition('$raw.mrs', coreId: 'mihomo')['format'],
          'mrs',
        );
      }
    },
  );

  test(
    'native mrs and classical input never require a second derived file',
    () {
      for (final provider in [
        const ClashProvider(
          id: 2,
          label: 'mrs',
          behavior: RuleProviderBehavior.ipcidr,
          format: RuleProviderFormat.mrs,
        ),
        const ClashProvider(
          id: 3,
          label: 'classical',
          format: RuleProviderFormat.text,
        ),
      ]) {
        for (final core in ['mihomo', 'meow-rs']) {
          expect(provider.isCompiled(core), isFalse);
          expect(
            provider.definition('raw', coreId: core)['format'],
            provider.format.name,
          );
        }
      }
    },
  );

  test(
    'Meow preserves the authoritative source definition and original format',
    () {
      final provider = ClashProvider(
        id: 4,
        label: 'remote',
        url: 'https://example.test/rules',
        behavior: RuleProviderBehavior.domain,
        format: RuleProviderFormat.yaml,
        sourceDefinition: {
          'interval': 123,
          'proxy': 'choice',
          'header': {
            'X-Test': ['value'],
          },
        },
      );
      final value = provider.definition('raw', coreId: 'meow-rs');
      expect(value['type'], 'http');
      expect(value['path'], 'raw');
      expect(value['url'], provider.url);
      expect(value['format'], 'yaml');
      expect(value['interval'], 123);
      expect(value['proxy'], 'choice');
      expect(value['header'], {
        'X-Test': ['value'],
      });
    },
  );
}
