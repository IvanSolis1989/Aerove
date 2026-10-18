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

/// Capture and filter counters accumulate per host; pixel counts describe this frame.
typedef LiquidGlassInkCapture = ({
  int generation,
  int masks,
  int maskPixels,
  int texturePixels,
  int sourceCaptures,
  int fallbackCaptures,
  int filterUpdates,
});

/// An opaque, self-contained background whose ancestors may move independently.
class LiquidGlassColorSource extends SingleChildRenderObjectWidget {
  const LiquidGlassColorSource({super.key, required super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _ColorSourceBox();
}

class _ColorSourceBox extends RenderRepaintBoundary {
  @override
  _ColorSourceLayer updateCompositedLayer({required covariant _ColorSourceLayer? oldLayer}) =>
      (oldLayer ?? _ColorSourceLayer())..size = size;
}

abstract class _ColorSnapshotLayer extends OffsetLayer {
  Size size = Size.zero;
  ui.Image? _image;
  List<Object?> _key = const [];

  ui.Image? capture() {
    if (size.isEmpty) return null;
    final key = <Object?>[size];
    void visit(ContainerLayer layer) {
      for (Layer? child = layer.firstChild; child != null;) {
        key.add(child.runtimeType);
        if (child is PictureLayer) key.add(child.picture);
        if (child is ContainerLayer) {
          key.addAll(_geometry(child)?.storage ?? const <double>[]);
          switch (child) {
            case OpacityLayer(:final alpha):
              key.add(alpha);
            case ClipRectLayer(:final clipRect):
              key.add(clipRect);
            case ClipRSuperellipseLayer(:final clipRSuperellipse):
              key.add(clipRSuperellipse);
            case ClipPathLayer(:final clipPath):
              key.add(clipPath);
            case ColorFilterLayer(:final colorFilter):
              key.add(colorFilter);
            case ImageFilterLayer(:final imageFilter):
              key.add(imageFilter);
            case ShaderMaskLayer(:final shader, :final maskRect, :final blendMode):
              key.addAll([shader, maskRect, blendMode]);
          }
          visit(child);
        }
        child = child.nextSibling;
      }
    }
    visit(this);
    if (_image != null && listEquals(key, _key)) return _image;
    final scale = math.min(1.0, 1024 / size.longestSide);
    final image = toImageSync(Offset.zero & size, pixelRatio: scale);
    _image?.dispose();
    _image = image;
    _key = key;
    return image;
  }

  @override
  void dispose() {
    _image?.dispose();
    _image = null;
    super.dispose();
  }
}

class _ColorSourceLayer extends _ColorSnapshotLayer {}

class _ColorFieldLayer extends _ColorSnapshotLayer {
  int generation = 0;
  ui.Image? _selected;

  (_ColorSnapshotLayer, Matrix4)? source(Matrix4 toHost) {
    (_ColorSourceLayer, Matrix4)? candidate;
    var valid = true;
    void visit(ContainerLayer layer, Matrix4 matrix) {
      for (Layer? child = layer.firstChild; child != null;) {
        if (child is OpacityLayer && child.alpha == 0) {
          child = child.nextSibling;
          continue;
        }
        if (child is _ColorSourceLayer) {
          if (candidate != null) valid = false;
          candidate = (child, _within(child, matrix));
        } else if (child is ContainerLayer) {
          if (child is OpacityLayer && child.alpha != 255 ||
              child is ColorFilterLayer || child is ImageFilterLayer ||
              child is ShaderMaskLayer || child is BackdropFilterLayer ||
              child is ClipPathLayer || child is ClipRSuperellipseLayer ||
              child is FollowerLayer) {
            valid = false;
          }
          final next = _within(child, matrix);
          if (child is ClipRectLayer && child.clipRect != null) {
            final inverse = Matrix4.tryInvert(next);
            if (inverse == null || !_contains(child.clipRect!, inverse, size)) {
              valid = false;
            }
          }
          visit(child, next);
        } else {
          valid = false;
        }
        child = child.nextSibling;
      }
    }
    visit(this, Matrix4.identity());
    final selected = candidate;
    if (!valid || selected == null) return null;
    final matrix = selected.$2;
    final storage = matrix.storage;
    final inverse = Matrix4.tryInvert(matrix);
    if (inverse == null || storage[3] != 0 || storage[7] != 0 ||
        storage[11] != 0 || storage[15] != 1 ||
        !_contains(Offset.zero & selected.$1.size, inverse, size)) {
      return null;
    }
    return (selected.$1, toHost.clone()..multiply(matrix));
  }

  bool _contains(Rect bounds, Matrix4 inverse, Size viewport) {
    for (final corner in [Offset.zero, Offset(viewport.width, 0),
      Offset(0, viewport.height), Offset(viewport.width, viewport.height)]) {
      final point = MatrixUtils.transformPoint(inverse, corner);
      if (point.dx < bounds.left - 0.001 || point.dx > bounds.right + 0.001 ||
          point.dy < bounds.top - 0.001 || point.dy > bounds.bottom + 0.001) {
        return false;
      }
    }
    return true;
  }

  void select(ui.Image? image) {
    if (!identical(_selected, image)) {
      _selected = image;
      generation++;
    }
  }
}

class _InkCounters {
  int sourceCaptures = 0;
  int fallbackCaptures = 0;
  int filterUpdates = 0;
}

final _inkCounters = Expando<_InkCounters>();

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
  bool _filterDirty = true;

  void configure({
    required Offset offset,
    required Size size,
    required double strength,
    required Color source,
    required double contrast,
    required bool dark,
    required ui.FragmentProgram? program,
  }) {
    final changed = this.size != size || this.strength != strength ||
        this.source != source || this.contrast != contrast ||
        this.dark != dark || !identical(this.program, program);
    this.offset = offset;
    if (!changed) return;
    this.size = size;
    this.strength = strength;
    this.source = source;
    alpha = source.a;
    this.contrast = contrast;
    this.dark = dark;
    if (!identical(this.program, program)) {
      _shader?.dispose();
      _shader = null;
      _filteredField = null;
      _uniforms = const [];
    }
    this.program = program;
    _filterDirty = true;
    markNeedsAddToScene();
  }

  bool _publishField(ui.Image? field, Size fieldSize, Matrix4 mapping) {
    if (!_filterDirty && identical(_field, field) && _fieldSize == fieldSize &&
        listEquals(_mapping.storage, mapping.storage)) {
      return false;
    }
    final previous = _filter;
    _field = field;
    _fieldSize = fieldSize;
    _mapping = mapping;
    _filterDirty = true;
    final changed = !identical(previous, filter);
    if (changed) {
      markNeedsAddToScene();
    }
    return changed;
  }

  ui.ImageFilter? get filter {
    if (!_filterDirty) return _filter;
    _filterDirty = false;
    final field = _field;
    final program = this.program;
    if (field == null || program == null || size.isEmpty) {
      _shader?.dispose();
      _shader = null;
      _filteredField = null;
      _uniforms = const [];
      return _filter = null;
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
      if (_uniforms.length <= index || _uniforms[index] != value) {
        shader.setFloat(index + 2, value);
      }
    }
    if (!identical(field, _filteredField)) {
      shader.setImageSampler(1, field, filterQuality: FilterQuality.low);
    }
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
    final viewport = field;
    final selected = viewport == null ? null : viewport.$1.source(viewport.$2);
    final snapshot = selected?.$1 ?? viewport?.$1;
    final toHost = selected?.$2 ?? viewport?.$2;
    final before = snapshot?._image;
    final image = inks.isEmpty ? null : snapshot?.capture();
    if (image != null) {
      viewport?.$1.select(image);
    }
    final inverse = toHost == null ? null : Matrix4.tryInvert(toHost);
    var updates = 0;
    for (final (ink, matrix) in inks) {
      if (ink._publishField(
        inverse == null ? null : image,
        snapshot?.size ?? Size.zero,
        inverse == null ? Matrix4.identity() : (inverse.clone()..multiply(matrix)),
      )) {
        updates++;
      }
    }
    assert(() {
      final counters = _inkCounters[this] ??= _InkCounters();
      if (image != null && !identical(before, image)) {
        if (selected == null) {
          counters.fallbackCaptures++;
        } else {
          counters.sourceCaptures++;
        }
      }
      counters.filterUpdates += updates;
      LiquidGlassHost.debugLastInkCapture = (
        generation: viewport?.$1.generation ?? 0,
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
        sourceCaptures: counters.sourceCaptures,
        fallbackCaptures: counters.fallbackCaptures,
        filterUpdates: counters.filterUpdates,
      );
      return true;
    }());
  }
}
