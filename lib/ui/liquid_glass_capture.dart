part of 'liquid_glass.dart';

// A capture shrinks while a sigma of its frost still spans two of its own
// pixels; at one, where g1455 records its regular finish, text under dark glass
// shows blocks. g1455 measured no divisor past _maxDivisor.
const double _captureFrost = 2;
const int _maxDivisor = 8;
const double _maxCaptureSide = 2048;
// The blur of a downscaled capture read linearly, in its own pixels.
const double _captureSoftness = 0.5;
// How far content may paint past the bounds its layer was recorded for.
const double _overflow = 64;
// Impeller keeps the targets of layers and of their blurs by size for a few
// frames: a layer resized as content moves would miss them every frame.
const double _atlasStep = 32;
// Past this many sigmas of its frost a pane takes under 1% of what it reads, so
// one painted there does not raise it a level.
const double _levelReach = 2.5;
const double _heldFrostShare = 0.9;
const double _coveredPixelsPerDp = 0.5;
const double _maxCoveredSide = 1024;
// Offsets into a pane's mapping, in the order of the shader's uniforms.
const _hazeSample = 10;
const _hazeShare = 18;
const _noHaze = <double>[0, 0, 1, 1, 0, 0, 0, 0];

typedef LiquidGlassCapture = ({
  int snapshots,
  int keptBands,
  int occludedPanes,
  int clippedPanes,
  int replayedPanes,
  int atlasPixels,
  int coveredRecordings,
  int skippedPanes,
  List<
    ({
      Rect pane,
      Rect slot,
      Rect place,
      double scale,
      int level,
      bool wallpaper,
      ui.Image atlas,
    })
  >
  panes,
});

/// Draws the panes below from one capture of what lies behind them, taken as
/// the frame is composited, in place of each pane reading the backdrop
/// (.agents/architecture.md). Only one may enclose a pane.
class LiquidGlassHost extends StatelessWidget {
  const LiquidGlassHost({
    super.key,
    required this.enabled,
    this.wallpaperOnly = false,
    required this.child,
  });

  final bool enabled;

  /// Samples the shared color field, except panes opting into content.
  final bool wallpaperOnly;
  final Widget child;

  @visibleForTesting
  static LiquidGlassCapture? debugLastCapture;
  @visibleForTesting
  static List<({Rect pane, double blur})> debugLastBodyCapture = const [];
  @visibleForTesting
  static ({int snapshots, int kept, int pixels, int groups})?
  debugLastFlatCapture;

  @visibleForTesting
  static LiquidGlassInkCapture? debugLastInkCapture;

  @visibleForTesting
  static bool debugDisableVisibilityCulling = false;

  @visibleForTesting
  static ({int panes, int stripPixels, int surfacePixels})?
  debugLastFeatherCapture;

  @visibleForTesting
  static bool? debugCarryBandsOverride;

  @visibleForTesting
  static bool? debugSourceBlurEnabledOverride;

  @visibleForTesting
  static int? debugSourceBlurBudgetOverride;

  @visibleForTesting
  static SourceBlurCapture? debugLastSourceBlurCapture;

  @visibleForTesting
  static int get debugLiveSourceBlurPixels => _liveSourceBlurPixels;

  @visibleForTesting
  static ({
    String reason,
    int releasedAtlasPixels,
    int releasedFlatPixels,
    int releasedSourceBlurPixels,
    int retainedAtlasPixels,
    int retainedFlatPixels,
    int retainedFrozenPixels,
  })?
  debugLastCacheTrim;

  @override
  Widget build(BuildContext context) {
    return _LiquidGlassHostBox(
      enabled: enabled,
      wallpaperOnly: wallpaperOnly,
      devicePixelRatio: MediaQuery.devicePixelRatioOf(context),
      quality: LiquidGlassScope.qualityOf(context),
      child: child,
    );
  }
}

/// Fades [child] as [FadeTransition] does, but as Core Animation fades a layer:
/// the panes within still bend what lies behind it, and fade with the rest.
class LiquidGlassFade extends SingleChildRenderObjectWidget {
  const LiquidGlassFade({super.key, required this.opacity, super.child});

  final Animation<double> opacity;

  /// [PaintingContext.pushOpacity] for a render object, fading as this does.
  static void push(
    PaintingContext context,
    Offset offset,
    int alpha,
    PaintingContextCallback painter,
  ) {
    context.pushLayer(
      _LiquidGlassFadeLayer()
        ..alpha = alpha
        ..offset = offset,
      painter,
      Offset.zero,
    );
  }

  @override
  RenderObject createRenderObject(BuildContext context) {
    return _RenderLiquidGlassFade(opacity);
  }

  @override
  void updateRenderObject(BuildContext context, RenderObject renderObject) {
    (renderObject as _RenderLiquidGlassFade).opacity = opacity;
  }
}

class _RenderLiquidGlassFade extends RenderProxyBox {
  _RenderLiquidGlassFade(this._opacity);

  final _layer = LayerHandle<_LiquidGlassFadeLayer>();
  var _visible = true;

  Animation<double> _opacity;
  set opacity(Animation<double> value) {
    if (value == _opacity) {
      return;
    }
    if (attached) {
      _opacity.removeListener(_update);
      value.addListener(_update);
    }
    _opacity = value;
    _update();
  }

  void _update() {
    final visible = _alpha != 0;
    if (visible != _visible) {
      _visible = visible;
      markNeedsSemanticsUpdate();
    }
    markNeedsPaint();
  }

  int get _alpha =>
      ui.Color.getAlphaFromOpacity(_opacity.value.clamp(0.0, 1.0));

  @override
  bool get alwaysNeedsCompositing => child != null;

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _opacity.addListener(_update);
    _update();
  }

  @override
  void detach() {
    _opacity.removeListener(_update);
    super.detach();
  }

  @override
  void dispose() {
    _layer.layer = null;
    super.dispose();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final alpha = _alpha;
    if (child == null || alpha == 0) {
      _layer.layer = null;
      return;
    }
    final layer = _layer.layer ??= _LiquidGlassFadeLayer();
    layer
      ..alpha = alpha
      ..offset = offset;
    context.pushLayer(layer, super.paint, Offset.zero);
  }

  @override
  void visitChildrenForSemantics(RenderObjectVisitor visitor) {
    if (_visible) {
      super.visitChildrenForSemantics(visitor);
    }
  }
}

