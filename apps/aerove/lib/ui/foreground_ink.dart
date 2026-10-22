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
    if (resolved.foreground != null ||
        resolved.background != null ||
        resolved.backgroundColor != null)
      return original;
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
    if (!GlassForeground.colorsInk(context, color)) return original;
    final hasShadows = resolved.shadows?.isNotEmpty == true;
    final fill = hasShadows
        ? _text(resolved.copyWith(shadows: const [])).build(context)
        : original;
    final ink = GlassInk(
      source: color,
      role: role,
      outset: outset,
      child: fill,
    );
    if (!hasShadows) return ink;
    return Stack(
      fit: StackFit.passthrough,
      clipBehavior: Clip.none,
      children: [
        ExcludeSemantics(
          child: _text(
            resolved.copyWith(color: Colors.transparent),
            shadowOnly: true,
          ).build(context),
        ),
        ink,
      ],
    );
  }

  Text _text(TextStyle resolved, {bool shadowOnly = false}) {
    InlineSpan span(InlineSpan value) {
      final text = value as TextSpan;
      return TextSpan(
        text: text.text,
        style: text.style?.copyWith(
          color: shadowOnly ? Colors.transparent : text.style?.color,
          shadows: shadowOnly ? text.style?.shadows : const [],
        ),
        children: text.children?.map(span).toList(),
        recognizer: text.recognizer,
        mouseCursor: text.mouseCursor,
        onEnter: text.onEnter,
        onExit: text.onExit,
        semanticsLabel: text.semanticsLabel,
        locale: text.locale,
        spellOut: text.spellOut,
      );
    }

    return Text.rich(
      span(textSpan ?? TextSpan(text: data)),
      style: resolved,
      strutStyle: strutStyle,
      textAlign: textAlign,
      textDirection: textDirection,
      locale: locale,
      softWrap: softWrap,
      overflow: overflow,
      textScaler: textScaler,
      maxLines: maxLines,
      semanticsLabel: semanticsLabel,
      textWidthBasis: textWidthBasis,
      textHeightBehavior: textHeightBehavior,
      selectionColor: selectionColor,
    );
  }

  bool _plainSpan(InlineSpan? span, Color source) {
    if (span == null) return true;
    if (span is! TextSpan ||
        span.style?.foreground != null ||
        span.style?.background != null ||
        span.style?.backgroundColor != null) {
      return false;
    }
    final color = span.style?.color;
    if (color != null && color != source) return false;
    return span.children?.every((child) => _plainSpan(child, source)) ?? true;
  }
}

// Ink samples the backdrop, before a flat control's fill is composited over it.
Widget glassFlatLabel(BuildContext context, Widget child) =>
    !LiquidGlassScope.of(context)
    ? child
    : GlassInkTextContrast(
        contrast: math.max(GlassInkTextContrast.of(context), 9),
        child: child,
      );

class GlassInkTextContrast extends InheritedWidget {
  const GlassInkTextContrast({
    super.key,
    this.contrast = 4.7,
    required super.child,
  }) : assert(contrast >= 1 && contrast <= 21);

  final double contrast;

  static double of(BuildContext context) => math.max(
    4.7,
    context
            .dependOnInheritedWidgetOfExactType<GlassInkTextContrast>()
            ?.contrast ??
        4.7,
  );

  @override
  bool updateShouldNotify(GlassInkTextContrast oldWidget) =>
      contrast != oldWidget.contrast;
}

/// A tint over the sampled backdrop, used for text and icon contrast.
class GlassInkBackground extends InheritedWidget {
  const GlassInkBackground({
    super.key,
    required this.color,
    required super.child,
  });

  final Color color;

  static Color? of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<GlassInkBackground>()?.color;

