import 'dart:math' as math;
import 'dart:ui';

typedef AuroraMotionPose = ({double angle, Offset translation, double side});

class AuroraMotion {
  AuroraMotion(int seed) {
    var state = (seed ^ 0x9E3779B9) & 0xFFFFFFFF;
    if (state == 0) state = 0x6D2B79F5;
    double next() {
      state = (state ^ (state << 13)) & 0xFFFFFFFF;
      state = (state ^ (state >> 17)) & 0xFFFFFFFF;
      state = (state ^ (state << 5)) & 0xFFFFFFFF;
      return state / 0x100000000;
    }

    _phases = [for (var i = 0; i < 7; i++) next() * 2 * math.pi];
    _periods = [
      for (final (base, range) in [
        (2400, 1800),
        (480, 240),
        (780, 360),
        (250, 180),
        (610, 360),
        (340, 170),
        (750, 360),
      ])
        (base + (next() * range).round()) * Duration.microsecondsPerSecond ~/ 5,
    ];
  }

  late final List<double> _phases;
  late final List<int> _periods;

  AuroraMotionPose at(Duration elapsed, Size viewport) {
    double phase(int index) =>
        2 *
            math.pi *
            (elapsed.inMicroseconds % _periods[index]) /
            _periods[index] +
        _phases[index];
    final reach = viewport.shortestSide * 0.12;
    return (
      angle: phase(0) + 0.08 * math.sin(phase(1)) + 0.05 * math.sin(phase(2)),
      translation: Offset(
        reach * (0.7 * math.sin(phase(3)) + 0.3 * math.sin(phase(4))),
        reach * (0.7 * math.sin(phase(5)) + 0.3 * math.sin(phase(6))),
      ),
      side:
          math.sqrt(
            math.pow(viewport.width + 2 * reach, 2) +
                math.pow(viewport.height + 2 * reach, 2),
          ) *
          1.01,
    );
  }
}