class _LiquidGlassFadeLayer extends OpacityLayer {}

class _LiquidGlassHostBox extends SingleChildRenderObjectWidget {
  const _LiquidGlassHostBox({
    required this.enabled,
    required this.devicePixelRatio,
    required this.quality,
    required this.wallpaperOnly,
    super.child,
  });

  final bool enabled;
  final double devicePixelRatio;
  final LiquidGlassQuality quality;
  final bool wallpaperOnly;

  @override
  _RenderLiquidGlassHost createRenderObject(BuildContext context) {
    return _RenderLiquidGlassHost(
      enabled: enabled,
      devicePixelRatio: devicePixelRatio,
      quality: quality,
      wallpaperOnly: wallpaperOnly,
    );
  }

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderLiquidGlassHost renderObject,
  ) {
    renderObject
      ..enabled = enabled
      ..devicePixelRatio = devicePixelRatio
      ..quality = quality
      ..wallpaperOnly = wallpaperOnly;
  }
}

class _RenderLiquidGlassHost extends RenderProxyBox
    with WidgetsBindingObserver {
  _RenderLiquidGlassHost({
    required bool enabled,
    required double devicePixelRatio,
    required LiquidGlassQuality quality,
    required bool wallpaperOnly,
  }) : _enabled = enabled,
       _devicePixelRatio = devicePixelRatio,
       _quality = quality,
       _wallpaperOnly = wallpaperOnly;

  final _layer = LayerHandle<_LiquidGlassHostLayer>();

  bool _enabled;
  bool _suspended = false;
  bool _wallpaperOnly;
  set wallpaperOnly(bool value) {
    if (_wallpaperOnly == value) return;
    _wallpaperOnly = value;
    markNeedsPaint();
  }

  set enabled(bool value) {
    if (value == _enabled) {
      return;
    }
    _enabled = value;
    markNeedsCompositingBitsUpdate();
    markNeedsPaint();
  }

  double _devicePixelRatio;
  set devicePixelRatio(double value) {
    if (value == _devicePixelRatio) {
      return;
    }
    _devicePixelRatio = value;
    markNeedsPaint();
  }

  LiquidGlassQuality _quality;
  set quality(LiquidGlassQuality value) {
    if (value == _quality) {
      return;
    }
    _quality = value;
    markNeedsPaint();
  }

  @override
  bool get alwaysNeedsCompositing => _enabled;

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    WidgetsBinding.instance.addObserver(this);
    final state = WidgetsBinding.instance.lifecycleState;
    if (state != null) didChangeAppLifecycleState(state);
  }

  @override
  void detach() {
    WidgetsBinding.instance.removeObserver(this);
    super.detach();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.hidden ||
        state == AppLifecycleState.paused) {
      _suspended = true;
      final layer = _layer.layer;
      if (layer != null) {
        layer.suspended = true;
        layer._trimCaches(all: true, reason: state.name);
      }
    } else if (state == AppLifecycleState.resumed ||
        state == AppLifecycleState.inactive) {
      _suspended = false;
      _layer.layer?.suspended = false;
      markNeedsPaint();
    }
  }

  @override
  void didHaveMemoryPressure() {
    final state = WidgetsBinding.instance.lifecycleState;
    final hidden =
        state == AppLifecycleState.hidden || state == AppLifecycleState.paused;
    _layer.layer?._trimCaches(
      all: hidden,
      preserveModal: !hidden,
      reason: 'pressure',
    );
    if (!hidden) markNeedsPaint();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    if (!_enabled) {
      _release();
      super.paint(context, offset);
      return;
    }
    final layer = _layer.layer ??= _LiquidGlassHostLayer();
    layer
      ..devicePixelRatio = _devicePixelRatio
      ..quality = _quality
      ..wallpaperOnly = _wallpaperOnly
      ..suspended = _suspended
      ..bounds = offset & size;
    context.pushLayer(layer, super.paint, offset);
  }

  // A pane captured on a pass still current would keep drawing from it.
  void _release() {
    if (_layer.layer != null) {
      _layer.layer = null;
      _LiquidGlassHostLayer._pass++;
    }
  }

  @override
  void dispose() {
    _release();
    super.dispose();
  }
}

class _LiquidGlassHostLayer extends ContainerLayer {
  static int _pass = 0;

  double devicePixelRatio = 1;
  LiquidGlassQuality quality = LiquidGlassQuality.ultra;
  Rect bounds = Rect.zero;
  bool wallpaperOnly = false;
  bool _suspended = false;
  set suspended(bool value) {
    if (_suspended == value) return;
    _suspended = value;
    markNeedsAddToScene();
  }

  (_ColorFieldLayer, Matrix4)? _field;

  final _atlases = <({ui.Image image, List<_Band> bands})>[];
  int _replayedPanes = 0;
  final _flatSnapshots = <_LiquidGlassOcclusionLayer, _FlatSnapshot>{};
  final _sourceBlur = _SourceBlurCache();
  bool _trimBeforeCapture = false;
  int _skippedPanes = 0;

  void _trimCaches({
    required bool all,
    bool preserveModal = false,
    required String reason,
  }) {
    final atlasPixels = _atlases.fold<int>(
      0,
      (pixels, atlas) => pixels + atlas.image.width * atlas.image.height,
    );
    final flatPixels = _flatSnapshots.values.fold<int>(
      0,
      (pixels, cached) => pixels + cached.image.width * cached.image.height,
    );
    final blurPixels = _sourceBlur.pixels;
    var frozenPixels = 0;
    void visit(ContainerLayer layer) {
      for (
        Layer? child = layer.firstChild;
        child != null;
        child = child.nextSibling
      ) {
        if (child is _LiquidGlassLayer) {
          if (all) child._trimCapture(preserveFrozen: preserveModal);
          final frozen = child._frozenImage;
          if (frozen != null) frozenPixels += frozen.width * frozen.height;
        }
        if (child is ContainerLayer) visit(child);
      }
    }

    visit(this);
    if (all) {
      for (final atlas in _atlases) {
        atlas.image.dispose();
      }
      _atlases.clear();
    }
    if (!preserveModal) {
      for (final cached in _flatSnapshots.values) {
        cached.image.dispose();
      }
      _flatSnapshots.clear();
    }
    _sourceBlur.dispose();
    _trimBeforeCapture = !all;
    markNeedsAddToScene();
    assert(() {
      LiquidGlassHost.debugLastCacheTrim = (
        reason: reason,
        releasedAtlasPixels: all ? atlasPixels : 0,
        releasedFlatPixels: preserveModal ? 0 : flatPixels,
        releasedSourceBlurPixels: blurPixels,
        retainedAtlasPixels: all ? 0 : atlasPixels,
        retainedFlatPixels: preserveModal ? flatPixels : 0,
        retainedFrozenPixels: frozenPixels,
      );
      if (all) LiquidGlassHost.debugLastCapture = null;
      return true;
    }());
  }

