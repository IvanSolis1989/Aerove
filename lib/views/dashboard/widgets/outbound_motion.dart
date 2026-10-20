import 'dart:math' as math;

import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/frame_pacer.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/motion.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'card.dart' show strokeUnitShape;

/// How the status card draws a bubble in each glass mode: a solid colour
/// without glass, frosted on standard glass and clear on ultra.
enum OutboundFinish {
  solid,
  frost,
  clear;

  static OutboundFinish of(BuildContext context) {
    final glass = LiquidGlassScope.of(context);
    if (!glass || MediaQuery.highContrastOf(context)) return solid;
    return switch (LiquidGlassScope.qualityOf(context)) {
      LiquidGlassQuality.standard => frost,
      LiquidGlassQuality.ultra => clear,
    };
  }
}

const _dropletSegments = 24;

/// Standing waves on a bubble's skin: (order, amplitude, swing rate, swing
/// offset, axis drift, axis); unequal rates keep the outline from repeating.
const _surfaceModes = [
  (2, 0.055, 3.1, 0.0, 0.35, 0.4),
  (3, 0.028, 4.7, 1.3, -0.5, 1.1),
  (4, 0.014, 6.3, 2.1, 0.7, 2.3),
  (5, 0.008, 8.9, 0.7, -0.9, 0.5),
];

/// A droplet outline meeting every ray from the center once, as a glass clip
/// must; [wobble] ripples its skin over [time], mirrored when negative.
Path _dropletPath(
  Size size, {
  double phase = 0,
  double fullness = 0,
  double squeeze = 0,
  double time = 0,
  double wobble = 0,
}) {
  final base = (0.41 + 0.025 * fullness) * (1 - 0.1 * wobble.abs());
  final squash = 0.07 * squeeze.clamp(-0.6, 1.0);
  final breath = 0.018 * wobble * math.sin(2.3 * time + 0.9);
  final swings = [
    for (final (_, amplitude, rate, offset, _, _) in _surfaceModes)
      wobble * amplitude * math.sin(rate * time + offset),
  ];
  double wave(int index, double angle) {
    final (order, _, _, _, drift, axis) = _surfaceModes[index];
    return order * (angle - drift * time) + axis;
  }

  double radius(double angle) {
    final tilt = 0.025 * math.cos(2 * angle + 1.1 + phase);
    final lobes = 0.03 * math.cos(3 * angle + 0.6 - 0.7 * phase);
    var skin = breath;
    for (var index = 0; index < swings.length; index++) {
      skin += swings[index] * math.cos(wave(index, angle));
    }
    return base * (1 + squash * math.cos(2 * angle) + tilt + lobes + skin);
  }

  double slope(double angle) {
    final tilt = 0.05 * math.sin(2 * angle + 1.1 + phase);
    final lobes = 0.09 * math.sin(3 * angle + 0.6 - 0.7 * phase);
    var skin = 0.0;
    for (var index = 0; index < swings.length; index++) {
      final (order, _, _, _, _, _) = _surfaceModes[index];
      skin += swings[index] * order * math.sin(wave(index, angle));
    }
    return -base * (2 * squash * math.sin(2 * angle) + tilt + lobes + skin);
  }

  Offset point(double angle) {
    final reach = radius(angle);
    final x = 0.5 + reach * math.cos(angle);
    final y = 0.5 + reach * math.sin(angle);
    return Offset(x * size.width, y * size.height);
  }

  Offset tangent(double angle) {
    final reach = radius(angle);
    final rise = slope(angle);
    final x = rise * math.cos(angle) - reach * math.sin(angle);
    final y = rise * math.sin(angle) + reach * math.cos(angle);
    return Offset(x * size.width, y * size.height);
  }

  const step = 2 * math.pi / _dropletSegments;
  final start = point(0);
  final path = Path()..moveTo(start.dx, start.dy);
  for (var index = 0; index < _dropletSegments; index++) {
    final from = index * step;
    final to = from + step;
    final out = point(from) + tangent(from) * (step / 3);
    final into = point(to) - tangent(to) * (step / 3);
    final end = point(to);
    path.cubicTo(out.dx, out.dy, into.dx, into.dy, end.dx, end.dy);
  }
  return path..close();
}

class OutboundDropletClipper extends CustomClipper<Path> {
  const OutboundDropletClipper({
    this.phase = 0,
    this.fullness = 0,
    this.squeeze = 0,
    this.time = 0,
    this.wobble = 0,
  });

  final double phase;
  final double fullness;
  final double squeeze;
  final double time;
  final double wobble;

