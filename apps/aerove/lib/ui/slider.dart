import 'dart:math' as math;

import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

import 'liquid_glass.dart';
import 'shape.dart';
import 'surface.dart';

class GlassSlider extends StatefulWidget {
  const GlassSlider({super.key, required this.child, this.colorPreview});

  final Slider child;
  final Color? colorPreview;

  @override
  State<GlassSlider> createState() => _GlassSliderState();
}

class _GlassSliderState extends State<GlassSlider> {
  static const _thumb = Size(12, 32);
  static const _thumbClearance = 3.0;
  static const _disabledShare = 0.4;
  static const _activeAlpha = 0.5;
  static const _paneTint = 0.6;

  RenderBox? _paintParent;
  late final _pane = LiquidGlassPane(_ready);

  void _ready() {
    if (_paintParent?.attached ?? false) _paintParent!.markNeedsPaint();
  }

  @override
  void dispose() {
    _pane.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!LiquidGlassScope.of(context) || MediaQuery.highContrastOf(context)) {
      _pane.clear();
      return widget.child;
    }
    final onGlass = GlassSurfaceScope.of(context);
    final preview = widget.colorPreview;
    if (preview != null && onGlass) {
      _pane.clear();
      return widget.child;
    }
    final theme = SliderTheme.of(context);
    final colors = Theme.of(context).colorScheme;
    final enabled =
        widget.child.onChanged != null && widget.child.min < widget.child.max;
    final share = enabled ? 1.0 : _disabledShare;
    Color faded(Color color) => color.withValues(alpha: color.a * share);
    final activeColor =
        (enabled
                ? widget.child.activeColor ??
                      theme.activeTrackColor ??
                      colors.primary
                : colors.onSurface)
            .withValues(alpha: _activeAlpha);
    final thumbColor = widget.child.thumbColor;
    final knob = thumbColor != null && thumbColor.a > 0
        ? thumbColor
        : colors.brightness == Brightness.dark
        ? colors.inverseSurface
        : SurfaceLevel.card.colorOf(colors);
    final thumb = _GlassThumbShape(
      delegate: theme.thumbShape ?? const HandleThumbShape(),
      size: preview == null ? _thumb : null,
      pane: onGlass ? null : _pane,
      style: LiquidGlassStyle.regular(
        colors,
      ).copyWith(blur: 0, tint: faded(knob.withValues(alpha: _paneTint))),
      knob: faded(knob),
      shadow: colors.shadow.withValues(alpha: 0.18 * share),
      preview: preview,
      onPaint: (parent) => _paintParent = parent,
    );
    return _SliderCompositing(
      child: SliderTheme(
        data: theme.copyWith(
          trackShape: preview != null
              ? null
              : _FlatTrackShape(
                  delegate: theme.trackShape ?? const GappedSliderTrackShape(),
                  gap: math.max(
                    theme.trackGap ?? 0,
                    _thumb.width / 2 + _thumbClearance,
                  ),
                  inactive: faded(
                    widget.child.inactiveColor?.withValues(
                          alpha: glassFillOf(colors).a,
                        ) ??
                        glassFillOf(colors),
                  ),
                  active: faded(activeColor),
                  secondary: faded(
                    (widget.child.secondaryActiveColor ?? activeColor)
                        .withValues(alpha: activeColor.a / 2),
                  ),
                ),
          thumbShape: thumb,
          tickMarkShape: SliderTickMarkShape.noTickMark,
          valueIndicatorColor: theme.valueIndicatorTextStyle == null
              ? null
              : SurfaceLevel.floatingOf(colors).colorOf(colors),
          valueIndicatorTextStyle: theme.valueIndicatorTextStyle?.copyWith(
            color: colors.onSurface,
          ),
        ),
        child: widget.child,
      ),
    );
  }
}

class _SliderCompositing extends SingleChildRenderObjectWidget {
  const _SliderCompositing({required super.child});

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _SliderCompositingBox();
}

class _SliderCompositingBox extends RenderProxyBox {
  @override
  bool get alwaysNeedsCompositing => true;
}

/// The inactive and active runs as flat fills, parted around the thumb.
class _FlatTrackShape extends SliderTrackShape {
  const _FlatTrackShape({
    required this.delegate,
    required this.gap,
    required this.inactive,
    required this.active,
    required this.secondary,
  });

  static const _innerCorner = Radius.circular(2);

  final SliderTrackShape delegate;
  final double gap;
  final Color inactive;
  final Color active;
  final Color secondary;

  @override
  bool get isRounded => delegate.isRounded;