  @override
  void addToScene(ui.SceneBuilder builder) {
    if (!_suspended) {
      _capture();
      updateSubtreeNeedsAddToScene();
    }
    super.addToScene(builder);
  }

  @override
  void dispose() {
    for (final atlas in _atlases) {
      atlas.image.dispose();
    }
    _atlases.clear();
    for (final cached in _flatSnapshots.values) {
      cached.image.dispose();
    }
    _flatSnapshots.clear();
    _sourceBlur.dispose();
    super.dispose();
  }

  /// A pane over one painted before it in the same root sits a level above
  /// it, and reads a capture with that one drawn as glass.
  void _capture() {
    if (_trimBeforeCapture) {
      _trimCaches(all: true, preserveModal: true, reason: 'pressure-frame');
    }
    this._captureInk();
    _pass++;
    _replayedPanes = 0;
    _skippedPanes = 0;
    final found = <_Behind>[];
    final coverage = <_GlassCoverage>[];
    _field = null;
    _gather(
      this,
      Matrix4.identity(),
      this,
      Matrix4.identity(),
      [],
      found,
      coverage,
      _GlassVisibility(bounds, 1 / devicePixelRatio),
    );
    final viewport = wallpaperOnly ? _field : null;
    final source = viewport == null ? null : viewport.$1.source(viewport.$2);
    final snapshot = source?.$1 ?? viewport?.$1;
    final image = found.isEmpty ? null : snapshot?.capture();
    final _Wallpaper? wallpaper = image == null
        ? null
        : (
            image: image,
            size: snapshot!.size,
            toHost: source?.$2 ?? viewport!.$2,
            staticSource: source != null,
          );
    final placed = <_Behind>[];
    for (final (index, entry) in found.indexed) {
      if (!entry.pane.contentBackdrop) entry.wallpaper = wallpaper;
      if (entry.wallpaper == null) {
        entry.flatBoundary = null;
      } else if (entry.flatContent) {
        entry.root = this;
        entry.rootToHost = Matrix4.identity();
      }
      _occlude(entry, index, coverage);
      if (entry.visible != null && entry.needsFreeze) {
        if (entry.pane._freezeCaptured()) {
          if (entry.visibleBounds.isEmpty) {
            if (entry.plain == null) {
              entry.pane._captureFrozen(_pass);
            } else {
              final inverse = Matrix4.inverted(entry.toHost).storage;
              entry.pane._captureCovered(
                _pass,
                entry.style,
                entry.covered?.transform(inverse),
                entry.plain?.transform(inverse),
                entry.coverageKey,
                entry.feathers,
              );
            }
            continue;
          }
        } else {
          entry.freezeAfterCapture = true;
          entry.coveredVisible = entry.visible;
          entry.visible = null;
        }
      } else {
        entry.pane._thawCaptured();
      }
      if (entry.visible != null && entry.visibleBounds.isEmpty) {
        entry.pane._capturePlain(_pass, entry.style);
        continue;
      }
      if (_place(entry)) {
        placed.add(entry);
        if (entry.style.haze > 0) {
          final haze = _Behind.hazeOf(entry);
          if (_place(haze)) {
            entry.haze = haze;
          }
        }
      } else {
        entry.pane._captureNothing(_pass);
      }
    }
    _prepareFlatSnapshots(placed);
    _prepareSourceBlur(placed, wallpaper);
    var levels = 0;
    final contentLevel = placed.any((entry) => entry.wallpaper != null) ? 1 : 0;
    for (final (index, entry) in placed.indexed) {
      if (wallpaper != null) {
        entry.level = entry.wallpaper == null ? contentLevel : 0;
        levels = math.max(levels, entry.level + 1);
        continue;
      }
      for (final before in placed.take(index)) {
        if ((identical(entry.root, this) ||
                before.ancestors.contains(entry.root)) &&
            before.visibleBounds.overlaps(entry.reach)) {
          entry.level = math.max(entry.level, before.level + 1);
        }
      }
      levels = math.max(levels, entry.level + 1);
    }
    var snapshots = 0;
    var keptBands = 0;
    for (var level = 0; level < levels; level++) {
      final members = [
        for (final entry in placed)
          if (entry.level == level) entry,
      ];
      final (:atlas, :taken, :kept) = _snapshot(level, [
        for (final entry in members) ...[entry, ?entry.haze],
      ]);
      if (taken) {
        snapshots++;
        keptBands += kept;
      }
      for (final entry in members) {
        entry.atlas = atlas;
        _publish(entry, atlas);
      }
    }
    while (_atlases.length > levels) {
      _atlases.removeLast().image.dispose();
    }
    assert(() {
      var stripPixels = 0;
      var surfacePixels = 0;
      var featheredPanes = 0;
      for (final entry in placed) {
        if (entry.pane._featherBands.isEmpty) continue;
        featheredPanes++;
        for (final band in entry.pane._featherBands) {
          final hostRect = MatrixUtils.transformRect(
            entry.toHost,
            band.rect,
          ).intersect(bounds);
          if (hostRect.isEmpty) continue;
          final pixels =
              (hostRect.width * devicePixelRatio).ceil() *
              (hostRect.height * devicePixelRatio).ceil();
          stripPixels += pixels;
          surfacePixels += pixels * band.surfaces;
        }
      }
      LiquidGlassHost.debugLastFeatherCapture = (
        panes: featheredPanes,
        stripPixels: stripPixels,
        surfacePixels: surfacePixels,
      );
      LiquidGlassHost.debugLastBodyCapture = [
        for (final entry in placed)
          if (entry.pane._simpleBlur)
            (pane: entry.device, blur: entry.style.blur),
      ];
      LiquidGlassHost.debugLastCapture = (
        skippedPanes: _skippedPanes,
        snapshots: snapshots,
        keptBands: keptBands,
        occludedPanes: found
            .where(
              (entry) => entry.visible != null && entry.visibleBounds.isEmpty,
            )
            .length,
        clippedPanes: placed.where((entry) => entry.visible != null).length,
        replayedPanes: _replayedPanes,
        atlasPixels: _atlases.fold(
          0,
          (sum, atlas) => sum + atlas.image.width * atlas.image.height,
        ),
        coveredRecordings: found.fold(
          0,
          (sum, entry) => sum + entry.pane._coveredRecordings,
        ),
        panes: [
          for (final entry in placed)
            (
              pane: entry.device,
              slot: entry.slot,
              place: entry.place,
              scale: entry.texelsPerDp,
              level: entry.level,
              wallpaper: entry.wallpaper != null,
              atlas: entry.atlas!,
            ),
        ],
      );
      return true;
    }());
  }

