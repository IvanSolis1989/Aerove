import 'dart:async';
import 'dart:math' as math;

import 'package:fl_clash/ui/context.dart';
import 'package:fl_clash/ui/foreground_ink.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/surface.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/gestures.dart';

/// A press lifts the thumb past the track; glass keeps its ambient finish
/// at rest and turns the lifted thumb into a clear lens.
class CommonSwitch extends StatefulWidget {
  const CommonSwitch({
    super.key,
    required this.value,
    required this.onChanged,
    this.semanticLabel,
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final String? semanticLabel;

  static const trackWidth = 52.0;
  static const trackHeight = 32.0;

  @override
  State<CommonSwitch> createState() => _CommonSwitchState();
}

class _CommonSwitchState extends State<CommonSwitch>
    with TickerProviderStateMixin {
  // M3's layout: 4 dp either side of the track, as tall as a tap target.
  static const _inset = 4.0;
  static const _thumb = 24.0;
  static const _midThumb = Size(34, 22);
  static const _heldThumb = 40.0;
  static const _heldStretch = Offset(10, -2);
  static const _toggleDuration = Duration(milliseconds: 300);
  static const _pressDuration = Duration(milliseconds: 150);
  static const _tapLift = Duration(milliseconds: 200);

  late final _toggle = AnimationController(
    vsync: this,
    duration: _toggleDuration,
    value: widget.value ? 1 : 0,
  );
  late final _position = CurvedAnimation(
    parent: _toggle,
    curve: Curves.easeOutBack,
    reverseCurve: Curves.easeOutBack.flipped,
  );
  late final _pressController = AnimationController(
    vsync: this,
    duration: _pressDuration,
  );
  late final _press = CurvedAnimation(
    parent: _pressController,
    curve: Curves.easeOutCubic,
  );
  late final _motion = Listenable.merge([_position, _press]);

  Timer? _tapLiftTimer;
  var _dragging = false;
  var _focused = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _toggle.duration = context.motionDuration(_toggleDuration);
    _pressController.duration = context.motionDuration(_pressDuration);
  }

  @override
  void didUpdateWidget(CommonSwitch oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.value != widget.value) {
      if (_toggle.isDismissed || _toggle.isCompleted) {
        _position
          ..curve = Curves.easeOutBack
          ..reverseCurve = Curves.easeOutBack.flipped;
      }
      // A drag hands over its flip with the thumb already past halfway.
      final across = (_toggle.value - (widget.value ? 1 : 0)).abs() > 0.5;
      if (across && widget.onChanged != null) {
        _liftAcross();
      }
      _settle();
    }
  }

  @override
  void dispose() {
    _tapLiftTimer?.cancel();
    _position.dispose();
    _press.dispose();
    _toggle.dispose();
    _pressController.dispose();
    super.dispose();
  }

  void _settle() => widget.value ? _toggle.forward() : _toggle.reverse();

  void _flip() => widget.onChanged?.call(!widget.value);

  void _handlePress() {
    _tapLiftTimer?.cancel();
    _pressController.forward();
  }

  void _liftAcross() {
    _handlePress();
    _tapLiftTimer = Timer(
      context.motionDuration(_tapLift),
      _pressController.reverse,
    );
  }

  void _handleDragStart() {
    _handlePress();
    _dragging = true;
    _position
      ..curve = Curves.linear
      ..reverseCurve = Curves.linear;
  }

  void _handleDrag(double dx) {
    final rtl = Directionality.of(context) == TextDirection.rtl;
    _toggle.value +=
        (rtl ? -dx : dx) / (CommonSwitch.trackWidth - CommonSwitch.trackHeight);
  }

