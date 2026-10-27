import 'dart:math' as math;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'card.dart';
import 'outbound_motion.dart';

const _minSpeedScale = 8 * 1024.0;

bool _trafficVisible(BuildContext context, WidgetRef ref) =>
    ref.watch(appVisibleProvider) &&
    TickerMode.valuesOf(context).enabled &&
    PageActivityScope.isActiveOf(context) &&
    (ModalRoute.isCurrentOf(context) ?? true);

/// Holds a claim on the Core's once-a-second sample while its page, route
/// and ticker show it and it is [enabled]; a dashboard nobody sees asks the
/// Core for nothing.
class TrafficClaim extends ConsumerStatefulWidget {
  const TrafficClaim({
    super.key,
    this.need = TrafficNeed.traffic,
    this.enabled = true,
    required this.child,
  });

  final TrafficNeed need;
  final bool enabled;
  final Widget child;

  @override
  ConsumerState<TrafficClaim> createState() => _TrafficClaimState();
}

class _TrafficClaimState extends ConsumerState<TrafficClaim> {
  late final TrafficDemand _demand = ref.read(trafficDemandProvider.notifier);
  var _shown = false;
  var _scheduled = false;

  bool get _wanted => _shown && widget.enabled;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _shown =
        TickerMode.valuesOf(context).enabled &&
        PageActivityScope.isActiveOf(context) &&
        (ModalRoute.isCurrentOf(context) ?? true);
    _schedule();
  }

  @override
  void didUpdateWidget(TrafficClaim oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.need != widget.need || oldWidget.enabled != widget.enabled) {
      _schedule();
    }
  }

  // Riverpod forbids changing a provider while widgets build or unmount.
  void _schedule() {
    if (_scheduled) return;
    _scheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scheduled = false;
      if (!mounted) return;
      if (_wanted) {
        _demand.claim(this, widget.need);
      } else {
        _demand.release(this);
      }
    });
  }

  @override
  void dispose() {
    final demand = _demand;
    WidgetsBinding.instance.addPostFrameCallback((_) => demand.release(this));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// Keeps what was last shown while [live] is off. A shown list is a copy:
/// the source list grows in place, and a held view must not grow with it.
class _TrafficHold<T> extends StatefulWidget {
  const _TrafficHold({
    required this.value,
    required this.live,
    required this.freeze,
    required this.builder,
  });

  final T value;
  final bool live;
  final T Function(T value) freeze;
  final Widget Function(BuildContext context, T value) builder;

  @override
  State<_TrafficHold<T>> createState() => _TrafficHoldState<T>();
}

class _TrafficHoldState<T> extends State<_TrafficHold<T>> {
  late T _source = widget.value;
  late T _shown = widget.freeze(_source);

  @override
  void didUpdateWidget(_TrafficHold<T> oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.live && widget.value != _source) {
      _source = widget.value;
      _shown = widget.freeze(_source);
    }
  }

  @override
  Widget build(BuildContext context) => widget.builder(context, _shown);
}

typedef _TrafficWindow = ({
  List<Traffic> samples,
  int revision,
  int capacity,
  int epoch,
});

class _TrafficTrace extends StatefulWidget {
  const _TrafficTrace({
    required this.samples,
    required this.latest,
    required this.active,
    required this.builder,
  });

  final FixedList<Traffic> samples;

  /// The samples as they stand now, which [samples] may hold back.
  final FixedList<Traffic> latest;
  final bool active;
  final Widget Function(BuildContext context, _TrafficWindow window) builder;

  @override
  State<_TrafficTrace> createState() => _TrafficTraceState();
}

class _TrafficTraceState extends State<_TrafficTrace> {
  int? _afterRevision;
  var _epoch = 0;
  (FixedList<Traffic>, int?, int)? _key;
  late _TrafficWindow _window;

  @override
  void didUpdateWidget(_TrafficTrace oldWidget) {
    super.didUpdateWidget(oldWidget);
    final latest = widget.latest;
    final before = oldWidget.latest;
    // A list changed without growing was cleared; all it holds came after.
    // A sample arriving with the start belongs to the new run.
    final cleared = latest != before && latest.revision <= before.revision;
    if (cleared || (!oldWidget.active && widget.active)) {
      _afterRevision = cleared ? 0 : before.revision;
      _epoch++;
    }
  }

