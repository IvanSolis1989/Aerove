import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/features.dart';
import 'package:fl_clash/features/ip_quality/labels.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/last_exit.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/layout.dart';
import 'package:fl_clash/views/dashboard/probe_start_hold.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'card.dart';
import 'outbound_motion.dart';
import 'status_layout.dart';
import 'traffic.dart';

const _builtinOutbounds = {'DIRECT', 'REJECT', 'REJECT-DROP', 'PASS'};

bool isBuiltinOutbound(String name) => _builtinOutbounds.contains(name);

const _ipMask = '*****';

/// What a screen reader takes after the title, the card's own label;
/// traffic comes where it is drawn.
enum _CardStop { chip, overlaidTraffic, summary, toggle, appendedTraffic }

/// The summary reports the exit before it offers the controls acting on it.
enum _SummaryStop { quality, runTime, organization, retry, address }

/// The address the proxied traffic leaves by, next to the start button, with
/// the traffic in its corner and its speed lines behind it.
class CurrentOutboundCard extends ConsumerStatefulWidget {
  const CurrentOutboundCard({super.key});

  @override
  ConsumerState<CurrentOutboundCard> createState() =>
      _CurrentOutboundCardState();
}

class _CurrentOutboundCardState extends ConsumerState<CurrentOutboundCard>
    with ProbeStartHold<CurrentOutboundCard> {
  late final OutboundIpProbe _probe;
  var _watching = false;

  @override
  void initState() {
    super.initState();
    _probe = ref.read(outboundIpProbeProvider.notifier);
  }

  @override
  void dispose() {
    if (_watching) _probe.unwatch(routedOutbound);
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final watching =
        PageActivityScope.isActiveOf(context) &&
        TickerMode.valuesOf(context).enabled;
    if (_watching == watching) return;
    _watching = watching;
    if (watching) {
      _probe.watch(routedOutbound);
    } else {
      _probe.unwatch(routedOutbound);
    }
  }

  void _refresh(String? ip) {
    _probe.refresh(const [routedOutbound]);
    if (ip != null && ref.read(ipQualityProvider(ip)).hasError) {
      ref.invalidate(ipQualityProvider(ip));
    }
  }

  @override
  Widget build(BuildContext context) {
    final density = DashboardDensityScope.of(context);
    final hasProfile = ref.watch(
      profilesProvider.select((state) => state.isNotEmpty),
    );
    final isStart = ref.watch(isStartProvider);
    final suspended = isStart && ref.watch(suspendProvider);
    final entry = ref.watch(
      outboundIpProbeProvider.select((state) => state.entryOf(routedOutbound)),
    );
    final ipInfo = entry.value;
    final phase = shownPhase(entry);
    final failed = phase == ProbePhase.failed && ipInfo == null;
    final qualityState = ipInfo == null
        ? null
        : ref.watch(ipQualityProvider(ipInfo.ip));
    final lastQuality = ipInfo == null
        ? null
        : ref.watch(
            lastExitProvider.select((last) => last?.qualityOf(ipInfo.ip)),
          );
    final qualityLoading =
        qualityState != null &&
        qualityState.value == null &&
        qualityState.isLoading;
    final quality =
        qualityState?.value ?? (qualityLoading ? lastQuality : null);
    final loading = ipInfo == null ? !failed : qualityLoading;
    final metrics = _OutboundMetrics.of(density);
    final appLocalizations = context.appLocalizations;
    final (chipLabel, chipTone) = suspended
        ? (appLocalizations.suspended, DashboardChipTone.warn)
        : isStart
        ? (appLocalizations.running, DashboardChipTone.ok)
        : (appLocalizations.notRunning, DashboardChipTone.idle);
    final chip = DashboardChip(
      label: chipLabel,
      tone: chipTone,
      dot: isStart,
      wrap: true,
      dense: true,
    );
    final header = DashboardCardHeader(
      glyph: AppGlyphs.status,
      title: appLocalizations.status,
      wrap: true,
      titleWrapper: (title) => StatusForeground(
        margin: const EdgeInsetsDirectional.only(end: 8),
        child: title,
      ),
      leading: [
        DashboardStop(
          order: _CardStop.chip.index,
          child: StatusForeground(
            margin: const EdgeInsetsDirectional.only(end: 8),
            outline: DashboardChip.shape,
            child: chip,
          ),
        ),
      ],
    );
    final summary = DashboardStop(
      order: _CardStop.summary.index,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          StatusCurveEdge(
            child: _ExitRow(
              info: ipInfo,
              failed: failed,
              loading: loading,
              checking: phase == ProbePhase.probing || qualityLoading,
              onRefresh: () => _refresh(ipInfo?.ip),
              quality: quality,
              active: isStart,
              metrics: metrics,
              large: !density.isDesktop,
            ),
          ),
          if (density.isDesktop) const Spacer() else const SizedBox(height: 16),
          StatusCurveEdge(
            floor: true,
            child: _Cells(
              quality: quality,
              loading: loading,
              active: isStart && !suspended,
              gap: metrics.cellGap,
            ),
          ),
        ],
      ),
    );
    final body = Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      spacing: metrics.gap,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              header,
              SizedBox(height: _ipTopOf(context, density, metrics)),
              if (density.isDesktop) Expanded(child: summary) else summary,
            ],
          ),
        ),
        SizedBox(
          width: metrics.buttonSlot,
          child: Center(
            child: DashboardStop(
              order: _CardStop.toggle.index,
              child: StatusForeground(
                text: false,
                child: _StartToggle(
                  size: metrics.buttonSize,
                  enabled: hasProfile,
                  running: isStart,
                  suspended: suspended,
                ),
              ),
            ),
          ),
        ),
      ],
    );
    return DashboardCard(
      padding: EdgeInsets.zero,
      child: StatusCardLayout(
        padding: metrics.padding,
        shape: AppShape.all(dashboardCardRadius(density)),
        baselineRatio: OutboundTrafficBackground.baselineRatio,
        backdrop: OutboundTrafficBackground(running: isStart && !suspended),
        body: body,
        figures: const OutboundTrafficFigures(),
        overlaidOrder: _CardStop.overlaidTraffic.index,
        appendedOrder: _CardStop.appendedTraffic.index,
      ),
    );
  }
}

