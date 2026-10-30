import 'package:fl_clash/common/product_core_copy.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const locales = [
    Locale('en'),
    Locale('es'),
    Locale('fr'),
    Locale('ja'),
    Locale('ko'),
    Locale('ru'),
    Locale('zh', 'CN'),
  ];

  test(
    'both products select their actual core copy in every supported locale',
    () async {
      for (final locale in locales) {
        final l = await AppLocalizations.load(locale);
        final standard = productCoreCopy('mihomo', l);
        expect(standard.description, l.desc, reason: '$locale');
        expect(
          standard.software,
          l.disclaimerSoftwareContent,
          reason: '$locale',
        );
        expect(standard.license, l.disclaimerLicenseContent, reason: '$locale');
        final lite = productCoreCopy('meow-rs', l);
        expect(lite.description, l.liteDesc, reason: '$locale');
        expect(
          lite.software,
          l.liteDisclaimerSoftwareContent,
          reason: '$locale',
        );
        expect(lite.license, l.liteDisclaimerLicenseContent, reason: '$locale');
        for (final copy in [lite.description, lite.software, lite.license]) {
          expect(copy, contains('meow-rs'), reason: '$locale');
          expect(
            RegExp(r'Clash\.?Meta|mihomo', caseSensitive: false).hasMatch(copy),
            isFalse,
            reason: '$locale',
          );
        }
        expect(lite.description, contains('Android 13'), reason: '$locale');
        expect(lite.software, contains('Android 13'), reason: '$locale');
        expect(
          lite.license.replaceAll('meow-rs', 'Clash.Meta'),
          standard.license,
          reason: '$locale',
        );
      }
    },
  );

  test(
    'an unknown core never silently receives Standard legal identity',
    () async {
      final l = await AppLocalizations.load(const Locale('en'));
      expect(() => productCoreCopy('unknown', l), throwsArgumentError);
    },
  );
}
