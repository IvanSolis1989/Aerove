import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:crypto/crypto.dart';
import 'package:fl_clash/common/system_fonts.dart';
import 'package:fl_clash/manager/app_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/font_family.dart';
import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/system_test_font.dart';
import '../helpers/test_app.dart';

const _step = Duration(milliseconds: 16);
const _openKey = ValueKey('open-font-preview');

enum _Gate { ticker, page, app, inactive }

class _QuietStore extends StoreAction {
  @override
  void savePreferencesDebounce() {}

  @override
  Future<void> savePreferencesNow() async {}
}

class _QuietPermissions extends LocationPermissions {
  @override
  Future<void> refresh() async {}
}

class _QuietProxies extends ProxiesAction {
  @override
  void build() {}

  @override
  void updateGroupsDebounce([Duration? duration]) {}
}

class _Evidence {
  _Evidence(this.name) {
    if (Platform.environment['AEROVE_FONT_PREVIEW_TRACE_FRAMES'] != 'true') {
      return;
    }
    final previousPrint = debugPrint;
    final previousStacks = debugPrintScheduleFrameStacks;
    debugPrint = (String? message, {int? wrapWidth}) {
      if (message != null) scheduleFrameLog.add(message);
      previousPrint(message, wrapWidth: wrapWidth);
    };
    debugPrintScheduleFrameStacks = true;
    addTearDown(() {
      debugPrint = previousPrint;
      debugPrintScheduleFrameStacks = previousStacks;
    });
  }

  final String name;
  final frames = <Map<String, Object?>>[];
  final pixels = <String, Uint8List>{};
  final scheduleFrameLog = <String>[];

  Future<void> record(
    WidgetTester tester,
    ProviderContainer container,
    List<String> families,
    String stage, {
    GlobalKey? capture,
  }) async {
    if (debugPrintScheduleFrameStacks) {
      debugPrint('FONT_PREVIEW_OBSERVATION $name $stage');
    }
    final styles = <String, String?>{};
    final tickers = <String, bool>{};
    final pages = <String, bool>{};
    for (final family in families) {
      final text = find.text(family, skipOffstage: false);
      if (text.evaluate().isNotEmpty) {
        final element = tester.element(text.first);
        styles[family] = DefaultTextStyle.of(element).style.fontFamily;
        tickers[family] = TickerMode.getValuesNotifier(element).value.enabled;
        final scope = element
            .getInheritedWidgetOfExactType<PageActivityScope>();
        pages[family] = scope?.isActive ?? true;
      }
    }
    final row = <String, Object?>{
      'stage': stage,
      'appVisible': container.read(appVisibleProvider),
      'lifecycle': tester.binding.lifecycleState?.name,
      'scheduledFrame': tester.binding.hasScheduledFrame,
      'transientCallbacks': tester.binding.transientCallbackCount,
      'contentFades': [
        for (final fade in tester.widgetList<FadeTransition>(
          find.descendant(
            of: find.byType(FontFamilyView, skipOffstage: false),
            matching: find.byType(FadeTransition, skipOffstage: false),
            skipOffstage: false,
          ),
        ))
          {
            'value': fade.opacity.value,
            'status': fade.opacity.status.name,
            'animating': fade.opacity.isAnimating,
          },
      ],
      'families': styles,
      'tickerEnabled': tickers,
      'pageActive': pages,
      'previewed': [
        for (final family in families)
          if (styles[family] == family) family,
      ],
    };
    if (capture?.currentContext != null) {
      final boundary =
          capture!.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final raw = await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          final data = (await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          ))!;
          row['width'] = image.width;
          row['height'] = image.height;
          return Uint8List.fromList(
            data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
          );
        } finally {
          image.dispose();
        }
      });
      pixels[stage] = raw!;
      row['rawRgbaSha256'] = sha256.convert(raw).toString();
    }
    frames.add(row);
  }

  int countAt(String stage) =>
      (frames.singleWhere((frame) => frame['stage'] == stage)['previewed']
              as List)
          .length;

  Future<void> save(WidgetTester tester) async {
    final output = Platform.environment['XCLASH_SCREENSHOT_DIR'];
    if (output == null) return;
    await tester.runAsync(() async {
      final directory = Directory('$output/font-preview/$name')
        ..createSync(recursive: true);
      for (final MapEntry(:key, :value) in pixels.entries) {
        await File('${directory.path}/$key.rgba').writeAsBytes(value);
      }
      if (scheduleFrameLog.isNotEmpty) {
        await File(
          '${directory.path}/schedule-frame.log',
        ).writeAsString(scheduleFrameLog.join('\n'));
      }
      await File('${directory.path}/trace.json').writeAsString(
        const JsonEncoder.withIndent(
          '  ',
        ).convert({'name': name, 'pixelFormat': 'rawRgba', 'frames': frames}),
      );
    });
  }
}