  void _gather(
    ContainerLayer layer,
    Matrix4 toHost,
    ContainerLayer root,
    Matrix4 rootToHost,
    List<ContainerLayer> chain,
    List<_Behind> found,
    List<_GlassCoverage> coverage,
    _GlassVisibility visibility,
  ) {
    chain.add(layer);
    for (Layer? child = layer.firstChild; child != null;) {
      switch (child) {
        case final _LiquidGlassLayer pane:
          pane._setSimpleBlur(
            quality == LiquidGlassQuality.standard &&
                chain.any((layer) => layer is _GlassBodyLayer),
          );
          if (!visibility.shows(pane.rect, toHost)) {
            _skippedPanes++;
            if (pane._captured != null || pane._captureShader != null) {
              pane._trimCapture(preserveFrozen: true);
            }
            pane._capturedPass = _pass;
            break;
          }
          final boundary = chain
              .whereType<_LiquidGlassOcclusionLayer>()
              .lastOrNull;
          found.add(
            _Behind(
                pane,
                pane._simpleBlur
                    ? pane.style._bodyBlur
                    : switch (quality) {
                        LiquidGlassQuality.standard => pane.style._standard,
                        LiquidGlassQuality.ultra => pane.style,
                      },
                toHost,
                root,
                rootToHost,
                chain.toSet(),
              )
              ..flatBoundary =
                  wallpaperOnly &&
                      !pane.contentBackdrop &&
                      boundary?.captureContent == true
                  ? boundary
                  : null,
          );
        case final ContainerLayer container:
          final offscreen = _drawsOffscreen(container);
          final transform = _within(container, toHost);
          if (container is _ColorFieldLayer) _field = (container, transform);
          if (container is _LiquidGlassOcclusionLayer &&
              !chain.any(
                (layer) => layer is OpacityLayer && layer.alpha != 255,
              )) {
            coverage.add(_GlassCoverage(container, transform, found.length));
          }
          _gather(
            container,
            transform,
            offscreen ? container : root,
            offscreen ? toHost : rootToHost,
            chain,
            found,
            coverage,
            visibility.through(container, transform),
          );
      }
      child = child.nextSibling;
    }
    chain.removeLast();
  }

  void _occlude(_Behind entry, int index, List<_GlassCoverage> coverage) {
    Path? visible;
    for (final cover in [
      ...coverage.where(
        (cover) => cover.layer.mode == LiquidGlassOcclusionMode.freeze,
      ),
      ...coverage.where(
        (cover) => cover.layer.mode == LiquidGlassOcclusionMode.plain,
      ),
    ]) {
      final plain = cover.layer.mode == LiquidGlassOcclusionMode.plain;
      final feather = cover.feather;
      if (index >= cover.before ||
          entry.ancestors.contains(cover.layer) ||
          (!entry.visibleBounds.overlaps(cover.bounds) &&
              !(feather?.path.getBounds().overlaps(entry.visibleBounds) ??
                  false))) {
        continue;
      }
      visible ??=
          (entry.pane.outline ??
                  (Path()..addRSuperellipse(
                    entry.pane.corners.toRSuperellipse(entry.pane.rect),
                  )))
              .transform(entry.toHost.storage);
      final overlap = Path.combine(
        PathOperation.intersect,
        visible,
        cover.path,
      );
      final inBand = feather == null
          ? null
          : Path.combine(PathOperation.intersect, visible, feather.path);
      if (overlap.getBounds().isEmpty && (inBand?.getBounds().isEmpty ?? true))
        continue;
      if (!plain) entry.needsFreeze = true;
      if (!overlap.getBounds().isEmpty) {
        visible = Path.combine(PathOperation.difference, visible, cover.path);
        final previous = plain ? entry.plain : entry.covered;
        final combined = Path.combine(
          PathOperation.union,
          previous ?? Path(),
          cover.path,
        );
        if (plain) {
          entry.plain = combined;
        } else {
          entry.covered = combined;
        }
      }
      if (inBand != null && !inBand.getBounds().isEmpty) {
        final inverse = Matrix4.tryInvert(entry.toHost);
        if (inverse != null) {
          entry.feathers.add(
            _GlassFeather(
              cover.layer.geometry!.$3,
              cover.layer.geometry!.$4,
              inverse..multiply(cover.toHost),
              plain: plain,
            ),
          );
        }
      }
      entry.visible = visible;
      entry.coverageKey.addAll([
        cover.layer.path,
        cover.layer.geometry!.$4,
        cover.layer.mode,
        ...cover.toHost.storage,
      ]);
      if (visible.getBounds().isEmpty) break;
    }
    if (entry.visible != null) entry.coverageKey.addAll(entry.toHost.storage);
  }

