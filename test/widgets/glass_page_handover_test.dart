import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/navigation_dock.dart';
import 'package:fl_clash/ui/navigator.dart';
import 'package:fl_clash/ui/open_container.dart';
import 'package:fl_clash/views/config/advanced.dart';
import 'package:fl_clash/views/config/general.dart';
import 'package:fl_clash/views/profiles/extend/extend.dart';
import 'package:fl_clash/views/profiles/profiles.dart';
import 'package:fl_clash/views/theme.dart';
import 'package:fl_clash/views/tools.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

const _size = Size(390, 800);
const _steps = [0.0, 0.1, 0.25, 0.5, 0.75, 1.0];
const _profileName = 'Handover example profile';
const _profile = Profile(
  id: 721,
  label: _profileName,
  autoUpdate: false,
  autoUpdateDuration: Duration(days: 1),
);

void main() {
  setUpAll(() async {
    if (Platform.environment['XCLASH_REQUIRE_IMPELLER'] == 'true') {
      expect(ui.ImageFilter.isShaderFilterSupported, isTrue);
    }
    await (FontLoader('HandoverProbe')
          ..addFont(rootBundle.load('assets/fonts/JetBrainsMono-Regular.ttf')))
        .load();
  });

  for (final brightness in Brightness.values) {
    for (final quality in [null, ...LiquidGlassQuality.values]) {
      for (final entry in {
        'Extend': ExtendView,
        'General': GeneralView,
        'Advanced configuration': AdvancedConfigView,
        'Theme': ThemeView,
      }.entries) {
        testWidgets('page handover $brightness $quality ${entry.key}', (
          tester,
        ) async {
          final previousPlatform = debugDefaultTargetPlatformOverride;
          debugDefaultTargetPlatformOverride = TargetPlatform.android;
          tester.view.physicalSize = _size;
          tester.view.devicePixelRatio = 1;
          final semantics = tester.ensureSemantics();
          final homeVisible = ValueNotifier(true);
          final container = ProviderContainer(
            overrides: [
              profilesProvider.overrideWith(() => TestProfiles([_profile])),
              setupActionProvider.overrideWith(_IdleSetupAction.new),
              clashConfigProvider(_profile.id).overrideWith(
                (_) async => const ClashConfig(),
              ),
              profileRulesProvider.overrideWith2((_) => _EmptyRules()),
              themeSettingProvider.overrideWithValue(
                ThemeProps(
                  auroraEnabled: quality != null,
                  auroraSeed: 271828,
                  liquidGlass: quality == null
                      ? LiquidGlassMode.none
                      : quality == LiquidGlassQuality.ultra
                      ? LiquidGlassMode.ultra
                      : LiquidGlassMode.standard,
                ),
              ),
              navigationItemsStateProvider.overrideWithValue(
                NavigationItemsState(
                  value: [
                    NavigationItem(
                      glyph: AppGlyphs.profiles,
                      label: PageLabel.profiles,
                      builder: (_) => const ProfilesView(),
                    ),
                    NavigationItem(
                      glyph: AppGlyphs.tools,
                      label: PageLabel.tools,
                      builder: (_) => const ToolsView(),
                    ),
                  ],
                ),
              ),
            ],
          );
          globalState.container = container;
          container.read(viewSizeProvider.notifier).value = _size;
          final page = entry.key == 'Extend'
              ? PageLabel.profiles
              : PageLabel.tools;
          container.read(currentPageLabelProvider.notifier).toPage(page);
          final capture = GlobalKey();
          final frames = <Map<String, Object?>>[];
          final prefix =
              'handover-${brightness.name}-${quality?.name ?? 'none'}-'
              '${entry.key.replaceAll(' ', '-')}';
          final theme = ThemeData(
            brightness: brightness,
            platform: TargetPlatform.android,
            fontFamily: 'HandoverProbe',
            pageTransitionsTheme: const PageTransitionsTheme(
              builders: {
                TargetPlatform.android: commonSharedXPageTransitions,
              },
            ),
          );
          try {
            await tester.pumpWidget(
              UncontrolledProviderScope(
                container: container,
                child: MaterialApp(
                  navigatorKey: globalState.navigatorKey,
                  debugShowCheckedModeBanner: false,
                  theme: theme,
                  localizationsDelegates: const [
                    AppLocalizations.delegate,
                    AppUiLocalizations.delegate,
                    ...GlobalMaterialLocalizations.delegates,
                  ],
                  supportedLocales: AppLocalizations.delegate.supportedLocales,
                  builder: (context, child) => wrapAppScopes(
                    context,
                    RepaintBoundary(
                      key: capture,
                      child: AuroraAppSurface(motion: false, child: child!),
                    ),
                  ),
                  home: ValueListenableBuilder<bool>(
                    valueListenable: homeVisible,
                    builder: (_, visible, child) => Opacity(
                      opacity: visible ? 1 : 0,
                      child: child,
                    ),
                    child: const HomePage(),
                  ),
                ),
              ),
            );
            await tester.pumpAndSettle();
            expect(find.byType(NavigationDock), findsOneWidget);
            final originalHome = tester.element(find.byType(HomePage));
            final originalPage = container.read(currentPageLabelProvider);
            final opened = await _open(tester, entry.key);
            final opener = opened.opener;
            final route = ModalRoute.of(
              tester.element(find.byType(entry.value)),
            )!;
            expect(route.opaque, isTrue);
            expect(route.animation!.value, 0);
            expect(find.byType(LiquidGlassHost), findsOneWidget);
            if (quality == null) {
              expect(find.byType(LiquidGlassBackdrop), findsNothing);
            }
            final duration = route.transitionDuration;

            Future<void> sample(String phase, double progress) async {
              final full = Offset.zero & _size;
              var covered = phase == 'restored'
                  ? full
                  : tester.getRect(find.byType(entry.value));
              if (opened.closed != null &&
                  phase != 'held-drag' &&
                  phase != 'restored') {
                final curve = phase == 'pop'
                    ? Curves.fastOutSlowIn.flipped
                    : Curves.fastOutSlowIn;
                covered = Rect.lerp(
                  opened.closed,
                  full,
                  curve.transform(route.animation!.value),
                )!;
              }
              final interior = covered.intersect(full).deflate(2);
              final exposedProfile = find
                  .bySemanticsLabel(RegExp(_profileName))
                  .evaluate()
                  .isNotEmpty;
              final actual = await _pixels(
                tester,
                capture,
                '$prefix-$phase-$progress',
              );
              // Keep the same route, layout, clock and wallpaper. Only the
              // underlying Home paint is removed for an independent oracle.
              homeVisible.value = false;
              await tester.pump();
              await tester.pump();
              final oracle = await _pixels(
                tester,
                capture,
                '$prefix-$phase-$progress-wallpaper',
              );
              await tester.pump();
              final repeat = await _pixels(tester, capture, null);
              homeVisible.value = true;
              await tester.pump();
              await tester.pump();
              final noise = _maxDelta(oracle, repeat);
              var residual = 0;
              var outsideResidual = 0;
              var testedBytes = 0;
              var insideMaximum = 0;
              final regions = {'header': 0, 'content': 0, 'dock': 0};
              for (var i = 0; i < actual.length; i++) {
                final x = (i ~/ 4) % _size.width.toInt();
                final y = i ~/ 4 ~/ _size.width.toInt();
                final inside = interior.contains(Offset(x + 0.5, y + 0.5));
                if (inside) testedBytes++;
                if ((actual[i] - oracle[i]).abs() <= noise) continue;
                if (!inside) {
                  outsideResidual++;
                  continue;
                }
                residual++;
                insideMaximum = math.max(
                  insideMaximum,
                  (actual[i] - oracle[i]).abs(),
                );
                final region = y < 160
                    ? 'header'
                    : (y < 700 ? 'content' : 'dock');
                regions[region] = regions[region]! + 1;
              }
              frames.add({
                'phase': phase,
                'progress': progress,
                'animation': route.animation?.value,
                'referenceNoise': noise,
                'residualBytes': residual,
                'outsideResidualBytes': outsideResidual,
                'testedBytes': testedBytes,
                'destination': [
                  covered.left,
                  covered.top,
                  covered.right,
                  covered.bottom,
                ],
                'regions': regions,
                'destinationMaxDelta': insideMaximum,
                'fullFrameMaxDelta': _maxDelta(actual, oracle),
                'underlyingProfileSemantics': exposedProfile,
              });
              expect(container.read(currentPageLabelProvider), originalPage);
              expect(tester.takeException(), isNull);
            }

            var previous = 0.0;
            for (final progress in _steps) {
              await tester.pump(duration * (progress - previous));
              expect(route.animation!.value, closeTo(progress, 0.00001));
              await sample('push', progress);
              previous = progress;
            }
            await tester.pumpAndSettle();
            final drag = await tester.startGesture(const Offset(18, 600));
            await drag.moveBy(const Offset(30, 0));
            await drag.moveBy(const Offset(100, 0));
            await tester.pump();
            expect(route.navigator!.userGestureInProgress, isTrue);
            expect(route.animation!.value, inExclusiveRange(0.5, 1));
            await sample('held-drag', route.animation!.value);
            await drag.moveBy(const Offset(-130, 0));
            await drag.up();
            await tester.pumpAndSettle();
            expect(route.animation!.value, 1);
            expect(route.navigator!.userGestureInProgress, isFalse);

            route.navigator!.pop();
            await tester.pump();
            await tester.pump();
            previous = 0;
            for (final progress in _steps) {
              await tester.pump(
                route.reverseTransitionDuration * (progress - previous),
              );
              if (progress < 1) await sample('pop', progress);
              previous = progress;
            }
            await tester.pumpAndSettle();
            expect(find.byType(entry.value), findsNothing);
            expect(tester.element(find.byType(HomePage)), same(originalHome));
            final dockLabel = find.descendant(
              of: find.byType(NavigationDock),
              matching: find.text(
                page == PageLabel.profiles ? 'Profiles' : 'Tools',
              ),
            );
            expect(dockLabel.hitTestable(), findsOneWidget);
            expect(opener.hitTestable(), findsOneWidget);
            if (page == PageLabel.profiles) {
              expect(find.bySemanticsLabel(RegExp(_profileName)), findsWidgets);
            }
            await sample('restored', 1);
            await _open(tester, entry.key);
            await tester.pumpAndSettle();
            expect(find.byType(entry.value), findsOneWidget);
            final commit = await tester.startGesture(const Offset(18, 600));
            await commit.moveBy(const Offset(30, 0));
            await commit.moveBy(const Offset(280, 0));
            await tester.pump();
            await commit.up();
            await tester.pumpAndSettle();
            expect(find.byType(entry.value), findsNothing);
            expect(opener.hitTestable(), findsOneWidget);
            await tester.tap(dockLabel);
            await tester.pumpAndSettle();
            expect(container.read(currentPageLabelProvider), originalPage);
            final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
            if (output != null) {
              await tester.runAsync(() async {
                await Directory(output).create(recursive: true);
                await File('$output/$prefix.json').writeAsString(
                  const JsonEncoder.withIndent('  ').convert({
                    'shaderFiltersSupported':
                        ui.ImageFilter.isShaderFilterSupported,
                    'requireImpeller':
                        Platform.environment['XCLASH_REQUIRE_IMPELLER'],
                    'frames': frames,
                  }),
                );
              });
            }
            expect(
              frames.where((frame) => frame['referenceNoise'] != 0).toList(),
              isEmpty,
              reason: 'The independent reference must be stable at a frozen animation clock.',
            );
            expect(frames.last['residualBytes'], greaterThan(0));
            expect(frames.first['outsideResidualBytes'], greaterThan(0));
            expect(
              frames.every((frame) => (frame['testedBytes']! as int) > 0),
              isTrue,
            );
            if (quality != null) {
              expect(
                frames.where((frame) =>
                  frame['phase'] != 'restored' &&
                  frame['residualBytes'] != 0,
                ).toList(),
                isEmpty,
                reason: 'The destination interior must sample only wallpaper; the exposed opener may keep its animation.',
              );
            }
          } finally {
            await tester.pumpWidget(const SizedBox.shrink());
            await tester.pump();
            homeVisible.dispose();
            container.dispose();
            semantics.dispose();
            tester.view.reset();
            debugDefaultTargetPlatformOverride = previousPlatform;
          }
        });
      }
    }
  }
}