/// A desktop card's height is fixed, so the exit row starts below the corner
/// figures and they always fit beside the header.
double _ipTopOf(
  BuildContext context,
  DashboardDensity density,
  _OutboundMetrics metrics,
) {
  if (!density.isDesktop) return metrics.ipTop;
  final header =
      DashboardCardHeader.height * DashboardDensityScope.scaleOf(context);
  final figures = OutboundTrafficFigures.heightOf(
    MediaQuery.textScalerOf(context),
  );
  return math.max(metrics.ipTop, figures - header);
}

class _OutboundMetrics {
  const _OutboundMetrics({
    required this.padding,
    required this.gap,
    required this.ipTop,
    required this.cellGap,
    required this.badgeSize,
    required this.ip,
    required this.buttonSize,
    required this.buttonSlot,
  });

  factory _OutboundMetrics.of(DashboardDensity density) => switch (density) {
    DashboardDensity.standard => _OutboundMetrics(
      padding: dashboardCardPadding(density),
      gap: 16,
      ipTop: 10,
      cellGap: 28,
      badgeSize: 48,
      ip: AppTextStyle.title1,
      buttonSize: 104,
      buttonSlot: 120,
    ),
    DashboardDensity.compact => _OutboundMetrics(
      padding: dashboardCardPadding(density),
      gap: 16,
      ipTop: 8,
      cellGap: 22,
      badgeSize: 40,
      ip: AppTextStyle.title2,
      buttonSize: 84,
      buttonSlot: 100,
    ),
    DashboardDensity.mobile => const _OutboundMetrics(
      padding: EdgeInsetsDirectional.fromSTEB(20, 18, 16, 18),
      gap: 10,
      ipTop: 12,
      cellGap: 24,
      badgeSize: 48,
      ip: AppTextStyle.title2,
      buttonSize: 88,
      buttonSlot: 88,
    ),
  };

  final EdgeInsetsGeometry padding;
  final double gap;
  final double ipTop;
  final double cellGap;
  final double badgeSize;
  final AppTextStyle ip;
  final double buttonSize;
  final double buttonSlot;
}

class _ExitRow extends StatelessWidget {
  const _ExitRow({
    required this.info,
    required this.failed,
    required this.loading,
    required this.checking,
    required this.onRefresh,
    required this.quality,
    required this.active,
    required this.metrics,
    required this.large,
  });

  final IpInfo? info;
  final bool failed;
  final bool loading;
  final bool checking;
  final VoidCallback onRefresh;
  final IpQuality? quality;
  final bool active;
  final _OutboundMetrics metrics;
  final bool large;

