import 'dart:math' as math;

import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:intl/intl.dart' show NumberFormat;
import 'package:material_ui/material_ui.dart';

double? _shareOf(SubscriptionInfo? info) {
  if (info == null || info.total <= 0 || info.upload < 0 || info.download < 0) {
    return null;
  }
  return (info.upload + info.download) / info.total;
}

({Path path, double width, double depth}) subscriptionUsageMeniscus({
  required Size size,
  required double share,
  required Duration time,
  bool rtl = false,
}) {
  final level = size.width * share;
  final phase = time.inMicroseconds / Duration.microsecondsPerSecond;
  final depth = share >= 1
      ? 0.0
      : math.min(4.0, math.min(level, size.width - level) / 3) *
            (1 + math.sin(phase * 1.3) * 0.05);
  final contact = level + depth;
  final shoulder = level + depth * 0.55;
  // The cubic integral balances both contact bends against the flat center.
  final plane = level - depth * (0.0955 / 0.864);
  final width = math.min(size.width, contact + (depth > 0 ? 0.5 : 0));
  double x(double value) => rtl ? width - value : value;
  return (
    path: Path()
      ..moveTo(x(contact), 0)
      ..cubicTo(
        x(shoulder),
        0,
        x(plane),
        size.height * 0.08,
        x(plane),
        size.height * 0.22,
      )
      ..cubicTo(
        x(plane),
        size.height * 0.36,
        x(plane),
        size.height * 0.64,
        x(plane),
        size.height * 0.78,
      )
      ..cubicTo(
        x(plane),
        size.height * 0.92,
        x(shoulder),
        size.height,
        x(contact),
        size.height,
      ),
    width: width,
    depth: depth,
  );
}

class SubscriptionUsageFill extends StatefulWidget {
  const SubscriptionUsageFill({
    super.key,
    required this.info,
    this.motionTime,
    required this.child,
  });

  final SubscriptionInfo? info;
  final Duration? motionTime;
  final Widget child;

  static String? percentageOf(BuildContext context, SubscriptionInfo? info) {
    final share = _shareOf(info);
    return share == null
        ? null
        : NumberFormat.percentPattern(
            Localizations.localeOf(context).toString(),
          ).format(share);
  }

  @override
  State<SubscriptionUsageFill> createState() => _SubscriptionUsageFillState();
}

class _SubscriptionUsageFillState extends State<SubscriptionUsageFill>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  static const _interval = Duration(microseconds: 33333);

  late final Ticker _ticker;
  late final ValueNotifier<Duration> _time;
  _UsageFillRender? _render;
  ScrollPosition? _position;
  bool _visibilityCheckScheduled = false;
  Duration _base = Duration.zero;
  Duration _lastTick = Duration.zero;
  bool _foreground = true;

  @override
  void initState() {
    super.initState();
    _foreground = switch (WidgetsBinding.instance.lifecycleState) {
      null || AppLifecycleState.resumed => true,
      _ => false,
    };
    _time = ValueNotifier(widget.motionTime ?? Duration.zero);
    _ticker = createTicker((elapsed) {
      if (_render?.visible != true) {
        _ticker.stop();
        return;
      }
      if (elapsed - _lastTick < _interval) return;
      _lastTick = elapsed;
      _time.value = _base + elapsed;
    });
    WidgetsBinding.instance.addObserver(this);
  }

  bool get _moving {
    final share = _shareOf(widget.info);
    return share != null &&
        share > 0 &&
        share < 1 &&
        widget.motionTime == null &&
        _foreground &&
        LiquidGlassScope.of(context) &&
        !MediaQuery.highContrastOf(context) &&
        TickerMode.valuesOf(context).enabled &&
        PageActivityScope.isActiveOf(context) &&
        !MediaQuery.disableAnimationsOf(context);
  }

  void _syncTicker() {
    final moving = mounted && _moving && _render?.visible == true;
    if (moving && !_ticker.isActive) {
      _base = _time.value;
      _lastTick = Duration.zero;
      _ticker.start();
    } else if (!moving && _ticker.isActive) {
      _ticker.stop();
    }
  }

  void _painted(_UsageFillRender render) {
    _render = render;
    _syncTicker();
  }

  void _scrollChanged() {
    if (_visibilityCheckScheduled) return;
    _visibilityCheckScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _visibilityCheckScheduled = false;
      if (mounted) _syncTicker();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final position = Scrollable.maybeOf(context)?.position;
    if (position != _position) {
      _position?.removeListener(_scrollChanged);
      _position = position;
      _position?.addListener(_scrollChanged);
    }
    _syncTicker();
  }

  @override
  void didUpdateWidget(SubscriptionUsageFill oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.motionTime != oldWidget.motionTime &&
        widget.motionTime != null) {
      _time.value = widget.motionTime!;
    }
    _syncTicker();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _syncTicker();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _position?.removeListener(_scrollChanged);
    _ticker.dispose();
    _time.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final share = _shareOf(widget.info);
    if (share == null) return widget.child;
    final colors = context.colorScheme;
    final color = share > 1
        ? colors.error
        : share >= 0.9
        ? colors.warning
        : colors.primary;
    return ClipRSuperellipse(
      borderRadius: AppRadius.xl,
      child: Stack(
        fit: StackFit.passthrough,
        children: [
          Positioned.fill(
            child: IgnorePointer(
              child: _UsageFillBackground(
                share: widget.info!.usage!,
                color: color,
                glass:
                    LiquidGlassScope.of(context) &&
                    !MediaQuery.highContrastOf(context),
                dark: colors.brightness == Brightness.dark,
                rtl: Directionality.of(context) == TextDirection.rtl,
                time: _time,
                onPaint: _painted,
              ),
            ),
          ),
          widget.child,
        ],
      ),
    );
  }
}