  /// Snaps the slot to the capture's pixels, so that content holding still
  /// lands on the same ones while the pane moves. A pane with a hold keeps
  /// its slot while it fits inside at no more frost, nor much less, as a
  /// spring settling past its card would need, rather than glass fading.
  bool _place(_Behind entry) {
    final pane = entry.pane;
    final style = entry.style;
    final hazed = entry.hazed;
    final blur = hazed ? style.hazeBlur : style.blur;
    final storage = entry.toHost.storage;
    final stretch = math.max(storage[0].abs(), storage[5].abs());
    final device = entry.visibleBounds;
    if (stretch == 0 || device.isEmpty || !device.overlaps(bounds)) {
      pane._held = null;
      return false;
    }
    final hold = hazed || entry.visible != null ? null : pane.hold;
    final sweep = hold == null
        ? device
        : device.expandToInclude(MatrixUtils.transformRect(entry.toHost, hold));
    final bend = math.max(0.0, -style.refraction) + style.dispersion;
    final reach = (3 * (blur + style.rimBlur) + bend + 2) * stretch;
    entry.reach = sweep.inflate(
      (_levelReach * (blur + style.rimBlur) + bend) * stretch,
    );
    final needed = device.inflate(reach).intersect(bounds);
    final covered = sweep.inflate(reach).intersect(bounds);
    final longest = math.max(covered.width, covered.height) * devicePixelRatio;
    final divisor = math.max(
      math.min(
        _maxDivisor,
        math.max(
          1,
          (blur * stretch * devicePixelRatio / _captureFrost).floor(),
        ),
      ),
      (longest / _maxCaptureSide).ceil(),
    );
    final scale = devicePixelRatio / divisor;
    final frost = blur * stretch * scale;
    final softness = divisor > 1 ? _captureSoftness : 0.0;
    final blurSigma = frost > softness
        ? math.sqrt(frost * frost - softness * softness)
        : 0.0;
    final held = pane._held;
    final _Placement placement;
    if (hold != null &&
        held != null &&
        held.texelsPerDp == scale &&
        held.blurSigma >= blurSigma &&
        blurSigma >= held.blurSigma * _heldFrostShare &&
        _encloses(held.slot, needed)) {
      placement = held;
    } else {
      final left = (covered.left * scale).floorToDouble();
      final top = (covered.top * scale).floorToDouble();
      final right = (covered.right * scale).ceilToDouble();
      final bottom = (covered.bottom * scale).ceilToDouble();
      placement = (
        slot: Rect.fromLTRB(
          left / scale,
          top / scale,
          right / scale,
          bottom / scale,
        ),
        texels: Size(right - left, bottom - top),
        texelsPerDp: scale,
        heldFrost: math.max(frost, softness),
        blurSigma: blurSigma,
      );
    }
    if (!hazed) {
      pane._held = hold == null ? null : placement;
    }
    entry
      ..texelsPerDp = placement.texelsPerDp
      ..heldFrost = placement.heldFrost
      ..blurSigma = placement.blurSigma
      ..slot = placement.slot
      ..texels = placement.texels;
    return true;
  }

  void _prepareFlatSnapshots(List<_Behind> entries) {
    final groups = <_LiquidGlassOcclusionLayer, List<_Behind>>{};
    for (final entry in entries) {
      if (entry.flatBoundary case final boundary?
          when entry.wallpaper != null) {
        (groups[boundary] ??= []).add(entry);
      }
    }
    var snapshots = 0;
    var kept = 0;
    for (final group in groups.entries) {
      final members = group.value;
      final requested = members
          .map(
            (entry) => entry.haze == null
                ? entry.slot
                : entry.slot.expandToInclude(entry.haze!.slot),
          )
          .reduce((a, b) => a.expandToInclude(b));
      final previous = _flatSnapshots[group.key];
      final area = previous != null && _encloses(previous.bounds, requested)
          ? previous.bounds
          : requested;
      final scale = math.min(
        _coveredPixelsPerDp,
        _maxCoveredSide / area.longestSide,
      );
      final key = <Object?>[area, scale];
      final recorder = ui.PictureRecorder();
      final canvas = Canvas(recorder)
        ..scale(scale)
        ..translate(-area.left, -area.top);
      final entry = members.first;
      entry.flatCaptureBounds = area;
      entry.flatUI = true;
      _Replay(canvas, key, entry).run();
      entry.flatUI = false;
      entry.flatCaptureBounds = null;
      final picture = recorder.endRecording();
      final _FlatSnapshot cached;
      if (previous != null && listEquals(previous.key, key)) {
        picture.dispose();
        cached = previous;
        kept++;
      } else {
        final image = picture.toImageSync(
          math.max(1, (area.width * scale).ceil()),
          math.max(1, (area.height * scale).ceil()),
        );
        picture.dispose();
        previous?.image.dispose();
        cached = (image: image, bounds: area, key: key);
        _flatSnapshots[group.key] = cached;
        snapshots++;
      }
      for (final entry in members) {
        entry.flatSnapshot = cached;
        entry.haze?.flatSnapshot = cached;
      }
    }
    for (final boundary in _flatSnapshots.keys.toList()) {
      if (!groups.containsKey(boundary)) {
        _flatSnapshots.remove(boundary)!.image.dispose();
      }
    }
    assert(() {
      LiquidGlassHost.debugLastFlatCapture = (
        snapshots: snapshots,
        kept: kept,
        groups: groups.length,
        pixels: _flatSnapshots.values.fold(
          0,
          (sum, cached) => sum + cached.image.width * cached.image.height,
        ),
      );
      return true;
    }());
  }

