import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui';

typedef AuroraMotionPose = ({double angle, Offset translation, double side});

/// Lite's `FullAuroraMotion` repeats every operation in this order with only
/// integer and IEEE double +, -, *, /, sqrt, floor and ceil (no sin/cos), so
/// the shared golden poses match bit for bit. Rules: docs/shared/appearance.md
/// (灵动极光运动).
class AuroraMotion {
  AuroraMotion(int seed) {
    var state = (seed ^ 0x9E3779B9) & _mask;
    if (state == 0) state = 0x6D2B79F5;
    _key = state;
    double next() {
      state = (state ^ (state << 13)) & _mask;
      state = (state ^ (state >> 17)) & _mask;
      state = (state ^ (state << 5)) & _mask;
      return state / 0x100000000;
    }

    _phase = next() * 2 * math.pi;
    // Draws 2-7 fed the retired wobble; draw 8 keeps each seed's old period.
    for (var draw = 0; draw < 6; draw++) {
      next();
    }
    final tempo = (next() * 600 + 0.5).floor();
    _turn = (800 + tempo) * 1600000;
  }

  static const _mask = 0xFFFFFFFF;
  static const _warp = 0.2;
  static const _warpKnot = 9000000;
  static const _reach = 0.34;
  static const _saltWarp = 3;
  static const _saltHeading = 11;
  static const _saltBlock = 12;
  static const _saltWalk = 13;
  static const _knot = 250000;
  static const _blockSteps = 1920;
  static const _blockMicros = _blockSteps * _knot;
  static const _step = 0.035;
  static const _aim = 0.85;
  static const _limit = 0.97;
  static const _candidates = 43;
  static const _legMin = 0.4;
  static const _legCap = 1.3;
  static const _turnMax = 28;
  static const _turnSoft = 17;
  static const _steer = 1;
  static const _arrive = 1.5;
  static const _window = 120;
  static const _windCap = 21;
  static const _band = 6;
  static const _bandFree = 0.42;
  static const _wLen = 0.585;
  static const _wTurn = 0.0104;
  static const _wSharp = 0.42;
  static const _wOffset = 2.8;
  static const _wWind = 0.1;
  static const _wCap = 0.0856;
  static const _wBand = 18.75;
  static const _wNoise = 0.82;
  static const _homeFrom = _blockSteps - 80;
  static const _homeMin = 0.5;
  static const _homeTurn = 8;
  static const _homeForced = _homeFrom + 70;
  static const _homeSteer = 3;
  static const _homeBehind = 16;
  static const _homeAny = _homeForced + 30;
  // A block ends within 1973 steps and keeps three shared points past it.
  static const _capacity = 2048;
  static const _quadrant = [
    1.0,
    0.9951847266721969,
    0.9807852804032304,
    0.9569403357322088,
    0.9238795325112867,
    0.881921264348355,
    0.8314696123025452,
    0.773010453362737,
    0.7071067811865476,
    0.6343932841636455,
    0.5555702330196022,
    0.47139673682599764,
    0.3826834323650898,
    0.2902846772544624,
    0.19509032201612828,
    0.0980171403295606,
    0.0,
  ];
  static final _ux = _table(cosine: true);
  static final _uy = _table(cosine: false);

  late final int _key;
  late final double _phase;
  late final int _turn;
  final _xs = Float64List(_capacity);
  final _ys = Float64List(_capacity);
  final _ring = Int32List(_window);
  final _axes = Int32List(_window);
  final _bands = Int32List(32);
  int _block = -1;
  int _extra = 0;
  int _walkKey = 0;
  int _draws = 0;
  double _aimX = 0;
  double _aimY = 0;

  /// The 64 headings: cos(2πk/64) and sin(2πk/64) from exact table entries.
  static Float64List _table({required bool cosine}) {
    final values = Float64List(64);
    for (var k = 0; k < 64; k++) {
      final c = _quadrant[k % 16];
      final s = _quadrant[16 - k % 16];
      final (x, y) = switch (k ~/ 16) {
        0 => (c, s),
        1 => (-s, c),
        2 => (-c, -s),
        _ => (s, -c),
      };
      values[k] = cosine ? x : y;
    }
    return values;
  }

  static int _lowbias(int value) {
    var x = value;
    x ^= x >> 16;
    x = (x * 0x7FEB352D) & _mask;
    x ^= x >> 15;
    x = (x * 0x846CA68B) & _mask;
    x ^= x >> 16;
    return x;
  }

  static int _hashInt(int key, int index, int salt) => _lowbias(
    (key ^ ((index & _mask) * 0x9E3779B1) ^ (salt * 0x85EBCA77)) & _mask,
  );

  double _hash(int index, int salt) =>
      _hashInt(_key, index, salt) / 0x100000000;

