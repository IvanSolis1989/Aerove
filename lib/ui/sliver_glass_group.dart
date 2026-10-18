import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

class SliverGlassGroup extends SingleChildRenderObjectWidget {
  SliverGlassGroup({
    super.key,
    required this.shape,
    required this.style,
    required List<Widget> slivers,
  }) : super(child: SliverMainAxisGroup(slivers: slivers));

  final RoundedSuperellipseBorder shape;
  final LiquidGlassStyle style;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderSliverGlassGroup(
        shape.borderRadius.resolve(Directionality.of(context)),
        style,
      );

  @override
  void updateRenderObject(
    BuildContext context,
    covariant RenderObject renderObject,
  ) {
    (renderObject as _RenderSliverGlassGroup).update(
      shape.borderRadius.resolve(Directionality.of(context)),
      style,
    );
  }
}

class _RenderSliverGlassGroup extends RenderProxySliver {
  _RenderSliverGlassGroup(this._shape, this._style);

  BorderRadius _shape;
  LiquidGlassStyle _style;
  late final _pane = LiquidGlassPane(markNeedsPaint);
  final _clip = LayerHandle<ClipRectLayer>();

  void update(BorderRadius shape, LiquidGlassStyle style) {
    if (shape == _shape && style == _style) return;
    _shape = shape;
    _style = style;
    markNeedsPaint();
  }

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void dispose() {
    _pane.dispose();
    _clip.layer = null;
    super.dispose();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final group = child! as RenderSliverMainAxisGroup;
    final first = group.firstChild;
    final layout = geometry!;
    if (!layout.visible || first == null) {
      _pane.clear();
      _clip.layer = null;
      return;
    }
    assert(constraints.axisDirection == AxisDirection.down);
    final top = (first.parentData! as SliverPhysicalParentData).paintOffset.dy;
    // The real tail can lie beyond the viewport; clipping must not round it early.
    final bottom = layout.scrollExtent - constraints.scrollOffset;
    final rect = Rect.fromLTRB(0, top, constraints.crossAxisExtent, bottom);
    if (rect.isEmpty) {
      _pane.clear();
      _clip.layer = null;
      super.paint(context, offset);
      return;
    }
    _clip.layer = context.pushClipRect(
      true,
      offset,
      Rect.fromLTWH(
        0,
        layout.paintOrigin,
        constraints.crossAxisExtent,
        layout.paintExtent,
      ),
      (context, offset) {
        _pane.paint(
          context,
          rect.shift(offset),
          shape: LiquidGlassCorners(_shape),
          style: _style,
        );
        super.paint(context, offset);
      },
      oldLayer: _clip.layer,
    );
  }
}
