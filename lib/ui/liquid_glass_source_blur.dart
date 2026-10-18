part of 'liquid_glass.dart';

const _sourceBlurBudget = 8 * 1024 * 1024;
const _sourceBlurredBand = Object();
int _liveSourceBlurPixels = 0;
typedef _SourceSigma = ({double x, double y});
typedef _SourceBlurImage = ({ui.Image image, int pixels, int used});

typedef SourceBlurCapture = ({
  int sourceBlurBuilds,
  int sourceBlurHits,
  int sourceBlurPixels,
  int fallbacks,
  int evictions,
  int generation,
  int entries,
  int pinned,
  int zeroSigma,
  Map<String, int> reasons,
});

class _SourceBlurCache {
  ui.Image? _source;
  Size _size = Size.zero;
  int _seen = 0;
  Duration? _seenFrame;
  int _clock = 0;
  int builds = 0;
  int hits = 0;
  int pixels = 0;
  int fallbacks = 0;
  int evictions = 0;
  int generation = 0;
  int zeroSigma = 0;
  final reasons = <String, int>{};
  final images = <_SourceSigma, _SourceBlurImage>{};
  final scales = <double>[];
  Set<_SourceSigma> pinned = {};

  int get budget => math.max(
    0,
    math.min(
      _sourceBlurBudget,
      LiquidGlassHost.debugSourceBlurBudgetOverride ?? _sourceBlurBudget,
    ),
  );

  void begin(_Wallpaper? source) {
    final image = source?.staticSource == true ? source!.image : null;
    final size = image == null ? Size.zero : source!.size;
    if (!identical(image, _source) || size != _size) {
      _release();
      _source = image;
      _size = size;
      _seen = 0;
      _seenFrame = null;
      generation++;
    }
    final scheduler = SchedulerBinding.instance;
    final frame = scheduler.schedulerPhase == SchedulerPhase.idle
        ? null
        : scheduler.currentFrameTimeStamp;
    if (image != null && frame != null && frame != _seenFrame) {
      _seen++;
      _seenFrame = frame;
    }
    pinned = {};
    while (pixels > budget) {
      _evict();
    }
  }

  bool get stable => _source != null && _seen >= 2;

  double canonicalScale(double value) {
    // Rotation arithmetic can change an unchanged scale by a few double ulps.
    for (final scale in scales) {
      if ((scale - value).abs() <=
          32 *
              2.220446049250313e-16 *
              math.max(1, math.max(scale.abs(), value.abs())))
        return scale;
    }
    if (scales.length == 64) scales.removeAt(0);
    scales.add(value);
    return value;
  }

  void fallback(String reason) {
    fallbacks++;
    reasons.update(reason, (value) => value + 1, ifAbsent: () => 1);
  }

  bool _evict() {
    _SourceSigma? oldest;
    var clock = _clock + 1;
    for (final entry in images.entries) {
      if (!pinned.contains(entry.key) && entry.value.used < clock) {
        oldest = entry.key;
        clock = entry.value.used;
      }
    }
    if (oldest == null) return false;
    final removed = images.remove(oldest)!;
    pixels -= removed.pixels;
    removed.image.dispose();
    assert(() {
      _liveSourceBlurPixels -= removed.pixels;
      return true;
    }());
    evictions++;
    return true;
  }

  ui.Image? read(_SourceSigma sigma) {
    if (images[sigma] case final previous?) {
      images[sigma] = (
        image: previous.image,
        pixels: previous.pixels,
        used: ++_clock,
      );
      hits++;
      return previous.image;
    }
    if (SchedulerBinding.instance.schedulerPhase == SchedulerPhase.idle) {
      fallback('idle');
      return null;
    }
    final source = _source!;
    final needed = source.width * source.height;
    if (needed > budget) {
      fallback('budget');
      return null;
    }
    while (pixels + needed > budget) {
      if (!_evict()) {
        fallback('budget');
        return null;
      }
    }
    final bounds = Rect.fromLTWH(
      0,
      0,
      source.width.toDouble(),
      source.height.toDouble(),
    );
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.saveLayer(
      bounds,
      Paint()
        ..imageFilter = ui.ImageFilter.blur(
          sigmaX: sigma.x,
          sigmaY: sigma.y,
          tileMode: TileMode.clamp,
        ),
    );
    canvas.drawImage(
      source,
      Offset.zero,
      Paint()..filterQuality = FilterQuality.none,
    );
    canvas.restore();
    final picture = recorder.endRecording();
    final image = picture.toImageSync(source.width, source.height);
    picture.dispose();
    images[sigma] = (image: image, pixels: needed, used: ++_clock);
    pixels += needed;
    assert(() {
      _liveSourceBlurPixels += needed;
      return true;
    }());
    builds++;
    return image;
  }

  void _release() {
    for (final entry in images.values) {
      entry.image.dispose();
    }
    images.clear();
    scales.clear();
    pinned = {};
    assert(() {
      _liveSourceBlurPixels -= pixels;
      return true;
    }());
    pixels = 0;
  }

  void dispose() {
    _release();
    _source = null;
  }
}