  /// One window per state of the samples, so a held view rebuilds nothing.
  _TrafficWindow _windowOf(FixedList<Traffic> source) {
    final key = (source, _afterRevision, _epoch);
    if (key == _key) return _window;
    _key = key;
    final list = source.list;
    final after = _afterRevision;
    final count = after == null
        ? list.length
        : (source.revision - after).clamp(0, list.length);
    return _window = (
      samples: list.skip(list.length - count).toList(),
      revision: source.revision,
      capacity: source.maxLength,
      epoch: _epoch,
    );
  }

  @override
  Widget build(BuildContext context) =>
      widget.builder(context, _windowOf(widget.samples));
}

class OutboundTrafficBackground extends ConsumerWidget {
  const OutboundTrafficBackground({super.key, required this.running});

  final bool running;

  /// Shared with the Lite edition; every peak scales into the band above.
  static const baselineRatio = 1.0;

  @override
  Widget build(BuildContext context, WidgetRef ref) => TrafficClaim(
    child: OutboundSampleGate(
      builder: (context, inView) => Consumer(
        builder: (context, ref, _) => _content(context, ref, inView),
      ),
    ),
  );

  Widget _content(BuildContext context, WidgetRef ref, bool inView) {
    final shown = _trafficVisible(context, ref);
    final latest = ref.watch(trafficsProvider);
    final animate = shown && inView;
    return _TrafficHold(
      value: latest,
      live: animate,
      freeze: (samples) => samples.frozen(),
      builder: (context, samples) => _TrafficTrace(
        samples: samples,
        latest: latest,
        // Scrolling the card away holds the curve and catches up on return;
        // leaving the page stops the samples, so what follows starts over.
        active: shown && running,
        // Paced ahead of the swell, which then grows only what is drawn: a
        // window held back while it lifts would appear at full height.
        builder: (context, window) => InteractionPaced(
          revision: (
            window.revision,
            window.epoch,
            window.capacity,
            window.samples.length,
            running,
            animate,
          ),
          reuseUnchanged: true,
          builder: (context) => IgnorePointer(
            child: ExcludeSemantics(
              child: _TrafficSwell(
                window: window,
                raised: running,
                animate: animate,
                builder: _curves,
              ),
            ),
          ),
        ),
      ),
    );
  }

  static Widget _curves(
    BuildContext context,
    _TrafficWindow window,
    Animation<double> lift,
  ) {
    final scale = window.samples.fold<double>(
      _minSpeedScale,
      (peak, sample) => math.max(
        peak,
        math.max(sample.up.toDouble(), sample.down.toDouble()),
      ),
    );
    return _TrafficCurves(window: window, scale: scale, lift: lift);
  }
}

/// Grows a window's curve out of the flat zero line and settles it back; a
/// new window waits for the old to settle. Without [animate] changes land.
class _TrafficSwell extends StatefulWidget {
  const _TrafficSwell({
    required this.window,
    required this.raised,
    required this.animate,
    required this.builder,
  });

  final _TrafficWindow window;
  final bool raised;
  final bool animate;
  final Widget Function(
    BuildContext context,
    _TrafficWindow window,
    Animation<double> lift,
  )
  builder;

  @override
  State<_TrafficSwell> createState() => _TrafficSwellState();
}

