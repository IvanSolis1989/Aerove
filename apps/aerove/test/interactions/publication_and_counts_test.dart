import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/frame_pacer.dart';
import 'package:fl_clash/views/dashboard/widgets/profile_detail.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';
import '../helpers/test_app.dart';

class _Core extends Mock implements CoreHandlerInterface {}

class _Counts extends ConsumerStatefulWidget {
  const _Counts();
  @override
  ConsumerState<_Counts> createState() => _CountsState();
}

class _CountsState extends ConsumerState<_Counts>
    with ConfigCountsWatch<_Counts> {
  @override
  Widget build(BuildContext context) {
    watchConfigCounts();
    return Text('${configCounts?.rules ?? -1}/$configCountsFailed');
  }
}

void main() {
  for (final revoke in ['demand', 'visibility', 'statistics']) {
    testWidgets(
      'a queued sample is rejected after $revoke changes, including pacer stop flush',
      (tester) async {
        final core = _Core();
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
        addTearDown(c.dispose);
        final owner = Object();
        c
            .read(trafficDemandProvider.notifier)
            .claim(owner, TrafficNeed.traffic);
        final action = c.read(commonActionProvider.notifier);
        final pacer = FramePacer((_) {});
        addTearDown(pacer.dispose);
        pacer.start();
        await tester.pump();
        await action.updateTraffic();
        expect(c.read(trafficsProvider).list, isEmpty);
        switch (revoke) {
          case 'demand':
            c.read(trafficDemandProvider.notifier).release(owner);
          case 'visibility':
            c.read(appVisibleProvider.notifier).value = false;
          case 'statistics':
            c
                .read(appSettingProvider.notifier)
                .update(
                  (s) =>
                      s.copyWith(onlyStatisticsProxy: !s.onlyStatisticsProxy),
                );
        }
        pacer.stop();
        expect(c.read(trafficsProvider).list, isEmpty);
        expect(c.read(totalTrafficProvider), const Traffic());
        c.dispose();
        await tester.pump();
      },
    );
  }
  testWidgets(
    'cold uninitialized counts never parse a file and receipts replace the display',
    (tester) async {
      final c = ProviderContainer();
      addTearDown(c.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
          child: const TestApp(child: Scaffold(body: _Counts())),
        ),
      );
      await tester.pump(const Duration(seconds: 2));
      expect(find.text('-1/false'), findsOneWidget);
      c.read(appliedConfigCountsProvider.notifier).publish((
        groups: 1,
        proxies: 2,
        rules: 73,
      ));
      await tester.pump();
      expect(find.text('73/false'), findsOneWidget);
      c.read(appliedConfigCountsProvider.notifier).publish(null);
      c.read(initProvider.notifier).value = true;
      await tester.pump();
      expect(find.text('-1/false'), findsOneWidget);
      expect(
        c.read(appliedConfigCountsProvider.notifier).allowsFileFallback,
        isFalse,
      );
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );
}