  @override
  Widget build(BuildContext context) {
    final colorScheme = context.colorScheme;
    final style = DashboardText.figure(
      context,
      metrics.ip,
      color: active ? colorScheme.onSurface : colorScheme.onSurfaceVariant,
    ).copyWith(letterSpacing: 0);
    final info = this.info;
    final appLocalizations = context.appLocalizations;
    final Widget ip = info != null
        ? Tooltip(
            message: appLocalizations.details(appLocalizations.outboundIp),
            child: FocusRing(
              shape: AppShape.xs,
              standsOff: true,
              child: InkWell(
                onTap: () => showIpQualitySheet(context, ip: info.ip),
                customBorder: AppShape.xs,
                hoverColor: Colors.transparent,
                splashColor: Colors.transparent,
                highlightColor: Colors.transparent,
                child: _IpText(ip: info.ip, style: style),
              ),
            ),
          )
        : failed
        ? GlassText(
            appLocalizations.serviceFailed,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: style.copyWith(color: colorScheme.error),
          )
        : SkeletonText(width: 140, style: style);
    return Row(
      spacing: 12,
      children: [
        DashboardStop(
          order: _SummaryStop.retry.index,
          child: StatusForeground(
            text: false,
            child: _CountryBadge(
              countryCode: info?.countryCode,
              size: metrics.badgeSize,
              active: active,
              checking: checking,
              onRefresh: onRefresh,
            ),
          ),
        ),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              DashboardStop(
                order: _SummaryStop.address.index,
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minHeight:
                        metrics.ip.leading *
                        DashboardDensityScope.scaleOf(context),
                  ),
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: StatusForeground(child: ip),
                  ),
                ),
              ),
              const SizedBox(height: 4),
              DashboardStop(
                order: _SummaryStop.quality.index,
                child: failed
                    ? SizedBox(
                        height: _chipHeight(large),
                        child: Align(
                          alignment: AlignmentDirectional.centerStart,
                          child: StatusForeground(
                            child: GlassText(
                              appLocalizations.tapIconToRetry,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: DashboardText.label(context),
                            ),
                          ),
                        ),
                      )
                    : _IpChips(
                        quality: quality,
                        loading: loading,
                        large: large,
                      ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _IpText extends ConsumerWidget {
  const _IpText({required this.ip, required this.style});

  final String ip;
  final TextStyle style;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hideIp = ref.watch(
      appSettingProvider.select((state) => state.hideIp),
    );
    return DashboardFitText(hideIp ? _ipMask : ip, style: style);
  }
}

double _chipHeight(bool large) => large ? 28 : 24;

const _chipSkeletonWidths = [48.0, 72.0];

class _IpChips extends StatelessWidget {
  const _IpChips({
    required this.quality,
    required this.loading,
    required this.large,
  });

  final IpQuality? quality;
  final bool loading;
  final bool large;

  @override
  Widget build(BuildContext context) {
    final quality = this.quality;
    final height = _chipHeight(large);
    // A chip that does not fit its line moves to the next and the card grows;
    // one too long for any line wraps inside its pill.
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        for (final chip in switch (quality) {
          final quality? => [
            DashboardChip(
              label: quality.type.label(context),
              large: large,
              wrap: true,
            ),
            DashboardChip(
              label:
                  '${context.appLocalizations.ipQualityShort} ${quality.level.label(context)}',
              tone: switch (quality.level) {
                IpQualityLevel.good => DashboardChipTone.ok,
                IpQualityLevel.normal => DashboardChipTone.idle,
                IpQualityLevel.risky => DashboardChipTone.warn,
              },
              large: large,
              wrap: true,
            ),
          ],
          null when loading => [
            for (final width in _chipSkeletonWidths)
              SkeletonPill(width: width, height: height),
          ],
          null => [
            DashboardChip(
              label: context.appLocalizations.ipTypeUnknown,
              large: large,
              wrap: true,
            ),
          ],
        })
          StatusForeground(outline: DashboardChip.shape, child: chip),
      ],
    );
  }
}

/// Bounds the flag by the bubble's own skin and fades it out at the rim. A
/// resting bubble shows one picture of that, taken once, so no frame spends
/// a saved layer on it; only a writhing one clips and feathers as it paints.
class _FlagSkin extends StatefulWidget {
  const _FlagSkin({
    required this.skin,
    required this.checking,
    required this.flag,
    required this.size,
    required this.child,
  });

