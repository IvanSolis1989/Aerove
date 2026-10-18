part of 'liquid_glass.dart';

class LiquidGlassColorField extends SingleChildRenderObjectWidget {
  const LiquidGlassColorField({super.key, required super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _ColorFieldBox();
}

class _ColorFieldBox extends RenderProxyBox {
  final _layer = LayerHandle<_ColorFieldLayer>();

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void paint(PaintingContext context, Offset offset) {
    final layer = _layer.layer ??= _ColorFieldLayer();
    layer
      ..offset = offset
      ..size = size;
    context.pushLayer(layer, super.paint, Offset.zero);
  }

  @override
  void dispose() {
    _layer.layer = null;
    super.dispose();
  }
}

class _ColorFieldLayer extends OffsetLayer {
  Size size = Size.zero;
  ui.Image? _image;
  List<Object?> _key = const [];
  int generation = 0;

  ui.Image? capture() {
    if (size.isEmpty) return null;
    final key = <Object?>[size, offset];
    void visit(ContainerLayer layer) {
      for (Layer? child = layer.firstChild; child != null;) {
        key.add(child.runtimeType);
        if (child is PictureLayer) key.add(child.picture);
        if (child is ContainerLayer) {
          key.addAll(_geometry(child)?.storage ?? const <double>[]);
          if (child is OpacityLayer) key.add(child.alpha);
          if (child is ClipRectLayer) key.add(child.clipRect);
          visit(child);
        }
        child = child.nextSibling;
      }
    }

    visit(this);
    if (_image != null && listEquals(key, _key)) return _image;
    final scale = math.min(1.0, 1024 / size.longestSide);
    final image = toImageSync(offset & size, pixelRatio: scale);
    _image?.dispose();
    _image = image;
    _key = key;
    generation++;
    return image;
  }

  @override
  void dispose() {
    _image?.dispose();
    super.dispose();
  }
}

class LiquidGlassInkLayer extends OffsetLayer {
  Size size = Size.zero;
  double strength = 1;
  double alpha = 1;
  Color source = Colors.black;
  double contrast = 4.5;
  bool dark = false;
  ui.FragmentProgram? program;
  ui.FragmentShader? _shader;
  ui.ImageFilter? _filter;
  ui.Image? _filteredField;
  List<double> _uniforms = const [];
  ui.Image? _field;
  Size _fieldSize = Size.zero;
  Matrix4 _mapping = Matrix4.identity();

  void configure({
    required Offset offset,
    required Size size,
    required double strength,
    required Color source,
    required double contrast,
    required bool dark,
    required ui.FragmentProgram? program,
  }) {
    this.offset = offset;
    this.size = size;
    this.strength = strength;
    this.source = source;
    alpha = source.a;
    this.contrast = contrast;
    this.dark = dark;
    this.program = program;
    markNeedsAddToScene();
  }

  void _publishField(ui.Image? field, Size fieldSize, Matrix4 mapping) {
    final previous = _filter;
    _field = field;
    _fieldSize = fieldSize;
    _mapping = mapping;
    if (!identical(previous, filter)) {
      markNeedsAddToScene();
    }
  }

  ui.ImageFilter? get filter {
    final field = _field;
    final program = this.program;
    if (field == null || program == null || size.isEmpty) {
      return null;
    }
    final origin = MatrixUtils.transformPoint(_mapping, Offset.zero);
    final x = MatrixUtils.transformPoint(_mapping, Offset(size.width, 0));
    final y = MatrixUtils.transformPoint(_mapping, Offset(0, size.height));
    final values = [
      _fieldSize.width,
      _fieldSize.height,
      origin.dx,
      origin.dy,
      x.dx - origin.dx,
      x.dy - origin.dy,
      y.dx - origin.dx,
      y.dy - origin.dy,
      strength,
      dark ? 1.0 : 0.0,
      contrast,
      alpha,
      source.r,
      source.g,
      source.b,
    ];
    if (identical(field, _filteredField) && listEquals(values, _uniforms)) {
      return _filter;
    }
    final shader = _shader ??= program.fragmentShader();
    for (final (index, value) in values.indexed) {
      shader.setFloat(index + 2, value);
    }
    shader.setImageSampler(1, field, filterQuality: FilterQuality.low);
    _filteredField = field;
    _uniforms = values;
    return _filter = ui.ImageFilter.shader(shader);
  }

  @override
  void addToScene(ui.SceneBuilder builder) {
    final effect = filter;
    if (effect == null) {
      super.addToScene(builder);
      return;
    }
    engineLayer = builder.pushOffset(
      offset.dx,
      offset.dy,
      oldLayer: engineLayer as ui.OffsetEngineLayer?,
    );
    builder.pushClipRect(Offset.zero & size);
    builder.pushImageFilter(effect);
    addChildrenToScene(builder);
    builder
      ..pop()
      ..pop()
      ..pop();
  }

  @override
  void dispose() {
    _shader?.dispose();
    super.dispose();
  }
}

extension _InkCapture on _LiquidGlassHostLayer {
  void _captureInk() {
    (_ColorFieldLayer, Matrix4)? field;
    final inks = <(LiquidGlassInkLayer, Matrix4)>[];
    void visit(ContainerLayer layer, Matrix4 matrix) {
      for (Layer? child = layer.firstChild; child != null;) {
        if (child is ContainerLayer) {
          final next = _within(child, matrix);
          if (child is _ColorFieldLayer) field = (child, next);
          if (child is LiquidGlassInkLayer) inks.add((child, next));
          visit(child, next);
        }
        child = child.nextSibling;
      }
    }

    visit(this, Matrix4.identity());
    final source = field;
    final image = inks.isEmpty ? null : source?.$1.capture();
    final inverse = source == null ? null : Matrix4.tryInvert(source.$2);
    for (final (ink, matrix) in inks) {
      ink._publishField(
        inverse == null ? null : image,
        source?.$1.size ?? Size.zero,
        inverse == null
            ? Matrix4.identity()
            : (inverse.clone()..multiply(matrix)),
      );
    }
    assert(() {
      LiquidGlassHost.debugLastInkCapture = (
        generation: source?.$1.generation ?? 0,
        masks: inks.length,
        maskPixels: inks.fold(
          0,
          (sum, ink) =>
              sum +
              (ink.$1.size.width *
                      ink.$1.size.height *
                      devicePixelRatio *
                      devicePixelRatio)
                  .ceil(),
        ),
        texturePixels: image == null ? 0 : image.width * image.height,
      );
      return true;
    }());
  }
}
