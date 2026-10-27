import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
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
           _StatusLocal(child: RepaintBoundary(child: backdrop)),
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
  void performLayout() => _layoutCard();

  Size _bodySize = Size.zero;
  Size _laidOutSize = Size.zero;
  bool _layingOut = false;

  double get _innerWidth => constraints.deflate(_padding).maxWidth;

  void _layoutCard() {
    final backdrop = _backdrop;
    final body = _body;
    final figures = _figures;
    if (backdrop == null || body == null || figures is! _RenderTrafficStop) {
      size = constraints.smallest;
      return;
    }
    _layingOut = true;
    try {
      invokeLayoutCallback<BoxConstraints>((_) {
        for (final local in [backdrop, figures]) {
          if (local is _StatusLocalBuild) local.flushBuild?.call();
        }
      });
      body.layout(constraints.deflate(_padding).loosen(), parentUsesSize: true);
      _bodySize = body.size;
      final provisional = constraints.constrain(
        _padding.inflateSize(_bodySize),
      );
      figures.layout(BoxConstraints.tight(_padding.deflateSize(provisional)));
      size = figures._plan!.cardSize;
      _laidOutSize = size;
      figures.layout(BoxConstraints.tight(_padding.deflateSize(size)));
      _parentDataOf(body).offset = _padding.topLeft;
      _parentDataOf(figures).offset = _padding.topLeft;
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
    } finally {
      _layingOut = false;
    }
  }

  _StatusFiguresPlan _planFigures(
    Size figures,
    List<Rect> rows,
    double? firstHeight,
    _StatusTextLayout textLayout,
  ) {
    final body = _body!;
    final provisional = constraints.constrain(_padding.inflateSize(_bodySize));
    final anchor = _cornerCenter(provisional);
    final centers = _footerCenters(body, textLayout);
    final top = centers == null || firstHeight == null
        ? anchor.dy - _padding.top
        : centers.$1 - firstHeight / 2;
    var corner = _cornerFor(
      figures,
      body,
      textLayout,
      math.min(_bleed, _padding.right),
      top,
      rows: rows,
      right: anchor.dx - _padding.left,
    );
    final available = corner != null;
    if (corner == null && !_allowAppend) {
      corner =
          Offset(math.max(0, anchor.dx - _padding.left - figures.width), top) &
          figures;
    }
    final overlaid = corner != null;
    return _StatusFiguresPlan(
      available: available,
      overlaid: overlaid,
      origin:
          corner?.topLeft ??
          Offset(
            math.max(0, anchor.dx - _padding.left - figures.width),
            _bodySize.height + _gap,
          ),
      cardSize: constraints.constrain(
        _padding.inflateSize(
          overlaid
              ? _bodySize
              : Size(
                  math.max(_bodySize.width, figures.width),
                  _bodySize.height + _gap + figures.height,
                ),
        ),
      ),
    );
  }

  void _commitFigures(_StatusFiguresPlan plan, _RenderTrafficStop stop) {
    _footerSlotAvailable = plan.available;
    _overlaid = plan.overlaid;
    stop.order = plan.overlaid ? _overlaidOrder : _appendedOrder;
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
    if (figures.width > _bodySize.width + bleed ||
        figures.height > _bodySize.height) {
      return null;
    }
    var left = (right ?? _bodySize.width + bleed) - figures.width;
    while (left >= 0 && left + figures.width <= _bodySize.width + bleed) {
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
      if (left < 0 || left + figures.width > _bodySize.width + bleed) {
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

class _StatusFiguresPlan {
  const _StatusFiguresPlan({
    required this.available,
    required this.overlaid,
    required this.origin,
    required this.cardSize,
  });

  final bool available;
  final bool overlaid;
  final Offset origin;
  final Size cardSize;
}

class _StatusLocal extends SingleChildRenderObjectWidget {
  const _StatusLocal({required super.child});

  @override
  SingleChildRenderObjectElement createElement() => _StatusLocalElement(this);

  @override
  RenderBox createRenderObject(BuildContext context) => _RenderStatusBackdrop();
}

mixin _StatusLocalBuild on RenderBox {
  VoidCallback? flushBuild;
}

class _RenderStatusBackdrop extends RenderProxyBox with _StatusLocalBuild {}

/// These children need no new constraints to rebuild. Flush their build scope
/// before layout instead of asking the enclosing SliverLayoutBuilder to run.
class _StatusLocalElement extends SingleChildRenderObjectElement {
  _StatusLocalElement(_StatusLocal super.widget);

  @override
  BuildScope get buildScope => _scope;
  late final _scope = BuildScope(scheduleRebuild: _schedule);
  bool _scheduled = false;

  @override
  void mount(Element? parent, Object? newSlot) {
    super.mount(parent, newSlot);
    (renderObject as _StatusLocalBuild).flushBuild = _flush;
  }

  @override
  void unmount() {
    (renderObject as _StatusLocalBuild).flushBuild = null;
    super.unmount();
  }

  @override
  void update(_StatusLocal newWidget) {
    super.update(newWidget);
    if (renderObject case final _RenderTrafficStop figures) {
      figures._card.markNeedsLayout();
    }
  }

  void _schedule() {
    if (SchedulerBinding.instance.schedulerPhase ==
        SchedulerPhase.persistentCallbacks) {
      if (renderObject.parent case final RenderStatusCardLayout card) {
        card.markNeedsLayout();
      }
      return;
    }
    if (_scheduled) return;
    _scheduled = true;
    scheduleMicrotask(() {
      if (_scheduled && mounted) _flush();
    });
  }

  void _flush() {
    _scheduled = false;
    owner!.buildScope(this);
    if (renderObject case final _RenderTrafficStop figures) {
      figures.prepareFigures();
    }
  }
}

class _TrafficStop extends _StatusLocal {
  const _TrafficStop({required super.child});

  @override
  _RenderTrafficStop createRenderObject(BuildContext context) =>
      _RenderTrafficStop();
}

class _RenderTrafficStop extends RenderShiftedBox with _StatusLocalBuild {
  _RenderTrafficStop() : super(null);
  _StatusFiguresPlan? _plan;
  RenderStatusCardLayout get _card => parent! as RenderStatusCardLayout;

  RenderTrafficReadings? get _readings {
    RenderTrafficReadings? readings;
    void visit(RenderObject node) {
      if (node is RenderTrafficReadings) readings ??= node;
      node.visitChildren(visit);
    }

    child?.visitChildren(visit);
    if (child is RenderTrafficReadings) return child as RenderTrafficReadings;
    return readings;
  }

  void prepareFigures() {
    final card = _card;
    if (!card.hasSize || card._layingOut) return;
    final readings = _readings;
    if (readings == null) {
      card.markNeedsLayout();
      return;
    }
    final textLayout = _StatusTextLayout();
    try {
      final projection = readings.projectedGeometry(
        card._innerWidth,
        textLayout.of,
      );
      if (projection == null) {
        card.markNeedsLayout();
        return;
      }
      final plan = card._planFigures(
        projection.size,
        projection.ink,
        readings.firstRowHeight,
        textLayout,
      );
      if (plan.cardSize != card._laidOutSize) card.markNeedsLayout();
    } finally {
      textLayout.dispose();
    }
  }

  @override
  void performLayout() {
    size = constraints.biggest;
    final card = _card;
    final child = this.child;
    if (child == null) return;
    final childConstraints = BoxConstraints(maxWidth: card._innerWidth);
    child.layout(childConstraints, parentUsesSize: true);
    final textLayout = _StatusTextLayout();
    try {
      final readings = _readings;
      final centers = card._footerCenters(card._body!, textLayout);
      if (centers != null && readings != null) {
        invokeLayoutCallback<BoxConstraints>((_) {
          readings.alignLineCenters(centers.$2 - centers.$1);
        });
        child.layout(childConstraints, parentUsesSize: true);
      }
      late _StatusFiguresPlan plan;
      invokeLayoutCallback<BoxConstraints>((_) {
        final rows = [
          for (final row
              in readings?.inkBoundsWith(textLayout.of) ?? const <Rect>[])
            MatrixUtils.transformRect(readings!.getTransformTo(child), row),
        ];
        plan = _plan = card._planFigures(
          child.size,
          rows,
          readings?.firstRowHeight,
          textLayout,
        );
      });
      assert(card._layingOut || plan.cardSize == card._laidOutSize);
      (child.parentData! as BoxParentData).offset = plan.origin;
      card._commitFigures(plan, this);
    } finally {
      textLayout.dispose();
    }
  }

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
