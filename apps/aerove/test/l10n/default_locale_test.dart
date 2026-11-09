import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('unset and invalid selections follow system without rewriting explicit choices', () {
    expect(effectiveAppLocale(null), isNull);
    expect(effectiveAppLocale(''), isNull);
    expect(effectiveAppLocale('de'), isNull);
    expect(effectiveAppLocale('en'), const Locale('en'));
    expect(effectiveAppLocale('zh_CN'), const Locale('zh', 'CN'));
    expect(effectiveAppLocale('fr'), const Locale('fr'));
    expect(getLocaleForString(null), isNull);
  });

  testWidgets(
    'unset locale follows Chinese system and explicit selections take precedence',
    (tester) async {
      tester.binding.platformDispatcher.localesTestValue = const [Locale('zh', 'CN')];
      addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
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
      expect(find.textContaining('仅用于合法'), findsOneWidget);
      await render('zh_CN');
      expect(find.textContaining('仅用于合法'), findsOneWidget);
      await render('en');
      expect(find.textContaining('For lawful use'), findsOneWidget);
      tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
      await render(null);
      expect(find.textContaining('For lawful use'), findsOneWidget);
      await render('zh_CN');
      expect(find.textContaining('仅用于合法'), findsOneWidget);
    },
  );
}