  int _heading(int block) => (_hash(block, _saltHeading) * 64).floor();

  double _draw() => _hashInt(_walkKey, _draws++, _saltWalk) / 0x100000000;

  static int _wrap(int slots) => (slots + 32) % 64 - 32;

  static int _nearest(double dx, double dy) {
    var best = dx * _ux[0] + dy * _uy[0];
    var nearest = 0;
    for (var k = 1; k < 64; k++) {
      final value = dx * _ux[k] + dy * _uy[k];
      if (value > best) {
        best = value;
        nearest = k;
      }
    }
    return nearest;
  }

  static double _weight(int i, double f) => switch (i) {
    0 => (1 - f) * (1 - f) * (1 - f) / 6,
    1 => (3 * f * f * f - 6 * f * f + 4) / 6,
    2 => (-3 * f * f * f + 3 * f * f + 3 * f + 1) / 6,
    _ => f * f * f / 6,
  };

  AuroraMotionPose at(Duration elapsed, Size viewport) {
    final time = elapsed.inMicroseconds;
    final knot = time ~/ _warpKnot;
    var f = (time % _warpKnot) / _warpKnot;
    var angle = 2 * math.pi * ((27 * time) % _turn) / _turn + _phase;
    for (var i = 0; i < 4; i++) {
      angle += _weight(i, f) * _warp * (2 * _hash(knot + i - 1, _saltWarp) - 1);
    }
    final block = time ~/ _blockMicros;
    final tau = time % _blockMicros;
    if (block != _block) _simulate(block);
    final u = tau / _blockMicros;
    final g = u * u * u * (u * (u * 6 - 15) + 10);
    final s = tau / _knot + _extra * g;
    final k = s.floor();
    f = s - k;
    var x = 0.0;
    var y = 0.0;
    for (var i = 0; i < 4; i++) {
      final weight = _weight(i, f);
      x += weight * _xs[k + i];
      y += weight * _ys[k + i];
    }
    final reach = viewport.shortestSide * _reach;
    return (
      angle: angle,
      translation: Offset(reach * x, reach * y),
      side:
          (math.sqrt(
                viewport.width * viewport.width +
                    viewport.height * viewport.height,
              ) +
              2 * reach) *
          1.01,
    );
  }

