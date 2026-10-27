import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/label_breaks.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/rendering.dart';
import 'package:material_new_shapes/material_new_shapes.dart';
import 'package:material_ui/material_ui.dart';

EdgeInsets dashboardCardPadding(DashboardDensity density) => switch (density) {
  DashboardDensity.standard => const EdgeInsets.symmetric(
    horizontal: 22,
    vertical: 20,
  ),
  DashboardDensity.compact => const EdgeInsets.symmetric(
    horizontal: 20,
    vertical: 16,
  ),
  DashboardDensity.mobile => const EdgeInsets.symmetric(
    horizontal: 20,
    vertical: 18,
  ),
};

double dashboardCardRadius(DashboardDensity density) => density.isDesktop
    ? DashboardCorner.desktopCard
    : DashboardCorner.mobileCard;

class DashboardGlass extends StatelessWidget {
  const DashboardGlass({
    super.key,
    required this.shape,
    this.style,
    required this.child,
  });

  final OutlinedBorder shape;
  final LiquidGlassStyle? style;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (!LiquidGlassScope.of(context)) {
      return child;
    }
    if (GlassSurfaceScope.of(context)) {
      return DecoratedBox(
        decoration: ShapeDecoration(
          color: glassRaisedFillOf(context),
          shape: shape,
        ),
        child: child,
      );
    }
    return LiquidGlass(
      style: style ?? LiquidGlassStyle.card(context),
      clipper: ShapeBorderClipper(
        shape: shape,
        textDirection: Directionality.of(context),
      ),
      child: GlassSurfaceScope(child: child),
    );
  }
}

class DashboardCard extends StatelessWidget {
  const DashboardCard({
    super.key,
    this.padding,
    this.onTap,
    this.semanticLabel,
    this.clipBehavior = Clip.none,
    this.pane = true,
    required this.child,
  });

  final EdgeInsetsGeometry? padding;
  final VoidCallback? onTap;
  final String? semanticLabel;

  /// Off by default so a control swelling under an [ElasticPress] can paint
  /// past the card's edge; only a card whose own content bleeds clips.
  final Clip clipBehavior;
  final bool pane;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final density = DashboardDensityScope.of(context);
    final shape = AppShape.all(dashboardCardRadius(density));
    final content = Padding(
      padding: padding ?? dashboardCardPadding(density),
      child: child,
    );
    final card = Container(
      clipBehavior: clipBehavior,
      decoration: ShapeDecoration(
        color: !pane && LiquidGlassScope.of(context)
            ? glassRaisedFillOf(context)
            : DashboardColors.of(context).card,
        shape: shape,
        shadows: LiquidGlassScope.of(context)
            ? null
            : DashboardShadows.e1(context),
      ),
      child: Material(
        type: MaterialType.transparency,
        child: onTap == null
            ? content
            : FocusRing(
                shape: shape,
                child: InkWell(
                  customBorder: shape,
                  onTap: onTap,
                  child: content,
                ),
              ),
      ),
    );
    return Semantics(
      container: true,
      label: semanticLabel,
      child: pane ? DashboardGlass(shape: shape, child: card) : card,
    );
  }
}

/// A screen-reader stop read in [order]; unkeyed siblings fall back to geometry.
class DashboardStop extends StatelessWidget {
  const DashboardStop({super.key, required this.order, required this.child});

  final int order;
  final Widget child;

  @override
  Widget build(BuildContext context) => Semantics(
    container: true,
    sortKey: OrdinalSortKey(order.toDouble()),
    child: child,
  );
}

class DashboardCardHeader extends StatelessWidget {
  const DashboardCardHeader({
    super.key,
    this.glyph,
    required this.title,
    this.leading = const [],
    this.trailing = const [],
    this.chevron = false,
    this.wrap = false,
    this.titleWrapper,
  });

  static const height = 24.0;
  static const glyphSize = 18.0;

  final Glyph? glyph;
  final String title;

  /// Sits right after the title, before the space that pushes [trailing] out.
  final List<Widget> leading;
  final List<Widget> trailing;
  final bool chevron;

  /// Keeps the title and [leading] whole: what does not fit beside the title
  /// moves to a line under it and the header grows.
  final bool wrap;
  final Widget Function(Widget title)? titleWrapper;