class _TrafficSwellState extends State<_TrafficSwell>
    with SingleTickerProviderStateMixin {
  late _TrafficWindow _shown = widget.window;
  late final _lift = AnimationController(
    vsync: this,
    duration: fadeTabDuration,
    value: _standing ? 1 : 0,
  )..addStatusListener(_landed);
  late final _height = CurvedAnimation(parent: _lift, curve: fadeTabCurve);

  bool get _standing =>
      widget.raised &&
      widget.window.samples.isNotEmpty &&
      widget.window.epoch == _shown.epoch;

  bool get _animates =>
      widget.animate &&
      !MediaQuery.disableAnimationsOf(context) &&
      !MediaQuery.highContrastOf(context);

  void _sync({required bool animate}) {
    final latest = widget.window;
    if (!animate ||
        latest.epoch == _shown.epoch ||
        _lift.isDismissed ||
        _shown.samples.isEmpty) {
      _shown = latest;
    }
    final target = _standing ? 1.0 : 0.0;
    if (!animate) {
      _lift.value = target;
    } else if (target == 1) {
      if (!_lift.isCompleted && _lift.status != AnimationStatus.forward) {
        _lift.forward();
      }
    } else if (!_lift.isDismissed && _lift.status != AnimationStatus.reverse) {
      _lift.reverse();
    }
  }

  void _landed(AnimationStatus status) {
    if (status.isDismissed && mounted && widget.window.epoch != _shown.epoch) {
      setState(() => _sync(animate: _animates));
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_animates) _sync(animate: false);
  }

  @override
  void didUpdateWidget(_TrafficSwell oldWidget) {
    super.didUpdateWidget(oldWidget);
    _sync(animate: oldWidget.animate && _animates);
  }

  @override
  void dispose() {
    _height.dispose();
    _lift.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) =>
      widget.builder(context, _shown, _height);
}

/// On glass with Aurora's foreground colors both strokes take one ink.
class _TrafficCurves extends StatelessWidget {
  const _TrafficCurves({
    required this.window,
    required this.scale,
    required this.lift,
  });

  final _TrafficWindow window;
  final double scale;
  final Animation<double> lift;

  static const _opacity = 0.34;
  static const _highContrastOpacity = 0.12;
  static const _fillShare = 0.14;

  @override
  Widget build(BuildContext context) {
    final highContrast = MediaQuery.highContrastOf(context);
    final opacity = highContrast ? _highContrastOpacity : _opacity;
    final ink = context.colorScheme.onSurface;
    final inked =
        LiquidGlassScope.of(context) &&
        GlassForeground.colorsInk(context, ink, icon: true);
    LineChart chart(
      TrafficDirection direction,
      LineChartPart part, {
      Color? color,
    }) => LineChart(
      key: ValueKey((direction, part, window.epoch)),
      values: [
        for (final sample in window.samples) direction.of(sample).toDouble(),
      ],
      revision: window.revision,
      capacity: window.capacity,
      minScale: scale,
      baselineRatio: OutboundTrafficBackground.baselineRatio,
      color: color ?? direction.colorOf(context),
      part: part,
      strokeOpacity: opacity,
      fillOpacity: _fillShare * opacity,
      fillToBaseline: true,
      lift: lift,
    );
    final strokes = [
      for (final direction in TrafficDirection.values)
        chart(
          direction,
          LineChartPart.stroke,
          color: !inked
              ? null
              : direction == TrafficDirection.upload
              ? ink.withValues(alpha: ink.a * DashboardColors.series2Share)
              : ink,
        ),
    ];
    return Stack(
      fit: StackFit.expand,
      children: [
        for (final direction in TrafficDirection.values)
          chart(direction, LineChartPart.fill),
        if (inked)
          _CurveBand(
            baselineRatio: OutboundTrafficBackground.baselineRatio,
            child: GlassInk(
              source: ink,
              role: GlassInkRole.icon,
              child: _FullHeight(
                child: Stack(fit: StackFit.expand, children: strokes),
              ),
            ),
          )
        else
          ...strokes,
      ],
    );
  }
}

/// Holds the ink to the band above the zero line; the child keeps full height.
class _CurveBand extends SingleChildRenderObjectWidget {
  const _CurveBand({required this.baselineRatio, required super.child});

  final double baselineRatio;

  @override
  _RenderCurveBand createRenderObject(BuildContext context) =>
      _RenderCurveBand(baselineRatio);

  @override
  void updateRenderObject(BuildContext context, _RenderCurveBand renderObject) {
    renderObject.baselineRatio = baselineRatio;
  }
}

class _RenderCurveBand extends RenderProxyBox {
  _RenderCurveBand(this._baselineRatio);

  // A stroke centred on the zero line reaches half its width below it.
  static const _reach = 3.0;

  double _baselineRatio;
  set baselineRatio(double value) {
    if (value == _baselineRatio) return;
    _baselineRatio = value;
    markNeedsLayout();
  }

  @override
  void performLayout() {
    size = constraints.biggest;
    final band = math.min(size.height, size.height * _baselineRatio + _reach);
    child?.layout(
      BoxConstraints(
        minWidth: size.width,
        maxWidth: size.width,
        minHeight: band,
        maxHeight: size.height,
      ),
    );
  }
}

