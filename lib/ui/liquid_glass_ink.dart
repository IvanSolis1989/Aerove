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
  int frozenMasks,
  int skippedMasks,
  int skippedBackdrops,
});

/// An opaque, self-contained background whose ancestors may move independently.
class LiquidGlassColorSource extends SingleChildRenderObjectWidget {
  const LiquidGlassColorSource({super.key, required super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _ColorSourceBox();
}

class _ColorSourceBox extends RenderRepaintBoundary {
  @override
  _ColorSourceLayer updateCompositedLayer({
    required covariant _ColorSourceLayer? oldLayer,
  }) => (oldLayer ?? _ColorSourceLayer())..size = size;
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
            case ShaderMaskLayer(
              :final shader,
              :final maskRect,
              :final blendMode,
            ):
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
              child is ColorFilterLayer ||
              child is ImageFilterLayer ||
              child is ShaderMaskLayer ||
              child is BackdropFilterLayer ||
              child is ClipPathLayer ||
              child is ClipRSuperellipseLayer ||
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
    if (inverse == null ||
        storage[3] != 0 ||
        storage[7] != 0 ||
        storage[11] != 0 ||
        storage[15] != 1 ||
        !_contains(Offset.zero & selected.$1.size, inverse, size)) {
      return null;
    }
    return (selected.$1, toHost.clone()..multiply(matrix));
  }

  bool _contains(Rect bounds, Matrix4 inverse, Size viewport) {
    for (final corner in [
      Offset.zero,
      Offset(viewport.width, 0),
      Offset(0, viewport.height),
      Offset(viewport.width, viewport.height),
    ]) {
      final point = MatrixUtils.transformPoint(inverse, corner);
      if (point.dx < bounds.left - 0.001 ||
          point.dx > bounds.right + 0.001 ||
          point.dy < bounds.top - 0.001 ||
          point.dy > bounds.bottom + 0.001) {
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
  Rect paintBounds = Rect.zero;
  double strength = 1;
  double alpha = 1;
  Color source = Colors.black;
  double contrast = 4.5;
  bool dark = false;
  ui.FragmentProgram? program;
  ui.FragmentShader? _shader;
  ui.Picture? _coveragePicture;
  ui.Shader? _effect;
  ui.Image? _filteredField;
  List<double> _uniforms = const [];
  ui.Image? _field;
  Size _fieldSize = Size.zero;
  Matrix4 _mapping = Matrix4.identity();
  bool _filterDirty = true;
  bool inkFrozen = false;

  void _thaw() {
    if (!inkFrozen) return;
    inkFrozen = false;
    _filterDirty = true;
    markNeedsAddToScene();
  }

  void _freeze() {
    if (program != null) inkFrozen = true;
  }

  void _skipField() {
    if (inkFrozen || _field == null && _effect == null) return;
    _shader?.dispose();
    _shader = null;
    _effect = null;
    _filteredField = null;
    _field = null;
    _uniforms = const [];
    _filterDirty = true;
    markNeedsAddToScene();
  }

  void configure({
    required Offset offset,
    required Size size,
    Rect? paintBounds,
    required double strength,
    required Color source,
    required double contrast,
    required bool dark,
    required ui.FragmentProgram? program,
  }) {
    final bounds = paintBounds ?? (Offset.zero & size);
    final changed =
        this.size != size ||
        this.paintBounds != bounds ||
        this.strength != strength ||
        this.source != source ||
        this.contrast != contrast ||
        this.dark != dark ||
        !identical(this.program, program);
    this.offset = offset;
    if (!changed) return;
    _thaw();
    if (this.size != size) {
      _coveragePicture?.dispose();
      _coveragePicture = null;
    }
    this.size = size;
    this.paintBounds = bounds;
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
    if (inkFrozen) return false;
    if (!_filterDirty &&
        identical(_field, field) &&
        _fieldSize == fieldSize &&
        listEquals(_mapping.storage, mapping.storage)) {
      return false;
    }
    final previousUniforms = _uniforms;
    final previousField = _filteredField;
    _field = field;
    _fieldSize = fieldSize;
    _mapping = mapping;
    _filterDirty = true;
    inkShader;
    final changed =
        !identical(previousUniforms, _uniforms) ||
        !identical(previousField, _filteredField);
    if (changed) {
      markNeedsAddToScene();
    }
    return changed;
  }

  @visibleForTesting
  (ui.Image, Size, Matrix4)? get debugField =>
      _field == null ? null : (_field!, _fieldSize, _mapping.clone());

  Rect get maskRect => Offset.zero & size;

  ui.Picture? get coveragePicture {
    if (inkShader == null) return null;
    if (_coveragePicture case final picture?) return picture;
    final recorder = ui.PictureRecorder();
    // Hinted glyph bounds can be tighter than their rasterized pixels.
    Canvas(recorder).drawRect(
      maskRect,
      Paint()
        ..color = Colors.transparent
        ..blendMode = BlendMode.src,
    );
    return _coveragePicture = recorder.endRecording();
  }

  ui.Shader? get inkShader {
    if (!_filterDirty) return _effect;
    _filterDirty = false;
    final field = _field;
    final program = this.program;
    if (field == null || program == null || size.isEmpty) {
      _shader?.dispose();
      _shader = null;
      _filteredField = null;
      _uniforms = const [];
      return _effect = null;
    }
    final origin = MatrixUtils.transformPoint(_mapping, Offset.zero);
    final x = MatrixUtils.transformPoint(_mapping, const Offset(1, 0));
    final y = MatrixUtils.transformPoint(_mapping, const Offset(0, 1));
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
      return _effect;
    }
    final shader = _shader ??= program.fragmentShader();
    for (final (index, value) in values.indexed) {
      if (_uniforms.length <= index || _uniforms[index] != value) {
        shader.setFloat(index, value);
      }
    }
    if (!identical(field, _filteredField)) {
      shader.setImageSampler(0, field, filterQuality: FilterQuality.low);
    }
    _filteredField = field;
    _uniforms = values;
    return _effect = shader;
  }

  @override
  void addToScene(ui.SceneBuilder builder) {
    final effect = inkShader;
    if (effect == null) {
      super.addToScene(builder);
      return;
    }
    engineLayer = builder.pushOffset(
      offset.dx,
      offset.dy,
      oldLayer: engineLayer as ui.OffsetEngineLayer?,
    );
    builder.pushShaderMask(effect, maskRect, BlendMode.srcATop);
    builder.addPicture(Offset.zero, coveragePicture!);
    addChildrenToScene(builder);
    builder
      ..pop()
      ..pop();
  }

  @override
  void dispose() {
    _shader?.dispose();
    _coveragePicture?.dispose();
    super.dispose();
  }
}

class LiquidGlassBackdrop extends LeafRenderObjectWidget {
  const LiquidGlassBackdrop({super.key});

  @override
  RenderObject createRenderObject(BuildContext context) => _BackdropBox();
}

class _BackdropBox extends RenderBox {
  final _layer = LayerHandle<LiquidGlassBackdropLayer>();

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  bool get sizedByParent => true;

  @override
  Size computeDryLayout(BoxConstraints constraints) => constraints.biggest;

  @override
  void paint(PaintingContext context, Offset offset) {
    final layer = _layer.layer ??= LiquidGlassBackdropLayer();
    layer.configure(offset, size);
    context.pushLayer(layer, (context, offset) {}, Offset.zero);
  }

  @override
  void dispose() {
    _layer.layer = null;
    super.dispose();
  }
}

class LiquidGlassBackdropLayer extends OffsetLayer {
  Size size = Size.zero;
  ui.Picture? backdropPicture;
  ui.Image? _field;
  Size _fieldSize = Size.zero;
  Matrix4 _mapping = Matrix4.zero();

  void configure(Offset offset, Size size) {
    this.offset = offset;
    if (this.size == size) return;
    this.size = size;
    _mapping = Matrix4.zero();
    markNeedsAddToScene();
  }

  void _publishField(ui.Image? image, Size fieldSize, Matrix4 mapping) {
    if (identical(image, _field) &&
        fieldSize == _fieldSize &&
        listEquals(mapping.storage, _mapping.storage))
      return;
    _field = image;
    _fieldSize = fieldSize;
    _mapping = mapping;
    backdropPicture?.dispose();
    backdropPicture = null;
    final inverse = Matrix4.tryInvert(mapping);
    if (image != null && inverse != null && !size.isEmpty) {
      final recorder = ui.PictureRecorder();
      final canvas = Canvas(recorder)
        ..clipRect(Offset.zero & size)
        ..transform(inverse.storage);
      canvas.drawImageRect(
        image,
        Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
        Offset.zero & fieldSize,
        Paint()..filterQuality = FilterQuality.low,
      );
      backdropPicture = recorder.endRecording();
    }
    markNeedsAddToScene();
  }

  @override
  void addToScene(ui.SceneBuilder builder) {
    engineLayer = builder.pushOffset(
      offset.dx,
      offset.dy,
      oldLayer: engineLayer as ui.OffsetEngineLayer?,
    );
    final picture = backdropPicture;
    if (picture != null) builder.addPicture(Offset.zero, picture);
    builder.pop();
  }

  @override
  void dispose() {
    backdropPicture?.dispose();
    super.dispose();
  }
}

extension _InkCapture on _LiquidGlassHostLayer {
  void _captureInk() {
    (_ColorFieldLayer, Matrix4)? field;
    final inks = <(LiquidGlassInkLayer, Matrix4, bool)>[];
    final backdrops = <(LiquidGlassBackdropLayer, Matrix4, bool)>[];
    final covers = <_GlassCoverage>[];
    void visit(
      ContainerLayer layer,
      Matrix4 matrix,
      bool opaque,
      _GlassVisibility visibility,
    ) {
      for (Layer? child = layer.firstChild; child != null;) {
        if (child is ContainerLayer) {
          final next = _within(child, matrix);
          final visible = visibility.through(child, next);
          final solid =
              opaque && (child is! OpacityLayer || child.alpha == 255);
          if (child is _LiquidGlassOcclusionLayer && solid) {
            covers.add(_GlassCoverage(child, next, inks.length));
          }
          if (child is _ColorFieldLayer) field = (child, next);
          if (child is LiquidGlassInkLayer) {
            inks.add((child, next, visible.shows(child.paintBounds, next)));
          }
          if (child is LiquidGlassBackdropLayer) {
            backdrops.add((child, next, visible.shows(Offset.zero & child.size, next)));
          }
          visit(child, next, solid, visible);
        }
        child = child.nextSibling;
      }
    }

    visit(this, Matrix4.identity(), true, _GlassVisibility(bounds, 1 / devicePixelRatio));
    final updating = <(LiquidGlassInkLayer, Matrix4)>[];
    final covered = <LiquidGlassInkLayer>{};
    for (final (index, entry) in inks.indexed) {
      final (ink, matrix, visible) = entry;
      if (!visible) {
        ink._skipField();
        continue;
      }
      Path? remaining;
      final bounds = MatrixUtils.transformRect(matrix, ink.maskRect);
      var hidden = false;
      for (final cover in covers) {
        if (index >= cover.before || !bounds.overlaps(cover.bounds)) continue;
        if (cover.layer.geometry?.$1 is RoundedSuperellipseBorder &&
            [
              bounds.topLeft,
              bounds.topRight,
              bounds.bottomLeft,
              bounds.bottomRight,
            ].every(cover.path.contains)) {
          hidden = true;
          break;
        }
        remaining ??= (Path()..addRect(ink.maskRect)).transform(matrix.storage);
        remaining = Path.combine(
          PathOperation.difference,
          remaining,
          cover.path,
        );
        if (remaining.getBounds().isEmpty) {
          hidden = true;
          break;
        }
      }
      if (hidden) {
        covered.add(ink);
      } else {
        ink._thaw();
      }
      if (!ink.inkFrozen) updating.add((ink, matrix));
    }
    final viewport = field;
    final selected = viewport == null ? null : viewport.$1.source(viewport.$2);
    final snapshot = selected?.$1 ?? viewport?.$1;
    final toHost = selected?.$2 ?? viewport?.$2;
    final before = snapshot?._image;
    final image = updating.isEmpty && !backdrops.any((entry) => entry.$3)
        ? null
        : snapshot?.capture();
    if (image != null) {
      viewport?.$1.select(image);
    }
    final inverse = toHost == null ? null : Matrix4.tryInvert(toHost);
    for (final (backdrop, matrix, visible) in backdrops) {
      if (!visible) {
        backdrop._publishField(null, Size.zero, backdrop._mapping);
        continue;
      }
      backdrop._publishField(
        inverse == null ? null : image,
        snapshot?.size ?? Size.zero,
        inverse == null
            ? Matrix4.identity()
            : (inverse.clone()..multiply(matrix)),
      );
    }
    var updates = 0;
    for (final (ink, matrix) in updating) {
      if (ink._publishField(
        inverse == null ? null : image,
        snapshot?.size ?? Size.zero,
        inverse == null
            ? Matrix4.identity()
            : (inverse.clone()..multiply(matrix)),
      )) {
        updates++;
      }
    }
    for (final ink in covered) {
      ink._freeze();
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
        skippedMasks: inks.where((entry) => !entry.$3).length,
        skippedBackdrops: backdrops.where((entry) => !entry.$3).length,
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
        frozenMasks: inks.where((entry) => entry.$1.inkFrozen).length,
      );
      return true;
    }());
  }
}
