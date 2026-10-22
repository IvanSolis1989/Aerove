import 'package:fl_clash/ui/frame_pacer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('requests a frame only when one is due, not every vsync', (
    tester,
  ) async {
    final published = <Duration>[];
    final pacer = FramePacer(published.add);
    pacer.start();
    expect(tester.binding.hasScheduledFrame, isTrue);
    await tester.pump();
    expect(published, isEmpty);
    for (final refreshRate in [60, 120]) {
      final before = published.length;
      final frames = _countFrames(tester);
      final interval = Duration(
        microseconds: (Duration.microsecondsPerSecond / refreshRate).round(),
      );
      for (var vsync = 0; vsync < refreshRate; vsync++) {
        await tester.pump(interval);
      }
      expect(published.length - before, inInclusiveRange(29, 30));
      expect(frames(), published.length - before);
    }
    for (var index = 0; index < published.length; index++) {
      expect(
        published[index].inMicroseconds * 30 ~/ Duration.microsecondsPerSecond,
        index + 1,
      );
    }
    pacer.dispose();
  });

  testWidgets('pacers running together share their frames', (tester) async {
    final fast = <Duration>[];
    final slow = <Duration>[];
    final wallpaper = FramePacer(fast.add);
    final drift = FramePacer(slow.add, framesPerSecond: 15);
    addTearDown(wallpaper.dispose);
    addTearDown(drift.dispose);
    wallpaper.start();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 11));
    drift.start();
    await tester.pump();
    const vsync = Duration(microseconds: 8333);
    final frames = _countFrames(tester);
    for (var index = 0; index < 120; index++) {
      await tester.pump(vsync);
    }
    expect(frames(), inInclusiveRange(29, 30));
    expect(slow.length, inInclusiveRange(14, 15));
    expect(fast.length, frames());

    drift.framesPerSecond = 10;
    final slower = _countFrames(tester);
    final before = (fast.length, slow.length);
    for (var index = 0; index < 120; index++) {
      await tester.pump(vsync);
    }
    expect(slower(), inInclusiveRange(29, 30));
    expect(fast.length - before.$1, slower());
    expect(slow.length - before.$2, inInclusiveRange(9, 10));
    wallpaper.dispose();
    drift.dispose();
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('a rate change moves the next frame onto the new rate', (
    tester,
  ) async {
    final published = <Duration>[];
    final pacer = FramePacer(published.add);
    pacer.start();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 40));
    expect(published, const [Duration(milliseconds: 40)]);
    pacer.framesPerSecond = 10;
    expect(pacer.framesPerSecond, 10);
    await tester.pump(const Duration(milliseconds: 99));
    expect(published, hasLength(1));
    await tester.pump(const Duration(milliseconds: 1));
    expect(published, const [
      Duration(milliseconds: 40),
      Duration(milliseconds: 140),
    ]);
    pacer.dispose();
  });

  testWidgets('starting during a frame counts elapsed time from that frame', (
    tester,
  ) async {
    final published = <Duration>[];
    final pacer = FramePacer(published.add, framesPerSecond: 20);
    tester.binding.addPostFrameCallback((_) => pacer.start());
    tester.binding.scheduleFrame();
    await tester.pump();
    expect(pacer.isActive, isTrue);
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pump(const Duration(milliseconds: 49));
    expect(published, isEmpty);
    await tester.pump(const Duration(milliseconds: 1));
    expect(published, const [Duration(milliseconds: 50)]);
    pacer.dispose();
  });

  testWidgets('stopping, resuming and disposing leave nothing scheduled', (
    tester,
  ) async {
    final published = <Duration>[];
    final pacer = FramePacer(published.add);
    pacer.start();
    pacer.stop();
    expect(pacer.isActive, isFalse);
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pump(const Duration(minutes: 1));
    expect(published, isEmpty);

    pacer.start();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(published, const [Duration(milliseconds: 50)]);
    pacer.stop();
    final stopped = _countFrames(tester);
    await tester.pump(const Duration(minutes: 1));
    expect(stopped(), 0);
    expect(published, hasLength(1));

    pacer.start();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 34));
    expect(published.last, const Duration(milliseconds: 34));
    pacer.dispose();
    final disposed = _countFrames(tester);
    await tester.pump(const Duration(minutes: 1));
    expect(disposed(), 0);
    expect(published, hasLength(2));
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('a callback that stops the pacer leaves nothing scheduled', (
    tester,
  ) async {
    var calls = 0;
    late final FramePacer pacer;
    pacer = FramePacer((_) {
      calls++;
      pacer.stop();
    });
    pacer.start();
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    final frames = _countFrames(tester);
    await tester.pump(const Duration(minutes: 1));
    expect(calls, 1);
    expect(frames(), 0);
    expect(pacer.isActive, isFalse);
    expect(tester.binding.transientCallbackCount, 0);
  });
}

int Function() _countFrames(WidgetTester tester) {
  var frames = 0;
  var counting = true;
  void count(Duration _) {
    if (!counting) return;
    frames++;
    tester.binding.addPostFrameCallback(count);
  }

  tester.binding.addPostFrameCallback(count);
  return () {
    counting = false;
    return frames;
  };
}
