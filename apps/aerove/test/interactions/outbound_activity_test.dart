import 'dart:async';
import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/widgets/current_outbound.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:mocktail/mocktail.dart';
import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class _Core extends Mock implements CoreHandlerInterface {}

void main() {
  setUpAll(() => registerFallbackValue(const OutboundIpParams(timeout: 0)));
  testWidgets(
    'an inactive status card releases real route telemetry and reacquires it once',
    (tester) async {
      final core = _Core();
      final watched = <bool>[];
      when(() => core.watchRoute(any())).thenAnswer((invocation) async {
        watched.add(invocation.positionalArguments.single as bool);
        return const RouteSnapshot(coreEpoch: 1, routeVersion: 1);
      });
      when(
        () => core.outboundIp(any()),
      ).thenAnswer((_) => Completer<OutboundIpResult?>().future);
      final c = ProviderContainer(
        overrides: [
          profilesProvider.overrideWith(
            () => TestProfiles([Profile.normal(label: 'p').copyWith(id: 1)]),
          ),
          currentProfileIdProvider.overrideWithBuild((_, _) => 1),
          initProvider.overrideWithBuild((_, _) => true),
          runTimeProvider.overrideWithBuild((_, _) => 1),
          suspendProvider.overrideWithValue(false),
          coreStatusProvider.overrideWithBuild((_, _) => CoreStatus.connected),
          coreHandlerProvider.overrideWithValue(CoreController.scoped(core)),
        ],
      );
      addTearDown(c.dispose);
      final active = ValueNotifier(true);
      addTearDown(active.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
          child: TestApp(
            child: Builder(
              builder: (context) => MediaQuery(
                data: MediaQuery.of(context).copyWith(disableAnimations: true),
                child: ValueListenableBuilder<bool>(
                  valueListenable: active,
                  builder: (_, shown, child) => PageActivityScope(
                    isActive: shown,
                    child: TickerMode(enabled: shown, child: child!),
                  ),
                  child: const Scaffold(
                    body: SizedBox(
                      width: 380,
                      child: DashboardDensityScope(
                        density: DashboardDensity.mobile,
                        scale: 1,
                        child: CurrentOutboundCard(),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(watched, [true]);
      active.value = false;
      await tester.pump();
      await tester.pump();
      expect(watched, [true, false]);
      await tester.pump(const Duration(seconds: 3));
      expect(watched, [true, false]);
      active.value = true;
      await tester.pump();
      await tester.pump();
      expect(watched, [true, false, true]);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(watched, [true, false, true, false]);
      c.dispose();
      await tester.pump();
    },
  );
}
