import 'dart:math' as math;
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

import 'outbound_motion.dart';
import 'traffic.dart';

/// Aligns the readings with the footer and the corner's inner horizontal
/// centre; the continuous curves start at the card's bottom behind the body.
class StatusCardLayout extends MultiChildRenderObjectWidget {
  StatusCardLayout({
    super.key,
    required this.padding,
    required this.shape,
    required this.baselineRatio,
    required Widget backdrop,
    required Widget body,
    required Widget figures,
    this.gap = 8,
    this.bleed = 8,
    this.overlaidOrder = 0,
    this.appendedOrder = 0,
    this.allowAppend = false,
  }) : super(
         children: [
           RepaintBoundary(child: backdrop),
           RepaintBoundary(child: body),
           _TrafficStop(child: RepaintBoundary(child: figures)),
         ],
       );

  final EdgeInsetsGeometry padding;
  final ShapeBorder shape;

  /// Where the [backdrop] draws its zero line, as a share of its height.
  final double baselineRatio;
  final double gap;
  final double bleed;

  /// Where a screen reader takes the figures: [overlaidOrder] in the corner,
  /// [appendedOrder] under the body.
  final int overlaidOrder;
  final int appendedOrder;
  final bool allowAppend;

  @override
  RenderStatusCardLayout createRenderObject(BuildContext context) {
    final textDirection = Directionality.of(context);
    return RenderStatusCardLayout(
      padding: padding.resolve(textDirection),
      shape: shape,
      baselineRatio: baselineRatio,
      gap: gap,
      bleed: bleed,
      overlaidOrder: overlaidOrder,
      appendedOrder: appendedOrder,
      allowAppend: allowAppend,
      textDirection: textDirection,
    );
  }

  @override
  void updateRenderObject(
    BuildContext context,
    RenderStatusCardLayout renderObject,
  ) {
    final textDirection = Directionality.of(context);
    renderObject
      ..padding = padding.resolve(textDirection)
      ..shape = shape
      ..baselineRatio = baselineRatio
      ..gap = gap
      ..bleed = bleed
      ..overlaidOrder = overlaidOrder
      ..appendedOrder = appendedOrder
      ..allowAppend = allowAppend
      ..textDirection = textDirection;
  }
}

class _StatusLayoutParentData extends ContainerBoxParentData<RenderBox> {}

class RenderStatusCardLayout extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _StatusLayoutParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _StatusLayoutParentData> {
  RenderStatusCardLayout({
    required EdgeInsets padding,
    required ShapeBorder shape,
    required double baselineRatio,
    required double gap,
    required double bleed,
    required int overlaidOrder,
    required int appendedOrder,
    required bool allowAppend,
    required TextDirection textDirection,
  }) : _padding = padding,
       _shape = shape,
       _baselineRatio = baselineRatio,
       _gap = gap,
       _bleed = bleed,
       _overlaidOrder = overlaidOrder,
       _appendedOrder = appendedOrder,
       _allowAppend = allowAppend,
       _textDirection = textDirection;

  final _foreground = <_RenderStatusForeground>{};
  final _edges = <_RenderStatusCurveEdge>{};
  EdgeInsets _padding;
  set padding(EdgeInsets value) {
    if (value == _padding) return;
    _padding = value;
    markNeedsLayout();
  }

  ShapeBorder _shape;
  set shape(ShapeBorder value) {
    if (value == _shape) return;
    _shape = value;
    markNeedsPaint();
  }

  double _baselineRatio;
  set baselineRatio(double value) {
    if (value == _baselineRatio) return;
    _baselineRatio = value;
    markNeedsLayout();
  }

  double _gap;
  set gap(double value) {
    if (value == _gap) return;
    _gap = value;
    markNeedsLayout();
  }

  double _bleed;
  set bleed(double value) {
    if (value == _bleed) return;
    _bleed = value;
    markNeedsLayout();
  }

  int _overlaidOrder;
  set overlaidOrder(int value) {
    if (value == _overlaidOrder) return;
    _overlaidOrder = value;
    markNeedsLayout();
  }

  int _appendedOrder;
  set appendedOrder(int value) {
    if (value == _appendedOrder) return;
    _appendedOrder = value;
    markNeedsLayout();
  }

