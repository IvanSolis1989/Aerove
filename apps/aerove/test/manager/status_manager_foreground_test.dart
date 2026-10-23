import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/status_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

Future<(StatusManagerState, ProviderContainer)> _pump(
  WidgetTester tester, {
  bool reduced = false,
}) async {
  tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  final origin = tester.binding.clock.now();
  await tester.pumpWidget(
    TestApp(
      wrapInProviderScope: true,
      overrides: [isMobileViewProvider.overrideWithValue(true)],
      homeBuilder: (child) => Builder(
        builder: (context) => MediaQuery(
          data: MediaQuery.of(context).copyWith(disableAnimations: reduced),
          child: child,
        ),
      ),
      child: StatusManager(
        elapsed: () => tester.binding.clock.now().difference(origin),
        child: const SizedBox(),
      ),
    ),
  );
  final element = tester.element(find.byType(StatusManager));
  return (
    tester.state<StatusManagerState>(find.byType(StatusManager)),
    ProviderScope.containerOf(element),
  );
}

Future<void> _lifecycle(
  WidgetTester tester,
  ProviderContainer container,
  AppLifecycleState state,
) async {
  tester.binding.handleAppLifecycleStateChanged(state);
  // Isolate this consumer of AppStateManager's existing authoritative provider.
  container.read(appVisibleProvider.notifier).value =
      state == AppLifecycleState.resumed;
  await tester.pump();
}

Future<void> _background(
  WidgetTester tester,
  ProviderContainer container,
) async {
  for (final state in [
    AppLifecycleState.inactive,
    AppLifecycleState.hidden,
    AppLifecycleState.paused,
  ]) {
    await _lifecycle(tester, container, state);
  }
}

Future<void> _resume(WidgetTester tester, ProviderContainer container) async {
  for (final state in [
    AppLifecycleState.hidden,
    AppLifecycleState.inactive,
    AppLifecycleState.resumed,
  ]) {
    await _lifecycle(tester, container, state);
  }
}

void main() {
  testWidgets('background hides and pauses only the remaining display time', (
    tester,
  ) async {
    final (status, container) = await _pump(tester);
    status.message('remaining');
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    await _background(tester, container);
    expect(find.text('remaining'), findsNothing);
    expect(find.text('remaining', skipOffstage: false), findsOneWidget);
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pump(const Duration(seconds: 30));
    expect(find.text('remaining', skipOffstage: false), findsOneWidget);
    await _resume(tester, container);
    await tester.pump(const Duration(milliseconds: 1900));
    expect(find.text('remaining'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 200));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('remaining'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('hidden queue keeps bounds deduplication and error priority', (
    tester,
  ) async {
    final (status, container) = await _pump(tester);
    await _background(tester, container);
    for (var index = 0; index < 10; index++) {
      status.message('queued$index');
    }
    status.message('queued9');
    status.message('error', level: MessageLevel.error);
    await tester.pump(const Duration(seconds: 20));
    expect(find.text('queued9', skipOffstage: false), findsNothing);
    expect(find.text('error', skipOffstage: false), findsNothing);
    expect(tester.binding.transientCallbackCount, 0);
    await _resume(tester, container);
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('error'), findsOneWidget);
    expect(find.text('queued4'), findsOneWidget);
    for (var index = 0; index <= 3; index++) {
      expect(find.text('queued$index'), findsNothing);
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('duplicate while hidden resets duration and uses latest action', (
    tester,
  ) async {
    final (status, container) = await _pump(tester);
    var old = 0;
    var replacement = 0;
    status.message(
      'action',
      actionState: MessageActionState(actionText: 'old', action: () => old++),
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 5));
    await _background(tester, container);
    status.message(
      'action',
      actionState: MessageActionState(
        actionText: 'retry',
        action: () => replacement++,
      ),
    );
    await tester.pump(const Duration(seconds: 30));
    await _resume(tester, container);
    await tester.pump(const Duration(milliseconds: 1500));
    expect(find.text('action'), findsOneWidget);
    await tester.tap(find.text('retry'));
    await tester.pumpAndSettle();
    expect(old, 0);
    expect(replacement, 1);
    expect(find.text('action'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('reduced motion snaps transitions but keeps notice duration', (
    tester,
  ) async {
    final (status, _) = await _pump(tester, reduced: true);
    status.message('reduced');
    await tester.pump();
    expect(find.text('reduced'), findsOneWidget);
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pump(const Duration(milliseconds: 2900));
    expect(find.text('reduced'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 200));
    await tester.pump();
    expect(find.text('reduced'), findsNothing);
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
