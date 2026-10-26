import 'dart:math' as math;

import 'package:fl_clash/ui/color.dart';
import 'package:fl_clash/ui/context.dart';
import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

class CommonLinearLoading extends StatelessWidget {
  const CommonLinearLoading({super.key, this.value, this.semanticLabel});

  final double? value;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final glass =
        LiquidGlassScope.of(context) && !MediaQuery.highContrastOf(context);
    if (!glass) {
      return RepaintBoundary(
        child: LinearProgressIndicator(
          value: value,
          semanticsLabel: semanticLabel,
        ),
      );
    }
    final color = context.colorScheme.onSurface;
    final clear =
        LiquidGlassScope.qualityOf(context) == LiquidGlassQuality.ultra;
    return GlassInk(
      source: color,
      role: GlassInkRole.icon,
      child: RepaintBoundary(
        child: LinearProgressIndicator(
          value: value,
          semanticsLabel: semanticLabel,
          color: color.withValues(alpha: clear ? 0.88 : 0.72),
          backgroundColor: color.withValues(alpha: 0.08),
          stopIndicatorRadius: 0,
        ),
      ),
    );
  }
}

class CommonCircleLoading extends StatefulWidget {
  const CommonCircleLoading({
    super.key,
    this.size,
    this.color,
    this.semanticLabel,
  });

  const CommonCircleLoading.page({super.key, this.color, this.semanticLabel})
    : size = 48;

  final double? size;
  final Color? color;
  final String? semanticLabel;

  @override
  State<CommonCircleLoading> createState() => _CommonCircleLoadingState();
}

class _CommonCircleLoadingState extends State<CommonCircleLoading>
    with SingleTickerProviderStateMixin {
  static const _turnDuration = Duration(milliseconds: 1000);

  late final AnimationController _turns;
  _LoadingPlacementRender? _placement;
  ScrollPosition? _position;
  ValueListenable<TickerModeData>? _tickerMode;
  var _active = true;
  var _checkScheduled = false;

  @override
  void initState() {
    super.initState();
    _turns = AnimationController(vsync: this, duration: _turnDuration)
      ..repeat();
  }

  bool get _shown => _tickerMode?.value.enabled ?? true;

  void _placed(_LoadingPlacementRender placement) {
    _placement = placement;
    _scheduleCheck();
  }

  void _scheduleCheck() {
    if (_checkScheduled) return;
    _checkScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkScheduled = false;
      _check();
    });
  }

  /// Runs after a frame and never asks for one, so a stopped ring looks
  /// again on every frame something else draws: a card above it can shrink
  /// or the view can resize and bring it into sight without any scroll.
  void _check() {
    if (!mounted || !_active) return;
    if (_placement?.outsideViewport ?? false) {
      if (_turns.isAnimating) _turns.stop();
      if (_shown) _scheduleCheck();
    } else if (!_turns.isAnimating) {
      _turns.repeat();
    }
  }

  void _followTickerMode() {
    final tickerMode = TickerMode.getValuesNotifier(context);
    if (identical(tickerMode, _tickerMode)) return;
    _tickerMode?.removeListener(_scheduleCheck);
    _tickerMode = tickerMode..addListener(_scheduleCheck);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _followTickerMode();
    final position = Scrollable.maybeOf(context)?.position;
    if (position != _position) {
      _position?.removeListener(_scheduleCheck);
      _position = position;
      _position?.addListener(_scheduleCheck);
    }
    _scheduleCheck();
  }

  @override
  void activate() {
    super.activate();
    _active = true;
    if (!_turns.isAnimating) _turns.repeat();
    _scheduleCheck();
  }

  @override
  void deactivate() {
    _active = false;
    _turns.stop();
    super.deactivate();
  }

  @override
  void dispose() {
    _position?.removeListener(_scheduleCheck);
    _tickerMode?.removeListener(_scheduleCheck);
    _turns.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final side = widget.size ?? IconTheme.of(context).size ?? Glyph.size;
    final colors = context.colorScheme;
    final color =
        widget.color ??
        (GlassForeground.colorsInk(context, colors.onSurface)
            ? colors.onSurface
            : colors.primary);
    return _LoadingPlacement(
      onPlaced: _placed,
      child: Semantics(
        label: widget.semanticLabel,
        child: GlassInk(
          source: color,
          role: GlassInkRole.icon,
          child: RepaintBoundary(
            child: CustomPaint(
              size: Size.square(side),
              painter: _CometRingPainter(turns: _turns, color: color),
            ),
          ),
        ),
      ),
    );
  }
}