  TextDirection _textDirection;
  set textDirection(TextDirection value) {
    if (value == _textDirection) return;
    _textDirection = value;
    markNeedsLayout();
  }

  bool? get overlaid => _overlaid;
  bool? _overlaid;
  bool? get footerSlotAvailable => _footerSlotAvailable;
  bool? _footerSlotAvailable;

  bool _allowAppend;
  set allowAppend(bool value) {
    if (value == _allowAppend) return;
    _allowAppend = value;
    markNeedsLayout();
  }

  /// The backdrop's zero line, from the card's top edge.
  double? get baseline => _baseline;
  double? _baseline;

  /// Where the curves may peak, from the card's top edge.
  double? get bandTop => _bandTop;
  double? _bandTop;

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _StatusLayoutParentData) {
      child.parentData = _StatusLayoutParentData();
    }
  }

  RenderBox? get _backdrop => firstChild;

  RenderBox? get _body => _backdrop == null ? null : childAfter(_backdrop!);

  RenderBox? get _figures => _body == null ? null : childAfter(_body!);

  @override
  double computeMinIntrinsicWidth(double height) =>
      math.max(
        _body?.getMinIntrinsicWidth(height) ?? 0,
        _figures?.getMinIntrinsicWidth(double.infinity) ?? 0,
      ) +
      _padding.horizontal;

  @override
  double computeMaxIntrinsicWidth(double height) =>
      math.max(
        _body?.getMaxIntrinsicWidth(height) ?? 0,
        _figures?.getMaxIntrinsicWidth(double.infinity) ?? 0,
      ) +
      _padding.horizontal;

  @override
  double computeMinIntrinsicHeight(double width) =>
      (_body?.getMinIntrinsicHeight(math.max(0, width - _padding.horizontal)) ??
          0) +
      _padding.vertical;

  @override
  double computeMaxIntrinsicHeight(double width) {
    final inner = math.max(0.0, width - _padding.horizontal);
    final body = _body?.getMaxIntrinsicHeight(inner) ?? 0;
    final figures = _figures?.getMaxIntrinsicHeight(inner);
    return (figures == null ? body : body + _gap + figures) + _padding.vertical;
  }

  @override
  void performLayout() {
    final textLayout = _StatusTextLayout();
    try {
      _layoutCard(textLayout);
    } finally {
      textLayout.dispose();
    }
  }

  void _layoutCard(_StatusTextLayout textLayout) {
    final backdrop = _backdrop;
    final body = _body;
    final figures = _figures;
    if (backdrop == null || body == null || figures == null) {
      size = constraints.smallest;
      return;
    }
    final inner = constraints.deflate(_padding);
    final loose = inner.loosen();
    body.layout(loose, parentUsesSize: true);
    figures.layout(
      BoxConstraints(maxWidth: inner.maxWidth),
      parentUsesSize: true,
    );
    final bleed = math.min(_bleed, _padding.right);
    final provisional = constraints.constrain(_padding.inflateSize(body.size));
    final anchor = _cornerCenter(provisional);
    final centers = _footerCenters(body, textLayout);
    RenderTrafficReadings? readings;
    void findReadings(RenderObject node) {
      if (node is RenderTrafficReadings) readings ??= node;
      node.visitChildren(findReadings);
    }

    findReadings(figures);
    if (centers != null && readings != null) {
      invokeLayoutCallback<BoxConstraints>((_) {
        readings!.alignLineCenters(centers.$2 - centers.$1);
      });
      figures.layout(
        BoxConstraints(maxWidth: inner.maxWidth),
        parentUsesSize: true,
      );
    }
    final top = centers == null || readings == null
        ? anchor.dy - _padding.top
        : centers.$1 - readings!.firstRowHeight / 2;
    Rect? corner;
    invokeLayoutCallback<BoxConstraints>((_) {
      final rows = [
        for (final row in readings?.inkBounds ?? const <Rect>[])
          MatrixUtils.transformRect(readings!.getTransformTo(figures), row),
      ];
      corner = _cornerFor(
        figures.size,
        body,
        textLayout,
        bleed,
        top,
        rows: rows,
        right: anchor.dx - _padding.left,
      );
    });
    _footerSlotAvailable = corner != null;
    if (corner == null && !_allowAppend) {
      corner =
          Offset(
            math.max(0, anchor.dx - _padding.left - figures.size.width),
            top,
          ) &
          figures.size;
    }
    final overlaid = corner != null;
    final origin = _padding.topLeft;
    _parentDataOf(body).offset = origin;
    _parentDataOf(figures).offset =
        origin +
        (corner?.topLeft ??
            Offset(
              math.max(0, anchor.dx - _padding.left - figures.size.width),
              body.size.height + _gap,
            ));
    size = constraints.constrain(
      _padding.inflateSize(
        overlaid
            ? body.size
            : Size(
                math.max(body.size.width, figures.size.width),
                body.size.height + _gap + figures.size.height,
              ),
      ),
    );
    _overlaid = overlaid;
    if (figures is _RenderTrafficStop) {
      figures.order = overlaid ? _overlaidOrder : _appendedOrder;
    }
    final baseline = math.max(0.0, size.height - 1);
    _baseline = baseline;
    const bandTop = 0.0;
    _bandTop = bandTop;
    backdrop.layout(
      BoxConstraints.tightFor(
        width: size.width,
        height: (baseline - bandTop) / _baselineRatio,
      ),
    );
    _parentDataOf(backdrop).offset = const Offset(0, bandTop);
  }

  Offset _cornerCenter(Size size) {
    final rect = Offset.zero & size;
    final shape = _shape;
    if (shape is RoundedSuperellipseBorder) {
      final inner = shape.borderRadius
          .resolve(_textDirection)
          .toRSuperellipse(rect)
          .deflate(shape.side.strokeInset)
          .scaleRadii();
      return Offset(inner.right - inner.trRadiusX, inner.top + inner.trRadiusY);
    }
    return shape
        .getInnerPath(rect, textDirection: _textDirection)
        .getBounds()
        .topRight;
  }

  /// The end corner of the row at [top], or the nearest clear place toward
  /// the start along it; null when there is none.
  Rect? _cornerFor(
    Size figures,
    RenderBox body,
    _StatusTextLayout textLayout,
    double bleed,
    double top, {
    required List<Rect> rows,
    double? right,
  }) {
    if (figures.width > body.size.width + bleed ||
        figures.height > body.size.height) {
      return null;
    }
    var left = (right ?? body.size.width + bleed) - figures.width;
    while (left >= 0 && left + figures.width <= body.size.width + bleed) {
      final corner = Offset(left, top) & figures;
      final stops = [
        for (final mark in _foreground)
          for (final row in rows.isEmpty ? [Offset.zero & figures] : rows)
            for (final block in mark.blockingBounds(
              row.shift(corner.topLeft),
              body,
              textLayout,
            ))
              block.left - row.right,
      ];
      if (stops.isEmpty) return corner;
      left = stops.reduce(math.min);
      if (left < 0 || left + figures.width > body.size.width + bleed) {
        return null;
      }
    }
    return null;
  }

  (double, double)? _footerCenters(
    RenderBox body,
    _StatusTextLayout textLayout,
  ) {
    final paragraphs = <RenderParagraph>[];
    void visit(RenderObject node) {
      if (node is RenderParagraph &&
          node.text.toPlainText().trim().isNotEmpty) {
        paragraphs.add(node);
      }
      node.visitChildren(visit);
    }

    for (final edge in _edges) {
      if (edge.floor) {
        visit(edge);
        break;
      }
    }
    if (paragraphs.length < 2) return null;
    double center(RenderParagraph paragraph) {
      final painter = textLayout.of(paragraph);
      final center = MatrixUtils.transformRect(
        paragraph.getTransformTo(body),
        Offset.zero & paragraph.constraints.constrain(painter.size),
      ).center.dy;
      return center;
    }

    return (center(paragraphs[0]), center(paragraphs[1]));
  }

  _StatusLayoutParentData _parentDataOf(RenderBox child) =>
      child.parentData! as _StatusLayoutParentData;

  @override
  void paint(PaintingContext context, Offset offset) {
    final backdrop = _backdrop;
    if (backdrop == null) return;
    context.paintChild(backdrop, _parentDataOf(backdrop).offset + offset);
    for (
      var child = childAfter(backdrop);
      child != null;
      child = childAfter(child)
    ) {
      context.paintChild(child, _parentDataOf(child).offset + offset);
    }
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}