  void _handleRelease() {
    if (!_dragging) {
      _flip();
      _liftAcross();
      return;
    }
    _pressController.reverse();
    _dragging = false;
    if (_toggle.value >= 0.5 == widget.value) {
      _settle();
      return;
    }
    _flip();
    // The flipped value arrives with the next build, if the owner takes it.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _settle();
      }
    });
  }

  void _handleCancel() {
    _pressController.reverse();
    if (_dragging) {
      _dragging = false;
      _settle();
    }
  }

  Rect _thumbIn(Rect track, bool rtl) {
    final on = _toggle.value;
    final stretch = math.sin(math.pi * on.clamp(0.0, 1.0));
    final size = Size.lerp(
      Size.lerp(const Size.square(_thumb), _midThumb, stretch),
      const Size.square(_heldThumb) + _heldStretch * stretch,
      _press.value,
    )!;
    final along =
        track.height / 2 + (track.width - track.height) * _position.value;
    return Rect.fromCenter(
      center: Offset(
        rtl ? track.right - along : track.left + along,
        track.center.dy,
      ),
      width: size.width,
      height: size.height,
    );
  }

  Widget _buildParts(BuildContext context, Size size) {
    final colorScheme = context.colorScheme;
    final enabled = widget.onChanged != null;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final on = _toggle.value.clamp(0.0, 1.0);
    final lift = _press.value;
    final track = Rect.fromCenter(
      center: size.center(Offset.zero),
      width: CommonSwitch.trackWidth,
      height: CommonSwitch.trackHeight,
    );
    final trackColor = enabled
        ? Color.lerp(
            colorScheme.surfaceContainerHighest,
            colorScheme.primary,
            on,
          )
        : Color.lerp(
            colorScheme.surfaceContainerHighest.withValues(alpha: 0.12),
            colorScheme.onSurface.withValues(alpha: 0.12),
            on,
          );
    final thumbColor = enabled
        ? Color.lerp(colorScheme.outline, colorScheme.onPrimary, on)!
        : Color.lerp(
            colorScheme.onSurface.withValues(alpha: 0.38),
            colorScheme.surface,
            on,
          )!;
    final shadows = [
      if (lift > 0)
        BoxShadow(
          color: colorScheme.shadow.withValues(alpha: 0.2 * lift),
          offset: const Offset(0, 2),
          blurRadius: 6,
        ),
    ];
    final glass =
        LiquidGlassScope.of(context) && !MediaQuery.highContrastOf(context);
    final quality = LiquidGlassScope.qualityOf(context);
    final finish = LiquidGlassStyle.regular(colorScheme).copyWith(blur: 0);
    final thumbFill = glass ? Colors.transparent : thumbColor;
    final thumb = DecoratedBox(
      decoration: ShapeDecoration(
        color: thumbFill,
        shape: AppShape.full,
        shadows: glass ? null : shadows,
      ),
    );
    return Stack(
      clipBehavior: Clip.none,
      children: [
        Positioned.fromRect(
          rect: track,
          child: glass
              ? LiquidGlass(
                  style: finish.copyWith(
                    tint: Color.lerp(
                      colorScheme.onSurface.withValues(
                        alpha: enabled
                            ? (quality == LiquidGlassQuality.standard
                                  ? 0.13
                                  : 0.06)
                            : 0.03,
                      ),
                      colorScheme.primary.withValues(
                        alpha: enabled ? 0.18 : 0.03,
                      ),
                      on,
                    ),
                  ),
                  pressed: enabled && lift > 0,
                )
              : DecoratedBox(
                  decoration: ShapeDecoration(
                    color: trackColor,
                    shape: AppShape.full,
                  ),
                ),
        ),
        Positioned.fromRect(
          rect: _thumbIn(track, rtl),
          child: glass
              ? LiquidGlass(
                  style: LiquidGlassStyle.lerp(
                    finish.copyWith(
                      tint: colorScheme.surface.withValues(
                        alpha: enabled ? 0.08 : 0.03,
                      ),
                    ),
                    LiquidGlassStyle.lens(colorScheme),
                    lift,
                  ),
                  shadows: shadows,
                  child: thumb,
                )
              : thumb,
        ),
        if (_focused)
          Positioned.fromRect(
            rect: track,
            child: DecoratedBox(
              decoration: ShapeDecoration(
                shape: AppShape.full.copyWith(
                  side: focusRingSide(colorScheme, standsOff: true),
                ),
              ),
            ),
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onChanged != null;
    final size = Size(CommonSwitch.trackWidth + _inset * 2, switch (Theme.of(
      context,
    ).materialTapTargetSize) {
      MaterialTapTargetSize.padded => kMinInteractiveDimension,
      MaterialTapTargetSize.shrinkWrap => kMinInteractiveDimension - 8,
    });
    return Semantics(
      label: widget.semanticLabel,
      toggled: widget.value,
      enabled: enabled,
      onTap: enabled ? _flip : null,
      child: FocusableActionDetector(
        enabled: enabled,
        mouseCursor: enabled ? SystemMouseCursors.click : MouseCursor.defer,
        onShowFocusHighlight: (value) => setState(() => _focused = value),
        actions: {
          ActivateIntent: CallbackAction<ActivateIntent>(
            onInvoke: (_) => _flip(),
          ),
        },
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          excludeFromSemantics: true,
          onHorizontalDragStart: enabled ? (_) => _handleDragStart() : null,
          onHorizontalDragUpdate: enabled
              ? (details) => _handleDrag(details.primaryDelta!)
              : null,
          onHorizontalDragEnd: enabled ? (_) => _handleRelease() : null,
          onHorizontalDragCancel: enabled ? _handleCancel : null,
          child: _buildTapTarget(
            enabled: enabled,
            child: SizedBox.fromSize(
              size: size,
              child: AnimatedBuilder(
                animation: _motion,
                builder: (context, _) => _buildParts(context, size),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Taps go through an invisible [InkResponse] because an InkWell around the
  /// switch skips its own press ink while one inside it is pressed.
  Widget _buildTapTarget({required bool enabled, required Widget child}) {
    if (!enabled) {
      return child;
    }
    if (Material.maybeOf(context) == null) {
      return GestureDetector(
        excludeFromSemantics: true,
        onTapDown: (_) => _handlePress(),
        onTap: _handleRelease,
        onTapCancel: _handleCancel,
        child: child,
      );
    }
    return InkResponse(
      onTapDown: (_) => _handlePress(),
      onTap: _handleRelease,
      onTapCancel: _handleCancel,
      excludeFromSemantics: true,
      canRequestFocus: false,
      enableFeedback: false,
      splashFactory: NoSplash.splashFactory,
      overlayColor: const WidgetStatePropertyAll(Colors.transparent),
      child: child,
    );
  }
}

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
    final value = widget.child.value;
    final states = <WidgetState>{
      if (value) WidgetState.selected,
      if (!_enabled) WidgetState.disabled,
      if (_focused) WidgetState.focused,
      if (_hovered) WidgetState.hovered,
      if (_pointer != null) WidgetState.pressed,
    };
    final tint =
        widget.child.trackColor?.resolve(states) ??
        (value ? colors.primary : colors.onSurface);
    final quality = LiquidGlassScope.qualityOf(context);
    final finish = LiquidGlassStyle.regular(
      colors,
    ).copyWith(blur: 0).forQuality(quality);
    final inactiveAlpha = quality == LiquidGlassQuality.standard ? 0.13 : 0.06;
    final trackStyle = finish.copyWith(
      tint: tint.withValues(
        alpha: _enabled ? (value ? 0.18 : inactiveAlpha) : 0.03,
      ),
    );
    final outline = widget.child.trackOutlineColor?.resolve(states);
    final side = _focused && _enabled
        ? focusRingSide(colors)
        : outline != null && outline.a > 0
        ? BorderSide(
            color: outline,
            width: widget.child.trackOutlineWidth?.resolve(states) ?? 1,
          )
        : BorderSide.none;
    final thumbColor = widget.child.thumbColor?.resolve(states);
    final saturated =
        thumbColor != null && HSVColor.fromColor(thumbColor).saturation >= 0.20;
    final customIcon = widget.child.thumbIcon?.resolve(states);
    final iconColor =
        customIcon?.color ??
        colors.onSurface.withValues(alpha: _enabled ? 1 : 0.38);
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
    final thumbStyle = finish.copyWith(
      tint: thumbTint.withValues(alpha: _enabled ? 0.08 : 0.03),
    );
    final thumb = LiquidGlass(
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
                            child: LiquidGlass(
                              style: trackStyle,
                              pressed:
                                  _enabled && (_pointer != null || _hovered),
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