  final OutboundSkin skin;
  final bool checking;
  final String flag;
  final double size;
  final Widget child;

  @override
  State<_FlagSkin> createState() => _FlagSkinState();
}

class _FlagSkinState extends State<_FlagSkin> {
  final _boundary = GlobalKey();
  ui.Image? _picture;
  Object? _pictureKey;
  var _capturing = false;
  late bool _resting = _isResting;

  bool get _isResting => !widget.checking && widget.skin.wobble == 0;

  @override
  void initState() {
    super.initState();
    widget.skin.addListener(_follow);
  }

  @override
  void didUpdateWidget(_FlagSkin oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.skin, widget.skin)) {
      oldWidget.skin.removeListener(_follow);
      widget.skin.addListener(_follow);
    }
    _resting = _isResting;
  }

  void _follow() {
    final resting = _isResting;
    if (resting != _resting) setState(() => _resting = resting);
  }

  void _drop() {
    _picture?.dispose();
    _picture = null;
    _pictureKey = null;
  }

  /// Runs once the live flag has painted, so its layer is there to read.
  void _capture(Object key, double ratio) {
    if (_capturing) return;
    _capturing = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _capturing = false;
      final boundary = _boundary.currentContext?.findRenderObject();
      if (!mounted ||
          !_resting ||
          _picture != null ||
          boundary is! RenderRepaintBoundary) {
        return;
      }
      var painted = true;
      assert(() {
        painted = !boundary.debugNeedsPaint;
        return true;
      }());
      if (!painted) return;
      setState(() {
        _picture = boundary.toImageSync(pixelRatio: ratio);
        _pictureKey = key;
      });
    });
  }

  @override
  void dispose() {
    widget.skin.removeListener(_follow);
    _drop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ratio = MediaQuery.devicePixelRatioOf(context);
    final key = (widget.flag, widget.size, ratio);
    if (!_resting || (_pictureKey != null && _pictureKey != key)) _drop();
    return _RestingPicture(
      picture: _picture,
      scale: ratio,
      onPainted: _resting && _picture == null
          ? () => _capture(key, ratio)
          : null,
      child: RepaintBoundary(
        key: _boundary,
        child: ClipPath(
          clipper: OutboundDropletClipper(
            skin: widget.skin,
            irregularity: null,
          ),
          clipBehavior: Clip.antiAliasWithSaveLayer,
          child: CustomPaint(
            foregroundPainter: OutboundDropletFeather(skin: widget.skin),
            child: widget.child,
          ),
        ),
      ),
    );
  }
}

/// Paints [picture] in place of its child once there is one; the child
/// stays laid out and keeps its semantics, so only its pixels are reused.
class _RestingPicture extends SingleChildRenderObjectWidget {
  const _RestingPicture({
    required this.picture,
    required this.scale,
    required this.onPainted,
    required super.child,
  });

  final ui.Image? picture;
  final double scale;
  final VoidCallback? onPainted;

  @override
  _RenderRestingPicture createRenderObject(BuildContext context) =>
      _RenderRestingPicture(picture, scale)..onPainted = onPainted;

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderRestingPicture renderObject,
  ) {
    renderObject
      ..picture = picture
      ..scale = scale
      ..onPainted = onPainted;
  }
}

class _RenderRestingPicture extends RenderProxyBox {
  _RenderRestingPicture(this._picture, this._scale);

  VoidCallback? onPainted;

  ui.Image? _picture;
  set picture(ui.Image? value) {
    if (identical(value, _picture)) return;
    _picture = value;
    markNeedsPaint();
  }

  double _scale;
  set scale(double value) {
    if (value == _scale) return;
    _scale = value;
    markNeedsPaint();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final picture = _picture;
    if (picture == null) {
      super.paint(context, offset);
      onPainted?.call();
      return;
    }
    paintImage(
      canvas: context.canvas,
      rect: offset & size,
      image: picture,
      scale: _scale,
      fit: BoxFit.fill,
      filterQuality: FilterQuality.low,
    );
  }
}

class _CountryBadge extends StatefulWidget {
  const _CountryBadge({
    required this.countryCode,
    required this.size,
    required this.active,
    required this.checking,
    required this.onRefresh,
  });

  final String? countryCode;
  final double size;
  final bool active;
  final bool checking;
  final VoidCallback onRefresh;

