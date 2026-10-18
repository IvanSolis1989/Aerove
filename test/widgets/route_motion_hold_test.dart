import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/ui/paged_sheet.dart';
import 'package:fl_clash/ui/route_motion_hold.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/navigator.dart';
import 'package:fl_clash/ui/surface.dart';
import 'package:fl_clash/ui/page_transition.dart';
import 'package:fl_clash/ui/sheet.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

class _Feed extends StatefulWidget {
  const _Feed({required this.source});

  final ValueNotifier<int> source;

  @override
  State<_Feed> createState() => _FeedState();
}

class _FeedState extends State<_Feed> with RouteMotionHoldMixin<_Feed> {
  late int _shown = widget.source.value;

  @override
  void initState() {
    super.initState();
    widget.source.addListener(_handleSource);
  }

  @override
  void dispose() {
    widget.source.removeListener(_handleSource);
    super.dispose();
  }

  void _handleSource() {
    updateWhenRouteSettled(() => setState(() => _shown = widget.source.value));
  }

  @override
  Widget build(BuildContext context) => Text('shown $_shown');
}

class _Arrival extends StatefulWidget {
  const _Arrival({required this.settles});

  final List<String> settles;

  @override
  State<_Arrival> createState() => _ArrivalState();
}

class _ArrivalState extends State<_Arrival> with RouteSettledMixin<_Arrival> {
  @override
  void didSettleRoute() => setState(() => widget.settles.add('settled'));

  @override
  Widget build(BuildContext context) {
    return Text(routeSettled ? 'settled' : 'arriving');
  }
}

