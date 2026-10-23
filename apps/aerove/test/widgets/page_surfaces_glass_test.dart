import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/access.dart';
import 'package:fl_clash/views/config/on_demand.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:fl_clash/views/hotkey.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/views/theme_preview.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart' show Override;
import 'package:wifi_ssid/wifi_ssid.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class _ExcludeSSIDs extends ExcludeSSIDs {
  @override
  List<String> build() => const ['Home'];
}

class _LocationPermissions extends LocationPermissions {
  @override
  WifiSsidPermission build() => WifiSsidPermission.denied;
}

class _BatteryIgnored extends BatteryOptimizationIgnored {
  @override
  Stream<bool> build() => Stream.value(false);
}

class _SystemAction extends SystemAction {
  @override
  Future<List<Package>> getPackages() async => ref.read(packagesProvider);

  @override
  Future<bool> isInstalledAppsPermissionGranted() async => true;
}

typedef _Case = ({
  Brightness brightness,
  LiquidGlassMode mode,
  bool highContrast,
});

final _cases = <_Case>[
  for (final brightness in Brightness.values) ...[
    for (final mode in LiquidGlassMode.values)
      (brightness: brightness, mode: mode, highContrast: false),
    (brightness: brightness, mode: LiquidGlassMode.ultra, highContrast: true),
  ],
];

