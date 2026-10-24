import 'dart:math' as math;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'card.dart';
import 'outbound_motion.dart';

const _minSpeedScale = 8 * 1024.0;

const _chartBleed = 3.0;

bool _trafficVisible(BuildContext context, WidgetRef ref) =>
    ref.watch(appVisibleProvider) &&
    TickerMode.valuesOf(context).enabled &&
    PageActivityScope.isActiveOf(context) &&
    (ModalRoute.isCurrentOf(context) ?? true);

class _TrafficHold<T> extends StatefulWidget {
  const _TrafficHold({
    required this.value,
    required this.live,
    required this.builder,
  });

  final T value;
  final bool live;
  final Widget Function(BuildContext context, T value) builder;

  @override
  State<_TrafficHold<T>> createState() => _TrafficHoldState<T>();
}

class _TrafficHoldState<T> extends State<_TrafficHold<T>> {
  late T _shown = widget.value;

  @override
  void didUpdateWidget(_TrafficHold<T> oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.live) _shown = widget.value;
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
    required this.currentRevision,
    required this.active,
    required this.builder,
  });

  final FixedList<Traffic> samples;
  final int currentRevision;
  final bool active;
  final Widget Function(BuildContext context, _TrafficWindow window) builder;

  @override
  State<_TrafficTrace> createState() => _TrafficTraceState();
}

class _TrafficTraceState extends State<_TrafficTrace> {
  int? _afterRevision;
  var _epoch = 0;

  @override
  void didUpdateWidget(_TrafficTrace oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.active && widget.active) {
      _afterRevision = widget.currentRevision;
      _epoch++;
    } else if (widget.currentRevision < oldWidget.currentRevision) {
      _afterRevision = widget.currentRevision;
      _epoch++;
    }
  }

  @override
  Widget build(BuildContext context) {
    final source = widget.samples;
    final list = source.list;
    final after = _afterRevision;
    final count = after == null
        ? list.length
        : (source.revision - after).clamp(0, list.length);
    return widget.builder(context, (
      samples: list.skip(list.length - count).toList(),
      revision: source.revision,
      capacity: source.maxLength,
      epoch: _epoch,
    ));
  }
}

class OutboundTrafficBackground extends ConsumerWidget {
  const OutboundTrafficBackground({super.key, required this.running});

  final bool running;

  @override
  Widget build(BuildContext context, WidgetRef ref) => OutboundSampleGate(
    builder: (context, inView) =>
        Consumer(builder: (context, ref, _) => _content(context, ref, inView)),
  );

  Widget _content(BuildContext context, WidgetRef ref, bool inView) {
    final visible = inView && _trafficVisible(context, ref);
    final foreground = ref.watch(appVisibleProvider);
    final latest = ref.watch(trafficsProvider);
    return _TrafficHold(
      value: latest,
      live: visible && running,
      builder: (context, samples) => _TrafficTrace(
        samples: samples,
        currentRevision: latest.revision,
        active: foreground && running,
        builder: _background,
      ),
    );
  }

