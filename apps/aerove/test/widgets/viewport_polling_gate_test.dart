import 'dart:async';

import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/connection/connections.dart';
import 'package:fl_clash/views/dashboard/widgets/memory_info.dart';
import 'package:fl_clash/widgets/active_polling.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

/// Advances the clock without drawing, drawing only the frames something
/// asked for, and returns how many that was.
Future<int> _framesOver(WidgetTester tester, Duration span) async {
  const step = Duration(milliseconds: 10);
  var frames = 0;
  for (var elapsed = Duration.zero; elapsed < span; elapsed += step) {
    await tester.binding.delayed(step);
    if (tester.binding.hasScheduledFrame) {
      frames++;
      await tester.pump();
    }
  }
  return frames;
}

Widget _scrolling(ScrollController controller, Widget child) => TestApp(
  wrapInProviderScope: true,
  homeBuilder: (child) => Scaffold(body: child),
  child: CustomScrollView(
    controller: controller,
    slivers: [
      SliverToBoxAdapter(
        child: Column(
          children: [
            const SizedBox(height: 1600),
            SizedBox(width: 380, child: child),
            const SizedBox(height: 1600),
          ],
        ),
      ),
    ],
  ),
);

void _openSheet(BuildContext context) => unawaited(
  showSnapSheet<void>(
    context,
    builder: (_, controller) => ListView(
      controller: controller,
      children: const [SizedBox(height: 40, child: Text('sheet'))],
    ),
  ),
);

void main() {
  setUp(() {
    WidgetsBinding.instance.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    );
  });

  testWidgets('a memory card below the fold neither reads nor asks for a '
      'frame, and reads as soon as it scrolls into view', (tester) async {
    var reads = 0;
    Future<MemorySnapshot> readMemory() async =>
        MemorySnapshot(app: ++reads * 1024 * 1024);
    final controller = ScrollController();
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _scrolling(controller, MemoryInfo(memoryReader: readMemory)),
    );
    await tester.pump();
    expect(reads, 0);
    expect(await _framesOver(tester, const Duration(seconds: 10)), 0);
    expect(reads, 0);

    controller.jumpTo(1400);
    await tester.pump();
    expect(reads, 1);
    await tester.pump(const Duration(seconds: 2));
    expect(reads, 2);

    controller.jumpTo(0);
    await tester.pumpAndSettle();
    final before = reads;
    expect(await _framesOver(tester, const Duration(seconds: 6)), 0);
    expect(reads, before);
  });

  testWidgets('a memory card under a sheet rests and reads again as the '
      'sheet closes', (tester) async {
    var reads = 0;
    Future<MemorySnapshot> readMemory() async =>
        MemorySnapshot(app: ++reads * 1024 * 1024);
    late BuildContext pageContext;

    await tester.pumpWidget(
      TestApp(
        wrapInProviderScope: true,
        homeBuilder: (child) => Scaffold(body: child),
        child: Builder(
          builder: (context) {
            pageContext = context;
            return MemoryInfo(memoryReader: readMemory);
          },
        ),
      ),
    );
    await tester.pump();
    expect(reads, 1);

    _openSheet(pageContext);
    await tester.pumpAndSettle();
    expect(TickerMode.valuesOf(pageContext).enabled, isTrue);
    final covered = reads;
    expect(await _framesOver(tester, const Duration(seconds: 10)), 0);
    expect(reads, covered);

    globalState.navigatorKey.currentState!.pop();
    await tester.pump();
    expect(reads, covered + 1);
    await tester.pumpAndSettle();
  });

  testWidgets('a TickBuilder keeps ticking below the fold and under a sheet', (
    tester,
  ) async {
    final controller = ScrollController();
    addTearDown(controller.dispose);
    late BuildContext tickContext;
    await tester.pumpWidget(
      _scrolling(
        controller,
        TickBuilder(
          duration: const Duration(seconds: 1),
          builder: (context, tick) {
            tickContext = context;
            return Text('tick: $tick');
          },
        ),
      ),
    );
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('tick: 1', skipOffstage: false), findsOneWidget);

    _openSheet(tickContext);
    await tester.pumpAndSettle();
    final shown = int.parse(
      tester
          .widget<Text>(find.textContaining('tick: ', skipOffstage: false))
          .data!
          .substring('tick: '.length),
    );
    await tester.pump(const Duration(seconds: 1));
    expect(
      find.text('tick: ${shown + 1}', skipOffstage: false),
      findsOneWidget,
    );

    globalState.navigatorKey.currentState!.pop();
    await tester.pumpAndSettle();
  });

  testWidgets('the connections page keeps reading under a sheet', (
    tester,
  ) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    globalState.container = container;
    var reads = 0;
    late BuildContext pageContext;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          child: Builder(
            builder: (context) {
              pageContext = context;
              return ConnectionsView(
                connectionsReader: () async {
                  reads++;
                  return const <TrackerInfo>[];
                },
              );
            },
          ),
        ),
      ),
    );
    await tester.pump();
    expect(reads, 1);

    _openSheet(pageContext);
    await tester.pumpAndSettle();
    final covered = reads;
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(reads, covered + 1);

    globalState.navigatorKey.currentState!.pop();
    await tester.pumpAndSettle();
  });

  testWidgets('a memory card reads once as focus comes back, as before, '
      'its viewport and route gates staying put meanwhile', (tester) async {
    var reads = 0;
    Future<MemorySnapshot> readMemory() async =>
        MemorySnapshot(app: ++reads * 1024 * 1024);

    await tester.pumpWidget(
      TestApp(
        wrapInProviderScope: true,
        homeBuilder: (child) => Scaffold(body: child),
        child: Consumer(
          builder: (_, ref, child) =>
              TickerMode(enabled: ref.watch(appVisibleProvider), child: child!),
          child: MemoryInfo(memoryReader: readMemory),
        ),
      ),
    );
    await tester.pump();
    expect(reads, 1);
    final visible = ProviderScope.containerOf(
      tester.element(find.byType(MemoryInfo)),
    ).read(appVisibleProvider.notifier);

    for (var cycle = 1; cycle <= 3; cycle++) {
      visible.value = false;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump();
      expect(await _framesOver(tester, const Duration(seconds: 3)), 0);
      expect(reads, cycle);

      visible.value = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      await tester.pump();
      expect(reads, cycle + 1);
    }
    await tester.pump(const Duration(seconds: 2));
    expect(reads, 5);
  });
}