  @override
  Path getClip(Size size) => _dropletPath(
    size,
    phase: phase,
    fullness: fullness,
    squeeze: squeeze,
    time: time,
    wobble: wobble,
  );

  @override
  bool shouldReclip(OutboundDropletClipper oldClipper) =>
      oldClipper.phase != phase ||
      oldClipper.fullness != fullness ||
      oldClipper.squeeze != squeeze ||
      oldClipper.time != time ||
      oldClipper.wobble != wobble;
}

class OutboundDropletPainter extends CustomPainter {
  const OutboundDropletPainter({
    this.phase = 0,
    this.fullness = 0,
    this.squeeze = 0,
    this.time = 0,
    this.wobble = 0,
    required this.color,
    this.shadow = Colors.transparent,
    this.ring,
    this.highlight = true,
  });

  static const _shadowOffset = Offset(0, 6);
  static const _shadowBlur = MaskFilter.blur(BlurStyle.normal, 7);

  final double phase;
  final double fullness;
  final double squeeze;
  final double time;
  final double wobble;

  /// No color leaves the inside to glass, and [shadow] then falls only
  /// outside the droplet, as one under glass would show.
  final Color? color;
  final Color shadow;
  final BorderSide? ring;
  final bool highlight;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final path = _dropletPath(
      size,
      phase: phase,
      fullness: fullness,
      squeeze: squeeze,
      time: time,
      wobble: wobble,
    );
    final color = this.color;
    if (shadow.a > 0) {
      canvas.save();
      if (color == null) {
        final around = (Offset.zero & size).inflate(size.longestSide);
        canvas.clipPath(
          Path()
            ..fillType = PathFillType.evenOdd
            ..addRect(around)
            ..addPath(path, Offset.zero),
        );
      }
      canvas
        ..drawPath(
          path.shift(_shadowOffset),
          Paint()
            ..color = shadow
            ..maskFilter = _shadowBlur,
        )
        ..restore();
    }
    if (color != null) {
      canvas.drawPath(path, Paint()..color = color);
      if (highlight) {
        canvas.drawPath(
          path,
          Paint()
            ..shader = LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                Colors.white.withValues(alpha: 0.28),
                Colors.white.withValues(alpha: 0),
                Colors.black.withValues(alpha: 0.06),
              ],
              stops: const [0, 0.46, 1],
            ).createShader(Offset.zero & size),
        );
        final edges = path.computeMetrics().iterator;
        if (edges.moveNext()) {
          final edge = edges.current;
          canvas.drawPath(
            edge.extractPath(edge.length * 0.56, edge.length * 0.71),
            Paint()
              ..color = Colors.white.withValues(alpha: 0.48)
              ..style = PaintingStyle.stroke
              ..strokeWidth = 1.2
              ..strokeCap = StrokeCap.round,
          );
        }
      }
    }
    if (ring case final ring?) {
      final unit = _dropletPath(
        const Size.square(1),
        phase: phase,
        fullness: fullness,
        squeeze: squeeze,
        time: time,
        wobble: wobble,
      );
      canvas
        ..save()
        ..scale(size.width, size.height);
      strokeUnitShape(canvas, unit, size.width, ring);
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(OutboundDropletPainter oldDelegate) =>
      oldDelegate.phase != phase ||
      oldDelegate.fullness != fullness ||
      oldDelegate.squeeze != squeeze ||
      oldDelegate.time != time ||
      oldDelegate.wobble != wobble ||
      oldDelegate.color != color ||
      oldDelegate.shadow != shadow ||
      oldDelegate.ring != ring ||
      oldDelegate.highlight != highlight;

  @override
  bool shouldRebuildSemantics(OutboundDropletPainter oldDelegate) => false;
}

final _bubbleSettle = SpringCurve(
  const SpringDescription(mass: 1, stiffness: 180, damping: 12),
  seconds: 0.7,
);

/// A bubble around [child] that writhes while [checking], then settles.
class OutboundBubble extends StatefulWidget {
  const OutboundBubble({
    super.key,
    required this.size,
    required this.checking,
    required this.well,
    required this.tint,
    required this.finish,
    this.ring,
    required this.child,
  });

  final double size;
  final bool checking;
  final Color well;
  final Color tint;
  final OutboundFinish finish;
  final BorderSide? ring;
  final Widget child;

  @override
  State<OutboundBubble> createState() => _OutboundBubbleState();
}