  ({ui.Image atlas, bool taken, int kept}) _snapshot(
    int level,
    List<_Behind> members,
  ) {
    // Slots that blur alike lie together, under one layer that blurs them;
    // one that does not blur lies alone, so it is kept or drawn on its own.
    final classes = <Object, List<_Behind>>{};
    for (final entry in members) {
      (classes[entry.sourceBlur != null && entry.blurSigma > 0
                  ? _sourceBlurredBand
                  : entry.blurSigma == 0
                  ? entry
                  : entry.blurSigma] ??=
              [])
          .add(entry);
    }
    double step(double extent) => (extent / _atlasStep).ceil() * _atlasStep;
    final bands = <_Band>[];
    final pictures = <ui.Picture>[];
    var top = 0.0;
    for (final entries in classes.values) {
      var bottom = 0.0;
      var width = 0.0;
      for (final entry in entries) {
        entry.place = Offset(0, top + bottom) & entry.texels;
        bottom += entry.texels.height;
        width = math.max(width, entry.texels.width);
      }
      final rect = Rect.fromLTWH(0, top, step(width), step(bottom));
      final sigma = entries.first.sourceBlur == null
          ? entries.first.blurSigma
          : 0.0;
      final drawn = <Object?>[sigma, rect.size];
      final recorder = ui.PictureRecorder();
      final canvas = Canvas(recorder);
      for (final entry in entries) {
        final place = entry.place.shift(-rect.topLeft);
        drawn
          ..add(place)
          ..add(entry.slot)
          ..addAll(entry.rootToHost.storage);
        canvas
          ..save()
          ..clipRect(place)
          ..translate(place.left, place.top)
          ..scale(entry.texelsPerDp)
          ..translate(-entry.slot.left, -entry.slot.top);
        if (entry.wallpaper case final wallpaper?) {
          final image = entry.sourceBlur ?? wallpaper.image;
          drawn.addAll([image, wallpaper.size, ...wallpaper.toHost.storage]);
          canvas
            ..save()
            ..transform(wallpaper.toHost.storage)
            ..drawImageRect(
              image,
              Rect.fromLTWH(
                0,
                0,
                image.width.toDouble(),
                image.height.toDouble(),
              ),
              Offset.zero & wallpaper.size,
              Paint()..filterQuality = FilterQuality.low,
            )
            ..restore();
          if (entry.flatSnapshot case final cached?) {
            drawn.addAll([cached.image, cached.bounds]);
            canvas.drawImageRect(
              cached.image,
              Rect.fromLTWH(
                0,
                0,
                cached.image.width.toDouble(),
                cached.image.height.toDouble(),
              ),
              cached.bounds,
              Paint()..filterQuality = FilterQuality.low,
            );
          }
        } else {
          canvas.transform(entry.rootToHost.storage);
          final replay = _Replay(canvas, drawn, entry)..run();
          _replayedPanes += replay.panes;
        }
        canvas.restore();
      }
      bands.add((rect: rect, sigma: sigma, drawn: drawn));
      pictures.add(recorder.endRecording());
      top = rect.bottom;
    }
    final size = Size(
      bands.fold(0.0, (width, band) => math.max(width, band.rect.width)),
      top,
    );
    final previous = level < _atlases.length ? _atlases[level] : null;
    final last = previous?.bands ?? const <_Band>[];
    if (previous != null &&
        previous.image.width == size.width &&
        previous.image.height == size.height &&
        last.length == bands.length &&
        bands.indexed.every(
          (band) =>
              band.$2.rect == last[band.$1].rect &&
              listEquals(band.$2.drawn, last[band.$1].drawn),
        )) {
      for (final picture in pictures) {
        picture.dispose();
      }
      return (atlas: previous.image, taken: false, kept: 0);
    }
    // Skia keeps what an image was drawn from for as long as the image lives,
    // so a capture copied from the last would hold every one before it.
    final carry =
        LiquidGlassHost.debugCarryBandsOverride ??
        ui.ImageFilter.isShaderFilterSupported;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    var kept = 0;
    for (final (index, band) in bands.indexed) {
      final picture = pictures[index];
      final old = carry
          ? last.where((old) => listEquals(old.drawn, band.drawn)).firstOrNull
          : null;
      if (old != null) {
        canvas.drawImageRect(
          previous!.image,
          old.rect,
          band.rect,
          Paint()
            ..filterQuality = FilterQuality.none
            ..blendMode = BlendMode.src,
        );
        kept++;
      } else {
        final sigma = band.sigma;
        canvas
          ..save()
          ..clipRect(band.rect);
        if (sigma > 0) {
          canvas.saveLayer(
            band.rect,
            Paint()
              ..imageFilter = ui.ImageFilter.blur(
                sigmaX: sigma,
                sigmaY: sigma,
                tileMode: TileMode.clamp,
              ),
          );
        }
        // Impeller filters a layer under the transform current at its
        // restore (flutter-upgrade.md).
        canvas
          ..save()
          ..translate(band.rect.left, band.rect.top)
          ..drawPicture(picture)
          ..restore();
        if (sigma > 0) {
          canvas.restore();
        }
        canvas.restore();
      }
      picture.dispose();
    }
    final picture = recorder.endRecording();
    final image = picture.toImageSync(size.width.toInt(), size.height.toInt());
    picture.dispose();
    if (previous == null) {
      _atlases.add((image: image, bands: bands));
    } else {
      previous.image.dispose();
      _atlases[level] = (image: image, bands: bands);
    }
    return (atlas: image, taken: true, kept: kept);
  }

  void _publish(_Behind entry, ui.Image atlas) {
    final pane = entry.pane;
    final storage = entry.toHost.storage;
    final haze = entry.haze;
    final blendMode = entry.wallpaper != null || identical(entry.root, this)
        ? BlendMode.srcOver
        : BlendMode.src;
    pane._captureFrom(
      _pass,
      atlas,
      [
        ..._sampling(entry),
        1 / (devicePixelRatio * math.max(storage[0].abs(), storage[5].abs())),
        entry.heldFrost,
        ...haze == null ? _noHaze : _sampling(haze),
        if (haze == null) 0 else entry.style.haze,
      ],
      blendMode,
      entry.style,
      visible: entry.visible?.transform(Matrix4.inverted(entry.toHost).storage),
      coverageKey: entry.coverageKey,
      feathers: entry.feathers,
      covered: entry.covered?.transform(Matrix4.inverted(entry.toHost).storage),
      plain: entry.plain?.transform(Matrix4.inverted(entry.toHost).storage),
      prepareFreeze: entry.freezeAfterCapture,
    );
    if (entry.freezeAfterCapture && pane._freezeCaptured()) {
      if (entry.coveredVisible!.getBounds().isEmpty) {
        if (entry.plain == null) {
          pane._captureFrozen(_pass);
        } else {
          final inverse = Matrix4.inverted(entry.toHost).storage;
          pane._captureCovered(
            _pass,
            entry.style,
            entry.covered?.transform(inverse),
            entry.plain?.transform(inverse),
            entry.coverageKey,
            entry.feathers,
          );
        }
      }
    }
  }