  @override
  bool updateShouldNotify(GlassInkBackground oldWidget) =>
      color != oldWidget.color;
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
    if (!GlassForeground.colorsInk(
          context,
          source,
          icon: role == GlassInkRole.icon,
        ) ||
        !ui.ImageFilter.isShaderFilterSupported) {
      return child;
    }
    return _InkBox(
      role: role,
      source: source,
      outset: outset,
      dark: Theme.of(context).brightness == Brightness.dark,
      quality: LiquidGlassScope.qualityOf(context),
      subtle: !LiquidGlassScope.of(context),
      background:
          GlassInkBackground.of(context) ??
          (role == GlassInkRole.icon
              ? GlassForeground.solidBackgroundOf(context, source)
              : null),
      contrast: role == GlassInkRole.icon
          ? 3.2
          : GlassInkTextContrast.of(context),
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
    required this.quality,
    required this.subtle,
    required this.background,
    required this.contrast,
    required super.child,
  });
  final GlassInkRole role;
  final Color source;
  final double outset;
  final bool dark;
  final LiquidGlassQuality quality;
  final bool subtle;
  final Color? background;
  final double contrast;

  @override
  RenderObject createRenderObject(BuildContext context) => _InkRender(
    role,
    source,
    outset,
    dark,
    quality,
    subtle,
    background,
    contrast,
  );

  @override
  void updateRenderObject(BuildContext context, covariant _InkRender render) {
    if (render.role == role &&
        render.source == source &&
        render.outset == outset &&
        render.dark == dark &&
        render.quality == quality &&
        render.subtle == subtle &&
        render.background == background &&
        render.contrast == contrast) {
      return;
    }
    render
      ..role = role
      ..source = source
      ..outset = outset
      ..dark = dark
      ..quality = quality
      ..subtle = subtle
      ..background = background
      ..contrast = contrast
      ..markNeedsPaint();
  }
}

class _InkRender extends RenderProxyBox {
  _InkRender(
    this.role,
    this.source,
    this.outset,
    this.dark,
    this.quality,
    this.subtle,
    this.background,
    this.contrast,
  );
  GlassInkRole role;
  Color source;
  double outset;
  bool dark;
  LiquidGlassQuality quality;
  bool subtle;
  Color? background;
  double contrast;
  final _layer = LayerHandle<LiquidGlassInkLayer>();

  @override
  bool get alwaysNeedsCompositing => true;

  Rect _contentBounds = Rect.zero;
  bool _boundsNeedUpdate = true;

  @override
  void performLayout() {
    super.performLayout();
    _boundsNeedUpdate = true;
  }

  void _updateContentBounds() {
    Rect? textBounds;
    var overhang = 2.0;
    void visit(RenderObject object) {
      if (object is RenderParagraph) {
        final transform = object.getTransformTo(this);
        final paragraphBounds = MatrixUtils.transformRect(
          transform,
          object.paintBounds,
        );
        textBounds =
            textBounds?.expandToInclude(paragraphBounds) ?? paragraphBounds;
        if (object.text.style?.fontStyle == FontStyle.italic) {
          overhang = math.max(
            overhang,
            (object.text.style?.fontSize ?? 17) * 0.25,
          );
        }
        for (final box in object.getBoxesForSelection(
          TextSelection(
            baseOffset: 0,
            extentOffset: object.text
                .toPlainText(includeSemanticsLabels: false)
                .length,
          ),
        )) {
          final bounds = MatrixUtils.transformRect(transform, box.toRect());
          textBounds = textBounds?.expandToInclude(bounds) ?? bounds;
        }
      }
      object.visitChildren(visit);
    }

    if (child case final child?) visit(child);
    _contentBounds = textBounds?.inflate(overhang) ?? (Offset.zero & size);
    _boundsNeedUpdate = false;
  }

  Rect get _inkBounds =>
      _contentBounds.expandToInclude(Offset.zero & size).inflate(outset);

  @override
  Rect get paintBounds => hasSize ? _inkBounds : Rect.zero;

  @override
  void paint(PaintingContext context, Offset offset) {
    if (_boundsNeedUpdate) _updateContentBounds();
    final layer = _layer.layer ??= LiquidGlassInkLayer();
    final bounds = _inkBounds;
    layer.configure(
      offset: offset,
      paintBounds: bounds,
      size: Size(
        math.max(size.width, bounds.right),
        math.max(size.height, bounds.bottom),
      ),
      strength: role.strength,
      source: source,
      contrast: contrast,
      dark: dark,
      quality: quality,
      subtle: subtle,
      background: background,
      program: _InkProgram.request(markNeedsPaint),
    );
    context.pushLayer(
      layer,
      super.paint,
      Offset.zero,
      childPaintBounds: bounds,
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