class _LoadingPlacement extends SingleChildRenderObjectWidget {
  const _LoadingPlacement({required this.onPlaced, required super.child});

  final ValueChanged<_LoadingPlacementRender> onPlaced;

  @override
  _LoadingPlacementRender createRenderObject(BuildContext context) =>
      _LoadingPlacementRender(onPlaced);

  @override
  void updateRenderObject(
    BuildContext context,
    _LoadingPlacementRender renderObject,
  ) => renderObject.onPlaced = onPlaced;
}

class _LoadingPlacementRender extends RenderProxyBox {
  _LoadingPlacementRender(this.onPlaced);

  ValueChanged<_LoadingPlacementRender> onPlaced;

  /// Only a measured miss counts: a ring outside any scrolling view, or not
  /// laid out yet, keeps turning. A sliver gives the rows in its keep-alive
  /// bucket an all-zero transform, which measures as NaN.
  bool get outsideViewport {
    if (!attached || !hasSize || size.isEmpty) return false;
    if (RenderAbstractViewport.maybeOf(this) case final RenderBox viewport) {
      final bounds = MatrixUtils.transformRect(
        getTransformTo(viewport),
        paintBounds,
      );
      return !bounds.isFinite || !bounds.overlaps(Offset.zero & viewport.size);
    }
    return false;
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    onPlaced(this);
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    onPlaced(this);
    super.paint(context, offset);
  }
}

class _CometRingPainter extends CustomPainter {
  _CometRingPainter({required this.turns, required this.color})
    : super(repaint: turns);

  // A glyph keeps its outline within 20 of its 24 grid, so the ring standing
  // in for one reads at the same size.
  static const _liveArea = 20 / 24;
  static const _minStrokeWidth = 1.5;
  static const _tail = 0.72;

  final Animation<double> turns;
  final Color color;

  Rect? _ring;
  late Paint _track;
  late Paint _comet;
  late Paint _head;

  /// Every turn draws the same ring, so its gradient and paints are made
  /// once per size rather than once a frame.
  void _prepare(Rect ring, double strokeWidth) {
    if (ring == _ring) return;
    _ring = ring;
    Paint stroke() => Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth;
    _track = stroke()..color = color.opacity12;
    _comet = stroke()
      ..shader = SweepGradient(
        colors: [color.opacity0, color.opacity30, color, color.opacity0],
        stops: const [0, _tail / 2, _tail, _tail],
      ).createShader(ring);
    _head = Paint()..color = color;
  }

  @override
  void paint(Canvas canvas, Size size) {
    final side = size.shortestSide;
    final strokeWidth = math.max(side / 12, _minStrokeWidth);
    final radius = (side * _liveArea - strokeWidth) / 2;
    if (radius <= 0) {
      return;
    }
    final ring = Rect.fromCircle(center: Offset.zero, radius: radius);
    const sweep = _tail * 2 * math.pi;
    _prepare(ring, strokeWidth);
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.drawCircle(Offset.zero, radius, _track);
    canvas.rotate(turns.value * 2 * math.pi);
    canvas.drawArc(ring, 0, sweep, false, _comet);
    canvas.drawCircle(
      Offset(math.cos(sweep), math.sin(sweep)) * radius,
      strokeWidth / 2,
      _head,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(_CometRingPainter oldDelegate) {
    return oldDelegate.turns != turns || oldDelegate.color != color;
  }
}
