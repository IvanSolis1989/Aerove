import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

class SliverGlassGroup extends SingleChildRenderObjectWidget {
  SliverGlassGroup({
    super.key,
    required this.shape,
    required this.style,
    required List<Widget> slivers,
  }) : super(
         child: GlassSurfaceScope(child: SliverMainAxisGroup(slivers: slivers)),
       );

  final RoundedSuperellipseBorder shape;
  final LiquidGlassStyle style;

  Color? _flatOf(BuildContext context) =>
      GlassSurfaceScope.of(context) ? glassRaisedFillOf(context) : null;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderSliverGlassGroup(
        shape.borderRadius.resolve(Directionality.of(context)),
        style,
        _flatOf(context),
      );

  @override
  void updateRenderObject(
    BuildContext context,
    covariant RenderObject renderObject,
  ) {
    (renderObject as _RenderSliverGlassGroup).update(
      shape.borderRadius.resolve(Directionality.of(context)),
      style,
      _flatOf(context),
    );
  }
}

class _RenderSliverGlassGroup extends RenderProxySliver {
  _RenderSliverGlassGroup(this._shape, this._style, this._flat);

  BorderRadius _shape;
  LiquidGlassStyle _style;
  Color? _flat;
  late final _pane = LiquidGlassPane(markNeedsPaint);
  final _clip = LayerHandle<ClipRectLayer>();

  void update(BorderRadius shape, LiquidGlassStyle style, Color? flat) {
    if (shape == _shape && style == _style && flat == _flat) return;
    _shape = shape;
    _style = style;
    _flat = flat;
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
        if (_flat case final flat?) {
          _pane.clear();
          context.canvas.drawRSuperellipse(
            _shape.toRSuperellipse(rect.shift(offset)),
            Paint()..color = flat,
          );
        } else {
          _pane.paint(
            context,
            rect.shift(offset),
            shape: LiquidGlassCorners(_shape),
            style: _style,
          );
        }
        super.paint(context, offset);
      },
      oldLayer: _clip.layer,
    );
  }
}
