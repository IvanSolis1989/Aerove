import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  for (final aurora in [false, true]) {
    for (final mode in LiquidGlassMode.values) {
      testWidgets('subscription progress follows $mode aurora=$aurora', (
        tester,
      ) async {
        final glass = mode != LiquidGlassMode.none;
        final quality = mode == LiquidGlassMode.ultra
            ? LiquidGlassQuality.ultra
            : LiquidGlassQuality.standard;
        await tester.pumpWidget(
          TestApp(
            homeBuilder: (child) => AuroraAppearance(
              enabled: aurora,
              child: LiquidGlassScope(
                enabled: glass,
                quality: quality,
                child: LiquidGlassHost(enabled: glass, child: child),
              ),
            ),
            child: const SizedBox(
              width: 300,
              child: SubscriptionInfoView(
                subscriptionInfo: SubscriptionInfo(
                  upload: 20,
                  download: 40,
                  total: 100,
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final loading = find.descendant(
          of: find.byType(SubscriptionInfoView),
          matching: find.byType(CommonLinearLoading),
        );
        expect(loading, findsOneWidget);
        expect(tester.widget<CommonLinearLoading>(loading).value, 0.6);
        expect(tester.getSize(loading).height, 4);
        final indicator = tester.widget<LinearProgressIndicator>(
          find.descendant(
            of: loading,
            matching: find.byType(LinearProgressIndicator),
          ),
        );
        expect(indicator.value, 0.6);
        expect(
          find.descendant(of: loading, matching: find.byType(GlassInk)),
          glass ? findsOneWidget : findsNothing,
        );
        if (glass) {
          expect(indicator.stopIndicatorRadius, 0);
          expect(
            indicator.color!.a,
            closeTo(mode == LiquidGlassMode.ultra ? 0.88 : 0.72, 0.005),
          );
        }
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('hides expiry when both values do not fit', (tester) async {
    const trafficLabel = '1.0 KB / 1.0 GB';

    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          AppUiLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        supportedLocales: AppLocalizations.delegate.supportedLocales,
        builder: (context, child) {
          return wrapAppScopes(context, child!);
        },
        home: const Scaffold(
          body: SizedBox(
            width: 120,
            child: SubscriptionInfoView(
              subscriptionInfo: SubscriptionInfo(
                upload: 1024,
                total: 1073741824,
                expire: 4102444800,
              ),
            ),
          ),
        ),
      ),
    );

    expect(tester.takeException(), isNull);
    expect(find.text(trafficLabel), findsOneWidget);
    expect(
      find.descendant(
        of: find.byType(SubscriptionInfoView),
        matching: find.bySubtype<Text>(),
      ),
      findsOneWidget,
    );
  });

  testWidgets('keeps expiry at its intrinsic width when both values fit', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        builder: (context, child) => wrapAppScopes(context, child!),
        localizationsDelegates: const [
          AppLocalizations.delegate,
          AppUiLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        supportedLocales: AppLocalizations.delegate.supportedLocales,
        home: const Scaffold(
          body: SizedBox(
            width: 400,
            child: SubscriptionInfoView(
              subscriptionInfo: SubscriptionInfo(
                upload: 1024,
                total: 1073741824,
                expire: 4102444800,
              ),
            ),
          ),
        ),
      ),
    );

    expect(tester.takeException(), isNull);
    expect(
      find.descendant(
        of: find.byType(SubscriptionInfoView),
        matching: find.bySubtype<Text>(),
      ),
      findsNWidgets(2),
    );
  });

  testWidgets('shows full subscription details in information rows', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          AppUiLocalizations.delegate,
          ...GlobalMaterialLocalizations.delegates,
        ],
        supportedLocales: AppLocalizations.delegate.supportedLocales,
        builder: (context, child) {
          return wrapAppScopes(context, child!);
        },
        home: const Scaffold(
          body: SubscriptionInfoDetailView(
            subscriptionInfo: SubscriptionInfo(
              upload: 1024,
              download: 2048,
              total: 1073741824,
              expire: 4102444800,
            ),
          ),
        ),
      ),
    );

    final appLocalizations = AppLocalizations.current;
    expect(find.text(appLocalizations.trafficUsage), findsOneWidget);
    expect(find.text(appLocalizations.usedTraffic), findsOneWidget);
    expect(find.text(appLocalizations.totalTraffic), findsOneWidget);
    expect(find.text(appLocalizations.expireTime), findsOneWidget);
    expect(find.text('3.0 KB'), findsOneWidget);
    expect(find.text('1.0 GB'), findsOneWidget);
    expect(find.byType(DecorationListItem), findsNWidgets(3));
  });
}
