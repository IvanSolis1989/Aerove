part of 'liquid_glass.dart';

class _GlassVisibility {
  const _GlassVisibility(this.bounds, this.margin, {this.transparent = false});

  final Rect? bounds;
  final double margin;
  final bool transparent;

  bool _affine(Matrix4 matrix) {
    final values = matrix.storage;
    return values.every((value) => value.isFinite) &&
        values[3] == 0 &&
        values[7] == 0 &&
        values[11] == 0 &&
        values[15] == 1;
  }

  _GlassVisibility through(ContainerLayer layer, Matrix4 toHost) {
    if (transparent) return this;
    if (layer is OpacityLayer && layer.alpha == 0) {
      return _GlassVisibility(bounds, margin, transparent: true);
    }
    // Filters can move or expand descendants beyond an ancestor's clip.
    if (layer is ImageFilterLayer) return _GlassVisibility(null, margin);
    final ignored = switch (layer) {
      ClipRectLayer(:final clipBehavior) ||
      ClipRRectLayer(:final clipBehavior) ||
      ClipRSuperellipseLayer(:final clipBehavior) ||
      ClipPathLayer(:final clipBehavior) => clipBehavior == Clip.none,
      _ => false,
    };
    final clip = ignored ? null : layer.describeClipBounds();
    if (clip == null || !_affine(toHost)) {
      return this;
    }
    final transformed = MatrixUtils.transformRect(toHost, clip);
    if (!transformed.isFinite) return this;
    final expanded = transformed.inflate(margin);
    return _GlassVisibility(bounds?.intersect(expanded) ?? expanded, margin);
  }

  bool shows(Rect rect, Matrix4 toHost) {
    var enabled = true;
    assert(() {
      enabled = !LiquidGlassHost.debugDisableVisibilityCulling;
      return true;
    }());
    if (!enabled) return true;
    if (transparent) return false;
    final clip = bounds;
    if (clip == null || !_affine(toHost)) {
      return true;
    }
    final transformed = MatrixUtils.transformRect(toHost, rect);
    return !transformed.isFinite || clip.overlaps(transformed.inflate(margin));
  }
}