/// A box in the status card's body that the card reads during its layout.
mixin _StatusMark on RenderProxyBox {
  RenderStatusCardLayout? _owner;

  // Kept from this box's own layout: the card reads it while laying itself
  // out, when another box's size is not yet the card's to read.
  Size _laidOut = Size.zero;

  void _join(RenderStatusCardLayout owner);

  void _leave(RenderStatusCardLayout owner);

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    RenderObject? ancestor = parent;
    while (ancestor != null && ancestor is! RenderStatusCardLayout) {
      ancestor = ancestor.parent;
    }
    _owner = ancestor as RenderStatusCardLayout?;
    if (_owner case final layout?) _join(layout);
  }

  @override
  void detach() {
    if (_owner case final layout?) _leave(layout);
    _owner = null;
    super.detach();
  }

  @override
  void performLayout() {
    super.performLayout();
    _laidOut = size;
  }

  /// This box in [body]'s space; null while it sits outside the body.
  Rect? rectIn(RenderBox body) {
    var offset = Offset.zero;
    RenderObject node = this;
    while (!identical(node, body)) {
      final parent = node.parent;
      if (parent == null || parent is RenderStatusCardLayout) return null;
      // A proxy places its child at its own origin and keeps no offset.
      if (node.parentData case final BoxParentData parentData) {
        offset += parentData.offset;
      }
      node = parent;
    }
    return offset & _laidOut;
  }
}

