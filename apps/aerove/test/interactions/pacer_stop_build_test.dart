import 'dart:io';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:fl_clash/ui/frame_pacer.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';
import '../helpers/test_app.dart';

class Core extends Mock implements CoreHandlerInterface {}

void main() {
  testWidgets(
    'reduced-motion stopping the last real wallpaper pacer safely flushes queued traffic',
    (tester) async {
      final core = Core();
      when(
        () => core.getTraffic(any()),
      ).thenAnswer((_) async => const Traffic(up: 1, down: 2));
      when(
        () => core.getTotalTraffic(any()),
      ).thenAnswer((_) async => const Traffic(up: 3, down: 4));
      final c = ProviderContainer(
        overrides: [
          coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        ],
      );
      final owner = Object();
      c.read(trafficDemandProvider.notifier).claim(owner, TrafficNeed.traffic);
      final reduced = ValueNotifier(false);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
          child: TestApp(
            child: ValueListenableBuilder<bool>(
              valueListenable: reduced,
              builder: (context, off, _) => MediaQuery(
                data: MediaQuery.of(context).copyWith(disableAnimations: off),
                child: Stack(
                  children: [
                    const SeededAuroraWallpaper(
                      colors: [
                        Color(0xffaabbcc),
                        Color(0xffbbaacc),
                        Color(0xffccbbaa),
                      ],
                      seed: 4,
                      brightness: Brightness.light,
                      motion: true,
                    ),
                    Consumer(
                      builder: (context, ref, _) => Text(
                        'samples ${ref.watch(trafficsProvider).list.length}',
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
      await c.read(commonActionProvider.notifier).updateTraffic();
      expect(
        c.read(trafficsProvider).list,
        isEmpty,
        reason: 'the real wallpaper pacer holds the pending update',
      );
      reduced.value = true;
      await tester.pump();
      final exception = tester.takeException();
      expect(c.read(trafficsProvider).list, hasLength(1));
      expect(c.read(totalTrafficProvider), const Traffic(up: 3, down: 4));
      stdout.writeln('stop-during-build exception=$exception');
      await tester.pumpWidget(const SizedBox.shrink());
      reduced.dispose();
      c.dispose();
      expect(exception, isNull);
    },
  );
  testWidgets(
    'a stop during build checks demand after all frame-end releases',
    (tester) async {
      final core = Core();
      when(
        () => core.getTraffic(any()),
      ).thenAnswer((_) async => const Traffic(up: 1, down: 2));
      when(
        () => core.getTotalTraffic(any()),
      ).thenAnswer((_) async => const Traffic(up: 3, down: 4));
      final c = ProviderContainer(
        overrides: [
          coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        ],
      );
      final owner = Object();
      c.read(trafficDemandProvider.notifier).claim(owner, TrafficNeed.traffic);
      final pacer = FramePacer((_) {});
      pacer.start();
      await tester.pump();
      await c.read(commonActionProvider.notifier).updateTraffic();
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
          child: TestApp(
            child: Builder(
              builder: (_) {
                pacer.stop();
                WidgetsBinding.instance.addPostFrameCallback(
                  (_) => c.read(trafficDemandProvider.notifier).release(owner),
                );
                return Consumer(
                  builder: (_, ref, _) => Text(
                    'samples ${ref.watch(trafficsProvider).list.length}',
                  ),
                );
              },
            ),
          ),
        ),
      );
      expect(tester.takeException(), isNull);
      expect(c.read(trafficsProvider).list, isEmpty);
      expect(c.read(totalTrafficProvider), const Traffic());
      await tester.pumpWidget(const SizedBox.shrink());
      pacer.dispose();
      c.dispose();
      await tester.pump();
    },
  );
}