  @override
  Widget build(BuildContext context) {
    final mobile = !DashboardDensityScope.of(context).isDesktop;
    final scale = DashboardDensityScope.scaleOf(context);
    final colorScheme = context.colorScheme;
    final iconColor = colorScheme.onSurfaceVariant;
    final style = mobile
        ? DashboardText.mobileCardTitle(context)
        : DashboardText.cardTitle(context);
    final end = [
      ...trailing,
      if (chevron)
        GlyphIcon(
          AppGlyphs.chevronForward,
          size: 18,
          color: colorScheme.onSurfaceVariant,
        ),
    ];
    final text = wrap
        ? GlassText(title, style: style)
        : GlassText(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: style,
          );
    final heading = titleWrapper?.call(text) ?? text;
    final line = (mobile ? 26 : height) * scale;
    final start = leading.isEmpty
        ? Row(children: [Flexible(child: heading)])
        : _HeaderLine(
            wrap: wrap,
            line: line,
            children: [
              heading,
              Row(
                mainAxisSize: MainAxisSize.min,
                spacing: 8,
                children: [for (final item in leading) Flexible(child: item)],
              ),
            ],
          );
    if (wrap && end.isEmpty) {
      return ConstrainedBox(
        constraints: BoxConstraints(minHeight: line),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 8,
          children: [
            if (glyph case final glyph?)
              SizedBox(
                height: line,
                child: Center(
                  child: GlyphIcon(glyph, size: glyphSize, color: iconColor),
                ),
              ),
            Expanded(child: start),
          ],
        ),
      );
    }
    return SizedBox(
      height: line,
      child: Row(
        spacing: 8,
        children: [
          if (glyph case final glyph?)
            GlyphIcon(glyph, size: glyphSize, color: iconColor),
          Expanded(
            child: end.isEmpty
                ? start
                : DashboardCappedRow(
                    start: start,
                    end: Row(
                      mainAxisSize: MainAxisSize.min,
                      spacing: 8,
                      children: end,
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

/// Wrapping headers keep title and chip whole; other headers give a long
/// chip less room before shortening the title.
class _HeaderLine extends MultiChildRenderObjectWidget {
  const _HeaderLine({
    this.wrap = false,
    this.line = 0,
    required super.children,
  });

  static const _spacing = 8.0;
  static const _runSpacing = 4.0;
  static const _titleShare = 0.4;

  final bool wrap;
  final double line;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderHeaderLine(Directionality.of(context), wrap: wrap, line: line);

  @override
  void updateRenderObject(BuildContext context, RenderObject renderObject) {
    (renderObject as _RenderHeaderLine)
      ..textDirection = Directionality.of(context)
      ..wrap = wrap
      ..line = line;
  }
}

class _HeaderLineParentData extends ContainerBoxParentData<RenderBox> {}

class _RenderHeaderLine extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _HeaderLineParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _HeaderLineParentData> {
  _RenderHeaderLine(
    this._textDirection, {
    required bool wrap,
    required double line,
  }) : _wrap = wrap,
       _line = line;

  TextDirection _textDirection;
  set textDirection(TextDirection value) {
    if (value == _textDirection) return;
    _textDirection = value;
    markNeedsLayout();
  }

  bool _wrap;
  set wrap(bool value) {
    if (value == _wrap) return;
    _wrap = value;
    markNeedsLayout();
  }

  double _line;
  set line(double value) {
    if (value == _line) return;
    _line = value;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _HeaderLineParentData) {
      child.parentData = _HeaderLineParentData();
    }
  }

  @override
  double computeMinIntrinsicWidth(double height) => 0;

  @override
  double computeMaxIntrinsicWidth(double height) {
    var width = 0.0;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      width += child.getMaxIntrinsicWidth(height);
    }
    return width + _HeaderLine._spacing * math.max(0, childCount - 1);
  }

  @override
  double computeMinIntrinsicHeight(double width) =>
      _tallest((child) => child.getMinIntrinsicHeight(width));

  @override
  double computeMaxIntrinsicHeight(double width) =>
      _tallest((child) => child.getMaxIntrinsicHeight(width));

  double _tallest(double Function(RenderBox child) of) {
    var height = 0.0;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      height = math.max(height, of(child));
    }
    return height;
  }

  void _stack(RenderBox title, RenderBox rest, double width) {
    final loose = BoxConstraints(maxWidth: width);
    title.layout(loose, parentUsesSize: true);
    rest.layout(loose, parentUsesSize: true);
    final first = math.max(_line, title.size.height);
    final second = math.max(_line, rest.size.height);
    final rtl = _textDirection == TextDirection.rtl;
    (title.parentData! as _HeaderLineParentData).offset = Offset(
      rtl ? width - title.size.width : 0,
      (first - title.size.height) / 2,
    );
    (rest.parentData! as _HeaderLineParentData).offset = Offset(
      rtl ? width - rest.size.width : 0,
      first + _HeaderLine._runSpacing + (second - rest.size.height) / 2,
    );
    size = constraints.constrain(
      Size(width, first + _HeaderLine._runSpacing + second),
    );
  }

  @override
  void performLayout() {
    final title = firstChild!;
    final rest = childAfter(title)!;
    final width = constraints.maxWidth;
    const spacing = _HeaderLine._spacing;
    final titleNatural = title.getMaxIntrinsicWidth(double.infinity);
    final restNatural = rest.getMaxIntrinsicWidth(double.infinity);
    if (_wrap && titleNatural + spacing + restNatural > width) {
      _stack(title, rest, width);
      return;
    }
    final kept = titleNatural + spacing + restNatural <= width
        ? titleNatural
        : math.min(
            titleNatural,
            math.max(
              width * _HeaderLine._titleShare,
              width - spacing - restNatural,
            ),
          );
    final loose = constraints.loosen();
    rest.layout(
      loose.copyWith(maxWidth: math.max(0, width - spacing - kept)),
      parentUsesSize: true,
    );
    title.layout(
      loose.copyWith(maxWidth: math.max(0, width - spacing - rest.size.width)),
      parentUsesSize: true,
    );
    final height = constraints.constrainHeight(
      math.max(
        _wrap ? _line : 0,
        math.max(title.size.height, rest.size.height),
      ),
    );
    final rtl = _textDirection == TextDirection.rtl;
    final restStart = title.size.width + spacing;
    (title.parentData! as _HeaderLineParentData).offset = Offset(
      rtl ? width - title.size.width : 0,
      (height - title.size.height) / 2,
    );
    (rest.parentData! as _HeaderLineParentData).offset = Offset(
      rtl ? width - restStart - rest.size.width : restStart,
      (height - rest.size.height) / 2,
    );
    size = constraints.constrain(Size(width, height));
  }

  @override
  void paint(PaintingContext context, Offset offset) =>
      defaultPaint(context, offset);

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}

/// Shrinks to fit, down to a legible [_minSize]; an address still too long
/// breaks after its dots and colons rather than being cut short.
class DashboardFitText extends StatelessWidget {
  const DashboardFitText(this.text, {super.key, required this.style});

  static const _minSize = 11.0;

  final String text;
  final TextStyle style;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final scaler = MediaQuery.textScalerOf(context);
        final painter = TextPainter(
          text: TextSpan(text: text, style: style),
          textDirection: Directionality.of(context),
          textScaler: scaler,
          maxLines: 1,
        )..layout();
        final width = painter.width;
        painter.dispose();
        final size = style.fontSize ?? 14;
        final floor = math.min(1.0, _minSize / scaler.scale(size));
        if (width * floor <= constraints.maxWidth) {
          return FittedBox(
            fit: BoxFit.scaleDown,
            alignment: AlignmentDirectional.centerStart,
            child: GlassText(
              text,
              inkRole: GlassInkRole.body,
              maxLines: 1,
              style: style,
            ),
          );
        }
        return GlassText(
          text.replaceAllMapped(RegExp('[.:]'), (match) => '${match[0]}\u200B'),
          semanticsLabel: text,
          inkRole: GlassInkRole.body,
          style: style.withFontSize(size * floor),
        );
      },
    );
  }
}

/// Lays [end] out first at its own width, up to [share] of the row and scaled
/// down past that, so long translations or large text never overflow; [start]
/// takes what is left.
class DashboardCappedRow extends StatelessWidget {
  const DashboardCappedRow({
    super.key,
    required this.start,
    required this.end,
    this.share = 0.5,
    this.spacing = 8,
  });

