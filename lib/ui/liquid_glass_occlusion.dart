part of 'liquid_glass.dart';

enum LiquidGlassOcclusionMode { freeze, plain }

/// Keeps glass below a covering surface outside its actual painted outline.
class LiquidGlassOcclusion extends SingleChildRenderObjectWidget {
  const LiquidGlassOcclusion({
    super.key,
    required this.shape,
    this.bottomFeather = 0,
    this.mode = LiquidGlassOcclusionMode.freeze,
    required super.child,
  });

  final ShapeBorder shape;
  final double bottomFeather;
  final LiquidGlassOcclusionMode mode;

  @override
  RenderObject createRenderObject(BuildContext context) => _GlassOcclusionBox(
    shape,
    Directionality.of(context),
    bottomFeather,
    mode,
  );

  @override
  void updateRenderObject(
    BuildContext context,
    covariant _GlassOcclusionBox render,
  ) {
    render
      ..shape = shape
      ..bottomFeather = bottomFeather
      ..mode = mode
      ..textDirection = Directionality.of(context)
      ..markNeedsPaint();
  }
}

class _GlassOcclusionBox extends RenderProxyBox {
  _GlassOcclusionBox(
    this.shape,
    this.textDirection,
    this.bottomFeather,
    this.mode,
  );

  ShapeBorder shape;
  TextDirection textDirection;
  double bottomFeather;
  LiquidGlassOcclusionMode mode;
  final _layer = LayerHandle<_LiquidGlassOcclusionLayer>();

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void paint(PaintingContext context, Offset offset) {
    final layer = _layer.layer ??= _LiquidGlassOcclusionLayer();
    layer.configure(
      shape,
      textDirection,
      Offset.zero & size,
      bottomFeather,
      mode,
    );
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
  (ShapeBorder, TextDirection, Rect, double)? geometry;
  Path path = Path();
  LiquidGlassOcclusionMode mode = LiquidGlassOcclusionMode.freeze;

  void configure(
    ShapeBorder shape,
    TextDirection direction,
    Rect rect, [
    double feather = 0,
    LiquidGlassOcclusionMode mode = LiquidGlassOcclusionMode.freeze,
  ]) {
    final key = (shape, direction, rect, math.max(0.0, feather));
    if (geometry == key && this.mode == mode) return;
    geometry = key;
    this.mode = mode;
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
  late final _GlassFeather? feather = (layer.geometry?.$4 ?? 0) == 0
      ? null
      : _GlassFeather(layer.geometry!.$3, layer.geometry!.$4, toHost);
}

class _GlassFeather {
  _GlassFeather(Rect rect, double extent, this.transform, {this.plain = false})
    : band = Rect.fromLTWH(rect.left, rect.bottom, rect.width, extent);

  final Rect band;
  final Matrix4 transform;
  final bool plain;
  late final Path path = (Path()..addRect(band)).transform(transform.storage);

  static final _live = List<Color>.generate(17, (index) {
    final t = index / 16;
    return Colors.white.withValues(alpha: t * t * (3 - 2 * t));
  }, growable: false);
  static final _frozen = _live.reversed.toList(growable: false);

  void mask(Canvas canvas, {bool inverse = false}) {
    canvas
      ..save()
      ..transform(transform.storage)
      ..clipRect(band, doAntiAlias: false)
      ..drawRect(
        band,
        Paint()
          ..blendMode = BlendMode.dstIn
          ..shader = LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: inverse ? _frozen : _live,
          ).createShader(band),
      )
      ..restore();
  }
}
