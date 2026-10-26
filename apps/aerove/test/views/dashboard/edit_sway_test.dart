import 'dart:math' as math;

import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/dashboard.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';
import '../../helpers/test_profiles.dart';

final _sways = find.byElementPredicate(
  (element) => element.widget.runtimeType.toString() == '_Sway',
  skipOffstage: false,
);

/// The tilt and bob the cards had as a [Transform.translate] around a
/// centered [Transform.rotate].
Matrix4 _swayAsWidgets(double value, int seed, Size size) {
  const swaysPerLoop = [6, 7, 5];
  final turn =
      value * 2 * math.pi * swaysPerLoop[seed % swaysPerLoop.length] +
      seed * 1.7;
  final rotate = Transform.rotate(angle: math.sin(turn) * 0.7 * math.pi / 180);
  final center = rotate.alignment!.resolve(TextDirection.ltr).alongSize(size);
  return Matrix4.translationValues(0, math.cos(turn) * 1.25, 0)
    ..multiply(Matrix4.translationValues(center.dx, center.dy, 0))
    ..multiply(rotate.transform)
    ..multiply(Matrix4.translationValues(-center.dx, -center.dy, 0));
}

void main() {
  late ProviderContainer container;

  setUp(() {
    container = ProviderContainer(
      overrides: [profilesProvider.overrideWith(TestProfiles.new)],
    );
    globalState.container = container;
    container
        .read(appSettingProvider.notifier)
        .update(
          (state) => state.copyWith(
            dashboardWidgets: const [
              DashboardWidget.currentOutbound,
              DashboardWidget.outboundMode,
              DashboardWidget.profiles,
            ],
          ),
        );
  });

  tearDown(() => container.dispose());

  Future<void> pumpEditor(WidgetTester tester, {bool reduced = false}) async {
    const size = Size(412, 1400);
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    container.read(viewSizeProvider.notifier).value = size;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: TestApp(
          homeBuilder: (child) => Builder(
            builder: (context) => MediaQuery(
              data: MediaQuery.of(context).copyWith(disableAnimations: reduced),
              child: child,
            ),
          ),
          child: const DashboardView(platform: SupportPlatform.Android),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byTooltip('Edit dashboard'));
    for (var frame = 0; frame < 10; frame++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  Future<void> teardown(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 5));
  }

  Rect badgeRect(WidgetTester tester) {
    final badge = find.byTooltip('Remove Profile');
    return tester.getRect(badge);
  }

  testWidgets('swaying cards report no semantics while they move', (
    tester,
  ) async {
    final handle = tester.ensureSemantics();
    await pumpEditor(tester);
    expect(_sways, findsNWidgets(3));
    final owner = tester.binding.renderViews.first.owner!.semanticsOwner!;
    var updates = 0;
    void count() => updates++;
    owner.addListener(count);
    final start = badgeRect(tester);

    var moved = false;
    for (var frame = 0; frame < 60; frame++) {
      await tester.pump(const Duration(milliseconds: 16));
      moved = moved || badgeRect(tester) != start;
    }
    owner.removeListener(count);

    expect(moved, isTrue);
    expect(updates, 0);
    handle.dispose();
    await teardown(tester);
  });

  testWidgets('a swaying card paints, hit tests and reports its place with '
      'the tilt it had as transform widgets', (tester) async {
    await pumpEditor(tester);
    for (var frame = 0; frame < 7; frame++) {
      await tester.pump(const Duration(milliseconds: 37));
      for (final element in _sways.evaluate()) {
        final Object widget = element.widget;
        final sway = widget is AnimatedWidget
            ? widget.listenable
            : (widget as dynamic).sway;
        final content = tester.renderObject<RenderBox>(
          find
              .descendant(
                of: find.byElementPredicate((each) => each == element),
                matching: find.byType(Stack),
              )
              .first,
        );
        final expected = _swayAsWidgets(
          (sway as Animation<double>).value,
          (widget as dynamic).seed as int,
          content.size,
        );
        final actual = content.getTransformTo(element.renderObject!.parent);
        for (var index = 0; index < 16; index++) {
          expect(actual.storage[index], closeTo(expected.storage[index], 1e-9));
        }
      }
      final badge = find.byTooltip('Remove Profile');
      final center = tester.getCenter(badge);
      final path = tester.hitTestOnBinding(center);
      final target = tester.renderObject(badge);
      expect(path.path.any((entry) => entry.target == target), isTrue);
    }

    await tester.tap(find.byTooltip('Remove Profile'));
    for (var frame = 0; frame < 10; frame++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(
      container.read(appSettingProvider).dashboardWidgets,
      isNot(contains(DashboardWidget.profiles)),
    );
    await teardown(tester);
  });

  testWidgets('cards hold still when animations are reduced', (tester) async {
    await pumpEditor(tester, reduced: true);
    expect(_sways, findsNothing);
    final start = badgeRect(tester);

    for (var frame = 0; frame < 20; frame++) {
      await tester.pump(const Duration(milliseconds: 50));
      expect(badgeRect(tester), start);
    }
    await teardown(tester);
  });
}