void main() {
  testWidgets('gesture route alpha is real at 0.1, 0.3 and 0.5', (
    tester,
  ) async {
    final key = GlobalKey();
    final coverage = AnimationController(vsync: tester, value: 0.9);
    addTearDown(coverage.dispose);
    for (final alpha in [0.1, 0.3, 0.5]) {
      coverage.value = 1 - alpha;
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: RepaintBoundary(
              key: key,
              child: SizedBox.square(
                dimension: 80,
                child: Stack(
                  children: [
                    const Positioned.fill(
                      child: ColoredBox(color: Color(0xFF0000FF)),
                    ),
                    SharedXPageTransition(
                      animation: kAlwaysCompleteAnimation,
                      secondaryAnimation: coverage,
                      color: Colors.transparent,
                      slide: Offset.zero,
                      overSettledPage: true,
                      uncoveredByGesture: true,
                      child: const ColoredBox(
                        color: Color(0xFFFF0000),
                        child: SizedBox.expand(),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      final image =
          (key.currentContext!.findRenderObject()! as RenderRepaintBoundary)
              .toImageSync();
      try {
        final bytes = (await tester.runAsync(image.toByteData))!;
        final at = (40 * image.width + 40) * 4;
        expect(bytes.getUint8(at), closeTo(alpha * 255, 1));
        expect(bytes.getUint8(at + 1), 0);
        expect(bytes.getUint8(at + 2), closeTo((1 - alpha) * 255, 1));
        expect(bytes.getUint8(at + 3), 255);
      } finally {
        image.dispose();
      }
    }
  });

  testWidgets('transparent route fade composites intermediate content alpha', (
    tester,
  ) async {
    final key = GlobalKey();
    final arrival = AnimationController(vsync: tester, value: 0.1);
    addTearDown(arrival.dispose);
    for (final progress in [0.1, 0.3, 0.5]) {
      arrival.value = progress;
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: RepaintBoundary(
              key: key,
              child: SizedBox.square(
                dimension: 80,
                child: Stack(
                  children: [
                    const Positioned.fill(
                      child: ColoredBox(color: Color(0xFF0000FF)),
                    ),
                    SharedXPageTransition(
                      animation: arrival,
                      secondaryAnimation: kAlwaysDismissedAnimation,
                      color: Colors.transparent,
                      slide: Offset.zero,
                      overSettledPage: true,
                      child: const ColoredBox(
                        color: Color(0xFFFF0000),
                        child: SizedBox.expand(),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      final image =
          (key.currentContext!.findRenderObject()! as RenderRepaintBoundary)
              .toImageSync();
      try {
        final bytes = (await tester.runAsync(image.toByteData))!;
        final at = (40 * image.width + 40) * 4;
        final alpha = progress < 0.2
            ? 0.0
            : const Interval(
                0.15,
                0.55,
                curve: Curves.easeOutCubic,
              ).transform(progress);
        expect(bytes.getUint8(at), closeTo(alpha * 255, 1));
        expect(bytes.getUint8(at + 1), 0);
        expect(bytes.getUint8(at + 2), closeTo((1 - alpha) * 255, 1));
        expect(bytes.getUint8(at + 3), 255);
      } finally {
        image.dispose();
      }
    }
  });

  for (final brightness in Brightness.values) {
    for (final quality in LiquidGlassQuality.values) {
      for (final glass in [false, true]) {
        testWidgets('route backdrop survives push and return '
            '$brightness $quality glass=$glass', (tester) async {
          const backdrop = Color(0xFF286C85);
          final capture = GlobalKey();
          final routeNavigator = GlobalKey<NavigatorState>();
          final colors = ColorScheme.fromSeed(
            seedColor: Colors.blue,
            brightness: brightness,
          );
          await tester.pumpWidget(
            MaterialApp(
              navigatorKey: routeNavigator,
              theme: ThemeData(colorScheme: colors),
              builder: (_, child) => LiquidGlassScope(
                enabled: glass,
                quality: quality,
                child: RepaintBoundary(
                  key: capture,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      const ColoredBox(color: backdrop),
                      child!,
                    ],
                  ),
                ),
              ),
              home: const SizedBox.expand(),
            ),
          );
          Future<Color> pixel() async {
            final boundary =
                capture.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary;
            final image = boundary.toImageSync();
            try {
              final bytes = (await tester.runAsync(image.toByteData))!;
              final at =
                  ((image.height ~/ 2) * image.width + image.width ~/ 2) * 4;
              return Color.fromARGB(
                bytes.getUint8(at + 3),
                bytes.getUint8(at),
                bytes.getUint8(at + 1),
                bytes.getUint8(at + 2),
              );
            } finally {
              image.dispose();
            }
          }

          await tester.pumpAndSettle();
          expect(await pixel(), backdrop);
          routeNavigator.currentState!.push(
            CommonRoute<void>(
              builder: (_) =>
                  const Scaffold(backgroundColor: Colors.transparent),
            ),
          );
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 120));
          if (glass) expect(await pixel(), backdrop);
          await tester.pumpAndSettle();
          expect(
            await pixel(),
            glass ? backdrop : SurfaceLevel.page.colorOf(colors),
          );
          routeNavigator.currentState!.pop();
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 120));
          if (glass) expect(await pixel(), backdrop);
          await tester.pumpAndSettle();
          expect(await pixel(), backdrop);
          expect(tester.takeException(), isNull);
        });
      }
    }
  }

  late ValueNotifier<int> source;
  late GlobalKey<NavigatorState> navigator;

  setUp(() {
    source = ValueNotifier(0);
    navigator = GlobalKey<NavigatorState>();
  });

  tearDown(() => source.dispose());

  Future<void> pumpFeed(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigator,
        home: Scaffold(body: _Feed(source: source)),
      ),
    );
  }

  testWidgets('applies updates at once while the route is still', (
    tester,
  ) async {
    await pumpFeed(tester);

    source.value = 1;
    await tester.pump();

    expect(find.text('shown 1'), findsOneWidget);
  });

  testWidgets('holds updates while covered and replays the latest', (
    tester,
  ) async {
    await pumpFeed(tester);

    navigator.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold()),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    source.value = 1;
    source.value = 2;
    await tester.pump();

    expect(find.text('shown 0', skipOffstage: false), findsOneWidget);

    await tester.pumpAndSettle();
    expect(find.text('shown 2', skipOffstage: false), findsOneWidget);
  });

  testWidgets('holds updates through a user gesture', (tester) async {
    await pumpFeed(tester);

    navigator.currentState!.didStartUserGesture();
    await tester.pump();
    source.value = 3;
    await tester.pump();
    expect(find.text('shown 0'), findsOneWidget);

    navigator.currentState!.didStopUserGesture();
    await tester.pump();
    expect(find.text('shown 3'), findsOneWidget);
  });

  group('under a sheet', () {
    Future<void> pumpOpener(WidgetTester tester, Widget content) async {
      const view = Size(400, 900);
      tester.view.physicalSize = view;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        TestApp(
          wrapInProviderScope: true,
          child: Scaffold(
            body: Column(
              children: [
                _Feed(source: source),
                Builder(
                  builder: (context) => TextButton(
                    onPressed: () => showSnapSheet<void>(
                      context,
                      builder: (_, _) => content,
                    ),
                    child: const Text('open'),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
      ProviderScope.containerOf(
        tester.element(find.text('open')),
      ).read(viewSizeProvider.notifier).value = view;
      await tester.pumpAndSettle();
      await tester.tap(find.text('open'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
    }

    testWidgets('holds the page below while a sheet slides over it', (
      tester,
    ) async {
      await pumpOpener(tester, const Scaffold(body: Text('sheet')));

      source.value = 1;
      await tester.pump();
      expect(find.text('shown 0'), findsOneWidget);

      await tester.pumpAndSettle();
      expect(find.text('shown 1'), findsOneWidget);
    });

    testWidgets('holds a page in a sheet while the sheet slides in', (
      tester,
    ) async {
      final inSheet = ValueNotifier(0);
      addTearDown(inSheet.dispose);
      await pumpOpener(
        tester,
        NestedPagedSheet(
          builder: (_) => Scaffold(body: _Feed(source: inSheet)),
        ),
      );

      inSheet.value = 1;
      await tester.pump();
      expect(find.text('shown 0'), findsNWidgets(2));

      await tester.pumpAndSettle();
      expect(find.text('shown 1'), findsOneWidget);
    });
  });

  group('RouteSettledMixin', () {
    testWidgets('settles at once on a page that sits still', (tester) async {
      final settles = <String>[];
      await tester.pumpWidget(MaterialApp(home: _Arrival(settles: settles)));
      await tester.pump();

      expect(settles, ['settled']);
      expect(find.text('settled'), findsOneWidget);
    });

    testWidgets('settles once the route bringing it in has arrived', (
      tester,
    ) async {
      final settles = <String>[];
      await tester.pumpWidget(
        MaterialApp(navigatorKey: navigator, home: const Scaffold()),
      );

      navigator.currentState!.push(
        MaterialPageRoute<void>(builder: (_) => _Arrival(settles: settles)),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(settles, isEmpty);
      expect(find.text('arriving'), findsOneWidget);

      await tester.pumpAndSettle();

      expect(settles, ['settled']);
      expect(find.text('settled'), findsOneWidget);

      navigator.currentState!.push(
        MaterialPageRoute<void>(builder: (_) => const Scaffold()),
      );
      await tester.pumpAndSettle();
      navigator.currentState!.pop();
      await tester.pumpAndSettle();

      expect(settles, ['settled']);
    });
  });
}