  @override
  State<_CountryBadge> createState() => _CountryBadgeState();
}

class _CountryBadgeState extends State<_CountryBadge> {
  var _hovered = false;
  var _focused = false;

  @override
  Widget build(BuildContext context) {
    final colorScheme = context.colorScheme;
    final foreground = widget.active
        ? colorScheme.onSurface
        : colorScheme.onSurfaceVariant;
    final enabled = !widget.checking;
    final size = widget.size;
    final code = widget.countryCode?.trim().toUpperCase();
    final flag = code?.countryFlagEmoji;
    final hasFlag =
        flag != null && flag != code && code != 'XX' && code != 'ZZ';
    final tooltip = context.appLocalizations.retry;
    final well = Color.alphaBlend(
      foreground.withValues(alpha: enabled && _hovered ? 0.08 : 0),
      DashboardColors.of(context).tone2,
    );
    final finish = OutboundFinish.of(context);
    final highContrast = MediaQuery.highContrastOf(context);
    final shadow = highContrast || finish == OutboundFinish.clear
        ? Colors.transparent
        : colorScheme.shadow.withValues(
            alpha: finish == OutboundFinish.solid ? 0.22 : 0.14,
          );
    final rim = Colors.white.withValues(
      alpha: colorScheme.brightness == Brightness.dark ? 0.32 : 0.6,
    );
    return Tooltip(
      message: tooltip,
      child: Semantics(
        button: true,
        enabled: enabled,
        label: tooltip,
        child: InkResponse(
          onTap: enabled ? widget.onRefresh : null,
          onHover: (value) => setState(() => _hovered = value),
          onFocusChange: (value) => setState(() => _focused = value),
          overlayColor: const WidgetStatePropertyAll(Colors.transparent),
          splashFactory: NoSplash.splashFactory,
          child: ElasticPress(
            enabled:
                enabled &&
                !context.disableAnimations &&
                !MediaQuery.highContrastOf(context),
            child: OutboundBubble(
              checking: widget.checking,
              builder: (_, skin, child) => SizedBox.square(
                dimension: size,
                child: CustomPaint(
                  painter: OutboundDropletPainter(
                    skin: skin,
                    irregularity: null,
                    color: hasFlag ? Colors.transparent : well,
                    shadow: hasFlag ? Colors.transparent : shadow,
                    highlight: false,
                  ),
                  foregroundPainter: OutboundDropletPainter(
                    skin: skin,
                    irregularity: null,
                    color: Colors.transparent,
                    highlight:
                        !hasFlag &&
                        !highContrast &&
                        finish != OutboundFinish.frost,
                    rim: !hasFlag && finish == OutboundFinish.clear
                        ? rim
                        : null,
                    ring: _focused ? focusRingSide(colorScheme) : null,
                  ),
                  child: hasFlag
                      ? _FlagSkin(
                          skin: skin,
                          checking: widget.checking,
                          flag: flag,
                          size: size,
                          child: child!,
                        )
                      : child,
                ),
              ),
              child: !hasFlag
                  ? Center(
                      child: GlyphIcon(
                        AppGlyphs.proxies,
                        size: size * 0.42,
                        color: foreground,
                      ),
                    )
                  : Semantics(
                      label: code,
                      child: ExcludeSemantics(
                        child: EmojiCover(flag, size: size),
                      ),
                    ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Cells extends StatelessWidget {
  const _Cells({
    required this.quality,
    required this.loading,
    required this.active,
    required this.gap,
  });

  final IpQuality? quality;
  final bool loading;
  final bool active;
  final double gap;

  @override
  Widget build(BuildContext context) {
    final appLocalizations = context.appLocalizations;
    final organization = switch (quality) {
      IpQuality(organization: final name?) when name.isNotEmpty => name,
      IpQuality(asn: final asn?) => 'AS$asn',
      _ => null,
    };
    final runtime = _Cell(
      label: appLocalizations.runTime,
      value: active ? const _RunTimeValue() : const _Placeholder(),
    );
    final identity = _Cell(
      label: appLocalizations.ipOrganization,
      value: organization != null
          ? _CellText(organization)
          : loading
          ? SkeletonText(width: 96, style: _cellStyle(context))
          : const _Placeholder(),
    );
    final runtimeStop = DashboardStop(
      order: _SummaryStop.runTime.index,
      child: StatusForeground(child: runtime),
    );
    final identityStop = DashboardStop(
      order: _SummaryStop.organization.index,
      child: StatusForeground(child: identity),
    );
    return _CellLine(
      gap: gap,
      stacked:
          !DashboardDensityScope.of(context).isDesktop &&
          MediaQuery.textScalerOf(context).scale(1) > 1.3,
      textDirection: Directionality.of(context),
      children: [runtimeStop, identityStop],
    );
  }
}

/// The run time and the organization side by side while the run time and
/// the organization's longest word fit, one under the other otherwise or
/// when [stacked]; neither is ever squeezed or cut.
class _CellLine extends MultiChildRenderObjectWidget {
  const _CellLine({
    required this.gap,
    required this.stacked,
    required this.textDirection,
    required super.children,
  });

  static const runGap = 8.0;

  final double gap;
  final bool stacked;
  final TextDirection textDirection;

  @override
  _RenderCellLine createRenderObject(BuildContext context) =>
      _RenderCellLine(gap, stacked, textDirection);

  @override
  void updateRenderObject(BuildContext context, _RenderCellLine renderObject) {
    renderObject
      ..gap = gap
      ..stacked = stacked
      ..textDirection = textDirection;
  }
}

class _CellLineParentData extends ContainerBoxParentData<RenderBox> {}

class _RenderCellLine extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _CellLineParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _CellLineParentData> {
  _RenderCellLine(this._gap, this._stacked, this._textDirection);

  double _gap;
  set gap(double value) {
    if (value == _gap) return;
    _gap = value;
    markNeedsLayout();
  }

  bool _stacked;
  set stacked(bool value) {
    if (value == _stacked) return;
    _stacked = value;
    markNeedsLayout();
  }

  TextDirection _textDirection;
  set textDirection(TextDirection value) {
    if (value == _textDirection) return;
    _textDirection = value;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _CellLineParentData) {
      child.parentData = _CellLineParentData();
    }
  }

  void _place(RenderBox child, double x, double y, double width) =>
      (child.parentData! as _CellLineParentData).offset = Offset(
        _textDirection == TextDirection.rtl ? width - x - child.size.width : x,
        y,
      );

  @override
  void performLayout() {
    final first = firstChild;
    final second = first == null ? null : childAfter(first);
    if (first == null || second == null) {
      size = constraints.smallest;
      return;
    }
    final width = constraints.maxWidth;
    final start = first.getMaxIntrinsicWidth(double.infinity);
    final beside =
        !_stacked &&
        start + _gap + second.getMinIntrinsicWidth(double.infinity) <= width;
    if (beside) {
      first.layout(BoxConstraints(maxWidth: start), parentUsesSize: true);
      second.layout(
        BoxConstraints(maxWidth: width - first.size.width - _gap),
        parentUsesSize: true,
      );
      _place(first, 0, 0, width);
      _place(second, first.size.width + _gap, 0, width);
      size = constraints.constrain(
        Size(width, math.max(first.size.height, second.size.height)),
      );
      return;
    }
    final loose = BoxConstraints(maxWidth: width);
    first.layout(loose, parentUsesSize: true);
    second.layout(loose, parentUsesSize: true);
    _place(first, 0, 0, width);
    _place(second, 0, first.size.height + _CellLine.runGap, width);
    size = constraints.constrain(
      Size(width, first.size.height + _CellLine.runGap + second.size.height),
    );
  }

  @override
  void paint(PaintingContext context, Offset offset) =>
      defaultPaint(context, offset);

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}

class _Cell extends StatelessWidget {
  const _Cell({required this.label, required this.value});

  final String label;
  final Widget value;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        GlassText(label, style: DashboardText.label(context)),
        const SizedBox(height: 2),
        value,
      ],
    );
  }
}

TextStyle _cellStyle(BuildContext context) =>
    AppTextStyle.subheadline.of(context, emphasized: true).toTabular;

class _CellText extends StatelessWidget {
  const _CellText(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return GlassText(text, style: _cellStyle(context));
  }
}

class _Placeholder extends StatelessWidget {
  const _Placeholder();

  @override
  Widget build(BuildContext context) {
    return GlassText('—', maxLines: 1, style: _cellStyle(context));
  }
}

/// Ticks behind its own repaint boundary, and only while the card is in
/// view: scrolled away it lets go of the run time, which then stops.
class _RunTimeValue extends StatelessWidget {
  const _RunTimeValue();

  @override
  Widget build(BuildContext context) => OutboundSampleGate(
    builder: (context, inView) => TrafficClaim(
      need: TrafficNeed.runTime,
      enabled: inView,
      child: RepaintBoundary(
        child: Consumer(
          builder: (context, ref, _) {
            final runTime = ref.watch(runTimeProvider);
            return InteractionPaced(
              revision: runTime,
              reuseUnchanged: true,
              builder: (_) => _TickingText(getTimeText(runTime)),
            );
          },
        ),
      ),
    ),
  );
}

/// Takes the box its text needs in the widest digits, so a tick lays out
/// this line and nothing around it.
class _TickingText extends StatefulWidget {
  const _TickingText(this.text);

  final String text;

  @override
  State<_TickingText> createState() => _TickingTextState();
}

class _TickingTextState extends State<_TickingText> {
  Object? _key;
  var _box = Size.zero;

  Size _boxOf(String template, TextStyle style) {
    final scaler = MediaQuery.textScalerOf(context);
    final direction = Directionality.of(context);
    final key = (template, style, scaler, direction);
    if (key == _key) return _box;
    _key = key;
    var box = Size.zero;
    for (var digit = 0; digit < 10; digit++) {
      final painter = TextPainter(
        text: TextSpan(text: template.replaceAll('0', '$digit'), style: style),
        textDirection: direction,
        textScaler: scaler,
        maxLines: 1,
      )..layout();
      box = Size(
        math.max(box.width, painter.width),
        math.max(box.height, painter.height),
      );
      painter.dispose();
    }
    return _box = Size(box.width.ceilToDouble(), box.height.ceilToDouble());
  }

  @override
  Widget build(BuildContext context) {
    final style = _cellStyle(context);
    final text = widget.text;
    return SizedBox.fromSize(
      size: _boxOf(text.replaceAll(RegExp('[0-9]'), '0'), style),
      child: GlassText(text, maxLines: 1, softWrap: false, style: style),
    );
  }
}

const _morphDuration = Duration(milliseconds: 450);
const _stateDuration = Duration(milliseconds: 150);
final _dropletSpring = SpringCurve(
  const SpringDescription(mass: 1, stiffness: 260, damping: 18),
  seconds: 0.5,
);
final _dropletFall = SpringCurve(
  const SpringDescription(mass: 1, stiffness: 300, damping: 16),
  seconds: 0.5,
).flipped;

class _StartToggle extends ConsumerStatefulWidget {
  const _StartToggle({
    required this.size,
    required this.enabled,
    required this.running,
    required this.suspended,
  });

  final double size;
  final bool enabled;
  final bool running;
  final bool suspended;

  @override
  ConsumerState<_StartToggle> createState() => _StartToggleState();
}

class _StartToggleState extends ConsumerState<_StartToggle>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: _morphDuration,
    value: widget.running ? 1 : 0,
  );
  late final CurvedAnimation _progress = CurvedAnimation(
    parent: _controller,
    curve: _dropletSpring,
    reverseCurve: _dropletFall,
  );
  var _hovered = false;
  var _pressed = false;
  var _focused = false;

  /// The M3 state layer, painted inside the shape: the card's decoration
  /// covers the button, so ink on the card's Material would never show.
  double get _overlay => !widget.enabled
      ? 0
      : _focused
      ? 0.1
      : _hovered
      ? 0.08
      : 0;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (context.disableAnimations || MediaQuery.highContrastOf(context)) {
      _controller.value = widget.running ? 1 : 0;
    }
  }

  @override
  void didUpdateWidget(_StartToggle oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.running != widget.running) {
      if (context.disableAnimations || MediaQuery.highContrastOf(context)) {
        _controller.value = widget.running ? 1 : 0;
      } else {
        widget.running ? _controller.forward() : _controller.reverse();
      }
    }
  }