class _OutboundBubbleState extends State<OutboundBubble>
    with SingleTickerProviderStateMixin {
  late final AnimationController _calm = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 700),
    reverseDuration: const Duration(milliseconds: 400),
    value: widget.checking ? 0 : 1,
  );
  late final CurvedAnimation _rest = CurvedAnimation(
    parent: _calm,
    curve: _bubbleSettle,
    reverseCurve: Curves.easeInCubic,
  );

  bool get _still =>
      MediaQuery.disableAnimationsOf(context) ||
      MediaQuery.highContrastOf(context);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_still) {
      _calm.value = 1;
    } else if (widget.checking && _calm.value == 1) {
      _calm.reverse();
    }
  }

  @override
  void didUpdateWidget(OutboundBubble oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.checking == widget.checking) return;
    if (_still) {
      _calm.value = 1;
    } else if (widget.checking) {
      _calm.reverse();
    } else {
      _calm.forward();
    }
  }

  @override
  void dispose() {
    _rest.dispose();
    _calm.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => OutboundDrift(
    moving: widget.checking,
    builder: (_, phase, child) => AnimatedBuilder(
      animation: _rest,
      builder: (_, child) => CustomPaint(
        painter: OutboundBubblePainter(
          phase: phase,
          calm: _rest.value,
          well: widget.well,
          tint: widget.tint,
          finish: widget.finish,
          ring: widget.ring,
        ),
        child: child,
      ),
      child: child,
    ),
    child: SizedBox.square(
      dimension: widget.size,
      child: Center(child: widget.child),
    ),
  );
}

class OutboundBubblePainter extends CustomPainter {
  const OutboundBubblePainter({
    required this.phase,
    required this.calm,
    required this.well,
    required this.tint,
    required this.finish,
    this.ring,
  });

  static const _sheen = RadialGradient(
    center: Alignment(-0.35, -0.4),
    radius: 0.75,
    colors: [Color(0x80FFFFFF), Color(0x00FFFFFF)],
  );
  static const _film = SweepGradient(
    colors: [
      Color(0x66FFB3D9),
      Color(0x6699E6FF),
      Color(0x66FFF0A0),
      Color(0x66C9A8FF),
      Color(0x66FFB3D9),
    ],
  );

  final double phase;

  /// 0 while the skin writhes and 1 at rest; the settle overshoots past 1.
  final double calm;
  final Color well;
  final Color tint;
  final OutboundFinish finish;
  final BorderSide? ring;

  static Path outline(
    Size size, {
    required double phase,
    required double calm,
  }) => _dropletPath(size, time: phase, wobble: 1 - calm);

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final bounds = Offset.zero & size;
    final path = outline(size, phase: phase, calm: calm);
    canvas.drawPath(path, _fill(well));
    switch (finish) {
      case OutboundFinish.solid:
        canvas
          ..drawPath(path, _fill(tint.withValues(alpha: 0.3)))
          ..drawPath(path, Paint()..shader = _sheen.createShader(bounds));
      case OutboundFinish.frost:
        canvas
          ..drawPath(path, _fill(tint.withValues(alpha: 0.16)))
          ..drawPath(path, Paint()..shader = _sheen.createShader(bounds))
          ..drawPath(path, _stroke(const Color(0x59FFFFFF), 0.8));
      case OutboundFinish.clear:
        final rim = math.max(0.8, size.shortestSide * 0.03);
        canvas
          ..drawPath(path, _fill(tint.withValues(alpha: 0.05)))
          ..drawPath(
            path,
            _stroke(Colors.white, rim)..shader = _film.createShader(bounds),
          )
          ..drawPath(path, _stroke(const Color(0x8CFFFFFF), rim * 0.5));
    }
    final edges = path.computeMetrics().iterator;
    if (edges.moveNext()) {
      final edge = edges.current;
      canvas.drawPath(
        edge.extractPath(edge.length * 0.56, edge.length * 0.7),
        _stroke(const Color(0xB3FFFFFF), 1.2)..strokeCap = StrokeCap.round,
      );
    }
    if (finish == OutboundFinish.clear) {
      canvas.drawOval(
        Rect.fromCenter(
          center: Offset(size.width * 0.33, size.height * 0.3),
          width: size.width * 0.12,
          height: size.height * 0.08,
        ),
        _fill(const Color(0xD9FFFFFF)),
      );
    }
    if (ring case final ring?) {
      final unit = outline(const Size.square(1), phase: phase, calm: calm);
      canvas
        ..save()
        ..scale(size.width, size.height);
      strokeUnitShape(canvas, unit, size.width, ring);
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(OutboundBubblePainter oldDelegate) =>
      oldDelegate.phase != phase ||
      oldDelegate.calm != calm ||
      oldDelegate.well != well ||
      oldDelegate.tint != tint ||
      oldDelegate.finish != finish ||
      oldDelegate.ring != ring;

  @override
  bool shouldRebuildSemantics(OutboundBubblePainter oldDelegate) => false;
}

Paint _fill(Color color) => Paint()..color = color;

Paint _stroke(Color color, double width) {
  return Paint()
    ..color = color
    ..style = PaintingStyle.stroke
    ..strokeWidth = width;
}

class OutboundDrift extends ConsumerStatefulWidget {
  const OutboundDrift({
    super.key,
    required this.moving,
    required this.builder,
    this.child,
  });