  @override
  Rect getPreferredRect({
    required RenderBox parentBox,
    Offset offset = Offset.zero,
    required SliderThemeData sliderTheme,
    bool isEnabled = false,
    bool isDiscrete = false,
  }) => delegate.getPreferredRect(
    parentBox: parentBox,
    offset: offset,
    sliderTheme: sliderTheme,
    isEnabled: isEnabled,
    isDiscrete: isDiscrete,
  );

  @override
  void paint(
    PaintingContext context,
    Offset offset, {
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required Animation<double> enableAnimation,
    required TextDirection textDirection,
    required Offset thumbCenter,
    Offset? secondaryOffset,
    bool isDiscrete = false,
    bool isEnabled = false,
  }) {
    final rect = getPreferredRect(
      parentBox: parentBox,
      offset: offset,
      sliderTheme: sliderTheme,
      isEnabled: isEnabled,
      isDiscrete: isDiscrete,
    );
    if (rect.isEmpty) return;
    final ltr = textDirection == TextDirection.ltr;
    final x = thumbCenter.dx.clamp(rect.left, rect.right);
    final outer = Radius.circular(rect.shortestSide / 2);
    final canvas = context.canvas;
    void run(double from, double to, Color color, {required bool startsOuter}) {
      if (to - from <= rect.height / 2 || color.a == 0) return;
      final start = startsOuter ? outer : _innerCorner;
      final end = startsOuter ? _innerCorner : outer;
      canvas.drawRSuperellipse(
        RSuperellipse.fromLTRBAndCorners(
          from,
          rect.top,
          to,
          rect.bottom,
          topLeft: start,
          bottomLeft: start,
          topRight: end,
          bottomRight: end,
        ),
        Paint()..color = color,
      );
    }

    final before = ltr ? active : inactive;
    final after = ltr ? inactive : active;
    run(rect.left, x - gap, before, startsOuter: true);
    run(x + gap, rect.right, after, startsOuter: false);
    final secondaryX = secondaryOffset?.dx.clamp(rect.left, rect.right);
    if (secondaryX != null &&
        (ltr ? secondaryX > x + gap : secondaryX < x - gap)) {
      if (ltr) {
        run(x + gap, secondaryX, secondary, startsOuter: false);
      } else {
        run(secondaryX, x - gap, secondary, startsOuter: true);
      }
    }
  }
}

class _GlassThumbShape extends SliderComponentShape {
  const _GlassThumbShape({
    required this.delegate,
    required this.size,
    required this.pane,
    required this.style,
    required this.knob,
    required this.shadow,
    required this.preview,
    required this.onPaint,
  });

  final SliderComponentShape delegate;

  /// What it paints, laid out at the delegate's size; null paints that.
  final Size? size;

  /// Null on glass, where the thumb is a flat knob.
  final LiquidGlassPane? pane;
  final LiquidGlassStyle style;
  final Color knob;
  final Color shadow;
  final Color? preview;
  final ValueChanged<RenderBox> onPaint;

  @override
  Size getPreferredSize(bool isEnabled, bool isDiscrete) =>
      delegate.getPreferredSize(isEnabled, isDiscrete);

  @override
  void paint(
    PaintingContext context,
    Offset center, {
    required Animation<double> activationAnimation,
    required Animation<double> enableAnimation,
    required bool isDiscrete,
    required TextPainter labelPainter,
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required TextDirection textDirection,
    required double value,
    required double textScaleFactor,
    required Size sizeWithOverflow,
  }) {
    onPaint(parentBox);
    final base =
        size ?? getPreferredSize(enableAnimation.value > 0, isDiscrete);
    final growth = preview == null ? 0.0 : activationAnimation.value * 4;
    final rect = Rect.fromCenter(
      center: center,
      width: base.width + growth,
      height: base.height + growth,
    );
    final pane = this.pane;
    if (pane == null) {
      final shape = RSuperellipse.fromRectAndRadius(
        rect,
        Radius.circular(rect.shortestSide / 2),
      );
      context.canvas
        ..drawRSuperellipse(
          shape.shift(const Offset(0, 1)),
          Paint()
            ..color = shadow
            ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 2),
        )
        ..drawRSuperellipse(shape, Paint()..color = knob);
    } else {
      pane.paint(
        context,
        rect,
        shape: const LiquidGlassCorners(AppRadius.full),
        style: style,
        contentBackdrop: preview != null,
      );
    }
    if (preview case final color?) {
      context.canvas.drawCircle(
        center,
        (rect.shortestSide / 2 - 3).clamp(0.0, double.infinity),
        Paint()..color = color,
      );
    }
  }
}
