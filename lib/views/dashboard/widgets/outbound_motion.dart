import 'dart:math' as math;

import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/frame_pacer.dart';
import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/motion.dart';
import 'package:fl_clash/widgets/aurora.dart' show AuroraMotionActivity;
import 'package:flutter/foundation.dart';
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
/// must; [wobble] ripples its skin over [time], mirrored when negative, and
/// [irregularity] scales the lobes away, down to a circle at 0.
Path _dropletPath(
  Size size, {
  double phase = 0,
  double fullness = 0,
  double squeeze = 0,
  double time = 0,
  double wobble = 0,
  double irregularity = 1,
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
    final tilt = irregularity * 0.025 * math.cos(2 * angle + 1.1 + phase);
    final lobes = irregularity * 0.03 * math.cos(3 * angle + 0.6 - 0.7 * phase);
    var skin = breath;
    for (var index = 0; index < swings.length; index++) {
      skin += swings[index] * math.cos(wave(index, angle));
    }
    return base * (1 + squash * math.cos(2 * angle) + tilt + lobes + skin);
  }

  double slope(double angle) {
    final tilt = irregularity * 0.05 * math.sin(2 * angle + 1.1 + phase);
    final lobes = irregularity * 0.09 * math.sin(3 * angle + 0.6 - 0.7 * phase);
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
    this.irregularity = 1,
  });

  final double phase;
  final double fullness;
  final double squeeze;
  final double time;
  final double wobble;
  final double irregularity;

  @override
  Path getClip(Size size) => _dropletPath(
    size,
    phase: phase,
    fullness: fullness,
    squeeze: squeeze,
    time: time,
    wobble: wobble,
    irregularity: irregularity,
  );

  @override
  bool shouldReclip(OutboundDropletClipper oldClipper) =>
      oldClipper.phase != phase ||
      oldClipper.fullness != fullness ||
      oldClipper.squeeze != squeeze ||
      oldClipper.time != time ||
      oldClipper.wobble != wobble ||
      oldClipper.irregularity != irregularity;
}

class OutboundDropletPainter extends CustomPainter {
  const OutboundDropletPainter({
    this.phase = 0,
    this.fullness = 0,
    this.squeeze = 0,
    this.time = 0,
    this.wobble = 0,
    this.irregularity = 1,
    required this.color,
    this.shadow = Colors.transparent,
    this.ring,
    this.rim,
    this.highlight = true,
  });

  final double phase;
  final double fullness;
  final double squeeze;
  final double time;
  final double wobble;
  final double irregularity;