/// A tight descendant can be queued behind this card in flushLayout. Measure
/// its current text without querying its not-yet-laid-out render paragraph.
/// Reuse the snapshot for every candidate in this pass, then release it.
class _StatusTextLayout {
  final _painters = <RenderParagraph, TextPainter>{};

  TextPainter of(RenderParagraph paragraph) => _painters.putIfAbsent(
    paragraph,
    () =>
        TextPainter(
          text: paragraph.text,
          textAlign: paragraph.textAlign,
          textDirection: paragraph.textDirection,
          textScaler: paragraph.textScaler,
          maxLines: paragraph.maxLines,
          ellipsis: paragraph.overflow == TextOverflow.ellipsis
              ? '\u2026'
              : null,
          locale: paragraph.locale,
          strutStyle: paragraph.strutStyle,
          textWidthBasis: paragraph.textWidthBasis,
          textHeightBehavior: paragraph.textHeightBehavior,
        )..layout(
          minWidth: paragraph.constraints.minWidth,
          maxWidth:
              paragraph.softWrap || paragraph.overflow == TextOverflow.ellipsis
              ? paragraph.constraints.maxWidth
              : double.infinity,
        ),
  );

  void dispose() {
    for (final painter in _painters.values) {
      painter.dispose();
    }
  }
}

/// Marks what the status card's corner figures must never cover; [margin]
/// widens the mark, as the gap a title keeps from them. The speed lines
/// stay off a mark holding [text]: off its own [outline], such as a chip's
/// pill, or off a rounded box a little larger than it.
class StatusForeground extends SingleChildRenderObjectWidget {
  const StatusForeground({
    super.key,
    this.margin = EdgeInsetsDirectional.zero,
    this.text = true,
    this.outline,
    required super.child,
  });

  final EdgeInsetsGeometry margin;
  final bool text;
  final ShapeBorder? outline;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderStatusForeground(
        margin.resolve(Directionality.of(context)),
        text,
        outline,
      );

  @override
  void updateRenderObject(BuildContext context, RenderObject renderObject) {
    (renderObject as _RenderStatusForeground)
      ..margin = margin.resolve(Directionality.of(context))
      ..text = text
      ..outline = outline;
  }
}

class _RenderStatusForeground extends RenderProxyBox with _StatusMark {
  _RenderStatusForeground(this._margin, this._text, this._outline);

  ShapeBorder? _outline;
  ShapeBorder? get outline => _outline;
  set outline(ShapeBorder? value) {
    if (value == _outline) return;
    _outline = value;
    _owner?.markNeedsLayout();
  }