  Widget _background(BuildContext context, _TrafficWindow window) {
    final samples = window.samples;
    if (!running || samples.isEmpty) {
      return OutboundRibbon(running: running && window.epoch == 0);
    }
    final scale = samples.fold<double>(
      _minSpeedScale,
      (peak, sample) => math.max(
        peak,
        math.max(sample.up.toDouble(), sample.down.toDouble()),
      ),
    );
    return IgnorePointer(
      child: ExcludeSemantics(
        child: InteractionPaced(
          revision: (window.revision, window.epoch),
          builder: (context) => Opacity(
            opacity: MediaQuery.highContrastOf(context) ? 0.12 : 0.34,
            child: Stack(
              fit: StackFit.expand,
              children: [
                for (final direction in TrafficDirection.values)
                  LineChart(
                    key: ValueKey((direction, window.epoch)),
                    values: [
                      for (final sample in samples)
                        direction.of(sample).toDouble(),
                    ],
                    revision: window.revision,
                    capacity: window.capacity,
                    minScale: scale,
                    baselineRatio: 0.7,
                    color: direction.colorOf(context),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class OutboundTrafficFigures extends ConsumerWidget {
  const OutboundTrafficFigures({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => OutboundSampleGate(
    builder: (context, inView) =>
        Consumer(builder: (context, ref, _) => _content(context, ref, inView)),
  );

  Widget _content(BuildContext context, WidgetRef ref, bool inView) {
    final visible = inView && _trafficVisible(context, ref);
    return _TrafficHold(
      value: (
        samples: ref.watch(trafficsProvider),
        total: ref.watch(totalTrafficProvider),
      ),
      live: visible,
      builder: _figures,
    );
  }

  Widget _figures(
    BuildContext context,
    ({FixedList<Traffic> samples, Traffic total}) reading,
  ) {
    final samples = reading.samples;
    final total = reading.total;
    final latest = samples.list.safeLast(const Traffic());
    return InteractionPaced(
      revision: (samples.revision, total),
      builder: (context) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 8,
        children: [
          for (final direction in TrafficDirection.values)
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  GlassText(
                    direction.labelOf(context),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: AppTextStyle.custom(context, size: 11, leading: 11),
                  ),
                  GlassText(
                    '${dashboardTraffic(direction.of(latest))}/s',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: AppTextStyle.custom(
                      context,
                      size: 14,
                      leading: 14,
                      weight: AppTextStyle.caption1.emphasizedWeight,
                    ).toTabular,
                  ),
                  GlassText(
                    context.appLocalizations.trafficTotal(
                      dashboardTraffic(direction.of(total)),
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: AppTextStyle.custom(context, size: 10, leading: 10),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

enum TrafficDirection {
  upload,
  download;

  num of(Traffic traffic) => this == upload ? traffic.up : traffic.down;

  Glyph get glyph => this == upload ? AppGlyphs.arrowUp : AppGlyphs.arrowDown;

  Color colorOf(BuildContext context) => this == upload
      ? DashboardColors.of(context).series2
      : context.colorScheme.primary;

  Color glyphColorOf(BuildContext context) => this == upload
      ? context.colorScheme.onSurfaceVariant
      : context.colorScheme.primary;

  String labelOf(BuildContext context) => this == upload
      ? context.appLocalizations.upload
      : context.appLocalizations.download;
}

String _speedUnit(TrafficShow show) => '${show.unit}/s';

class _Figure extends StatelessWidget {
  const _Figure({
    required this.show,
    required this.unit,
    required this.style,
    required this.unitStyle,
  });

  final TrafficShow show;
  final String unit;
  final AppTextStyle style;
  final AppTextStyle unitStyle;

  @override
  Widget build(BuildContext context) {
    final glass = LiquidGlassScope.of(context);
    final source = context.colorScheme.onSurface;
    return GlassText.rich(
      TextSpan(
        children: [
          TextSpan(
            text: show.value,
            style: DashboardText.figure(context, style),
          ),
          TextSpan(
            text: ' $unit',
            style: glass
                ? DashboardText.unit(context, unitStyle).copyWith(color: source)
                : DashboardText.unit(context, unitStyle),
          ),
        ],
      ),
      style: glass
          ? DefaultTextStyle.of(context).style.copyWith(color: source)
          : null,
      inkRole: GlassInkRole.body,
      maxLines: 1,
      overflow: TextOverflow.clip,
    );
  }
}

/// The zero line is the chart's bottom: a card lets the chart bleed past its
/// edge so idle traffic, or none sampled yet, leaves no line.
class _SpeedChart extends StatelessWidget {
  const _SpeedChart({required this.direction, required this.traffics});

  final TrafficDirection direction;
  final FixedList<Traffic> traffics;

  @override
  Widget build(BuildContext context) {
    return LineChart(
      values: [
        for (final traffic in traffics.list) direction.of(traffic).toDouble(),
      ],
      revision: traffics.revision,
      capacity: traffics.maxLength,
      minScale: _minSpeedScale,
      baselineRatio: 1,
      color: direction.colorOf(context),
      strokeInk: LiquidGlassScope.of(context)
          ? context.colorScheme.onSurface
          : null,
    );
  }
}

/// The upload or the download on its own desktop card.
class SpeedCard extends ConsumerWidget {
  const SpeedCard({super.key, required this.direction});

  final TrafficDirection direction;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final traffics = ref.watch(trafficsProvider);
    final total = ref.watch(
      totalTrafficProvider.select((state) => direction.of(state)),
    );
    return InteractionPaced(
      revision: (traffics, total),
      builder: (context) => _card(context, traffics, total),
    );
  }

  Widget _card(BuildContext context, FixedList<Traffic> traffics, num total) {
    final density = DashboardDensityScope.of(context);
    final compact = density.isCompact;
    final scale = DashboardDensityScope.scaleOf(context);
    final padding = dashboardCardPadding(density);
    final appLocalizations = context.appLocalizations;
    final current = direction.of(traffics.list.safeLast(const Traffic()));
    final peak = traffics.list.fold<num>(
      0,
      (peak, traffic) => math.max(peak, direction.of(traffic)),
    );
    final speed = current.traffic;
    return DashboardCard(
      padding: EdgeInsets.zero,
      semanticLabel: direction.labelOf(context),
      clipBehavior: Clip.antiAlias,
      child: Stack(
        children: [
          Positioned(
            left: -_chartBleed,
            right: -_chartBleed,
            bottom: -_chartBleed,
            height: compact ? 62 : 88,
            child: RepaintBoundary(
              child: _SpeedChart(direction: direction, traffics: traffics),
            ),
          ),
          Padding(
            padding: padding,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  height: 24 * scale,
                  child: Row(
                    spacing: 8,
                    children: [
                      GlyphIcon(
                        direction.glyph,
                        size: 18,
                        color: direction.glyphColorOf(context),
                      ),
                      GlassText(
                        direction.labelOf(context),
                        maxLines: 1,
                        style: DashboardText.cardTitle(context),
                      ),
                      Expanded(
                        child: GlassText(
                          appLocalizations.trafficPeak(
                            '${dashboardTraffic(peak)}/s',
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          textAlign: TextAlign.end,
                          style: DashboardText.label(context),
                        ),
                      ),
                    ],
                  ),
                ),
                SizedBox(height: compact ? 6 : 10),
                _Figure(
                  show: speed,
                  unit: _speedUnit(speed),
                  style: compact
                      ? AppTextStyle.largeTitle
                      : AppTextStyle.display,
                  unitStyle: AppTextStyle.subheadline,
                ),
                const SizedBox(height: 2),
                GlassText(
                  appLocalizations.trafficTotal(dashboardTraffic(total)),
                  maxLines: 1,
                  style: DashboardText.label(context),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// The phone's single card: both directions side by side.
class NetworkSpeedCard extends ConsumerWidget {
  const NetworkSpeedCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final traffics = ref.watch(trafficsProvider);
    final total = ref.watch(totalTrafficProvider);
    return InteractionPaced(
      revision: (traffics, total),
      builder: (context) => _card(context, traffics, total),
    );
  }

  Widget _card(
    BuildContext context,
    FixedList<Traffic> traffics,
    Traffic total,
  ) {
    final latest = traffics.list.safeLast(const Traffic());
    return DashboardCard(
      semanticLabel: context.appLocalizations.networkSpeed,
      child: Column(
        children: [
          DashboardCardHeader(
            glyph: AppGlyphs.speed,
            title: context.appLocalizations.networkSpeed,
          ),
          const SizedBox(height: 12),
          Row(
            spacing: 10,
            children: [
              for (final direction in TrafficDirection.values)
                Expanded(
                  child: _SpeedTile(
                    direction: direction,
                    speed: direction.of(latest),
                    total: direction.of(total),
                    traffics: traffics,
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Keeps one shape at every width: the chart takes the height the tile's
/// aspect ratio leaves under the text, down to [_minChartHeight].
class _SpeedTile extends StatelessWidget {
  const _SpeedTile({
    required this.direction,
    required this.speed,
    required this.total,
    required this.traffics,
  });

  final TrafficDirection direction;
  final num speed;
  final num total;
  final FixedList<Traffic> traffics;

  static const _aspectRatio = 1.06;
  static const _padding = EdgeInsets.fromLTRB(16, 14, 16, 0);
  static const _titleGap = 4.0;
  static const _chartGap = 12.0;
  static const _bleed = 2.0;
  static const _minChartHeight = 36.0;

  @override
  Widget build(BuildContext context) {
    final color = direction.glyphColorOf(context);
    final show = speed.traffic;
    final scale = DashboardDensityScope.scaleOf(context);
    final textHeight =
        _padding.top +
        _titleGap +
        (AppTextStyle.footnote.leading +
                AppTextStyle.title1.leading +
                AppTextStyle.caption1.leading) *
            scale;
    return LayoutBuilder(
      builder: (context, constraints) {
        final chartHeight = math.max(
          _minChartHeight,
          constraints.maxWidth / _aspectRatio - textHeight - _chartGap + _bleed,
        );
        final shape = AppShape.all(DashboardCorner.mobileBlock);
        return DashboardGlass(
          shape: shape,
          child: Container(
            clipBehavior: Clip.antiAlias,
            decoration: ShapeDecoration(
              color: DashboardColors.of(context).tone,
              shape: shape,
            ),
            child: Stack(
              children: [
                Positioned(
                  left: -_bleed,
                  right: -_bleed,
                  bottom: -_bleed,
                  height: chartHeight,
                  child: RepaintBoundary(
                    child: _SpeedChart(
                      direction: direction,
                      traffics: traffics,
                    ),
                  ),
                ),
                Padding(
                  padding: _padding.copyWith(
                    bottom: chartHeight - _bleed + _chartGap,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        spacing: 4,
                        children: [
                          GlyphIcon(direction.glyph, size: 16, color: color),
                          Flexible(
                            child: GlassText(
                              direction.labelOf(context),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: AppTextStyle.footnote
                                  .of(context, emphasized: true)
                                  .copyWith(
                                    color: context.colorScheme.onSurfaceVariant,
                                  ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: _titleGap),
                      _Figure(
                        show: show,
                        unit: _speedUnit(show),
                        style: AppTextStyle.title1,
                        unitStyle: AppTextStyle.footnote,
                      ),
                      GlassText(
                        context.appLocalizations.trafficTotal(
                          dashboardTraffic(total),
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: DashboardText.label(context),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