  /// No color leaves the inside to glass, and [shadow] then falls only
  /// outside the droplet, as one under glass would show.
  final Color? color;
  final Color shadow;
  final BorderSide? ring;
  final Color? rim;
  final bool highlight;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final extent = size.shortestSide;
    final path = _dropletPath(
      size,
      phase: phase,
      fullness: fullness,
      squeeze: squeeze,
      time: time,
      wobble: wobble,
      irregularity: irregularity,
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
          path.shift(Offset(0, extent * 0.058)),
          Paint()
            ..color = shadow
            ..maskFilter = MaskFilter.blur(BlurStyle.normal, extent * 0.067),
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
      }
    }
    if (rim case final rim?) {
      canvas.drawPath(
        path,
        Paint()
          ..color = rim
          ..style = PaintingStyle.stroke
          ..strokeWidth = math.max(1.0, extent * 0.025),
      );
    }
    if (ring case final ring?) {
      final unit = _dropletPath(
        const Size.square(1),
        phase: phase,
        fullness: fullness,
        squeeze: squeeze,
        time: time,
        wobble: wobble,
        irregularity: irregularity,
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
      oldDelegate.irregularity != irregularity ||
      oldDelegate.color != color ||
      oldDelegate.shadow != shadow ||
      oldDelegate.ring != ring ||
      oldDelegate.rim != rim ||
      oldDelegate.highlight != highlight;

  @override
  bool shouldRebuildSemantics(OutboundDropletPainter oldDelegate) => false;
}

final _bubbleSettle = SpringCurve(
  const SpringDescription(mass: 1, stiffness: 180, damping: 12),
  seconds: 0.7,
);

/// A bubble that writhes while [checking], then settles; [builder] draws it
/// from the drift [time] and the skin's [wobble], negative past rest.
class OutboundBubble extends StatefulWidget {
  const OutboundBubble({
    super.key,
    required this.checking,
    required this.builder,
    this.child,
  });

  final bool checking;
  final Widget Function(
    BuildContext context,
    double time,
    double wobble,
    Widget? child,
  )
  builder;
  final Widget? child;

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
    builder: (_, time, child) => AnimatedBuilder(
      animation: _rest,
      builder: (context, child) =>
          widget.builder(context, time, 1 - _rest.value, child),
      child: child,
    ),
    child: widget.child,
  );
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
  static const _restRate = 15;
  static const _interactionRate = 10;

  static final _members = <_OutboundDriftState>{};
  static final _pacer = FramePacer(_tick, framesPerSecond: _restRate);
  static var _now = Duration.zero;

  static Duration join(_OutboundDriftState member) {
    final first = _members.isEmpty;
    _members.add(member);
    retune();
    if (first) {
      _now = Duration.zero;
      _pacer.start();
    }
    return _now;
  }

  static void leave(_OutboundDriftState member) {
    if (!_members.remove(member)) return;
    if (_members.isEmpty) {
      _pacer.stop();
    } else {
      retune();
    }
  }

  static void retune() {
    _pacer.framesPerSecond = _members.any((member) => member._interacting)
        ? _interactionRate
        : _restRate;
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
  ValueListenable<bool>? _interaction;
  _OutboundVisibilityRender? _render;
  Duration? _joined;
  double _base = 0;
  bool _foreground = true;
  bool _checkScheduled = false;

  /// Measured only once layout is done: mid build, a card the dashboard
  /// editor has just moved has no place in its list yet.
  bool _visible = false;

  @override
  void initState() {
    super.initState();
    _foreground = switch (WidgetsBinding.instance.lifecycleState) {
      null || AppLifecycleState.resumed => true,
      _ => false,
    };
    WidgetsBinding.instance.addObserver(this);
  }

  void _measure() => _visible = _render?.visible == true;

  bool get _interacting => _interaction?.value ?? false;

  void _onInteraction() {
    if (_joined != null) _DriftClock.retune();
  }

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
    _measure();
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
    _measure();
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
      if (!mounted) return;
      _measure();
      _sync();
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
    final interaction = AuroraMotionActivity.interactionOf(context);
    if (!identical(_interaction, interaction)) {
      _interaction?.removeListener(_onInteraction);
      _interaction = interaction?..addListener(_onInteraction);
      _onInteraction();
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
    _interaction?.removeListener(_onInteraction);
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

  /// Each gauze strand as (height, sweep, wave number, wave rate, thickness,
  /// twist number, twist rate, opacity, offset); their own rates and
  /// directions keep the layers sliding past one another.
  static const _strands = [
    (0.58, 0.15, 5.2, 1.0, 0.12, 3.1, 0.8, 1.0, 0.0),
    (0.46, 0.11, 6.6, -0.75, 0.075, 4.4, -1.1, 0.7, 2.1),
    (0.7, 0.09, 4.3, 1.35, 0.06, 2.6, 1.4, 0.55, 4.3),
  ];
  static const _samples = 36;

  final double phase;
  final bool dark;
  final bool visible;

  static List<(Offset, Offset)> _edges(
    (double, double, double, double, double, double, double, double, double)
    strand,
    double phase,
  ) {
    final (height, sweep, wave, rate, thickness, twist, turn, _, offset) =
        strand;
    final run = rate * phase;
    final edges = <(Offset, Offset)>[];
    for (var index = 0; index <= _samples; index++) {
      final x = -0.08 + 1.16 * index / _samples;
      final center =
          height -
          0.22 * (x - 0.5) +
          sweep * math.sin(wave * x - run + offset) +
          0.35 * sweep * math.sin(1.9 * wave * x + 0.6 * run + 2 * offset);
      final fold = math.cos(twist * x - turn * phase + offset).abs();
      final half = thickness * (0.18 + 0.82 * fold);
      edges.add((Offset(x, center - half), Offset(x, center + half)));
    }
    return edges;
  }

  static void _through(Path path, List<Offset> points) {
    for (var index = 1; index < points.length - 1; index++) {
      final mid = Offset.lerp(points[index], points[index + 1], 0.5)!;
      path.quadraticBezierTo(
        points[index].dx,
        points[index].dy,
        mid.dx,
        mid.dy,
      );
    }
    path.lineTo(points.last.dx, points.last.dy);
  }

  @override
  void paint(Canvas canvas, Size size) {
    if (!visible || size.isEmpty) return;
    canvas
      ..save()
      ..scale(size.width, size.height);
    final bright = dark ? 0.12 : 0.26;
    final shade = dark ? 0.08 : 0.13;
    for (final strand in _strands) {
      final opacity = strand.$8;
      final edges = _edges(strand, phase);
      final upper = [for (final (top, _) in edges) top];
      final lower = [for (final (_, bottom) in edges) bottom].reversed.toList();
      final band = Path()..moveTo(upper.first.dx, upper.first.dy);
      _through(band, upper);
      band.lineTo(lower.first.dx, lower.first.dy);
      _through(band, lower);
      band.close();
      canvas.drawPath(
        band,
        Paint()
          ..shader = LinearGradient(
            colors: [
              const Color(0xFFD8E4EF).withValues(alpha: 0.02 * opacity),
              Colors.white.withValues(alpha: bright * opacity),
              const Color(0xFF73859B).withValues(alpha: shade * opacity),
              Colors.white.withValues(alpha: 0.8 * bright * opacity),
              const Color(0xFF73859B).withValues(alpha: 0.02 * opacity),
            ],
            stops: const [0, 0.27, 0.5, 0.72, 1],
          ).createShader(const Rect.fromLTWH(0, 0, 1, 1)),
      );
      final sheen = Path()..moveTo(upper.first.dx, upper.first.dy);
      _through(sheen, upper);
      canvas.drawPath(
        sheen,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.2 / size.height
          ..color = Colors.white.withValues(alpha: 1.6 * bright * opacity),
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
