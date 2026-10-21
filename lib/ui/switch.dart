import 'package:flutter/gestures.dart';
import 'package:material_ui/material_ui.dart';

import 'context.dart';
import 'foreground_ink.dart';
import 'icons/icons.dart';
import 'liquid_glass.dart';
import 'shape.dart';
import 'surface.dart';

class GlassSwitch extends StatefulWidget {
  const GlassSwitch({super.key, required this.child});

  final Switch child;

  @override
  State<GlassSwitch> createState() => _GlassSwitchState();
}

class _GlassSwitchState extends State<GlassSwitch> {
  static const _trackWidth = 52.0;
  static const _trackHeight = 32.0;
  static const _thumbExtent = 24.0;
  static const _inset = 4.0;
  static const _travel = _trackWidth - _thumbExtent - _inset * 2;
  static const _disabledShare = 0.4;
  static const _onTrackAlpha = 0.35;

  int? _pointer;
  double _start = 0;
  double? _drag;
  bool _focused = false;
  bool _hovered = false;

  bool get _enabled => widget.child.onChanged != null;

  void _down(PointerDownEvent event) {
    if (!_enabled || _pointer != null || event.buttons & kPrimaryButton == 0) {
      return;
    }
    setState(() {
      _pointer = event.pointer;
      _start = event.position.dx;
    });
  }

  void _move(PointerMoveEvent event) {
    if (event.pointer != _pointer) {
      return;
    }
    final delta = event.position.dx - _start;
    final direction = Directionality.of(context) == TextDirection.rtl ? -1 : 1;
    setState(
      () => _drag =
          ((widget.child.value ? 1.0 : 0.0) + delta * direction / _travel)
              .clamp(0.0, 1.0),
    );
  }

  void _end(PointerEvent event) {
    if (event.pointer != _pointer) {
      return;
    }
    setState(() {
      _pointer = null;
      _drag = null;
    });
  }

  @override
  void didUpdateWidget(GlassSwitch oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_enabled) {
      _pointer = null;
      _drag = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!LiquidGlassScope.of(context) || MediaQuery.highContrastOf(context)) {
      return widget.child;
    }
    final colors = context.colorScheme;
    final onGlass = GlassSurfaceScope.of(context);
    final value = widget.child.value;
    final states = <WidgetState>{
      if (value) WidgetState.selected,
      if (!_enabled) WidgetState.disabled,
      if (_focused) WidgetState.focused,
      if (_hovered) WidgetState.hovered,
      if (_pointer != null) WidgetState.pressed,
    };
    final track = value
        ? (widget.child.trackColor?.resolve(states) ?? colors.primary)
              .withValues(alpha: _onTrackAlpha)
        : glassFillOf(colors);
    final trackFill = _enabled
        ? track
        : track.withValues(alpha: track.a * _disabledShare);
    final outline = widget.child.trackOutlineColor?.resolve(states);
    final side = _focused && _enabled
        ? focusRingSide(colors)
        : outline != null && outline.a > 0
        ? BorderSide(
            color: outline,
            width: widget.child.trackOutlineWidth?.resolve(states) ?? 1,
          )
        : BorderSide.none;
    final dark = colors.brightness == Brightness.dark;
    final thumbColor = widget.child.thumbColor?.resolve(states);
    final saturated =
        thumbColor != null && HSVColor.fromColor(thumbColor).saturation >= 0.20;
    final knob = saturated
        ? thumbColor
        : dark
        ? colors.inverseSurface
        : SurfaceLevel.card.colorOf(colors);
    final customIcon = widget.child.thumbIcon?.resolve(states);
    final iconColor =
        customIcon?.color ??
        (onGlass && dark && !saturated
                ? colors.onInverseSurface
                : colors.onSurface)
            .withValues(alpha: _enabled ? 1 : 0.38);
    final mark = customIcon == null
        ? GlyphIcon(
            value ? AppGlyphs.check : AppGlyphs.close,
            size: 14,
            color: iconColor,
          )
        : customIcon.icon == null
        ? const SizedBox.shrink()
        : GlassInk(
            source: iconColor,
            role: GlassInkRole.icon,
            child: IconTheme.merge(
              data: IconThemeData(color: iconColor),
              child: customIcon,
            ),
          );
    final thumbTint = saturated ? thumbColor : colors.surface;
    final thumbStyle = LiquidGlassStyle.regular(colors).copyWith(
      blur: 0,
      tint: thumbTint.withValues(alpha: _enabled ? 0.08 : 0.03),
    );
    final thumb = onGlass
        ? DecoratedBox(
            decoration: ShapeDecoration(
              color: _enabled ? knob : Color.alphaBlend(trackFill, knob),
              shape: AppShape.circle,
              shadows: [
                BoxShadow(
                  color: colors.shadow.withValues(alpha: 0.18),
                  blurRadius: 4,
                  offset: const Offset(0, 1),
                ),
              ],
            ),
            child: Center(child: LiquidGlassScope(enabled: false, child: mark)),
          )
        : LiquidGlass(
            style: thumbStyle,
            pressed: _enabled && (_pointer != null || _hovered),
            child: Center(child: mark),
          );
    return Focus(
      canRequestFocus: false,
      skipTraversal: true,
      onFocusChange: (focused) => setState(() => _focused = focused),
      child: MouseRegion(
        onEnter: (_) => setState(() => _hovered = true),
        onExit: (_) => setState(() => _hovered = false),
        child: Listener(
          onPointerDown: _down,
          onPointerMove: _move,
          onPointerUp: _end,
          onPointerCancel: _end,
          child: Stack(
            alignment: Alignment.center,
            children: [
              Positioned.fill(
                child: IgnorePointer(
                  child: ExcludeSemantics(
                    child: Padding(
                      padding: widget.child.padding ?? EdgeInsets.zero,
                      child: Center(
                        child: SizedBox(
                          width: _trackWidth,
                          height: _trackHeight,
                          child: DecoratedBox(
                            position: DecorationPosition.foreground,
                            decoration: ShapeDecoration(
                              shape: AppShape.full.copyWith(side: side),
                            ),
                            child: DecoratedBox(
                              decoration: ShapeDecoration(
                                color: trackFill,
                                shape: AppShape.full,
                              ),
                              child: Padding(
                                padding: const EdgeInsets.all(_inset),
                                child: TweenAnimationBuilder<double>(
                                  tween: Tween(
                                    end: _drag ?? (value ? 1.0 : 0.0),
                                  ),
                                  duration: _pointer != null
                                      ? Duration.zero
                                      : context.motionDuration(
                                          kThemeAnimationDuration,
                                        ),
                                  curve: Curves.easeOut,
                                  builder: (_, position, _) => Align(
                                    alignment: AlignmentDirectional(
                                      position * 2 - 1,
                                      0,
                                    ),
                                    child: SizedBox.square(
                                      dimension: _thumbExtent,
                                      child: thumb,
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              // Native input and semantics stay authoritative; its opaque paint is replaced above.
              Opacity(
                opacity: 0,
                alwaysIncludeSemantics: true,
                child: widget.child,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