Future<({Finder opener, Rect? closed})> _open(
  WidgetTester tester,
  String entry,
) async {
  if (entry == 'Extend') {
    final opener = find.text(_profileName);
    await tester.longPress(opener);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Extend'));
    await tester.pump();
    await tester.pump();
    return (opener: opener, closed: null);
  }
  final opener = find.text(entry);
  await tester.scrollUntilVisible(
    opener,
    250,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.pumpAndSettle();
  final closed = tester.getRect(
    find.ancestor(
      of: opener,
      matching: find.byType(OpenContainer<dynamic>),
    ),
  );
  await tester.tap(opener);
  await tester.pump();
  await tester.pump();
  return (opener: opener, closed: closed);
}

Future<Uint8List> _pixels(
  WidgetTester tester,
  GlobalKey capture,
  String? name,
) async {
  final boundary = capture.currentContext!.findRenderObject()!
      as RenderRepaintBoundary;
  late Uint8List bytes;
  await tester.runAsync(() async {
    final image = boundary.toImageSync();
    bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!
        .buffer.asUint8List();
    final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (output != null && name != null) {
      await Directory(output).create(recursive: true);
      final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
      await File('$output/$name.png').writeAsBytes(png.buffer.asUint8List());
    }
    image.dispose();
  });
  expect(tester.takeException(), isNull);
  return bytes;
}

int _maxDelta(Uint8List a, Uint8List b) {
  var maximum = 0;
  for (var i = 0; i < a.length; i++) {
    maximum = math.max(maximum, (a[i] - b[i]).abs());
  }
  return maximum;
}

class _IdleSetupAction extends SetupAction {
  @override
  void autoApplyProfile() {}
}

class _EmptyRules extends ProfileRules {
  @override
  Stream<List<Rule>> build(int profileId) => Stream.value(const []);
}