Future<void> _pump(
  WidgetTester tester,
  _Case glass, {
  required Widget page,
  Size size = const Size(400, 900),
  List<Override> overrides = const [],
  void Function(ProviderContainer container)? seed,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final container = ProviderContainer(
    overrides: [
      profilesProvider.overrideWith(TestProfiles.new),
      themeSettingProvider.overrideWithBuild(
        (_, _) => ThemeProps(
          themeMode: glass.brightness == Brightness.light
              ? ThemeMode.light
              : ThemeMode.dark,
          liquidGlass: glass.mode,
          auroraEnabled: false,
        ),
      ),
      ...overrides,
    ],
  );
  addTearDown(container.dispose);
  addTearDown(() => tester.pumpWidget(const SizedBox.shrink()));
  globalState.container = container;
  container.read(viewSizeProvider.notifier).value = size;
  seed?.call(container);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: Consumer(
        builder: (_, ref, _) => MaterialApp(
          navigatorKey: globalState.navigatorKey,
          theme: watchAppTheme(ref, glass.brightness),
          darkTheme: watchAppTheme(ref, glass.brightness),
          themeMode: glass.brightness == Brightness.light
              ? ThemeMode.light
              : ThemeMode.dark,
          localizationsDelegates: const [
            AppLocalizations.delegate,
            AppUiLocalizations.delegate,
            ...GlobalMaterialLocalizations.delegates,
          ],
          supportedLocales: AppLocalizations.delegate.supportedLocales,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(highContrast: glass.highContrast),
            child: wrapAppScopes(
              context,
              AuroraAppSurface(motion: false, child: child!),
            ),
          ),
          home: page,
        ),
      ),
    ),
  );
  for (var frame = 0; frame < 6; frame++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

bool _glassOn(_Case glass) =>
    glass.mode != LiquidGlassMode.none && !glass.highContrast;

String _name(_Case glass) =>
    '${glass.brightness.name} ${glass.mode.name}'
    '${glass.highContrast ? ' high contrast' : ''}';

List<Rect> get _panes => [
  for (final pane in LiquidGlassHost.debugLastCapture!.panes) pane.pane,
];

bool _covers(Rect outer, Rect inner) {
  final bounds = outer.inflate(0.5);
  return bounds.left <= inner.left &&
      bounds.top <= inner.top &&
      bounds.right >= inner.right &&
      bounds.bottom >= inner.bottom;
}

List<({Color color, Element element})> _fillsBetween(
  WidgetTester tester,
  Finder anchor,
  Finder? outer,
) {
  final fills = <({Color color, Element element})>[];
  final stop = outer == null ? null : tester.element(outer);
  tester.element(anchor.first).visitAncestorElements((element) {
    if (identical(element, stop)) return false;
    final widget = element.widget;
    final decoration = switch (widget) {
      Container(:final decoration?) => decoration,
      DecoratedBox(:final decoration) => decoration,
      _ => null,
    };
    final color = switch ((decoration, widget)) {
      (ShapeDecoration(:final color?), _) => color,
      (_, Material(:final color?)) => color,
      _ => null,
    };
    if (color != null) fills.add((color: color, element: element));
    return true;
  });
  return fills;
}

/// The card [anchor] sits on: one [CardGroup] pane on glass with no fill of
/// its own between the pane and [anchor], the opaque fill off glass.
void _expectOneCardPane(WidgetTester tester, Finder anchor, _Case glass) {
  expect(anchor, findsWidgets);
  final group = find.ancestor(
    of: anchor.first,
    matching: find.byType(CardGroup),
  );
  expect(group, findsOneWidget);
  expect(
    find.descendant(of: group, matching: find.byType(LiquidGlass)),
    _glassOn(glass) ? findsOneWidget : findsNothing,
  );
  final fills = _fillsBetween(tester, anchor, group).map((fill) => fill.color);
  if (!_glassOn(glass)) {
    expect(fills.where((color) => color.a == 1), isNotEmpty);
    return;
  }
  expect(fills.where((color) => color.a > 0), isEmpty);
  final card = tester.getRect(group);
  expect(_panes.where((pane) => _covers(card, pane)), [
    rectMoreOrLessEquals(card, epsilon: 0.5),
  ]);
}

void _expectWashCard(WidgetTester tester, Finder anchor, _Case glass) {
  expect(anchor, findsWidgets);
  expect(
    find.ancestor(of: anchor.first, matching: find.byType(CardGroup)),
    findsNothing,
  );
  final wash = _fillsBetween(
    tester,
    anchor,
    null,
  ).firstWhere((fill) => fill.color.a > 0);
  expect(wash.color.a, _glassOn(glass) ? 0.24 : 1);
  if (_glassOn(glass)) {
    final card = tester.getRect(
      find.byElementPredicate((e) => e == wash.element),
    );
    expect(_panes.where((pane) => _covers(pane, card)), isEmpty);
  }
}

void main() {
  for (final glass in _cases) {
    testWidgets('theme page cards stand on one pane each ${_name(glass)}', (
      tester,
    ) async {
      await _pump(
        tester,
        glass,
        page: const ThemeView(),
        size: const Size(400, 4200),
      );
      _expectOneCardPane(
        tester,
        find.descendant(
          of: find.byType(ThemeLivePreview),
          matching: find.byType(MiniScreen),
        ),
        glass,
      );
      _expectOneCardPane(tester, find.byTooltip('Add'), glass);
      _expectOneCardPane(tester, find.text('Default'), glass);
      _expectWashCard(
        tester,
        find.text('Text in the app will look like this'),
        glass,
      );
      expect(tester.takeException(), isNull);
    });

    testWidgets('the hotkey intro stands on one pane ${_name(glass)}', (
      tester,
    ) async {
      await _pump(tester, glass, page: const HotKeyView());
      _expectOneCardPane(
        tester,
        find.textContaining('Global hotkeys work even while'),
        glass,
      );
      expect(tester.takeException(), isNull);
    });

    for (final enable in [false, true]) {
      testWidgets('the access footer is one pane, enabled $enable '
          '${_name(glass)}', (tester) async {
        await _pump(
          tester,
          glass,
          page: const AccessView(),
          overrides: [systemActionProvider.overrideWith(_SystemAction.new)],
          seed: (container) {
            container.read(packagesProvider.notifier).value = const [
              Package(
                packageName: 'com.example.chat',
                label: 'Chat',
                system: false,
                internet: true,
                lastUpdateTime: 0,
              ),
            ];
            container.read(vpnSettingProvider.notifier).value = VpnProps(
              accessControlProps: AccessControlProps(enable: enable),
            );
          },
        );
        final footer = find.byType(FloatingFootBar);
        expect(footer, findsOneWidget);
        final statusType = tester
            .widget<FloatingFootBar>(footer)
            .bar
            .runtimeType;
        final status = find.descendant(
          of: footer,
          matching: find.byWidgetPredicate(
            (widget) => widget.runtimeType == statusType,
          ),
        );
        final scheme = Theme.of(tester.element(status)).colorScheme;
        void expectFooterPane() {
          final pane = find.ancestor(
            of: status,
            matching: find.byType(LiquidGlass),
          );
          expect(pane, _glassOn(glass) ? findsOneWidget : findsNothing);
          expect(
            find.descendant(of: status, matching: find.byType(LiquidGlass)),
            findsNothing,
          );
          final fills = _fillsBetween(tester, status, footer);
          if (_glassOn(glass)) {
            expect(fills.where((fill) => fill.color.a > 0), isEmpty);
            final bounds = tester.getRect(pane);
            expect(_panes.where((rect) => _covers(bounds, rect)), [
              rectMoreOrLessEquals(bounds, epsilon: 0.5),
            ]);
          } else {
            expect(
              fills.first.color,
              SurfaceLevel.floatingOf(scheme).colorOf(scheme),
            );
            expect(fills.first.color.a, 1);
          }
        }

        expectFooterPane();
        expect(
          globalState.container.read(accessControlStateProvider).enable,
          enable,
        );
        if (!enable) {
          expect(find.byType(TonalChip), findsNothing);
          await tester.tap(find.widgetWithText(TonalButton, 'Turn on'));
          await tester.pumpAndSettle();
          expect(
            globalState.container.read(accessControlStateProvider).enable,
            isTrue,
          );
          expectFooterPane();
        }
        final chipFinder = find.descendant(
          of: status,
          matching: find.byType(TonalChip),
        );
        final localizations = tester.element(status).appLocalizations;
        expect(
          tester.widget<TonalChip>(chipFinder).label,
          localizations.accessControlSelectedCount(0),
        );
        await tester.tap(find.text('Chat'));
        await tester.pumpAndSettle();
        expect(
          globalState.container.read(accessControlStateProvider).currentList,
          ['com.example.chat'],
        );
        final chip = tester.widget<TonalChip>(chipFinder);
        expect(chip.label, localizations.accessControlSelectedCount(1));
        expect(chip.color, scheme.primary.opacity15);
        expect(chip.foregroundColor, scheme.primary);
        final material = find.descendant(
          of: chipFinder,
          matching: find.byType(Material),
        );
        expect(tester.widget<Material>(material).color, chip.color);
        expectFooterPane();
        expect(tester.takeException(), isNull);
      });
    }

    testWidgets('the on-demand authorize button keeps its warning fill '
        '${_name(glass)}', (tester) async {
      await _pump(
        tester,
        glass,
        page: const OnDemandView(isAndroid: true, isMacOS: false),
        overrides: [
          excludeSSIDsProvider.overrideWith(_ExcludeSSIDs.new),
          locationPermissionsProvider.overrideWith(_LocationPermissions.new),
          batteryOptimizationIgnoredProvider.overrideWith(_BatteryIgnored.new),
          isStartProvider.overrideWithValue(false),
        ],
      );
      await tester.pumpAndSettle();
      final labels = find.text('Tap to authorize').evaluate().toList();
      expect(labels, hasLength(2));
      for (final label in labels) {
        final button = find.byWidget(
          label.findAncestorWidgetOfExactType<FilledButton>()!,
        );
        final scheme = Theme.of(label).colorScheme;
        final fill = find
            .descendant(of: button, matching: find.byType(Material))
            .first;
        expect(tester.widget<Material>(fill).color, scheme.errorContainer);
        expect(
          find.descendant(of: button, matching: find.byType(LiquidGlass)),
          findsNothing,
        );
      }
      expect(tester.takeException(), isNull);
    });

    testWidgets('dashboard edit badges keep their state fills '
        '${_name(glass)}', (tester) async {
      await _pump(
        tester,
        glass,
        page: const DashboardView(platform: SupportPlatform.Android),
        size: const Size(412, 1800),
        seed: (container) => container
            .read(appSettingProvider.notifier)
            .update(
              (state) => state.copyWith(
                dashboardWidgets: const [
                  DashboardWidget.networkSpeed,
                  DashboardWidget.outboundMode,
                ],
              ),
            ),
      );
      await tester.tap(find.byTooltip('Edit dashboard'));
      for (var frame = 0; frame < 10; frame++) {
        await tester.pump(const Duration(milliseconds: 50));
      }
      for (final (prefix, fillOf, glyphOf) in [
        (
          'Remove ',
          (ColorScheme scheme) => scheme.errorContainer,
          (ColorScheme scheme) => scheme.onErrorContainer,
        ),
        (
          'Add ',
          (ColorScheme scheme) => scheme.primary,
          (ColorScheme scheme) => scheme.onPrimary,
        ),
      ]) {
        final badges = find.byWidgetPredicate(
          (widget) =>
              widget is IconButton &&
              widget.tooltip?.startsWith(prefix) == true,
        );
        expect(badges, findsWidgets, reason: prefix);
        final badge = badges.first;
        final scheme = Theme.of(tester.element(badge)).colorScheme;
        final fill = find
            .descendant(of: badge, matching: find.byType(Material))
            .first;
        expect(tester.widget<Material>(fill).color, fillOf(scheme));
        expect(
          find.descendant(of: badge, matching: find.byType(LiquidGlass)),
          findsNothing,
        );
        final glyph = find.descendant(
          of: badge,
          matching: find.byType(GlyphIcon),
        );
        expect(tester.widget<GlyphIcon>(glyph).color, glyphOf(scheme));
        expect(
          GlassForeground.colorsInk(tester.element(glyph), glyphOf(scheme)),
          isFalse,
        );
      }
      expect(tester.takeException(), isNull);
    });
  }
}
