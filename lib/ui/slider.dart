import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

import 'liquid_glass.dart';
import 'shape.dart';

class GlassSlider extends StatefulWidget {
  const GlassSlider({super.key, required this.child, this.colorPreview});

  final Slider child;
  final Color? colorPreview;

  @override
  State<GlassSlider> createState() => _GlassSliderState();
}

class _GlassSliderState extends State<GlassSlider> {
  RenderBox? _paintParent;
  late final _track = LiquidGlassPane(_ready);
  late final _active = LiquidGlassPane(_ready);
  late final _secondary = LiquidGlassPane(_ready);
  late final _thumb = LiquidGlassPane(_ready);

  void _ready() {
    if (_paintParent?.attached ?? false) _paintParent!.markNeedsPaint();
  }

  @override
  void dispose() {
    _track.dispose();
    _active.dispose();
    _secondary.dispose();
    _thumb.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!LiquidGlassScope.of(context) || MediaQuery.highContrastOf(context)) {
      _track.clear();
      _active.clear();
      _secondary.clear();
      _thumb.clear();
      return widget.child;
    }
    final theme = SliderTheme.of(context);
    final colors = Theme.of(context).colorScheme;
    final enabled =
        widget.child.onChanged != null && widget.child.min < widget.child.max;
    final trackColor = enabled
        ? widget.child.inactiveColor ??
              theme.inactiveTrackColor ??
              colors.onSurface
        : theme.disabledInactiveTrackColor ?? colors.onSurface;
    final activeColor = enabled
        ? widget.child.activeColor ?? theme.activeTrackColor ?? colors.primary
        : theme.disabledActiveTrackColor ?? colors.onSurface;
    final secondaryColor = enabled
        ? widget.child.secondaryActiveColor ??
              theme.secondaryActiveTrackColor ??
              activeColor
        : theme.disabledSecondaryActiveTrackColor ?? colors.onSurface;
    final thumbColor = widget.child.thumbColor ?? colors.surface;
    final style = LiquidGlassStyle.regular(colors).copyWith(blur: 0);
    return _SliderCompositing(
      child: SliderTheme(
        data: theme.copyWith(
          trackShape: _GlassTrackShape(
            delegate: theme.trackShape ?? const GappedSliderTrackShape(),
            track: _track,
            active: _active,
            secondary: _secondary,
            style: style.copyWith(
              tint: trackColor.withValues(
                alpha: trackColor.a * (enabled ? 0.05 : 0.02),
              ),
            ),
            activeStyle: style.copyWith(
              tint: activeColor.withValues(
                alpha: activeColor.a * (enabled ? 0.22 : 0.04),
              ),
            ),
            secondaryStyle: style.copyWith(
              tint: secondaryColor.withValues(
                alpha: secondaryColor.a * (enabled ? 0.12 : 0.03),
              ),
            ),
            preserveTrack: widget.colorPreview != null,
            onPaint: (parent) => _paintParent = parent,
          ),
          thumbShape: _GlassThumbShape(
            delegate: theme.thumbShape ?? const HandleThumbShape(),
            pane: _thumb,
            style: style.copyWith(
              tint: thumbColor.withValues(
                alpha: thumbColor.a * (enabled ? 0.08 : 0.03),
              ),
            ),
            preview: widget.colorPreview,
            onPaint: (parent) => _paintParent = parent,
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

class _GlassTrackShape extends SliderTrackShape {
  const _GlassTrackShape({
    required this.delegate,
    required this.track,
    required this.active,
    required this.secondary,
    required this.style,
    required this.activeStyle,
    required this.secondaryStyle,
    required this.preserveTrack,
    required this.onPaint,
  });

  final SliderTrackShape delegate;
  final LiquidGlassPane track;
  final LiquidGlassPane active;
  final LiquidGlassPane secondary;
  final LiquidGlassStyle style;
  final LiquidGlassStyle activeStyle;
  final LiquidGlassStyle secondaryStyle;
  final bool preserveTrack;
  final ValueChanged<RenderBox> onPaint;

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
    onPaint(parentBox);
    final rect = getPreferredRect(
      parentBox: parentBox,
      offset: offset,
      sliderTheme: sliderTheme,
      isEnabled: isEnabled,
      isDiscrete: isDiscrete,
    );
    if (preserveTrack) {
      delegate.paint(
        context,
        offset,
        parentBox: parentBox,
        sliderTheme: sliderTheme,
        enableAnimation: enableAnimation,
        textDirection: textDirection,
        thumbCenter: thumbCenter,
        secondaryOffset: secondaryOffset,
        isDiscrete: isDiscrete,
        isEnabled: isEnabled,
      );
    }
    track.paint(
      context,
      preserveTrack
          ? Rect.fromLTRB(
              offset.dx,
              rect.top,
              offset.dx + parentBox.size.width,
              rect.bottom,
            )
          : rect,
      shape: const LiquidGlassCorners(AppRadius.full),
      style: style,
      contentBackdrop: preserveTrack,
    );
    if (preserveTrack) {
      active.clear();
      secondary.clear();
      return;
    }
    final x = thumbCenter.dx.clamp(rect.left, rect.right);
    final secondaryX = secondaryOffset?.dx.clamp(rect.left, rect.right);
    if (secondaryX != null &&
        (textDirection == TextDirection.ltr
            ? secondaryX > x
            : secondaryX < x)) {
      secondary.paint(
        context,
        Rect.fromLTRB(
          textDirection == TextDirection.ltr ? x : secondaryX,
          rect.top,
          textDirection == TextDirection.ltr ? secondaryX : x,
          rect.bottom,
        ),
        shape: const LiquidGlassCorners(AppRadius.full),
        style: secondaryStyle,
      );
    } else {
      secondary.clear();
    }
    final selected = Rect.fromLTRB(
      textDirection == TextDirection.ltr ? rect.left : x,
      rect.top,
      textDirection == TextDirection.ltr ? x : rect.right,
      rect.bottom,
    );
    if (selected.isEmpty) {
      active.clear();
    } else {
      active.paint(
        context,
        selected,
        shape: const LiquidGlassCorners(AppRadius.full),
        style: activeStyle,
      );
    }
  }
}

class _GlassThumbShape extends SliderComponentShape {
  const _GlassThumbShape({
    required this.delegate,
    required this.pane,
    required this.style,
    required this.preview,
    required this.onPaint,
  });

  final SliderComponentShape delegate;
  final LiquidGlassPane pane;
  final LiquidGlassStyle style;
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
    final base = preview == null
        ? sliderTheme.thumbSize?.resolve(<WidgetState>{}) ??
              getPreferredSize(enableAnimation.value > 0, isDiscrete)
        : getPreferredSize(enableAnimation.value > 0, isDiscrete);
    final growth = preview == null ? 0.0 : activationAnimation.value * 4;
    final rect = Rect.fromCenter(
      center: center,
      width: base.width + growth,
      height: base.height + growth,
    );
    pane.paint(
      context,
      rect,
      shape: const LiquidGlassCorners(AppRadius.full),
      style: style,
      contentBackdrop: preview != null,
    );
    if (preview case final color?) {
      context.canvas.drawCircle(
        center,
        (rect.shortestSide / 2 - 3).clamp(0.0, double.infinity),
        Paint()..color = color,
      );
    }
  }
}