  /// Walks block [block] from the centre and back; step s lands at s + 1.
  void _simulate(int block) {
    final xs = _xs;
    final ys = _ys;
    final ring = _ring..fillRange(0, _window, 0);
    final axes = _axes..fillRange(0, _window, -1);
    final bands = _bands..fillRange(0, 32, 0);
    const length = _step;
    _walkKey = _hashInt(_key, block, _saltBlock);
    _draws = 0;
    final h0 = _heading(block);
    final h1 = _heading(block + 1);
    xs[1] = 0.0;
    ys[1] = 0.0;
    var x = 0.0;
    var y = 0.0;
    var h = h0;
    var wind = 0;
    var aim = false;
    var homing = false;
    var i = 0;
    var total = -1;
    var arrivalX = 0.0;
    var arrivalY = 0.0;
    while (total < 0) {
      i++;
      var turned = 0;
      var steer = i >= 2;
      if (steer && !homing && i >= _homeFrom) {
        if (i >= _homeForced) {
          homing = true;
        } else if (x * x + y * y >= _homeMin * _homeMin &&
            _wrap(_nearest(-x, -y) - h).abs() <= _homeTurn) {
          homing = true;
        }
        if (homing) {
          aim = true;
          _aimX = 0.0;
          _aimY = 0.0;
        }
      }
      if (steer && !homing) {
        if (aim) {
          final ex = _aimX - x;
          final ey = _aimY - y;
          if (ex * ex + ey * ey < (_arrive * length) * (_arrive * length) ||
              ex * _ux[h] + ey * _uy[h] <= 0) {
            aim = false;
          }
        }
        if (!aim) {
          _choose(x, y, h, wind);
          aim = true;
        }
      }
      if (steer &&
          homing &&
          x * x + y * y < _homeMin * _homeMin &&
          _wrap(_nearest(-x, -y) - h).abs() > _homeBehind) {
        steer = false;
      }
      if (steer) {
        final ex = _aimX - x;
        final ey = _aimY - y;
        for (var n = 0; n < (homing ? _homeSteer : _steer); n++) {
          final current = ex * _ux[h] + ey * _uy[h];
          final left = ex * _ux[(h + 1) % 64] + ey * _uy[(h + 1) % 64];
          final right = ex * _ux[(h - 1) % 64] + ey * _uy[(h - 1) % 64];
          if (left > current && left >= right) {
            h = (h + 1) % 64;
            turned += 1;
          } else if (right > current) {
            h = (h - 1) % 64;
            turned -= 1;
          } else {
            break;
          }
        }
      }
      final slot = i % _window;
      wind += turned - ring[slot];
      ring[slot] = turned;
      if (axes[slot] >= 0) bands[axes[slot]] -= 1;
      axes[slot] = h % 32;
      bands[h % 32] += 1;
      x = x + length * _ux[h];
      y = y + length * _uy[h];
      final r2 = x * x + y * y;
      if (r2 > _limit * _limit) {
        final scale = _limit / math.sqrt(r2);
        x = x * scale;
        y = y * scale;
      }
      xs[i + 1] = x;
      ys[i + 1] = y;
      if (!homing) continue;
      final d2 = x * x + y * y;
      if (d2 > (2 * length) * (2 * length) &&
          (_wrap(_nearest(-x, -y) - h).abs() <= 1 || i >= _homeAny)) {
        final d = math.sqrt(d2);
        arrivalX = -x / d;
        arrivalY = -y / d;
        final m = math.max(2, (d / length).ceil());
        final tx = -length * arrivalX;
        final ty = -length * arrivalY;
        for (var k = 1; k < m; k++) {
          final fraction = k / (m - 1);
          xs[i + 1 + k] = x + fraction * (tx - x);
          ys[i + 1 + k] = y + fraction * (ty - y);
        }
        xs[i + 1 + m] = 0.0;
        ys[i + 1 + m] = 0.0;
        total = i + m;
      }
    }
    // Turns the walk slowly about the centre so it arrives on heading h1.
    final ux1 = _ux[h1];
    final uy1 = _uy[h1];
    final c = arrivalX * ux1 + arrivalY * uy1;
    final sn = arrivalX * uy1 - arrivalY * ux1;
    var low = (1 - c) / 2;
    var high = (1 + c) / 2;
    if (low < 0) low = 0.0;
    if (high < 0) high = 0.0;
    var end = math.sqrt(low) / (1 + math.sqrt(high));
    if (sn < 0) end = -end;
    final span = total - 2;
    for (var s = 2; s < total - 1; s++) {
      final q = end * (s - 1) / span;
      final cq = (1 - q * q) / (1 + q * q);
      final sq = 2 * q / (1 + q * q);
      final c4 = cq * cq - sq * sq;
      final s4 = 2 * cq * sq;
      final px = xs[s + 1];
      final py = ys[s + 1];
      xs[s + 1] = c4 * px - s4 * py;
      ys[s + 1] = s4 * px + c4 * py;
    }
    xs[0] = -length * _ux[h0];
    ys[0] = -length * _uy[h0];
    xs[total] = -length * ux1;
    ys[total] = -length * uy1;
    xs[total + 1] = 0.0;
    ys[total + 1] = 0.0;
    xs[total + 2] = length * ux1;
    ys[total + 2] = length * uy1;
    _extra = total - _blockSteps;
    _block = block;
  }

  /// Picks the next aim point; 43 candidates of three draws each.
  void _choose(double x, double y, int h, int wind) {
    const length = _step;
    var found = false;
    var best = 0.0;
    var bestX = 0.0;
    var bestY = 0.0;
    for (var n = 0; n < _candidates; n++) {
      final u1 = _draw();
      final u2 = _draw();
      final u3 = _draw();
      final k = (u2 * 64).floor();
      final radius = _aim * math.sqrt(u1);
      final cx = radius * _ux[k];
      final cy = radius * _uy[k];
      final dx = cx - x;
      final dy = cy - y;
      final d2 = dx * dx + dy * dy;
      if (d2 < _legMin * _legMin) continue;
      final d = math.sqrt(d2);
      final heading = _nearest(dx, dy);
      final turn = _wrap(heading - h);
      if (turn.abs() > _turnMax) continue;
      var leg = d;
      if (leg > _legCap) leg = _legCap;
      final axis = heading % 32;
      var count = 0;
      for (var j = -_band; j <= _band; j++) {
        count += _bands[(axis + j) % 32];
      }
      final share = (count + leg / length) / (_window + leg / length);
      var sharp = turn.abs() - _turnSoft;
      if (sharp < 0) sharp = 0;
      final spin = (wind + turn).abs();
      var over = spin - _windCap;
      if (over < 0) over = 0;
      var crowded = share - _bandFree;
      if (crowded < 0) crowded = 0.0;
      final score =
          _wLen * leg -
          _wTurn * turn.abs() -
          _wSharp * sharp -
          _wOffset * (x * dy - y * dx).abs() / d -
          _wWind * spin -
          _wCap * over -
          _wBand * crowded +
          _wNoise * u3;
      if (!found || score > best) {
        found = true;
        best = score;
        bestX = cx;
        bestY = cy;
      }
    }
    _aimX = found ? bestX : 0.0;
    _aimY = found ? bestY : 0.0;
  }
}