class _FullHeight extends SingleChildRenderObjectWidget {
  const _FullHeight({required super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderFullHeight();
}

class _RenderFullHeight extends RenderProxyBox {
  @override
  void performLayout() {
    final child = this.child;
    if (child == null) {
      size = constraints.smallest;
      return;
    }
    child.layout(
      constraints.tighten(height: constraints.maxHeight),
      parentUsesSize: true,
    );
    size = constraints.constrain(Size(child.size.width, constraints.minHeight));
  }
}

/// The text roles of the corner figures and the box each column takes, from
/// the widest label or template, never the value.
class _FigureMetrics {
  const _FigureMetrics._(
    this.speed,
    this.rowHeight,
    this.arrow,
    this.pixelRatio,
    this._key,
  );
  factory _FigureMetrics.of(BuildContext context, {_FigureMetrics? previous}) {
    final speed = AppTextStyle.custom(context, size: 10, leading: 10).toTabular;
    final scaler = MediaQuery.textScalerOf(context);
    final arrow = scaler.scale(10);
    final pixelRatio = MediaQuery.devicePixelRatioOf(context);
    final key = (
      speed,
      scaler,
      Localizations.maybeLocaleOf(context),
      Directionality.of(context),
      pixelRatio,
    );
    if (previous != null && previous._key == key) return previous;
    final extent = _extentOf(
      context,
      _digitTemplates(context, trafficRateTemplates, speed),
      speed,
    );
    return _FigureMetrics._(
      speed,
      math.max(extent.height, arrow).ceilToDouble(),
      arrow,
      pixelRatio,
      key,
    );
  }
  final TextStyle speed;
  final double rowHeight;
  final double arrow;
  final double pixelRatio;
  final Object _key;
}

/// [templates] written in the widest digit [style] draws: tabular digits
/// share one width, but a font without them may draw some digit wider than 9.
List<String> _digitTemplates(
  BuildContext context,
  List<String> templates,
  TextStyle style,
) {
  var digit = '9';
  var width = _extentOf(context, [digit], style).width;
  for (var value = 0; value < 9; value++) {
    final candidate = _extentOf(context, ['$value'], style).width;
    if (candidate > width) {
      width = candidate;
      digit = '$value';
    }
  }
  return [for (final template in templates) template.replaceAll('9', digit)];
}

/// The widest and tallest of [texts] at the context's text scale and locale.
Size _extentOf(BuildContext context, Iterable<String> texts, TextStyle style) {
  var extent = Size.zero;
  final resolved = DefaultTextStyle.of(context).style.merge(style);
  for (final text in texts) {
    final painter = TextPainter(
      text: TextSpan(text: text, style: resolved),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      locale: Localizations.maybeLocaleOf(context),
      maxLines: 1,
    )..layout();
    extent = Size(
      math.max(extent.width, painter.width),
      math.max(extent.height, painter.height),
    );
    painter.dispose();
  }
  return extent;
}

class OutboundTrafficFigures extends StatefulWidget {
  const OutboundTrafficFigures({super.key});
  // Preserve the existing status-content clearance independently of the smaller readings.
  static double heightOf(TextScaler scaler) =>
      (scaler.scale(10) + scaler.scale(12) + scaler.scale(10)).ceilToDouble();
  @override
  State<OutboundTrafficFigures> createState() => _OutboundTrafficFiguresState();
}

class _OutboundTrafficFiguresState extends State<OutboundTrafficFigures> {
  _FigureMetrics? _metrics;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _metrics = _FigureMetrics.of(context, previous: _metrics);
  }

  @override
  Widget build(BuildContext context) => TrafficClaim(
    child: OutboundSampleGate(
      builder: (context, inView) => Consumer(
        builder: (context, ref, _) => _content(context, ref, inView),
      ),
    ),
  );
  Widget _content(BuildContext context, WidgetRef ref, bool inView) =>
      _TrafficHold(
        value: ref.watch(trafficsProvider),
        live: inView && _trafficVisible(context, ref),
        freeze: (samples) => samples.frozen(),
        builder: _figures,
      );
  Widget _figures(BuildContext context, FixedList<Traffic> samples) {
    final metrics = _metrics!;
    return InteractionPaced(
      revision: (samples.revision, samples.length, metrics),
      reuseUnchanged: true,
      builder: (context) {
        final latest = samples.list.safeLast(const Traffic());
        final ink = context.colorScheme.onSurface;
        final rows = TrafficReadings(
          pixelRatio: metrics.pixelRatio,
          children: [
            for (final direction in TrafficDirection.values)
              DashboardStop(
                order: direction.index,
                child: Semantics(
                  label: direction == TrafficDirection.upload
                      ? context.appLocalizations.trafficUploadSpeed(
                          trafficRate(direction.of(latest)),
                        )
                      : context.appLocalizations.trafficDownloadSpeed(
                          trafficRate(direction.of(latest)),
                        ),
                  child: ExcludeSemantics(
                    child: SizedBox(
                      height: metrics.rowHeight,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        spacing: 3,
                        children: [
                          GlyphIcon(
                            direction == TrafficDirection.upload
                                ? AppGlyphs.arrowUp
                                : AppGlyphs.arrowDown,
                            size: metrics.arrow,
                            color: ink,
                          ),
                          GlassText(
                            trafficRate(direction.of(latest)),
                            textAlign: TextAlign.end,
                            softWrap: false,
                            style: metrics.speed.copyWith(color: ink),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
        return GlassInk(
          source: ink,
          role: GlassInkRole.small,
          child: LiquidGlassScope(
            enabled: LiquidGlassScope.of(context),
            foregroundEnabled: false,
            quality: LiquidGlassScope.qualityOf(context),
            child: rows,
          ),
        );
      },
    );
  }
}

class TrafficReadings extends MultiChildRenderObjectWidget {
  const TrafficReadings({
    super.key,
    this.pixelRatio = 1,
    required super.children,
  });

  final double pixelRatio;

  @override
  RenderTrafficReadings createRenderObject(BuildContext context) =>
      RenderTrafficReadings(pixelRatio);

  @override
  void updateRenderObject(
    BuildContext context,
    RenderTrafficReadings renderObject,
  ) {
    renderObject.pixelRatio = pixelRatio;
  }
}

class _ReadingParentData extends ContainerBoxParentData<RenderBox> {}

class RenderTrafficReadings extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _ReadingParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _ReadingParentData> {
  RenderTrafficReadings([this._pixelRatio = 1]);
  double _pixelRatio;
  set pixelRatio(double value) {
    if (value == _pixelRatio) return;
    _pixelRatio = value;
    markNeedsLayout();
  }

  double _gap = 0;
  double get firstRowHeight => _firstHeight;
  double _firstHeight = 0;
  List<Rect> get inkBounds => _inkBounds();

  List<Rect> inkBoundsWith(TextPainter Function(RenderParagraph) textLayout) =>
      _inkBounds(textLayout);

  List<Rect> _inkBounds([TextPainter Function(RenderParagraph)? textLayout]) {
    final bounds = <Rect>[];
    void visit(RenderObject node) {
      if (node is RenderParagraph) {
        for (final word in RegExp(
          r'\S+',
        ).allMatches(node.text.toPlainText(includeSemanticsLabels: false))) {
          final selection = TextSelection(
            baseOffset: word.start,
            extentOffset: word.end,
          );
          final boxes = textLayout == null
              ? node.getBoxesForSelection(selection)
              : textLayout(node).getBoxesForSelection(selection);
          for (final box in boxes) {
            bounds.add(
              MatrixUtils.transformRect(
                node.getTransformTo(this),
                box.toRect(),
              ),
            );
          }
        }
      } else if (node is RenderCustomPaint && node.painter is GlyphPainter) {
        bounds.add(
          MatrixUtils.transformRect(
            node.getTransformTo(this),
            (node.painter! as GlyphPainter).paintedBounds(
              node.size,
              pixelRatio: _pixelRatio,
            ),
          ),
        );
      }
      node.visitChildren(visit);
    }

    visit(this);
    return bounds;
  }

  ({Size size, List<Rect> ink})? projectedGeometry(
    double maxWidth,
    TextPainter Function(RenderParagraph) textLayout,
  ) {
    final rows = <({double width, double height, List<Rect> ink})>[];
    var width = 0.0;
    var height = 0.0;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      RenderParagraph? paragraph;
      RenderCustomPaint? glyph;
      RenderFlex? row;
      void visit(RenderObject node) {
        if (node is RenderParagraph) paragraph ??= node;
        if (node is RenderFlex) row ??= node;
        if (node is RenderCustomPaint && node.painter is GlyphPainter) {
          glyph ??= node;
        }
        node.visitChildren(visit);
      }

      visit(child);
      if (paragraph == null || glyph == null || row == null || !child.hasSize) {
        return null;
      }
      final painter = textLayout(paragraph!);
      final arrowSize = glyph!.size;
      final naturalWidth = arrowSize.width + row!.spacing + painter.width;
      final rowWidth = math.min(maxWidth, naturalWidth);
      final rowHeight = child.size.height;
      final rtl = row!.textDirection == TextDirection.rtl;
      final textOrigin = Offset(
        rtl ? 0 : arrowSize.width + row!.spacing,
        (rowHeight - painter.height) / 2,
      );
      final arrowOrigin = Offset(
        rtl ? painter.width + row!.spacing : 0,
        (rowHeight - arrowSize.height) / 2,
      );
      final ink = <Rect>[
        (glyph!.painter! as GlyphPainter)
            .paintedBounds(arrowSize, pixelRatio: _pixelRatio)
            .shift(arrowOrigin),
        for (final word in RegExp(r'\S+').allMatches(
          paragraph!.text.toPlainText(includeSemanticsLabels: false),
        ))
          for (final box in painter.getBoxesForSelection(
            TextSelection(baseOffset: word.start, extentOffset: word.end),
          ))
            box.toRect().shift(textOrigin),
      ];
      rows.add((width: rowWidth, height: rowHeight, ink: ink));
      width = math.max(width, rowWidth);
      if (child != firstChild) height += _gap;
      height += rowHeight;
    }
    var top = 0.0;
    final ink = <Rect>[];
    for (final row in rows) {
      ink.addAll(
        row.ink.map((bound) => bound.shift(Offset(width - row.width, top))),
      );
      top += row.height + _gap;
    }
    return (size: Size(width, height), ink: ink);
  }

  void alignLineCenters(double distance) {
    final gap = math.max(0.0, distance - _firstHeight);
    if (gap == _gap) return;
    _gap = gap;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _ReadingParentData) {
      child.parentData = _ReadingParentData();
    }
  }

  @override
  double computeMaxIntrinsicWidth(double height) {
    var width = 0.0;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      width = math.max(width, child.getMaxIntrinsicWidth(height));
    }
    return width;
  }

  @override
  double computeMinIntrinsicWidth(double height) =>
      computeMaxIntrinsicWidth(height);

  @override
  double computeMaxIntrinsicHeight(double width) {
    var height = 0.0;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      if (child != firstChild) height += _gap;
      height += child.getMaxIntrinsicHeight(width);
    }
    return height;
  }

  @override
  double computeMinIntrinsicHeight(double width) =>
      computeMaxIntrinsicHeight(width);

  @override
  void performLayout() {
    var height = 0.0;
    var width = 0.0;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      child.layout(
        BoxConstraints(maxWidth: constraints.maxWidth),
        parentUsesSize: true,
      );
      if (child == firstChild) _firstHeight = child.size.height;
      if (child != firstChild) height += _gap;
      (child.parentData! as _ReadingParentData).offset = Offset(0, height);
      height += child.size.height;
      width = math.max(width, child.size.width);
    }
    size = constraints.constrain(Size(width, height));
    for (var child = firstChild; child != null; child = childAfter(child)) {
      final data = child.parentData! as _ReadingParentData;
      data.offset = Offset(size.width - child.size.width, data.offset.dy);
    }
  }

  @override
  void paint(PaintingContext context, Offset offset) =>
      defaultPaint(context, offset);

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}

enum TrafficDirection {
  upload,
  download;

  num of(Traffic traffic) => this == upload ? traffic.up : traffic.down;

  Color colorOf(BuildContext context) => this == upload
      ? DashboardColors.of(context).series2
      : context.colorScheme.primary;

  String labelOf(BuildContext context) => this == upload
      ? context.appLocalizations.upload
      : context.appLocalizations.download;
}
