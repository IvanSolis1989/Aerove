part of 'liquid_glass.dart';

/// Keeps glass below a covering surface outside its actual painted outline.
class LiquidGlassOcclusion extends SingleChildRenderObjectWidget {
  const LiquidGlassOcclusion({
    super.key,
    required this.shape,
    required super.child,
  });

  final ShapeBorder shape;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _GlassOcclusionBox(shape, Directionality.of(context));

  @override
  void updateRenderObject(
    BuildContext context,
    covariant _GlassOcclusionBox render,
  ) {
    render
      ..shape = shape
      ..textDirection = Directionality.of(context)
      ..markNeedsPaint();
  }
}

class _GlassOcclusionBox extends RenderProxyBox {
  _GlassOcclusionBox(this.shape, this.textDirection);

  ShapeBorder shape;
  TextDirection textDirection;
  final _layer = LayerHandle<_LiquidGlassOcclusionLayer>();

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void paint(PaintingContext context, Offset offset) {
    final layer = _layer.layer ??= _LiquidGlassOcclusionLayer();
    layer.configure(shape, textDirection, Offset.zero & size);
    layer.offset = offset;
    context.pushLayer(layer, super.paint, Offset.zero);
  }

  @override
  void dispose() {
    _layer.layer = null;
    super.dispose();
  }
}

class LiquidGlassOcclusionPane {
  final _layer = LayerHandle<_LiquidGlassOcclusionLayer>();

  void paint(
    PaintingContext context,
    Offset offset, {
    required Rect rect,
    required ShapeBorder shape,
    required PaintingContextCallback painter,
  }) {
    final layer = _layer.layer ??= _LiquidGlassOcclusionLayer();
    layer.configure(shape, TextDirection.ltr, rect);
    layer.offset = offset;
    context.pushLayer(layer, painter, Offset.zero);
  }

  void clear() => _layer.layer = null;

  void dispose() => clear();
}

class _LiquidGlassOcclusionLayer extends OffsetLayer {
  (ShapeBorder, TextDirection, Rect)? geometry;
  Path path = Path();

  void configure(ShapeBorder shape, TextDirection direction, Rect rect) {
    final key = (shape, direction, rect);
    if (geometry == key) return;
    geometry = key;
    path = shape.getOuterPath(rect, textDirection: direction);
    markNeedsAddToScene();
  }
}

class _GlassCoverage {
  _GlassCoverage(this.layer, this.toHost, this.before);

  final _LiquidGlassOcclusionLayer layer;
  final Matrix4 toHost;
  final int before;
  late final Path path = layer.path.transform(toHost.storage);
  late final Rect bounds = path.getBounds();
}