extension _SourceBlurCapture on _LiquidGlassHostLayer {
  void _prepareSourceBlur(List<_Behind> placed, _Wallpaper? wallpaper) {
    final cache = _sourceBlur;
    cache.begin(wallpaper);
    final plans = <(_Behind, _SourceSigma)>[];
    for (final owner in placed) {
      for (final entry in [owner, ?owner.haze]) {
        if (LiquidGlassHost.debugSourceBlurEnabledOverride == false) {
          cache.fallback('disabled');
          continue;
        }
        final source = entry.wallpaper;
        if (entry.pane.contentBackdrop) {
          cache.fallback('content');
          continue;
        }
        if (source == null || !source.staticSource) {
          cache.fallback('source');
          continue;
        }
        if (entry.flatSnapshot != null || entry.flatContent) {
          cache.fallback('flatUI');
          continue;
        }
        if (entry.style.haze > 0 || entry.style.rimBlur > 0) {
          cache.fallback('optics');
          continue;
        }
        if (!cache.stable) {
          cache.fallback('volatile');
          continue;
        }
        if (entry.blurSigma == 0) {
          entry.sourceBlur = source.image;
          cache.zeroSigma++;
          continue;
        }
        if (!ui.ImageFilter.isShaderFilterSupported) {
          cache.fallback('backend');
          continue;
        }
        final sigma = _sourceSigma(entry, source);
        if (sigma == null) continue;
        plans.add((entry, sigma));
      }
    }
    cache.pinned = {for (final (_, sigma) in plans) sigma};
    for (final (entry, sigma) in plans) {
      entry.sourceBlur = cache.read(sigma);
    }
    assert(() {
      LiquidGlassHost.debugLastSourceBlurCapture = (
        sourceBlurBuilds: cache.builds,
        sourceBlurHits: cache.hits,
        sourceBlurPixels: cache.pixels,
        fallbacks: cache.fallbacks,
        evictions: cache.evictions,
        generation: cache.generation,
        entries: cache.images.length,
        pinned: cache.pinned.length,
        zeroSigma: cache.zeroSigma,
        reasons: Map.unmodifiable(cache.reasons),
      );
      return true;
    }());
  }

  _SourceSigma? _sourceSigma(_Behind entry, _Wallpaper source) {
    final cache = _sourceBlur;
    final matrix = source.toHost.storage;
    if (matrix.any((value) => !value.isFinite) ||
        matrix[3] != 0 ||
        matrix[7] != 0 ||
        matrix[11] != 0 ||
        matrix[15] != 1 ||
        matrix[2] != 0 ||
        matrix[6] != 0 ||
        matrix[8] != 0 ||
        matrix[9] != 0 ||
        matrix[10] != 1 ||
        matrix[14] != 0) {
      cache.fallback('transform');
      return null;
    }
    final xx = matrix[0] * matrix[0] + matrix[1] * matrix[1];
    final yy = matrix[4] * matrix[4] + matrix[5] * matrix[5];
    final dot = matrix[0] * matrix[4] + matrix[1] * matrix[5];
    final tolerance = math.max(xx, yy) * 1e-12;
    if (xx <= 0 || (xx - yy).abs() > tolerance || dot.abs() > tolerance) {
      cache.fallback('transform');
      return null;
    }
    final scale = cache.canonicalScale(math.sqrt(xx));
    final x = source.size.width / source.image.width;
    final y = source.size.height / source.image.height;
    final sigma = (
      x: entry.blurSigma / (entry.texelsPerDp * scale * x),
      y: entry.blurSigma / (entry.texelsPerDp * scale * y),
    );
    if (!sigma.x.isFinite ||
        !sigma.y.isFinite ||
        sigma.x <= 0 ||
        sigma.y <= 0) {
      cache.fallback('transform');
      return null;
    }
    final paneScale = math.max(
      entry.toHost.storage[0].abs(),
      entry.toHost.storage[5].abs(),
    );
    final bend =
        math.max(0.0, -entry.style.refraction) + entry.style.dispersion;
    final samples = entry.visibleBounds
        .inflate((3 * entry.style.rimBlur + bend + 2) * paneScale)
        .intersect(bounds);
    final kernel = (3 * entry.blurSigma).ceil() / entry.texelsPerDp;
    if (!_encloses(entry.slot.deflate(kernel), samples)) {
      cache.fallback('edge');
      return null;
    }
    final inverse = Matrix4.tryInvert(source.toHost);
    if (inverse == null) {
      cache.fallback('transform');
      return null;
    }
    final logical = MatrixUtils.transformRect(inverse, samples);
    final pixels = Rect.fromLTRB(
      logical.left / x,
      logical.top / y,
      logical.right / x,
      logical.bottom / y,
    );
    final kernelX = (3 * sigma.x).ceil() + 1;
    final kernelY = (3 * sigma.y).ceil() + 1;
    if (!_encloses(
      Rect.fromLTRB(
        kernelX.toDouble(),
        kernelY.toDouble(),
        source.image.width - kernelX.toDouble(),
        source.image.height - kernelY.toDouble(),
      ),
      pixels,
    )) {
      cache.fallback('edge');
      return null;
    }
    return sigma;
  }
}