  @override
  void dispose() {
    _progress.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _toggle() {
    ref.read(commonActionProvider.notifier).toggleRunning();
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = context.colorScheme;
    final appLocalizations = context.appLocalizations;
    final highContrast = MediaQuery.highContrastOf(context);
    final finish = OutboundFinish.of(context);
    final glass = finish != OutboundFinish.solid;
    final size = widget.size;
    final enabled = widget.enabled;
    final fill = enabled
        ? colorScheme.primary
        : DashboardColors.of(context).tone2;
    final ink = enabled && !glass
        ? colorScheme.onPrimary
        : colorScheme.onSurfaceVariant;
    final tooltip = widget.suspended
        ? appLocalizations.suspended
        : widget.running
        ? appLocalizations.stop
        : appLocalizations.start;
    final stateDuration = highContrast
        ? Duration.zero
        : context.motionDuration(_stateDuration);
    final shadow = !enabled || finish == OutboundFinish.clear
        ? Colors.transparent
        : finish == OutboundFinish.solid
        ? colorScheme.primary.withValues(alpha: 0.3)
        : colorScheme.shadow.withValues(alpha: 0.14);
    final pane = LiquidGlassStyle.regular(colorScheme).copyWith(
      tint: finish == OutboundFinish.frost
          ? colorScheme.surfaceContainerHighest.withValues(alpha: 0.15)
          : Colors.transparent,
    );
    return Tooltip(
      message: tooltip,
      child: Semantics(
        button: true,
        enabled: enabled,
        label: tooltip,
        child: InkResponse(
          onTap: enabled ? _toggle : null,
          onHover: (value) => setState(() => _hovered = value),
          onHighlightChanged: (value) => setState(() => _pressed = value),
          onFocusChange: (value) => setState(() => _focused = value),
          overlayColor: const WidgetStatePropertyAll(Colors.transparent),
          splashFactory: NoSplash.splashFactory,
          child: ElasticPress(
            enabled: enabled && !context.disableAnimations && !highContrast,
            child: OutboundDrift(
              moving: widget.running && !widget.suspended,
              builder: (_, clock, child) => TweenAnimationBuilder<double>(
                tween: Tween(end: _overlay),
                duration: stateDuration,
                builder: (_, overlay, child) => TweenAnimationBuilder<double>(
                  tween: Tween(end: _pressed ? 1 : 0),
                  duration: highContrast
                      ? Duration.zero
                      : context.motionDuration(
                          _pressed
                              ? const Duration(milliseconds: 120)
                              : const Duration(milliseconds: 500),
                        ),
                  curve: _pressed ? Curves.easeOutCubic : _dropletSpring,
                  builder: (_, press, child) => AnimatedBuilder(
                    animation: _progress,
                    builder: (_, child) {
                      final progress = _progress.value;
                      final squeeze = press + progress * (1 - progress) * 2.4;
                      final phase = progress * 1.2;
                      final wobble = 0.55 * progress;
                      final color = Color.alphaBlend(
                        ink.withValues(alpha: overlay),
                        fill,
                      );
                      final skin = OutboundSkin(clock);
                      return CustomPaint(
                        painter: OutboundDropletPainter(
                          phase: phase,
                          fullness: progress,
                          squeeze: squeeze,
                          skin: skin,
                          wobble: wobble,
                          color: glass ? null : color,
                          shadow: shadow,
                          highlight:
                              enabled &&
                              !highContrast &&
                              finish != OutboundFinish.frost,
                          ring: _focused
                              ? focusRingSide(colorScheme, standsOff: true)
                              : null,
                        ),
                        // The edit-mode drag proxy carries this card into the
                        // overlay, outside the scaffold body's glass marker.
                        child: glass
                            ? LiquidGlassBody(
                                child: LiquidGlass(
                                  style: pane,
                                  clipper: OutboundDropletClipper(
                                    phase: phase,
                                    fullness: progress,
                                    squeeze: squeeze,
                                    skin: skin,
                                    wobble: wobble,
                                  ),
                                  pressed: _pressed,
                                  child: child,
                                ),
                              )
                            : child,
                      );
                    },
                    child: child,
                  ),
                  child: child,
                ),
                child: child,
              ),
              child: SizedBox.square(
                dimension: size,
                child: Center(
                  child: widget.suspended
                      ? GlyphIcon(
                          AppGlyphs.wifiOff,
                          size: size * 0.34,
                          color: ink,
                          fill: 1,
                        )
                      : TweenAnimationBuilder<double>(
                          tween: Tween(end: widget.running ? 1 : 0),
                          duration: highContrast
                              ? Duration.zero
                              : context.motionDuration(_morphDuration),
                          curve: Curves.easeOutBack,
                          builder: (_, progress, _) => GlyphIcon(
                            AppGlyphs.playPause(progress),
                            size: size * 0.34,
                            color: ink,
                            fill: 1,
                          ),
                        ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
