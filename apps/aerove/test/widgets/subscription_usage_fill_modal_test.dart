import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/widgets/subscription_usage_fill.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// Frames the page asks for by itself, stepped a millisecond at a time.
Future<int> _framesIn(WidgetTester tester, Duration span) async {
  var frames = 0;
  for (var ms = 0; ms < span.inMilliseconds; ms++) {
    await tester.binding.delayed(const Duration(milliseconds: 1));
    if (tester.binding.hasScheduledFrame) {
      frames++;
      await tester.pump();
    }
  }
  return frames;
}

void main() {
  testWidgets('the liquid rests under a popup route and moves at 15 fps once '
      'it pops', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigator,
        builder: (_, child) => LiquidGlassScope(enabled: true, child: child!),
        home: const Scaffold(
          body: Center(
            child: SizedBox(
              width: 300,
              height: 64,
              child: SubscriptionUsageFill(
                info: SubscriptionInfo(download: 50, total: 100),
                child: SizedBox.expand(),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    expect(await _framesIn(tester, const Duration(seconds: 2)), 30);

    navigator.currentState!.push(
      RawDialogRoute<void>(
        barrierColor: Colors.transparent,
        transitionDuration: Duration.zero,
        pageBuilder: (_, _, _) => const Align(
          alignment: Alignment.bottomCenter,
          child: SizedBox(height: 200),
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    expect(await _framesIn(tester, const Duration(seconds: 2)), 0);

    navigator.currentState!.pop();
    await tester.pump();
    await tester.pump();
    expect(await _framesIn(tester, const Duration(seconds: 2)), 30);

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
  });
}
