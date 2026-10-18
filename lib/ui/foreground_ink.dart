import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:emoji_regex/emoji_regex.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

final _emojiPattern = emojiRegex();

enum GlassInkRole {
  title(0.30),
  section(0.62),
  body(0.92),
  small(0.75),
  icon(1.0);

  const GlassInkRole(this.strength);
  final double strength;
}

class GlassText extends Text {
  const GlassText(
    super.data, {
    super.key,
    super.style,
    super.strutStyle,
    super.textAlign,
    super.textDirection,
    super.locale,
    super.softWrap,
    super.overflow,
    super.textScaler,
    super.maxLines,
    super.semanticsLabel,
    super.textWidthBasis,
    super.textHeightBehavior,
    super.selectionColor,
    this.inkRole,
  });

  const GlassText.rich(
    super.textSpan, {
    super.key,
    super.style,
    super.strutStyle,
    super.textAlign,
    super.textDirection,
    super.locale,
    super.softWrap,
    super.overflow,
    super.textScaler,
    super.maxLines,
    super.semanticsLabel,
    super.textWidthBasis,
    super.textHeightBehavior,
    super.selectionColor,
    this.inkRole,
  }) : super.rich();

  final GlassInkRole? inkRole;

  @override
  Widget build(BuildContext context) {
    final original = super.build(context);
    final resolved = DefaultTextStyle.of(context).style.merge(style);
    if (resolved.foreground != null) return original;
    final text = data ?? textSpan?.toPlainText() ?? '';
    final color = resolved.color ?? Theme.of(context).colorScheme.onSurface;
    if (_emojiPattern.hasMatch(text) || !_plainSpan(textSpan, color)) {
      return original;
    }
    final size = resolved.fontSize ?? 17;
    final role =
        inkRole ??
        (size >= 22
            ? GlassInkRole.title
            : size <= 13
            ? GlassInkRole.small
            : (resolved.fontWeight?.value ?? 400) >= 600
            ? GlassInkRole.section
            : GlassInkRole.body);
    final outset =
        resolved.shadows?.fold<double>(
          0,
          (reach, shadow) => math.max(
            reach,
            shadow.offset.distance + shadow.blurRadius * 2 + 2,
          ),
        ) ??
        0;
    return GlassInk(source: color, role: role, outset: outset, child: original);
  }

  bool _plainSpan(InlineSpan? span, Color source) {
    if (span == null) return true;
    if (span is! TextSpan || span.style?.foreground != null) return false;
    final color = span.style?.color;
    if (color != null && color != source) return false;
    return span.children?.every((child) => _plainSpan(child, source)) ?? true;
  }
}

class GlassInk extends StatelessWidget {
  const GlassInk({
    super.key,
    required this.source,
    required this.role,
    this.outset = 0,
    required this.child,
  });

  static Future<void> prepare() => _InkProgram.prepare();

  final Color source;
  final GlassInkRole role;
  final double outset;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (!GlassForeground.colorsInk(context, source) ||
        !ui.ImageFilter.isShaderFilterSupported) {
      return child;
    }
    return _InkBox(
      role: role,
      source: source,
      outset: outset,
      dark: Theme.of(context).brightness == Brightness.dark,
      child: child,
    );
  }
}

class _InkBox extends SingleChildRenderObjectWidget {
  const _InkBox({
    required this.role,
    required this.source,
    required this.outset,
    required this.dark,
    required super.child,
  });
  final GlassInkRole role;
  final Color source;
  final double outset;
  final bool dark;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _InkRender(role, source, outset, dark);

  @override
  void updateRenderObject(BuildContext context, covariant _InkRender render) {
    render
      ..role = role
      ..source = source
      ..outset = outset
      ..dark = dark
      ..markNeedsPaint();
  }
}

class _InkRender extends RenderProxyBox {
  _InkRender(this.role, this.source, this.outset, this.dark);
  GlassInkRole role;
  Color source;
  double outset;
  bool dark;
  final _layer = LayerHandle<LiquidGlassInkLayer>();

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void paint(PaintingContext context, Offset offset) {
    final layer = _layer.layer ??= LiquidGlassInkLayer();
    final inset = Offset(outset, outset);
    final bounds = Size(size.width + outset * 2, size.height + outset * 2);
    layer
      ..offset = offset - inset
      ..size = bounds
      ..strength = role.strength
      ..source = source
      ..alpha = source.a
      ..contrast = role == GlassInkRole.icon ? 3.2 : 4.7
      ..dark = dark
      ..program = _InkProgram.request(markNeedsPaint)
      ..markNeedsAddToScene();
    context.pushLayer(
      layer,
      (context, offset) => super.paint(context, offset + inset),
      Offset.zero,
      childPaintBounds: Offset.zero & bounds,
    );
  }

  @override
  void dispose() {
    _InkProgram.forget(markNeedsPaint);
    _layer.layer = null;
    super.dispose();
  }
}

abstract final class _InkProgram {
  static ui.FragmentProgram? _program;
  static Future<void>? _loading;
  static final _waiting = <VoidCallback>{};
  static bool _failed = false;

  static Future<void> prepare() {
    request(() {});
    return _loading ?? Future.value();
  }

  static ui.FragmentProgram? request(VoidCallback ready) {
    if (_program != null || _failed) return _program;
    _waiting.add(ready);
    _loading ??= ui.FragmentProgram.fromAsset('shaders/foreground_ink.frag')
        .then(
          (program) {
            _program = program;
            final callbacks = _waiting.toList();
            _waiting.clear();
            for (final callback in callbacks) {
              callback();
            }
          },
          onError: (Object error, StackTrace stack) {
            _failed = true;
            _waiting.clear();
            FlutterError.reportError(
              FlutterErrorDetails(
                exception: error,
                stack: stack,
                library: 'foreground ink',
              ),
            );
          },
        );
    return null;
  }

  static void forget(VoidCallback ready) => _waiting.remove(ready);
}