class _Harness {
  _Harness(this.tester, this.container, this.families, this.mobile);

  final WidgetTester tester;
  final ProviderContainer container;
  final List<String> families;
  final bool mobile;
  final catalog = Completer<List<String>>();
  final ticker = ValueNotifier(true);
  final page = ValueNotifier(true);
  final capture = GlobalKey();
  late BuildContext homeContext;

  Widget _fontPage() => ValueListenableBuilder(
    valueListenable: ticker,
    builder: (_, enabled, child) => TickerMode(enabled: enabled, child: child!),
    child: ValueListenableBuilder(
      valueListenable: page,
      builder: (_, active, child) =>
          PageActivityScope(isActive: active, child: child!),
      child: RepaintBoundary(key: capture, child: const FontFamilyView()),
    ),
  );

  Future<void> mount() async {
    tester.view.physicalSize = mobile
        ? const Size(430, 1000)
        : const Size(1200, 1000);
    tester.view.devicePixelRatio = 1;
    container.read(viewSizeProvider.notifier).value = tester.view.physicalSize;
    globalState.container = container;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: AppStateManager(
          child: TestApp(
            theme: ThemeData(platform: TargetPlatform.android),
            child: Consumer(
              builder: (_, ref, child) => TickerMode(
                enabled: ref.watch(appVisibleProvider),
                child: child!,
              ),
              child: Builder(
                builder: (context) {
                  homeContext = context;
                  final button = TextButton(
                    key: _openKey,
                    onPressed: () => unawaited(
                      showSheet<void>(
                        context: context,
                        builder: (_) => _fontPage(),
                      ),
                    ),
                    child: const Text('Open fonts'),
                  );
                  return Scaffold(
                    body: mobile
                        ? OpenContainer<void>(
                            closedBuilder: (_, open) => TextButton(
                              key: _openKey,
                              onPressed: open,
                              child: const Text('Open fonts'),
                            ),
                            openBuilder: (_, _) => _fontPage(),
                          )
                        : button,
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
  }

  Future<void> open([_Evidence? evidence]) async {
    await tester.tap(find.byKey(_openKey));
    await tester.pumpAndSettle();
    page.value = false;
    await tester.pump();
    catalog.complete(families);
    await tester.pumpAndSettle();
    if (evidence != null) {
      await evidence.record(
        tester,
        container,
        families,
        'content-settled-paused',
        capture: capture,
      );
    }
    page.value = true;
    await tester.pump();
    if (find.text(families.first).evaluate().isEmpty) {
      throw StateError('Font catalog did not become visible');
    }
  }

  Future<void> gate(_Gate gate, bool active) async {
    switch (gate) {
      case _Gate.ticker:
        ticker.value = active;
      case _Gate.page:
        page.value = active;
      case _Gate.app:
        container.read(appVisibleProvider.notifier).value = active;
      case _Gate.inactive:
        tester.binding.handleAppLifecycleStateChanged(
          active ? AppLifecycleState.resumed : AppLifecycleState.inactive,
        );
    }
    await tester.pump(_step);
  }

  Future<void> dispose() async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
    ticker.dispose();
    page.dispose();
    container.dispose();
  }
}

Future<void> _loadFonts(WidgetTester tester, Iterable<String> families) async {
  await tester.runAsync(() async {
    final bytes = await loadSdkTestFont();
    for (final family in families) {
      await (FontLoader(family)..addFont(Future.value(bytes))).load();
    }
  });
}

ProviderContainer _container(Future<List<String>> Function() fonts) =>
    ProviderContainer(
      overrides: [
        safeModeProvider.overrideWithValue(true),
        groupsProvider.overrideWithValue(const []),
        storeActionProvider.overrideWith(_QuietStore.new),
        locationPermissionsProvider.overrideWith(_QuietPermissions.new),
        proxiesActionProvider.overrideWith(_QuietProxies.new),
        installedSystemFontsProvider.overrideWith((_) => fonts()),
        themeSettingProvider.overrideWithBuild((_, _) => const ThemeProps()),
      ],
    );

Future<_Harness> _mount(
  WidgetTester tester,
  String name, {
  bool mobile = false,
}) async {
  final families = List.generate(6, (index) => '$name Preview $index');
  await _loadFonts(tester, families);
  late _Harness harness;
  final container = _container(() => harness.catalog.future);
  harness = _Harness(tester, container, families, mobile);
  await harness.mount();
  return harness;
}

Future<void> _frames(
  _Harness harness,
  _Evidence evidence,
  String stage,
  int count,
) async {
  for (var frame = 0; frame < count; frame++) {
    await harness.tester.pump(_step);
    await evidence.record(
      harness.tester,
      harness.container,
      harness.families,
      '$stage-$frame',
    );
  }
}

void _expectSingleSteps(_Evidence evidence, String prefix) {
  final counts = [
    for (final frame in evidence.frames)
      if ((frame['stage']! as String).startsWith(prefix))
        (frame['previewed']! as List).length,
  ];
  for (var index = 1; index < counts.length; index++) {
    expect(counts[index] - counts[index - 1], inInclusiveRange(0, 1));
  }
}

void _expectSettledContent(_Evidence evidence) {
  final frame = evidence.frames.singleWhere(
    (frame) => frame['stage'] == 'content-settled-paused',
  );
  expect(frame['previewed'], isEmpty);
  expect(frame['scheduledFrame'], isFalse);
  expect(
    (frame['contentFades']! as List).any(
      (fade) => (fade as Map)['animating'] == true,
    ),
    isFalse,
    reason: 'content arrival must settle while font previews stay paused',
  );
}

void main() {
  setUp(() {
    WidgetsBinding.instance.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    );
  });
  tearDown(() {
    WidgetsBinding.instance.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    );
  });

  testWidgets('active font previews keep their names, choice, default and '
      'one-font-per-frame rendering', (tester) async {
    addTearDown(tester.view.reset);
    final evidence = _Evidence('active');
    final harness = await _mount(tester, 'Active');
    try {
      await harness.open(evidence);
      await evidence.record(
        tester,
        harness.container,
        harness.families,
        'active-start',
        capture: harness.capture,
      );
      await _frames(harness, evidence, 'active', 8);
      await tester.pumpAndSettle();
      await evidence.record(
        tester,
        harness.container,
        harness.families,
        'active-complete',
        capture: harness.capture,
      );
      await evidence.save(tester);
      _expectSettledContent(evidence);
      expect(evidence.countAt('active-start'), lessThan(6));
      expect(evidence.countAt('active-complete'), 6);
      _expectSingleSteps(evidence, 'active');
      expect(find.text('Default'), findsOneWidget);
      for (final family in harness.families) {
        expect(find.text(family), findsOneWidget);
      }
      await tester.tap(find.text(harness.families.last));
      await tester.pumpAndSettle();
      expect(
        harness.container.read(themeSettingProvider).fontFamily,
        harness.families.last,
      );
      expect(find.byType(FontFamilyView), findsOneWidget);
      await tester.tap(find.text('Default'));
      await tester.pumpAndSettle();
      expect(harness.container.read(themeSettingProvider).fontFamily, isNull);
      expect(tester.takeException(), isNull);
    } finally {
      await evidence.save(tester);
      await harness.dispose();
    }
  });

  for (final (gate, mobile) in [
    (_Gate.ticker, false),
    (_Gate.page, false),
    (_Gate.app, false),
    (_Gate.inactive, false),
    (_Gate.app, true),
    (_Gate.inactive, true),
  ]) {
    final name = '${gate.name}-${mobile ? 'mobile' : 'sheet'}';
    testWidgets('$name pauses pending previews and resumes the same pixels', (
      tester,
    ) async {
      addTearDown(tester.view.reset);
      final evidence = _Evidence(name);
      final harness = await _mount(tester, name, mobile: mobile);
      try {
        await harness.open(evidence);
        await evidence.record(
          tester,
          harness.container,
          harness.families,
          'active-start',
          capture: harness.capture,
        );
        await harness.gate(gate, false);
        await evidence.record(
          tester,
          harness.container,
          harness.families,
          'hidden-start',
        );
        await _frames(harness, evidence, 'hidden', 4);
        final homeTicks = TickerMode.getValuesNotifier(
          harness.homeContext,
        ).value.enabled;
        final routeTicks = TickerMode.getValuesNotifier(
          tester.element(find.byType(FontFamilyView)),
        ).value.enabled;
        evidence.frames.last.addAll({
          'homeTickerBeforeResume': homeTicks,
          'routeTickerBeforeResume': routeTicks,
        });
        await harness.gate(gate, true);
        await evidence.record(
          tester,
          harness.container,
          harness.families,
          'resume-start',
        );
        await _frames(harness, evidence, 'resume', 8);
        await tester.pumpAndSettle();
        await evidence.record(
          tester,
          harness.container,
          harness.families,
          'resume-complete',
          capture: harness.capture,
        );
        await harness.gate(_Gate.ticker, false);
        await harness.gate(_Gate.ticker, true);
        await tester.pumpAndSettle();
        await evidence.record(
          tester,
          harness.container,
          harness.families,
          'warm-reference',
          capture: harness.capture,
        );
        await evidence.save(tester);

        _expectSettledContent(evidence);
        expect(evidence.countAt('hidden-start'), lessThan(6));
        for (var frame = 0; frame < 4; frame++) {
          expect(
            evidence.countAt('hidden-$frame'),
            evidence.countAt('hidden-start'),
            reason: 'pending font preview work must stay paused',
          );
        }
        expect(
          evidence.frames.singleWhere(
            (frame) => frame['stage'] == 'hidden-3',
          )['scheduledFrame'],
          isFalse,
          reason: 'a paused preview must not ask for another frame',
        );
        if (gate == _Gate.app || gate == _Gate.inactive) {
          expect(homeTicks, isFalse);
          expect(
            routeTicks,
            isTrue,
            reason: 'the real overlay route is outside the home ticker gate',
          );
        }
        expect(evidence.countAt('resume-complete'), 6);
        _expectSingleSteps(evidence, 'resume');
        expect(
          evidence.pixels['resume-complete'],
          evidence.pixels['warm-reference'],
        );
        expect(harness.container.read(themeSettingProvider).fontFamily, isNull);
        expect(tester.takeException(), isNull);
      } finally {
        await evidence.save(tester);
        await harness.dispose();
      }
    });
  }

  testWidgets('a hidden font queue does not block a visible page and can '
      'resume after the visible page has finished', (tester) async {
    addTearDown(tester.view.reset);
    tester.view.physicalSize = const Size(1200, 1000);
    tester.view.devicePixelRatio = 1;
    const left = ['Hidden Queue A', 'Hidden Queue B', 'Hidden Queue C'];
    const right = ['Visible Queue A', 'Visible Queue B', 'Visible Queue C'];
    await _loadFonts(tester, [...left, ...right]);
    final evidence = _Evidence('independent-queues');
    final visible = ValueNotifier(false);
    final capture = GlobalKey();
    final container = _container(() async => const []);
    globalState.container = container;
    container.read(viewSizeProvider.notifier).value = const Size(1200, 1000);
    Widget fontPage(List<String> families, Widget Function(Widget) wrap) =>
        ProviderScope(
          overrides: [
            installedSystemFontsProvider.overrideWithValue(AsyncData(families)),
          ],
          child: wrap(const FontFamilyView()),
        );
    try {
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            child: Row(
              children: [
                Expanded(
                  child: ValueListenableBuilder(
                    valueListenable: visible,
                    builder: (_, active, child) =>
                        TickerMode(enabled: active, child: child!),
                    child: fontPage(left, (child) => child),
                  ),
                ),
                Expanded(
                  child: fontPage(
                    right,
                    (child) => RepaintBoundary(key: capture, child: child),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
      for (var frame = 0; frame < 8; frame++) {
        await tester.pump(_step);
        await evidence.record(tester, container, [
          ...left,
          ...right,
        ], 'run-$frame');
      }
      await evidence.record(
        tester,
        container,
        [...left, ...right],
        'visible-complete',
        capture: capture,
      );
      for (final family in [...left, ...right]) {
        expect(
          find.text(family, skipOffstage: false),
          findsOneWidget,
          reason: 'both font queues must already have mounted labels',
        );
      }
      final hiddenBefore = [
        for (final family in left)
          DefaultTextStyle.of(
            tester.element(find.text(family, skipOffstage: false)),
          ).style.fontFamily,
      ];
      visible.value = true;
      for (var frame = 0; frame < 6; frame++) {
        await tester.pump(_step);
        await evidence.record(tester, container, [
          ...left,
          ...right,
        ], 'resume-$frame');
      }
      await evidence.save(tester);
      expect(hiddenBefore.where(left.contains), isEmpty);
      expect(evidence.countAt('visible-complete'), right.length);
      expect(evidence.countAt('resume-5'), left.length + right.length);
      _expectSingleSteps(evidence, 'resume');
      expect(tester.takeException(), isNull);
    } finally {
      await evidence.save(tester);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      visible.dispose();
      container.dispose();
    }
  });

  testWidgets('disposing a paused font route cancels its queued work without '
      'changing the selection or blocking the next route', (tester) async {
    addTearDown(tester.view.reset);
    final evidence = _Evidence('dispose');
    final first = await _mount(tester, 'Cancelled');
    try {
      await first.open();
      await first.gate(_Gate.ticker, false);
      await evidence.record(
        tester,
        first.container,
        first.families,
        'cancel-start',
      );
      Navigator.of(tester.element(find.byType(FontFamilyView))).pop();
      await tester.pumpAndSettle();
      await evidence.record(
        tester,
        first.container,
        first.families,
        'cancelled',
      );
      await evidence.save(tester);
      expect(find.byType(FontFamilyView), findsNothing);
      expect(first.container.read(themeSettingProvider).fontFamily, isNull);
      expect(tester.takeException(), isNull);
    } finally {
      await first.dispose();
    }
    final next = await _mount(tester, 'After Cancel');
    try {
      await next.open();
      await _frames(next, evidence, 'next', 8);
      await tester.pumpAndSettle();
      await evidence.record(
        tester,
        next.container,
        next.families,
        'next-complete',
        capture: next.capture,
      );
      await evidence.save(tester);
      expect(evidence.countAt('next-complete'), next.families.length);
      _expectSingleSteps(evidence, 'next');
      expect(tester.takeException(), isNull);
    } finally {
      await evidence.save(tester);
      await next.dispose();
    }
  });
}
