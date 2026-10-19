part of 'liquid_glass.dart';

/// Marks page content whose Standard glass only blurs its backdrop.
class LiquidGlassBody extends SingleChildRenderObjectWidget {
  const LiquidGlassBody({super.key, required super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _GlassBodyBox();
}

class _GlassBodyBox extends RenderProxyBox {
  final _layer = LayerHandle<_GlassBodyLayer>();

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void paint(PaintingContext context, Offset offset) {
    context.pushLayer(
      _layer.layer ??= _GlassBodyLayer(),
      super.paint,
      offset,
    );
  }

  @override
  void dispose() {
    _layer.layer = null;
    super.dispose();
  }
}

class _GlassBodyLayer extends ContainerLayer {}