  final bool moving;
  final Widget Function(BuildContext context, double phase, Widget? child)
  builder;
  final Widget? child;

  @override
  ConsumerState<OutboundDrift> createState() => _OutboundDriftState();
}

/// Every drift on screen reads one paced clock, so the badge and the gauze
/// moving together share their frames rather than asking for their own.
abstract final class _DriftClock {
  static final _members = <_OutboundDriftState>{};
  static final _pacer = FramePacer(_tick, framesPerSecond: 15);
  static var _now = Duration.zero;

  static Duration join(_OutboundDriftState member) {
    if (_members.isEmpty) {
      _now = Duration.zero;
      _pacer.start();
    }
    _members.add(member);
    return _now;
  }

  static void leave(_OutboundDriftState member) {
    if (_members.remove(member) && _members.isEmpty) _pacer.stop();
  }

  static void _tick(Duration elapsed) {
    _now = elapsed;
    for (final member in _members.toList()) {
      member._advance(elapsed);
    }
  }
}

class _OutboundDriftState extends ConsumerState<OutboundDrift>
    with WidgetsBindingObserver {
  static const _period = Duration(seconds: 12);

  final _phase = ValueNotifier<double>(0);
  ScrollPosition? _position;
  _OutboundVisibilityRender? _render;
  Duration? _joined;
  double _base = 0;
  bool _foreground = true;
  bool _checkScheduled = false;

  @override
  void initState() {
    super.initState();
    _foreground = switch (WidgetsBinding.instance.lifecycleState) {
      null || AppLifecycleState.resumed => true,
      _ => false,
    };
    WidgetsBinding.instance.addObserver(this);
  }

  bool get _visible => _render?.visible == true;

  void _runClock(bool run) {
    if (run == (_joined != null)) return;
    if (run) {
      _base = _phase.value;
      _joined = _DriftClock.join(this);
    } else {
      _joined = null;
      _DriftClock.leave(this);
    }
  }

  void _advance(Duration now) {
    final joined = _joined;
    if (joined == null || !_visible) {
      _runClock(false);
      return;
    }
    final elapsed = now - joined;
    _phase.value =
        _base + elapsed.inMicroseconds / _period.inMicroseconds * 2 * math.pi;
  }

  void _painted(_OutboundVisibilityRender render) {
    _render = render;
    _sync();
  }

  void _sync() {
    final moving =
        mounted &&
        widget.moving &&
        _foreground &&
        ref.read(appVisibleProvider) &&
        TickerMode.valuesOf(context).enabled &&
        PageActivityScope.isActiveOf(context) &&
        (ModalRoute.isCurrentOf(context) ?? true) &&
        !MediaQuery.disableAnimationsOf(context) &&
        !MediaQuery.highContrastOf(context) &&
        _visible;
    _runClock(moving);
  }

  void _checkAfterLayout() {
    if (_checkScheduled) return;
    _checkScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkScheduled = false;
      if (mounted) _sync();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final position = Scrollable.maybeOf(context)?.position;
    if (_position != position) {
      _position?.removeListener(_checkAfterLayout);
      _position = position;
      _position?.addListener(_checkAfterLayout);
    }
    _sync();
    _checkAfterLayout();
  }

  @override
  void didUpdateWidget(OutboundDrift oldWidget) {
    super.didUpdateWidget(oldWidget);
    _sync();
    _checkAfterLayout();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _sync();
  }

  @override
  void deactivate() {
    _runClock(false);
    super.deactivate();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _position?.removeListener(_checkAfterLayout);
    _phase.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(appVisibleProvider, (_, _) => _sync());
    return RepaintBoundary(
      child: _OutboundVisibility(
        onPaint: _painted,
        child: AnimatedBuilder(
          animation: _phase,
          builder: (context, child) =>
              widget.builder(context, _phase.value, child),
          child: widget.child,
        ),
      ),
    );
  }
}

class _OutboundVisibility extends SingleChildRenderObjectWidget {
  const _OutboundVisibility({required this.onPaint, required super.child});