  final Widget start;
  final Widget end;
  final double share;
  final double spacing;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => Row(
        spacing: spacing,
        children: [
          Expanded(child: start),
          ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: math.max(0, constraints.maxWidth - spacing) * share,
            ),
            child: FittedBox(
              fit: BoxFit.scaleDown,
              alignment: AlignmentDirectional.centerEnd,
              child: SizedBox(
                height: constraints.hasBoundedHeight
                    ? constraints.maxHeight
                    : null,
                child: end,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

const _connectedSelectDuration = Duration(milliseconds: 240);

/// A row of [DashboardConnectedButton]s; the one pressed last paints on top.
class DashboardConnectedGroup extends StatelessWidget {
  const DashboardConnectedGroup({
    super.key,
    this.crossAxisAlignment = CrossAxisAlignment.center,
    required this.children,
  });

  final CrossAxisAlignment crossAxisAlignment;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return _RaisedRow(
      crossAxisAlignment: crossAxisAlignment,
      spacing: 3,
      children: children,
    );
  }
}

class _RaisedRow extends Flex {
  const _RaisedRow({
    super.crossAxisAlignment,
    super.spacing,
    required super.children,
  }) : super(direction: Axis.horizontal);

  @override
  RenderFlex createRenderObject(BuildContext context) {
    return _RenderRaisedRow(
      direction: direction,
      mainAxisAlignment: mainAxisAlignment,
      mainAxisSize: mainAxisSize,
      crossAxisAlignment: crossAxisAlignment,
      textDirection: getEffectiveTextDirection(context),
      verticalDirection: verticalDirection,
      textBaseline: textBaseline,
      clipBehavior: clipBehavior,
      spacing: spacing,
    );
  }
}

class _RenderRaisedRow extends RenderFlex {
  _RenderRaisedRow({
    super.direction,
    super.mainAxisAlignment,
    super.mainAxisSize,
    super.crossAxisAlignment,
    super.textDirection,
    super.verticalDirection,
    super.textBaseline,
    super.clipBehavior,
    super.spacing,
  });

  RenderBox? _raised;

  @override
  void handleEvent(PointerEvent event, BoxHitTestEntry entry) {
    if (event is! PointerDownEvent) {
      return;
    }
    for (var child = firstChild; child != null; child = childAfter(child)) {
      final offset = (child.parentData! as FlexParentData).offset;
      if ((offset & child.size).contains(entry.localPosition)) {
        if (child != _raised) {
          _raised = child;
          markNeedsPaint();
        }
        return;
      }
    }
  }

  @override
  void dropChild(RenderObject child) {
    if (child == _raised) {
      _raised = null;
    }
    super.dropChild(child);
  }

  @override
  void defaultPaint(PaintingContext context, Offset offset) {
    void paint(RenderBox child) => context.paintChild(
      child,
      (child.parentData! as FlexParentData).offset + offset,
    );
    for (var child = firstChild; child != null; child = childAfter(child)) {
      if (child != _raised) {
        paint(child);
      }
    }
    if (_raised case final raised?) {
      paint(raised);
    }
  }
}

/// M3 Expressive connected buttons: the ends round off the group, the inner
/// corners stay tight, and the selected one turns into a full pill. Each one
/// swells under a press.
class DashboardConnectedButton extends StatelessWidget {
  const DashboardConnectedButton({
    super.key,
    this.pane = false,
    this.pill = false,
    required this.selected,
    required this.first,
    required this.last,
    required this.outer,
    required this.inner,
    required this.background,
    required this.onTap,
    required this.semanticLabel,
    this.toggled,
    this.tapHint,
    this.liveRegion = false,
    required this.builder,
  });

  final bool selected;
  final bool pane;

  /// Reads the button as a switch rather than one choice of a group.
  final bool? toggled;

  /// What a tap does, read after the label; the button then also answers an
  /// assistive tap itself.
  final String? tapHint;

  final bool liveRegion;

  /// Keeps every button a full pill whatever its selection, as the outbound
  /// modes require on every material.
  final bool pill;
  final bool first;
  final bool last;
  final double outer;
  final double inner;
  final Color background;
  final VoidCallback onTap;
  final String semanticLabel;
  final Widget Function(Color foreground) builder;

  @override
  Widget build(BuildContext context) {
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final startRest = first ? outer : inner;
    final endRest = last ? outer : inner;
    final colorScheme = context.colorScheme;
    final glass = LiquidGlassScope.of(context);
    final onGlass = GlassSurfaceScope.of(context);
    final ownPane = glass && pane && !MediaQuery.highContrastOf(context);
    final grouped = toggled == null && tapHint == null;
    return Semantics(
      container: !grouped,
      button: true,
      selected: grouped ? selected : null,
      inMutuallyExclusiveGroup: grouped ? true : null,
      toggled: toggled,
      liveRegion: liveRegion ? true : null,
      label: semanticLabel,
      onTapHint: tapHint,
      onTap: grouped ? null : onTap,
      excludeSemantics: true,
      child: ElasticPress(
        child: TweenAnimationBuilder<double>(
          tween: Tween(end: selected ? 1 : 0),
          duration: context.motionDuration(_connectedSelectDuration),
          curve: Curves.easeOutCubic,
          builder: (context, amount, child) {
            final start = lerpDouble(startRest, outer, amount)!;
            final end = lerpDouble(endRest, outer, amount)!;
            final shape = glass || pill
                ? AppShape.full
                : AppShape.of(
                    BorderRadius.horizontal(
                      left: Radius.circular(rtl ? end : start),
                      right: Radius.circular(rtl ? start : end),
                    ),
                  );
            final fill = Container(
              clipBehavior: Clip.antiAlias,
              decoration: ShapeDecoration(
                color: !glass
                    ? Color.lerp(background, colorScheme.primary, amount)
                    : ownPane
                    ? Colors.transparent
                    : onGlass
                    ? Color.lerp(
                        Colors.transparent,
                        glassChosenFillOf(colorScheme),
                        amount,
                      )
                    : background,
                shape: shape,
              ),
              child: Material(
                type: MaterialType.transparency,
                child: InkWell(
                  customBorder: shape,
                  splashFactory: NoSplash.splashFactory,
                  highlightColor: Colors.transparent,
                  onTap: onTap,
                  child: child,
                ),
              ),
            );
            Widget button = fill;
            if (ownPane) {
              final style = LiquidGlassStyle.regular(colorScheme)
                  .copyWith(
                    tint: Color.lerp(
                      Colors.transparent,
                      glassChosenFillOf(colorScheme),
                      amount,
                    ),
                    blur: 0,
                  )
                  .forQuality(LiquidGlassScope.qualityOf(context));
              button = LiquidGlass(
                style: style,
                clipper: ShapeBorderClipper(shape: shape),
                child: fill,
              );
            } else if (!onGlass) {
              button = DashboardGlass(
                shape: shape,
                style: selected
                    ? LiquidGlassStyle.tinted(colorScheme, colorScheme.primary)
                    : null,
                child: fill,
              );
            }
            return FocusRing(shape: shape, standsOff: selected, child: button);
          },
          child: glassFlatLabel(
            context,
            builder(
              selected
                  ? LiquidGlassScope.of(context)
                        ? colorScheme.onSurface
                        : colorScheme.onPrimary
                  : colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ),
    );
  }
}

enum DashboardChipTone { idle, ok, warn }

class DashboardChip extends StatelessWidget {
  const DashboardChip({
    super.key,
    required this.label,
    this.tone = DashboardChipTone.idle,
    this.dot = false,
    this.large = false,
    this.wrap = false,
    this.dense = false,
  });

  /// The least size a wrapping label shrinks its longest word to.
  static const minLabelSize = 11.0;

  /// A pill on one line; a chip that wraps keeps its lines inside the
  /// corners instead of turning round.
  static const shape = AppShape.md;

  final String label;
  final DashboardChipTone tone;
  final bool dot;
  final bool large;

  /// Wraps a label too long for its line and grows, where the chip's row can
  /// grow; otherwise the label shortens.
  final bool wrap;

  /// Tighter insets, for a chip that shares a line with other content.
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final colorScheme = context.colorScheme;
    final colors = DashboardColors.of(context);
    final (background, foreground) = switch (tone) {
      DashboardChipTone.idle => (colors.tone, colorScheme.onSurfaceVariant),
      DashboardChipTone.ok => (colors.okContainer, colors.onOkContainer),
      DashboardChipTone.warn => (colors.warnContainer, colors.onWarnContainer),
    };
    final height = large ? 28.0 : 24.0;
    final style = AppTextStyle.caption1
        .of(context, emphasized: true)
        .toTabular
        .copyWith(color: foreground);
    return Container(
      height: wrap ? null : height,
      constraints: wrap ? BoxConstraints(minHeight: height) : null,
      padding: EdgeInsets.symmetric(
        horizontal: large
            ? 12
            : dense
            ? 8
            : 10,
        vertical: wrap ? 4 : 0,
      ),
      decoration: ShapeDecoration(
        color: LiquidGlassScope.surfaceColorOf(context, background),
        shape: shape,
      ),
      // Its own width wherever there is room; a chip wider than the space it
      // is given shortens its label instead of overflowing the pill.
      child: IntrinsicWidth(
        child: Row(
          mainAxisSize: MainAxisSize.min,
          spacing: dense ? 4 : 6,
          children: [
            if (dot)
              DecoratedBox(
                decoration: ShapeDecoration(
                  color: foreground,
                  shape: AppShape.circle,
                ),
                child: const SizedBox.square(dimension: 6),
              ),
            Flexible(
              child: _WordFit(
                word: wrap ? _longestWord(context, style) : 0,
                minScale: math.min(
                  1,
                  minLabelSize /
                      MediaQuery.textScalerOf(
                        context,
                      ).scale(style.fontSize ?? minLabelSize),
                ),
                child: GlassText(
                  label,
                  maxLines: wrap ? null : 1,
                  overflow: wrap ? null : TextOverflow.ellipsis,
                  style: style,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  double _longestWord(BuildContext context, TextStyle style) {
    final painter = TextPainter(
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      locale: Localizations.maybeLocaleOf(context),
    );
    final resolved = DefaultTextStyle.of(context).style.merge(style);
    final width = longestPiece(
      label,
      (piece) =>
          (painter
                ..text = TextSpan(text: piece, style: resolved)
                ..layout())
              .width,
    );
    painter.dispose();
    return width;
  }
}

/// Shrinks its text just enough that its longest [word] fits the width it is
/// given, down to [minScale], so the text wraps between words rather than
/// inside one.
class _WordFit extends SingleChildRenderObjectWidget {
  const _WordFit({
    required this.word,
    required this.minScale,
    required super.child,
  });

  final double word;
  final double minScale;

  @override
  _RenderWordFit createRenderObject(BuildContext context) =>
      _RenderWordFit(word, minScale);

  @override
  void updateRenderObject(BuildContext context, _RenderWordFit renderObject) {
    renderObject
      ..word = word
      ..minScale = minScale;
  }
}

class _RenderWordFit extends RenderProxyBox {
  _RenderWordFit(this._word, this._minScale);

  final _shrink = LayerHandle<TransformLayer>();
  var _scale = 1.0;

  double _word;
  set word(double value) {
    if (value == _word) return;
    _word = value;
    markNeedsLayout();
  }

  double _minScale;
  set minScale(double value) {
    if (value == _minScale) return;
    _minScale = value;
    markNeedsLayout();
  }

  @override
  void performLayout() {
    final child = this.child;
    if (child == null) {
      size = constraints.smallest;
      return;
    }
    final room = constraints.maxWidth;
    final word = room.isFinite ? _word : 0.0;
    _scale = word > room ? math.max(_minScale, room / word) : 1;
    child.layout(
      constraints.loosen().copyWith(maxWidth: room / _scale),
      parentUsesSize: true,
    );
    size = constraints.constrain(child.size * _scale);
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    if (child == null) return;
    if (_scale == 1) {
      _shrink.layer = null;
      context.paintChild(child, offset);
      return;
    }
    _shrink.layer = context.pushTransform(
      needsCompositing,
      offset,
      Matrix4.diagonal3Values(_scale, _scale, 1),
      (context, offset) => context.paintChild(child, offset),
      oldLayer: _shrink.layer,
    );
  }

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    transform.scaleByDouble(_scale, _scale, 1, 1);
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    final child = this.child;
    if (child == null) return false;
    return result.addWithPaintTransform(
      transform: Matrix4.diagonal3Values(_scale, _scale, 1),
      position: position,
      hitTest: (result, position) => child.hitTest(result, position: position),
    );
  }

  @override
  void dispose() {
    _shrink.layer = null;
    super.dispose();
  }
}

/// A block inside a card, one tone off the card itself.
class DashboardBlock extends StatelessWidget {
  const DashboardBlock({
    super.key,
    required this.radius,
    this.color,
    this.padding = EdgeInsets.zero,
    this.onTap,
    this.semanticLabel,
    required this.child,
  });

  final double radius;
  final Color? color;
  final EdgeInsetsGeometry padding;
  final VoidCallback? onTap;
  final String? semanticLabel;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final shape = AppShape.all(radius);
    final content = Padding(padding: padding, child: child);
    return Container(
      decoration: ShapeDecoration(
        color: LiquidGlassScope.surfaceColorOf(
          context,
          color ?? DashboardColors.of(context).tone,
        ),
        shape: shape,
      ),
      child: onTap == null
          ? content
          : Material(
              type: MaterialType.transparency,
              child: Semantics(
                button: true,
                label: semanticLabel,
                child: FocusRing(
                  shape: shape,
                  child: InkWell(
                    customBorder: shape,
                    onTap: onTap,
                    child: content,
                  ),
                ),
              ),
            ),
    );
  }
}

/// An M3 Expressive shape, filled, with [child] centered on it.
class ShapeBadge extends StatelessWidget {
  const ShapeBadge({
    super.key,
    required this.shape,
    required this.color,
    required this.size,
    this.rotation = 0,
    this.ring,
    this.child,
  });

  final RoundedPolygon shape;
  final Color color;
  final double size;

  /// Turns the shape alone, leaving [child] upright.
  final double rotation;
  final BorderSide? ring;
  final Widget? child;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      painter: ShapePainter(
        path: shapePathOf(shape),
        color: LiquidGlassScope.surfaceColorOf(context, color),
        rotation: rotation,
        ring: ring,
      ),
      child: SizedBox.square(
        dimension: size,
        child: child == null ? null : Center(child: child),
      ),
    );
  }
}

final _shapePaths = Expando<Path>();

Path shapePathOf(RoundedPolygon shape) => _shapePaths[shape] ??= shape.toPath();

/// Paints a path laid out in the unit square, the space [MaterialShapes]
/// are normalized to.
class ShapePainter extends CustomPainter {
  const ShapePainter({
    required this.path,
    required this.color,
    this.rotation = 0,
    this.ring,
  });

  final Path path;
  final Color color;
  final double rotation;
  final BorderSide? ring;

  @override
  void paint(Canvas canvas, Size size) {
    canvas
      ..save()
      ..translate(size.width / 2, size.height / 2)
      ..rotate(rotation)
      ..scale(size.width, size.height)
      ..translate(-0.5, -0.5)
      ..drawPath(path, Paint()..color = color);
    if (ring case final ring?) {
      strokeUnitShape(canvas, path, size.width, ring);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(ShapePainter oldDelegate) =>
      oldDelegate.path != path ||
      oldDelegate.color != color ||
      oldDelegate.rotation != rotation ||
      oldDelegate.ring != ring;
}

/// Strokes [side] along a unit-square [path] drawn at [extent], placing it by
/// [BorderSide.strokeAlign] with a scale about the center rather than an offset.
void strokeUnitShape(Canvas canvas, Path path, double extent, BorderSide side) {
  final scale = 1 + side.width * side.strokeAlign / extent;
  canvas
    ..save()
    ..translate(0.5, 0.5)
    ..scale(scale)
    ..translate(-0.5, -0.5)
    ..drawPath(path, side.toPaint()..strokeWidth = side.width / extent / scale)
    ..restore();
}

class DashboardSwitch extends StatelessWidget {
  const DashboardSwitch({
    super.key,
    required this.value,
    required this.onChanged,
    this.semanticLabel,
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final String? semanticLabel;

  /// A switch lays out past its track, which would set every row's height.
  static const trackWidth = CommonSwitch.trackWidth;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: trackWidth,
      height: CommonSwitch.trackHeight,
      child: OverflowBox(
        maxWidth: double.infinity,
        maxHeight: double.infinity,
        child: CommonSwitch(
          value: value,
          onChanged: onChanged,
          semanticLabel: semanticLabel,
        ),
      ),
    );
  }
}

/// A row holding one setting behind a switch: a cookie-6 icon container while
/// it is on, a plain circle while it is off.
class DashboardSwitchRow extends StatelessWidget {
  const DashboardSwitchRow({
    super.key,
    required this.glyph,
    required this.title,
    this.subtitle,
    required this.value,
    required this.onChanged,
    required this.iconSize,
    required this.padding,
    required this.radius,
    this.color,
    this.onTap,
    this.showIcon = true,
  });

  final Glyph glyph;
  final String title;
  final String? subtitle;
  final bool value;
  final ValueChanged<bool>? onChanged;
  final double iconSize;
  final EdgeInsetsGeometry padding;
  final double radius;
  final Color? color;
  final VoidCallback? onTap;
  final bool showIcon;

  static const _gap = 12.0;

  /// Whether each of [titles] fits beside the icon in rows [width] wide, so
  /// sibling rows can drop their icons together before a title is cut short.
  static bool iconFits(
    BuildContext context, {
    required double width,
    required double iconSize,
    required EdgeInsetsGeometry padding,
    required Iterable<String> titles,
  }) {
    final style = DashboardText.rowTitle(context);
    final room =
        width -
        padding.horizontal -
        iconSize -
        _gap * 2 -
        DashboardSwitch.trackWidth;
    return titles.every(
      (title) =>
          globalState.measure
              .computeTextSize(Text(title, style: style))
              .width <=
          room,
    );
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = context.colorScheme;
    final colors = DashboardColors.of(context);
    final glyphSize = iconSize * 0.5;
    return DashboardBlock(
      radius: radius,
      color: color,
      padding: padding,
      onTap: onTap,
      semanticLabel: title,
      child: Row(
        spacing: _gap,
        children: [
          if (showIcon)
            AnimatedSwitcher(
              duration: context.motionDuration(commonDuration),
              child: value
                  ? ShapeBadge(
                      key: const ValueKey(true),
                      shape: MaterialShapes.cookie6Sided,
                      color: colorScheme.primary,
                      size: iconSize,
                      child: GlyphIcon(
                        glyph,
                        size: glyphSize,
                        color: colorScheme.onPrimary,
                      ),
                    )
                  : ShapeBadge(
                      key: const ValueKey(false),
                      shape: MaterialShapes.circle,
                      color: colors.card,
                      size: iconSize,
                      child: GlyphIcon(
                        glyph,
                        size: glyphSize,
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
            ),
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                GlassText(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: DashboardText.rowTitle(context),
                ),
                if (subtitle case final subtitle?)
                  GlassText(
                    subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: DashboardText.label(context),
                  ),
              ],
            ),
          ),
          DashboardSwitch(
            value: value,
            onChanged: onChanged,
            semanticLabel: title,
          ),
        ],
      ),
    );
  }
}

/// The last [height] dp of a list that runs past its card fade out instead of
/// showing a scroll bar.
class DashboardFadeOut extends StatelessWidget {
  const DashboardFadeOut({super.key, required this.child});

  static const height = 44.0;

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ShaderMask(
      blendMode: BlendMode.dstIn,
      shaderCallback: (bounds) {
        final stop = bounds.height <= 0
            ? 1.0
            : ((bounds.height - height) / bounds.height).clamp(0.0, 1.0);
        return LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: const [Colors.black, Colors.black, Colors.transparent],
          stops: [0, stop, 1],
        ).createShader(bounds);
      },
      child: ScrollConfiguration(
        behavior: ScrollConfiguration.of(context).copyWith(scrollbars: false),
        child: child,
      ),
    );
  }
}

class DashboardIconButton extends StatelessWidget {
  const DashboardIconButton({
    super.key,
    required this.glyph,
    required this.tooltip,
    required this.onPressed,
  });

  static const size = 32.0;
  static const glyphSize = 18.0;

  final Glyph glyph;
  final String tooltip;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    // A 24 tall card header would otherwise squeeze the circle into a pill.
    return SizedBox(
      width: size,
      child: OverflowBox(
        fit: OverflowBoxFit.deferToChild,
        minHeight: size,
        maxHeight: size,
        child: IconButton(
          tooltip: tooltip,
          padding: EdgeInsets.zero,
          iconSize: glyphSize,
          style: IconButton.styleFrom(
            minimumSize: const Size.square(size),
            fixedSize: const Size.square(size),
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            foregroundColor: context.colorScheme.onSurfaceVariant,
          ),
          onPressed: onPressed,
          icon: GlyphIcon(glyph, size: glyphSize),
        ),
      ),
    );
  }
}
