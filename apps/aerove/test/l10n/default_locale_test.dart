import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('default is English without rewriting explicit language selections', () {
    expect(effectiveAppLocale(null), const Locale('en'));
    expect(effectiveAppLocale(''), const Locale('en'));
    expect(effectiveAppLocale('de'), const Locale('en'));
    expect(effectiveAppLocale('en'), const Locale('en'));
    expect(effectiveAppLocale('zh_CN'), const Locale('zh', 'CN'));
    expect(effectiveAppLocale('fr'), const Locale('fr'));
    expect(getLocaleForString(null), isNull);
  });

  testWidgets(
    'Chinese system does not override default or explicit selection',
    (tester) async {
      tester.binding.platformDispatcher.localeTestValue = const Locale(
        'zh',
        'CN',
      );
      addTearDown(tester.binding.platformDispatcher.clearLocaleTestValue);
      Future<void> render(String? savedLocale) async {
        await tester.pumpWidget(
          MaterialApp(
            locale: effectiveAppLocale(savedLocale),
            supportedLocales: AppLocalizations.delegate.supportedLocales,
            localizationsDelegates: const [
              AppLocalizations.delegate,
              GlobalMaterialLocalizations.delegate,
              GlobalWidgetsLocalizations.delegate,
              GlobalCupertinoLocalizations.delegate,
            ],
            home: Builder(
              builder: (context) =>
                  Text(AppLocalizations.of(context).aboutUsageNotice),
            ),
          ),
        );
        await tester.pumpAndSettle();
      }

      await render(null);
      expect(find.textContaining('For lawful use'), findsOneWidget);
      await render('zh_CN');
      expect(find.textContaining('仅用于合法'), findsOneWidget);
      await render('en');
      expect(find.textContaining('For lawful use'), findsOneWidget);
    },
  );
}
