import 'dart:async';

import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/product.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/core.dart';
import 'package:fl_clash/views/dashboard/widgets/memory_info.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import '../helpers/test_app.dart';

class _Core extends Mock implements CoreHandlerInterface {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  ClientProduct.configure(
    product: ProductSpec(
      id: 'aerove-lite',
      displayName: 'AEROVE Lite',
      expectedCoreId: 'meow-rs',
      minimumAndroidApi: 33,
      resources: ProductResources(
        defaultScriptAsset: 'fixture',
        defaultScriptSha256: 'fixture',
        defaultScriptUrl: 'https://example.invalid/fixture',
        legacyScriptUrls: {},
        iconAsset: 'fixture',
      ),
      releaseFeed: 'https://example.invalid/fixture',
      releaseTagPrefix: 'fixture',
      releaseAssetPattern: 'fixture',
      changelogPath: 'fixture',
      coreSourceUrl: 'https://example.invalid/fixture',
    ),
    backendCoreId: 'meow-rs',
    backendFactory: () =>
        throw StateError('No native Core in memory widget tests'),
  );

  Future<_Core> showMemory(WidgetTester tester, {MemoryReader? reader}) async {
    final core = _Core();
    final container = ProviderContainer(
      overrides: [
        coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
      ],
    );
    container.read(coreStatusProvider.notifier).value = CoreStatus.connected;
    addTearDown(container.dispose);
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          withStatusManager: true,
          homeBuilder: (child) => Scaffold(body: child),
          child: MemoryInfo(memoryReader: reader),
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    return core;
  }

  Finder inSheet(String text) => find.descendant(
    of: find.byType(MemoryDetailSheet),
    matching: find.text(text),
  );

  testWidgets('the real Lite reader uses host RSS without a Core memory RPC', (
    tester,
  ) async {
    final core = await showMemory(tester);
    expect(find.text('App & Core'), findsOneWidget);
    expect(find.text('0 B'), findsNothing);
    verifyNever(() => core.getMemoryStats());

    await tester.tap(find.byType(MemoryInfo));
    await tester.pumpAndSettle();
    expect(inSheet('App & Core'), findsOneWidget);
    expect(inSheet('Core'), findsNothing);
    expect(inSheet('Core is not running'), findsNothing);
    expect(inSheet('Heap in use'), findsNothing);
    expect(inSheet('Heap idle'), findsNothing);
    expect(inSheet('Goroutine stacks'), findsNothing);
    expect(inSheet('Runtime overhead'), findsNothing);
    expect(
      inSheet(
        'App and Core share one process. Resident memory (RSS) is counted once and cannot be reliably split by component.',
      ),
      findsOneWidget,
    );
    verifyNever(() => core.getMemoryStats());
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'the shared release action clears real pending cache entries without pretending to run Rust GC',
    (tester) async {
      final core = await showMemory(tester);
      await tester.tap(find.byType(MemoryInfo));
      await tester.pumpAndSettle();
      final cache = PaintingBinding.instance.imageCache;
      final pending = Completer<ImageInfo>();
      cache.putIfAbsent(
        'lite-release-cache',
        () => OneFrameImageStreamCompleter(pending.future),
      );
      expect(cache.pendingImageCount, 1);

      await tester.tap(find.byTooltip('Release memory'));
      await tester.pump();
      expect(cache.pendingImageCount, 0);
      expect(find.byType(CommonCircleLoading), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(find.byType(CommonCircleLoading), findsNothing);
      expect(
        find.text(
          'Reclaimable image cache cleared. Resident memory may not decrease immediately.',
        ),
        findsOneWidget,
      );
      verifyNever(() => core.forceGc());
      verifyNever(() => core.getMemoryStats());
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'a failed post-release read reports failure and can recover on retry',
    (tester) async {
      var failRead = false;
      final core = await showMemory(
        tester,
        reader: () async {
          if (failRead) throw StateError('Resident snapshot unavailable');
          return const MemorySnapshot.sharedProcess(rss: 1024 * 1024);
        },
      );
      await tester.tap(find.byType(MemoryInfo));
      await tester.pumpAndSettle();
      failRead = true;
      await tester.tap(find.byTooltip('Release memory'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(find.byType(CommonCircleLoading), findsNothing);
      expect(find.text('Failed to release memory'), findsOneWidget);
      expect(
        find.text(
          'Reclaimable image cache cleared. Resident memory may not decrease immediately.',
        ),
        findsNothing,
      );

      failRead = false;
      await tester.tap(find.byTooltip('Release memory'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(find.byType(CommonCircleLoading), findsNothing);
      expect(
        find.text(
          'Reclaimable image cache cleared. Resident memory may not decrease immediately.',
        ),
        findsOneWidget,
      );
      verifyNever(() => core.forceGc());
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