class _UsageFillBackground extends LeafRenderObjectWidget {
  const _UsageFillBackground({
    required this.share,
    required this.color,
    required this.glass,
    required this.dark,
    required this.rtl,
    required this.time,
    required this.onPaint,
  });

  final double share;
  final Color color;
  final bool glass;
  final bool dark;
  final bool rtl;
  final ValueListenable<Duration> time;
  final ValueChanged<_UsageFillRender> onPaint;

  @override
  _UsageFillRender createRenderObject(BuildContext context) =>
      _UsageFillRender(this);

  @override
  void updateRenderObject(BuildContext context, _UsageFillRender render) =>
      render.update(this);
}

class _UsageFillRender extends RenderBox {
  _UsageFillRender(this.settings);

  _UsageFillBackground settings;
  late final _pane = LiquidGlassPane(markNeedsPaint);

  bool get visible {
    if (!attached || !hasSize || size.isEmpty) return false;
    final viewport = RenderAbstractViewport.maybeOf(this);
    if (viewport case RenderBox viewportBox) {
      return MatrixUtils.transformRect(
        getTransformTo(viewportBox),
        paintBounds,
      ).overlaps(Offset.zero & viewportBox.size);
    }
    return true;
  }

  void update(_UsageFillBackground next) {
    if (attached && settings.time != next.time) {
      settings.time.removeListener(markNeedsPaint);
      next.time.addListener(markNeedsPaint);
    }
    settings = next;
    markNeedsCompositingBitsUpdate();
    markNeedsPaint();
  }

  @override
  bool get sizedByParent => true;

  @override
  bool get alwaysNeedsCompositing => settings.glass;

  @override
  Size computeDryLayout(BoxConstraints constraints) => constraints.biggest;

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    settings.time.addListener(markNeedsPaint);
  }

  @override
  void detach() {
    settings.time.removeListener(markNeedsPaint);
    super.detach();
  }

  @override
  void dispose() {
    _pane.dispose();
    super.dispose();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    settings.onPaint(this);
    if (settings.share <= 0 || size.isEmpty) {
      _pane.clear();
      return;
    }
    final color = settings.color;
    if (!settings.glass) {
      _pane.clear();
      final level = size.width * settings.share;
      context.canvas.drawRect(
        Rect.fromLTWH(
          settings.rtl ? offset.dx + size.width - level : offset.dx,
          offset.dy,
          level,
          size.height,
        ),
        Paint()..color = color.withValues(alpha: settings.dark ? 0.25 : 0.17),
      );
      return;
    }
    final inset = math.min(5.0, size.shortestSide / 4);
    final cavity = (offset & size).deflate(inset);
    final cavityOutline = AppShape.all(
      math.max(0.0, AppCorner.xl - inset),
    ).getOuterPath(Offset.zero & cavity.size);
    final surface = subscriptionUsageMeniscus(
      size: cavity.size,
      share: settings.share,
      time: settings.time.value,
      rtl: settings.rtl,
    );
    final width = surface.width;
    double x(double value) => settings.rtl ? width - value : value;
    final water = surface.path;
    final path = Path.from(water)
      ..lineTo(x(0), cavity.height)
      ..lineTo(x(0), 0)
      ..close();
    final origin =
        cavity.topLeft + Offset(settings.rtl ? cavity.width - width : 0, 0);
    final liquid = Path.combine(
      PathOperation.intersect,
      path,
      cavityOutline.shift(Offset(settings.rtl ? width - cavity.width : 0, 0)),
    );
    _pane.paint(
      context,
      origin & Size(width, cavity.height),
      shape: LiquidGlassOutline(liquid),
      style: LiquidGlassStyle(
        tint: color.withValues(
          alpha: settings.share > 1
              ? 0.20
              : settings.share >= 0.9
              ? 0.16
              : 0.07,
        ),
        blur: 0.7,
        refraction: 3,
        bevel: 2.5,
        rim: 0.04,
        rimWidth: 0.6,
      ),
    );
    if (surface.depth > 0) {
      context.canvas
        ..save()
        ..clipPath(cavityOutline.shift(cavity.topLeft));
      context.canvas.drawPath(
        water.shift(origin),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 0.65
          ..color = Colors.white.withValues(alpha: settings.dark ? 0.16 : 0.20),
      );
      context.canvas.restore();
    }
  }
}