  List<double> _sampling(_Behind slot) {
    final pane = slot.pane;
    final storage = slot.toHost.storage;
    final place = slot.place;
    final scale = slot.texelsPerDp;
    final texelsPerPaneX = storage[0] * scale;
    final texelsPerPaneY = storage[5] * scale;
    final originX =
        place.left +
        (storage[0] * pane.rect.left + storage[12] - slot.slot.left) * scale;
    final originY =
        place.top +
        (storage[5] * pane.rect.top + storage[13] - slot.slot.top) * scale;
    return [
      -originX / texelsPerPaneX,
      -originY / texelsPerPaneY,
      1 / texelsPerPaneX,
      1 / texelsPerPaneY,
      place.left + 0.5,
      place.top + 0.5,
      place.right - 0.5,
      place.bottom - 0.5,
    ];
  }
}

typedef _Placement = ({
  Rect slot,
  Size texels,
  double texelsPerDp,
  double heldFrost,
  double blurSigma,
});

typedef _Band = ({Rect rect, double sigma, List<Object?> drawn});
typedef _FlatSnapshot = ({ui.Image image, Rect bounds, List<Object?> key});

typedef _Wallpaper = ({
  ui.Image image,
  Size size,
  Matrix4 toHost,
  bool staticSource,
});

bool _encloses(Rect outer, Rect inner) =>
    outer.left <= inner.left &&
    outer.top <= inner.top &&
    outer.right >= inner.right &&
    outer.bottom >= inner.bottom;

class _Behind {
  _Behind(
    this.pane,
    this.style,
    this.toHost,
    this.root,
    this.rootToHost,
    this.ancestors,
  ) : hazed = false;

  _Behind.hazeOf(_Behind owner)
    : pane = owner.pane,
      style = owner.style,
      toHost = owner.toHost,
      root = owner.root,
      rootToHost = owner.rootToHost,
      ancestors = owner.ancestors,
      hazed = true,
      visible = owner.visible,
      wallpaper = owner.wallpaper,
      flatBoundary = owner.flatBoundary,
      flatSnapshot = owner.flatSnapshot;

  final _LiquidGlassLayer pane;
  final LiquidGlassStyle style;
  final Matrix4 toHost;

  /// The host, or below it the nearest layer that draws offscreen: a backdrop
  /// filter there would read only that layer's texture.
  ContainerLayer root;
  Matrix4 rootToHost;
  final Set<ContainerLayer> ancestors;

  /// Captures the backdrop again for the pane's [LiquidGlassStyle.haze].
  final bool hazed;

  late final Rect device = MatrixUtils.transformRect(toHost, pane.rect);
  Path? visible;
  _Wallpaper? wallpaper;
  ui.Image? sourceBlur;
  _LiquidGlassOcclusionLayer? flatBoundary;
  bool get flatContent => flatBoundary != null;
  _FlatSnapshot? flatSnapshot;
  bool flatUI = false;
  Rect? flatCaptureBounds;
  bool freezeAfterCapture = false;
  Path? coveredVisible;
  Path? covered;
  Path? plain;
  bool needsFreeze = false;
  final feathers = <_GlassFeather>[];
  Rect get visibleBounds => visible?.getBounds() ?? device;
  final coverageKey = <Object?>[];

  /// Where a pane painted before this one would show in what it reads.
  Rect reach = Rect.zero;
  Rect slot = Rect.zero;
  double texelsPerDp = 1;
  double heldFrost = 0;
  double blurSigma = 0;
  Size texels = Size.zero;
  Rect place = Rect.zero;
  int level = 0;
  ui.Image? atlas;
  _Behind? haze;
}

/// The transform a layer gives its children, from what it holds now:
/// [Layer.applyTransform] may answer from the frame before.
Matrix4? _geometry(ContainerLayer layer) {
  Matrix4 applied(Layer child) {
    final matrix = Matrix4.identity();
    layer.applyTransform(child, matrix);
    return matrix;
  }

  return switch (layer) {
    TransformLayer(:final transform?, :final offset) =>
      offset == Offset.zero
          ? transform
          : (Matrix4.translationValues(offset.dx, offset.dy, 0)
              ..multiply(transform)),
    OffsetLayer(:final offset) || LeaderLayer(:final offset) =>
      offset == Offset.zero
          ? null
          : Matrix4.translationValues(offset.dx, offset.dy, 0),
    FollowerLayer(:final firstChild?) => applied(firstChild),
    _ => null,
  };
}

Matrix4 _within(ContainerLayer layer, Matrix4 toHost) {
  final geometry = _geometry(layer);
  return geometry == null ? toHost : (toHost.clone()..multiply(geometry));
}

bool _drawsOffscreen(Layer layer) {
  return switch (layer) {
    _LiquidGlassFadeLayer() || ImageFilterLayer() => false,
    ClipRectLayer(:final clipBehavior) ||
    ClipRRectLayer(:final clipBehavior) ||
    ClipRSuperellipseLayer(:final clipBehavior) ||
    ClipPathLayer(
      :final clipBehavior,
    ) => clipBehavior == Clip.antiAliasWithSaveLayer,
    OpacityLayer(:final alpha) => alpha != null && alpha < 255,
    ColorFilterLayer() ||
    ShaderMaskLayer() ||
    BackdropFilterLayer() ||
    LiquidGlassInkLayer() => true,
    _ => false,
  };
}

/// Redraws onto a capture what a pane's root holds below the pane, as the
/// engine composites it, but for the root's own effect, which the engine
/// applies to the glass as well.
class _Replay {
  _Replay(this.canvas, this.drawn, this.entry) : _toHost = [entry.rootToHost];

  final Canvas canvas;
  final List<Object?> drawn;
  final _Behind entry;
  final List<Matrix4> _toHost;
  int panes = 0;

  // A layer's effects are applied only once something under it draws.
  final _entered = <(ContainerLayer, Matrix4?, bool)>[];
  final _saves = <int>[];

  void run() => _walk(entry.root, root: true);