  final ValueChanged<_OutboundVisibilityRender> onPaint;

  @override
  _OutboundVisibilityRender createRenderObject(BuildContext context) =>
      _OutboundVisibilityRender(onPaint);

  @override
  void updateRenderObject(
    BuildContext context,
    _OutboundVisibilityRender renderObject,
  ) => renderObject.onPaint = onPaint;
}

class _OutboundVisibilityRender extends RenderProxyBox {
  _OutboundVisibilityRender(this.onPaint);

  ValueChanged<_OutboundVisibilityRender> onPaint;

  bool get visible {
    if (!attached || !hasSize || size.isEmpty) return false;
    RenderObject? ancestor = this;
    while (ancestor != null) {
      if (ancestor is RenderOffstage && ancestor.offstage ||
          ancestor is RenderOpacity && ancestor.opacity == 0) {
        return false;
      }
      ancestor = ancestor.parent;
    }
    final viewport = RenderAbstractViewport.maybeOf(this);
    if (viewport case final RenderBox viewportBox) {
      return MatrixUtils.transformRect(
        getTransformTo(viewportBox),
        paintBounds,
      ).overlaps(Offset.zero & viewportBox.size);
    }
    return true;
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    onPaint(this);
    super.paint(context, offset);
  }
}

class OutboundRibbon extends StatelessWidget {
  const OutboundRibbon({super.key, required this.running});

  final bool running;

  @override
  Widget build(BuildContext context) => IgnorePointer(
    child: ExcludeSemantics(
      child: OutboundDrift(
        moving: running && !MediaQuery.highContrastOf(context),
        builder: (context, phase, _) => CustomPaint(
          painter: OutboundRibbonPainter(
            phase: phase,
            dark: Theme.of(context).brightness == Brightness.dark,
            visible: !MediaQuery.highContrastOf(context),
          ),
        ),
      ),
    ),
  );
}

class OutboundRibbonPainter extends CustomPainter {
  const OutboundRibbonPainter({
    required this.phase,
    required this.dark,
    required this.visible,
  });

  final double phase;
  final bool dark;
  final bool visible;

  @override
  void paint(Canvas canvas, Size size) {
    if (!visible || size.isEmpty) return;
    final drift = math.sin(phase) * 0.035;
    final fold = math.cos(phase * 0.7) * 0.035;
    canvas
      ..save()
      ..scale(size.width, size.height);
    for (var layer = 0; layer < 3; layer++) {
      final shift = (layer - 1) * 0.055;
      final path = Path()
        ..moveTo(-0.1, 0.82 + shift + drift)
        ..cubicTo(0.17, 1.03, 0.31, 0.17 + fold, 0.61, 0.24 + shift)
        ..cubicTo(0.84, 0.29 - drift, 0.9, 0.95, 1.1, 0.47 + shift)
        ..cubicTo(0.87, 1.2 + fold, 0.78, 0.55, 0.58, 0.49 + shift)
        ..cubicTo(0.36, 0.36 - drift, 0.15, 1.2, -0.1, 0.88 + shift)
        ..close();
      final opacity = layer == 1 ? 1.0 : 0.45;
      canvas.drawPath(
        path,
        Paint()
          ..shader = LinearGradient(
            begin: const Alignment(-0.7, -1),
            end: const Alignment(0.7, 1),
            colors: [
              const Color(0xFFD8E4EF).withValues(alpha: 0.02 * opacity),
              Colors.white.withValues(alpha: (dark ? 0.11 : 0.22) * opacity),
              const Color(0xFF73859B).withValues(alpha: 0.07 * opacity),
              Colors.white.withValues(alpha: (dark ? 0.08 : 0.17) * opacity),
              const Color(0xFF73859B).withValues(alpha: 0.015 * opacity),
            ],
            stops: const [0, 0.28, 0.48, 0.66, 1],
          ).createShader(const Rect.fromLTWH(0, 0, 1, 1)),
      );
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(OutboundRibbonPainter oldDelegate) =>
      oldDelegate.phase != phase ||
      oldDelegate.dark != dark ||
      oldDelegate.visible != visible;

  @override
  bool shouldRebuildSemantics(OutboundRibbonPainter oldDelegate) => false;
}
