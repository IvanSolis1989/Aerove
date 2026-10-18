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
  RenderObject createRenderObject(BuildContext context) => _GlassOcclusionBox(
    shape,
    Directionality.of(context),
  );

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
    layer.configure(shape, textDirection, size);
    layer.offset = offset;
    context.pushLayer(layer, super.paint, Offset.zero);
  }

  @override
  void dispose() {
    _layer.layer = null;
    super.dispose();
  }
}

class _LiquidGlassOcclusionLayer extends OffsetLayer {
  (ShapeBorder, TextDirection, Size)? geometry;
  Path path = Path();

  void configure(ShapeBorder shape, TextDirection direction, Size size) {
    final key = (shape, direction, size);
    if (geometry == key) return;
    geometry = key;
    path = shape.getOuterPath(Offset.zero & size, textDirection: direction);
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