  bool _walk(ContainerLayer layer, {required bool root}) {
    if (entry.flatContent && layer is _LiquidGlassDecorationLayer) return false;
    if (entry.flatUI && layer is _ColorFieldLayer) return false;
    if (!root && !entry.ancestors.contains(layer) && !_mayShow(layer)) {
      return false;
    }
    final geometry = _geometry(layer);
    final outer = _toHost.last;
    _toHost.add(geometry == null ? outer : (outer.clone()..multiply(geometry)));
    _entered.add((layer, geometry, root));
    if (layer case LiquidGlassBackdropLayer(
      :final backdropPicture?,
    ) when _shows(Offset.zero & layer.size)) {
      if (entry.flatUI) {
        while (_saves.length < _entered.length) {
          _saves.add(_apply(_entered[_saves.length]));
        }
        canvas.drawRect(
          Offset.zero & layer.size,
          Paint()..blendMode = BlendMode.clear,
        );
        drawn.addAll([layer, layer.size]);
      } else {
        _draw(backdropPicture);
      }
    }
    var reached = false;
    for (Layer? child = layer.firstChild; child != null && !reached;) {
      switch (child) {
        case _
            when identical(
              child,
              entry.flatUI ? entry.flatBoundary : entry.pane,
            ):
          reached = true;
        case final _LiquidGlassLayer pane
            when pane._capturedPass == _LiquidGlassHostLayer._pass:
          if (pane._captured case final picture?
              when !entry.flatContent && _shows(pane.rect)) {
            panes++;
            _draw(picture);
          }
        case final PictureLayer recorded:
          if (recorded.picture case final picture?
              when _shows(recorded.canvasBounds.inflate(_overflow))) {
            _draw(picture);
          }
        case final ContainerLayer container:
          reached = _walk(container, root: false);
      }
      child = child.nextSibling;
    }
    _leave();
    _toHost.removeLast();
    return reached;
  }

  bool _mayShow(ContainerLayer layer) {
    if (layer is OpacityLayer && layer.alpha == 0) {
      return false;
    }
    final clip = layer.describeClipBounds();
    return clip == null || _shows(clip);
  }

  bool _shows(Rect bounds) => MatrixUtils.transformRect(
    _toHost.last,
    bounds,
  ).overlaps(entry.flatCaptureBounds ?? entry.slot);

  void _draw(ui.Picture picture) {
    while (_saves.length < _entered.length) {
      _saves.add(_apply(_entered[_saves.length]));
    }
    canvas.drawPicture(picture);
    drawn.add(picture);
  }

  int _apply((ContainerLayer, Matrix4?, bool) entered) {
    final (layer, geometry, root) = entered;
    var saves = 0;
    if (geometry != null) {
      canvas
        ..save()
        ..transform(geometry.storage);
      drawn.addAll(geometry.storage);
      saves++;
    }
    final effect = root
        ? null
        : switch (layer) {
            OpacityLayer(:final alpha?) when alpha < 255 => (
              Paint()..color = Color.fromARGB(alpha, 0, 0, 0),
              alpha,
            ),
            ColorFilterLayer(:final colorFilter?) => (
              Paint()..colorFilter = colorFilter,
              colorFilter,
            ),
            LiquidGlassInkLayer(:final inkShader?) => (
              Paint(),
              (
                inkShader,
                layer._filteredField,
                layer._uniforms,
                layer.maskRect,
              ),
            ),
            ImageFilterLayer(:final imageFilter?)
                when !entry.ancestors.contains(layer) =>
              (Paint()..imageFilter = imageFilter, imageFilter),
            BackdropFilterLayer() => (Paint(), BlendMode.srcOver),
            ShaderMaskLayer(:final shader) => (Paint(), shader),
            _ => null,
          };
    if (effect case (final paint, final key)?) {
      canvas.saveLayer(null, paint);
      drawn
        ..add(layer.runtimeType)
        ..add(key);
      saves++;
      if (layer case LiquidGlassInkLayer(:final coveragePicture?)) {
        canvas.drawPicture(coveragePicture);
        drawn.add(coveragePicture);
      }
    }
    if (_clip(layer)) {
      saves++;
      final isolated = switch (layer) {
        ClipRectLayer(:final clipBehavior) ||
        ClipRRectLayer(:final clipBehavior) ||
        ClipRSuperellipseLayer(:final clipBehavior) ||
        ClipPathLayer(
          :final clipBehavior,
        ) => clipBehavior == Clip.antiAliasWithSaveLayer,
        _ => false,
      };
      if (!root && isolated) {
        canvas.saveLayer(null, Paint());
        drawn.add(Clip.antiAliasWithSaveLayer);
        saves++;
      }
    }
    return saves;
  }

  bool _clip(ContainerLayer layer) {
    final Object clip;
    switch (layer) {
      case ClipRectLayer(:final clipRect?, :final clipBehavior)
          when clipBehavior != Clip.none:
        canvas
          ..save()
          ..clipRect(clipRect, doAntiAlias: clipBehavior != Clip.hardEdge);
        clip = clipRect;
      case ClipRSuperellipseLayer(
            :final clipRSuperellipse?,
            :final clipBehavior,
          )
          when clipBehavior != Clip.none:
        canvas
          ..save()
          ..clipRSuperellipse(
            clipRSuperellipse,
            doAntiAlias: clipBehavior != Clip.hardEdge,
          );
        clip = clipRSuperellipse;
      case ClipPathLayer(:final clipPath?, :final clipBehavior)
          when clipBehavior != Clip.none:
        canvas
          ..save()
          ..clipPath(clipPath, doAntiAlias: clipBehavior != Clip.hardEdge);
        clip = clipPath;
      default:
        // Lib clips to no rounded rect, so one a package draws clips by its
        // bounds here.
        final bounds = layer.describeClipBounds();
        if (bounds == null) {
          return false;
        }
        canvas
          ..save()
          ..clipRect(bounds);
        clip = bounds;
    }
    drawn.add(clip);
    return true;
  }

  void _leave() {
    final (layer, _, root) = _entered.removeLast();
    if (_saves.length <= _entered.length) {
      return;
    }
    final saves = _saves.removeLast();
    if (layer case LiquidGlassInkLayer(:final inkShader?) when !root) {
      canvas.drawRect(
        layer.maskRect,
        Paint()
          ..shader = inkShader
          ..blendMode = BlendMode.srcATop,
      );
      drawn.add(layer.maskRect);
    }
    if (layer case ShaderMaskLayer(
      :final shader?,
      :final maskRect?,
      :final blendMode?,
    ) when !root) {
      canvas
        ..translate(maskRect.left, maskRect.top)
        ..drawRect(
          Offset.zero & maskRect.size,
          Paint()
            ..shader = shader
            ..blendMode = blendMode,
        );
      drawn
        ..add(maskRect)
        ..add(blendMode);
    }
    for (var index = 0; index < saves; index++) {
      canvas.restore();
    }
    drawn.add(saves);
  }
}