  EdgeInsets _margin;
  set margin(EdgeInsets value) {
    if (value == _margin) return;
    _margin = value;
    _owner?.markNeedsLayout();
  }

  bool _text;
  bool get text => _text;
  set text(bool value) {
    if (value == _text) return;
    _text = value;
    _owner?.markNeedsLayout();
  }

  @override
  void _join(RenderStatusCardLayout owner) => owner._foreground.add(this);

  @override
  void _leave(RenderStatusCardLayout owner) => owner._foreground.remove(this);

  Rect? markIn(RenderBox body) {
    final rect = rectIn(body);
    return rect == null ? null : _margin.inflateRect(rect);
  }

  List<Rect> blockingBounds(
    Rect corner,
    RenderBox body,
    _StatusTextLayout textLayout,
  ) {
    final mark = markIn(body);
    if (mark == null) return const [];
    if (!text) {
      OutboundDropletPainter? painter;
      void visit(RenderObject node) {
        if (node is RenderCustomPaint &&
            node.painter is OutboundDropletPainter) {
          painter ??= node.painter! as OutboundDropletPainter;
        }
        node.visitChildren(visit);
      }

      visit(this);
      if (painter case final bubble?) {
        final overlap = Path.combine(
          PathOperation.intersect,
          bubble.pathOf(_laidOut),
          Path()..addRect(corner.shift(-mark.topLeft)),
        ).getBounds();
        return overlap.width > 0 && overlap.height > 0
            ? [overlap.shift(mark.topLeft)]
            : const [];
      }
    }
    final bounds = <Rect>[];
    if (_text && _outline == null) {
      void visit(RenderObject node) {
        if (node is RenderParagraph) {
          final text = node.text.toPlainText(includeSemanticsLabels: false);
          for (final word in RegExp(r'\S+').allMatches(text)) {
            for (final box
                in textLayout
                    .of(node)
                    .getBoxesForSelection(
                      TextSelection(
                        baseOffset: word.start,
                        extentOffset: word.end,
                      ),
                    )) {
              bounds.add(
                _margin.inflateRect(
                  MatrixUtils.transformRect(
                    node.getTransformTo(body),
                    box.toRect(),
                  ),
                ),
              );
            }
          }
        }
        node.visitChildren(visit);
      }

      visit(this);
    }
    return [
      for (final bound in bounds.isEmpty ? [mark] : bounds)
        if (bound.intersect(corner) case final overlap
            when overlap.width > 0 && overlap.height > 0)
          bound,
    ];
  }
}

/// Marks the footer whose label and value centres align the speed readings.
class StatusCurveEdge extends SingleChildRenderObjectWidget {
  const StatusCurveEdge({super.key, this.floor = false, required super.child});

  final bool floor;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderStatusCurveEdge(floor);

  @override
  void updateRenderObject(BuildContext context, RenderObject renderObject) {
    (renderObject as _RenderStatusCurveEdge).floor = floor;
  }
}

class _RenderStatusCurveEdge extends RenderProxyBox with _StatusMark {
  _RenderStatusCurveEdge(this._floor);

  bool _floor;
  bool get floor => _floor;
  set floor(bool value) {
    if (value == _floor) return;
    _floor = value;
    _owner?.markNeedsLayout();
  }

  @override
  void _join(RenderStatusCardLayout owner) => owner._edges.add(this);

  @override
  void _leave(RenderStatusCardLayout owner) => owner._edges.remove(this);
}

class _TrafficStop extends SingleChildRenderObjectWidget {
  const _TrafficStop({required super.child});

  @override
  _RenderTrafficStop createRenderObject(BuildContext context) =>
      _RenderTrafficStop();
}

class _RenderTrafficStop extends RenderProxyBox {
  int _order = 0;
  set order(int value) {
    if (value == _order) return;
    _order = value;
    markNeedsSemanticsUpdate();
  }

  @override
  void describeSemanticsConfiguration(SemanticsConfiguration config) {
    super.describeSemanticsConfiguration(config);
    config
      ..isSemanticBoundary = true
      ..sortKey = OrdinalSortKey(_order.toDouble());
  }
}
