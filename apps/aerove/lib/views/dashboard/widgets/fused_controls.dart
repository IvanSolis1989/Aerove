import 'dart:math' as math;

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/dashboard/label_breaks.dart';
import 'package:fl_clash/views/dashboard/style.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'card.dart';
import 'intranet_ip.dart';

/// The phone's network card: one row of up to three buttons, in this order,
/// for the members a layout keeps.
class FusedNetworkControls extends StatelessWidget {
  const FusedNetworkControls({super.key, required this.members});

  final Set<DashboardWidget> members;

  @override
  Widget build(BuildContext context) => DashboardCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        DashboardCardHeader(
          glyph: AppGlyphs.split,
          title: context.appLocalizations.dashboardNetworkControl,
        ),
        const SizedBox(height: 12),
        _NetworkControlRow(
          mode: members.contains(DashboardWidget.outboundMode),
          vpn: members.contains(DashboardWidget.vpnButton),
          lan: members.contains(DashboardWidget.intranetIp),
        ),
      ],
    ),
  );
}

enum _Control { mode, vpn, lan }

class _NetworkControlRow extends ConsumerStatefulWidget {
  const _NetworkControlRow({
    required this.mode,
    required this.vpn,
    required this.lan,
  });

  final bool mode;
  final bool vpn;
  final bool lan;

  @override
  ConsumerState<_NetworkControlRow> createState() => _NetworkControlRowState();
}

class _NetworkControlRowState extends ConsumerState<_NetworkControlRow> {
  int _lanGeneration = 0;
  ValueListenable<bool>? _pageActivity;

  bool get _active =>
      mounted &&
      ref.read(appVisibleProvider) &&
      PageActivityScope.isActiveOf(context) &&
      (ModalRoute.of(context)?.isCurrent ?? true);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final activity = PageActivityScope.activityOf(context);
    if (!identical(activity, _pageActivity)) {
      _pageActivity?.removeListener(_onPageActivity);
      _pageActivity = activity;
      _pageActivity?.addListener(_onPageActivity);
    }
    if (!_active) _lanGeneration++;
  }

  void _onPageActivity() {
    if (_pageActivity?.value == false) _lanGeneration++;
  }

  @override
  void dispose() {
    _pageActivity?.removeListener(_onPageActivity);
    super.dispose();
  }

  Future<void> _toggleLan() async {
    final coreSubscription = ref.listenManual(
      coreHandlerProvider,
      (_, _) => _lanGeneration++,
    );
    try {
      final enabled = !ref.read(patchClashConfigProvider).allowLan;
      final receipt = ref
          .read(setupActionProvider.notifier)
          .setAllowLan(enabled);
      final generation = ++_lanGeneration;
      final applied = await receipt;
      bool current() =>
          mounted &&
          generation == _lanGeneration &&
          _active &&
          ref.read(patchClashConfigProvider).allowLan;
      if (!mounted || !enabled || !applied || !current()) return;
      final port = ref.read(patchClashConfigProvider).mixedPort;
      try {
        await ref.read(systemActionProvider.notifier).updateLocalIp();
        if (!mounted ||
            !current() ||
            ref.read(patchClashConfigProvider).mixedPort != port) {
          return;
        }
        final address = lanProxyAddress(ref.read(localIpProvider), port);
        if (address == null) {
          context.showNotifier(context.appLocalizations.lanEnabledNoAddress);
          return;
        }
        await copyToClipboard(
          context,
          address,
          message: context.appLocalizations.lanAddressCopied(address),
          isCurrent: current,
        );
      } catch (_) {
        if (!mounted || !current()) return;
        final address = lanProxyAddress(ref.read(localIpProvider), port);
        context.showNotifier(
          address == null
              ? context.appLocalizations.lanEnabledNoAddress
              : context.appLocalizations.lanAddressCopyFailed(address),
          level: MessageLevel.error,
        );
      }
    } finally {
      coreSubscription.close();
    }
  }

  static const _outer = 26.0;
  static const _inner = 12.0;

  @override
  Widget build(BuildContext context) {
    ref.listen(appVisibleProvider, (_, visible) {
      if (!visible) _lanGeneration++;
    });
    ref.listen(patchClashConfigProvider, (_, _) => _lanGeneration++);
    ref.listen(currentProfileIdProvider, (_, _) => _lanGeneration++);
    ref.listen(isStartProvider, (_, _) => _lanGeneration++);
    ref.listen(coreStatusProvider, (_, status) {
      if (status == CoreStatus.disconnected ||
          !ref.read(setupActionProvider.notifier).isLanEnableRestarting) {
        _lanGeneration++;
      }
    });
    final l10n = context.appLocalizations;
    final current = ref.watch(
      patchClashConfigProvider.select((state) => state.mode),
    );
    final vpnOn = ref.watch(vpnSettingProvider.select((state) => state.enable));
    final lanOn = ref.watch(
      patchClashConfigProvider.select((state) => state.allowLan),
    );
    final controls = [
      if (widget.mode) _Control.mode,
      if (widget.vpn) _Control.vpn,
      if (widget.lan) _Control.lan,
    ];
    if (controls.isEmpty) return const SizedBox.shrink();
    final next = Mode.values[(current.index + 1) % Mode.values.length];
    final tone = DashboardColors.of(context).tone;
    final scaler = MediaQuery.textScalerOf(context);
    final candidates = [
      if (widget.mode)
        for (final item in Mode.values) item.label,
      if (widget.vpn) 'VPN',
      if (widget.lan) l10n.allowLan,
    ];
    Widget button(int index, _Control control, _ControlPlan plan) {
      final on = switch (control) {
        _Control.mode => false,
        _Control.vpn => vpnOn,
        _Control.lan => lanOn,
      };
      final (glyph, label) = switch (control) {
        _Control.mode => (_glyphOf(current), current.label),
        _Control.vpn => (AppGlyphs.vpn, 'VPN'),
        _Control.lan => (AppGlyphs.devices, l10n.allowLan),
      };
      return DashboardConnectedButton(
        key: ValueKey('dashboard-${control.name}'),
        pane: true,
        pill: true,
        selected: on,
        first: index == 0,
        last: index == controls.length - 1,
        outer: _outer,
        inner: _inner,
        background: tone,
        semanticLabel: control == _Control.mode
            ? l10n.outboundModeCurrent(current.label)
            : label,
        toggled: control == _Control.mode ? null : on,
        tapHint: control == _Control.mode
            ? l10n.outboundModeNext(next.label)
            : null,
        liveRegion: control == _Control.mode,
        onTap: () => switch (control) {
          _Control.mode => ref.read(setupActionProvider.notifier).cycleMode(),
          _Control.vpn =>
            ref
                .read(vpnSettingProvider.notifier)
                .update((state) => state.copyWith(enable: !state.enable)),
          _Control.lan => _toggleLan(),
        },
        builder: (foreground) => _ControlFace(
          name: control.name,
          glyph: glyph,
          label: plan.shown[label] ?? label,
          scale: plan.scale,
          inset: plan.inset,
          style: AppTextStyle.footnote
              .of(context, emphasized: on)
              .copyWith(color: foreground),
          foreground: foreground,
        ),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final plan = _ControlPlan.of(
          width: constraints.maxWidth,
          count: controls.length,
          candidates: candidates,
          style: DefaultTextStyle.of(
            context,
          ).style.merge(AppTextStyle.footnote.of(context, emphasized: true)),
          scaler: scaler,
          locale: Localizations.maybeLocaleOf(context),
          direction: Directionality.of(context),
          minHeight: 52 * math.max(1, scaler.scale(1)),
        );
        return _ControlRow(
          height: plan.height,
          children: [
            for (final (index, control) in controls.indexed)
              button(index, control, plan),
          ],
        );
      },
    );
  }
}

/// How the row shows every label a button can take, worked out once per
/// width: each whole on one line where it fits, else in its two most even
/// lines; past that the pills' inset narrows from 8 to 4 and then all labels
/// shrink together, down to [minLabelSize]. Only a word too long even then
/// breaks.
class _ControlPlan {
  const _ControlPlan({
    required this.shown,
    required this.scale,
    required this.inset,
    required this.height,
  });

  static const gap = 3.0;
  static const insets = [8.0, 4.0];

  /// A pill's block padding once its content outgrows the minimum height.
  static const pad = 7.0;
  static const minLabelSize = 11.0;

  final Map<String, String> shown;
  final double scale;
  final double inset;
  final double height;

  static (Object, _ControlPlan)? _last;

  factory _ControlPlan.of({
    required double width,
    required int count,
    required List<String> candidates,
    required TextStyle style,
    required TextScaler scaler,
    required Locale? locale,
    required TextDirection direction,
    required double minHeight,
  }) {
    final key = (
      width,
      count,
      candidates.join('\n'),
      style,
      scaler,
      locale,
      direction,
      minHeight,
    );
    if (_last case (final last, final plan) when last == key) return plan;
    final painter = TextPainter(
      textAlign: TextAlign.center,
      textDirection: direction,
      textScaler: scaler,
      locale: locale,
    );
    Size measure(String text) =>
        (painter
              ..text = TextSpan(text: text, style: style)
              ..layout())
            .size;
    double widthOf(String text) => measure(text).width;
    final forms = [
      for (final label in candidates)
        (
          label: label,
          single: widthOf(label),
          split: balancedLabel(label, widthOf),
        ),
    ];
    double fit(double room) {
      var scale = 1.0;
      for (final form in forms) {
        final narrowest = form.split?.width ?? form.single;
        if (form.single > room && narrowest > room) {
          scale = math.min(scale, room / narrowest);
        }
      }
      return scale;
    }

    final cell = (width - gap * (count - 1)) / math.max(1, count);
    final inset = insets.firstWhere(
      (inset) => fit(cell - 2 * inset) >= 1,
      orElse: () => insets.last,
    );
    final room = math.max(1.0, cell - 2 * inset);
    final floor = math.min(
      1.0,
      minLabelSize / scaler.scale(style.fontSize ?? minLabelSize),
    );
    final scale = math.max(floor, fit(room));
    final shown = <String, String>{};
    var label = 0.0;
    for (final form in forms) {
      final text = form.single * scale <= room + 0.01 || form.split == null
          ? form.label
          : form.split!.text;
      shown[form.label] = text;
      label = math.max(label, measure(text).height * scale);
    }
    painter.dispose();
    final plan = _ControlPlan(
      shown: shown,
      scale: scale,
      inset: inset,
      height: math.max(
        minHeight,
        _FaceLayout.iconSize + _FaceLayout.spacing + label + 2 * pad,
      ),
    );
    _last = (key, plan);
    return plan;
  }
}

Glyph _glyphOf(Mode mode) => switch (mode) {
  Mode.rule => AppGlyphs.rules,
  Mode.global => AppGlyphs.proxies,
  Mode.direct => AppGlyphs.target,
};

/// A button's icon over its label; a new mode fades in over the old.
class _ControlFace extends StatelessWidget {
  const _ControlFace({
    required this.name,
    required this.glyph,
    required this.label,
    required this.scale,
    required this.inset,
    required this.style,
    required this.foreground,
  });

  final String name;
  final Glyph glyph;
  final String label;
  final double scale;
  final double inset;
  final TextStyle style;
  final Color foreground;

  static const _duration = Duration(milliseconds: 240);

  @override
  Widget build(BuildContext context) {
    final duration = context.motionDuration(_duration);
    return _FaceLayout(
      scale: scale,
      inset: inset,
      children: [
        AnimatedSwitcher(
          duration: duration,
          child: GlyphIcon(
            glyph,
            key: ValueKey(('$name-icon', glyph)),
            size: _FaceLayout.iconSize,
            color: foreground,
          ),
        ),
        AnimatedSwitcher(
          duration: duration,
          child: GlassText(
            label,
            key: ValueKey(('$name-label', label)),
            textAlign: TextAlign.center,
            style: style,
          ),
        ),
      ],
    );
  }
}

/// Lays out [children] as equal pills [height] tall on one row; the button
/// pressed last paints over its neighbours.
class _ControlRow extends MultiChildRenderObjectWidget {
  const _ControlRow({required this.height, required super.children});

  final double height;

  @override
  _RenderControlRow createRenderObject(BuildContext context) =>
      _RenderControlRow(height, Directionality.of(context));

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderControlRow renderObject,
  ) {
    renderObject
      ..height = height
      ..textDirection = Directionality.of(context);
  }
}

class _ControlRowParentData extends ContainerBoxParentData<RenderBox> {}

class _RenderControlRow extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _ControlRowParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _ControlRowParentData> {
  _RenderControlRow(this._height, this._textDirection);

  double _height;
  set height(double value) {
    if (value == _height) return;
    _height = value;
    markNeedsLayout();
  }

  TextDirection _textDirection;
  set textDirection(TextDirection value) {
    if (value == _textDirection) return;
    _textDirection = value;
    markNeedsLayout();
  }

  RenderBox? _raised;

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _ControlRowParentData) {
      child.parentData = _ControlRowParentData();
    }
  }

  @override
  void performLayout() {
    final count = childCount;
    if (count == 0) {
      size = constraints.smallest;
      return;
    }
    final width = constraints.maxWidth;
    const gap = _ControlPlan.gap;
    final cell = (width - gap * (count - 1)) / count;
    final rtl = _textDirection == TextDirection.rtl;
    var index = 0;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      child.layout(BoxConstraints.tightFor(width: cell, height: _height));
      final start = index * (cell + gap);
      (child.parentData! as _ControlRowParentData).offset = Offset(
        rtl ? width - start - cell : start,
        0,
      );
      index++;
    }
    size = constraints.constrain(Size(width, _height));
  }

  @override
  void handleEvent(PointerEvent event, BoxHitTestEntry entry) {
    if (event is! PointerDownEvent) return;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      final offset = (child.parentData! as _ControlRowParentData).offset;
      if ((offset & child.size).contains(entry.localPosition)) {
        if (child != _raised) {
          _raised = child;
          markNeedsPaint();
        }
        return;
      }
    }
  }

  @override
  bool hitTestSelf(Offset position) => size.contains(position);

  @override
  void dropChild(RenderObject child) {
    if (child == _raised) _raised = null;
    super.dropChild(child);
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    void paint(RenderBox child) => context.paintChild(
      child,
      (child.parentData! as _ControlRowParentData).offset + offset,
    );
    for (var child = firstChild; child != null; child = childAfter(child)) {
      if (child != _raised) paint(child);
    }
    if (_raised case final raised?) paint(raised);
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}

/// An icon centred over its label, the label laid out within the pill's
/// [inset] and drawn at [scale] of its size.
class _FaceLayout extends MultiChildRenderObjectWidget {
  const _FaceLayout({
    required this.scale,
    required this.inset,
    required super.children,
  });

  static const iconSize = 18.0;
  static const spacing = 2.0;

  final double scale;
  final double inset;

  @override
  _RenderFaceLayout createRenderObject(BuildContext context) =>
      _RenderFaceLayout(scale, inset);

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderFaceLayout renderObject,
  ) {
    renderObject
      ..scale = scale
      ..inset = inset;
  }
}

class _FaceParentData extends ContainerBoxParentData<RenderBox> {}

class _RenderFaceLayout extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _FaceParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _FaceParentData> {
  _RenderFaceLayout(this._scale, this._inset);

  final _shrink = LayerHandle<TransformLayer>();

  double _scale;
  set scale(double value) {
    if (value == _scale) return;
    _scale = value;
    markNeedsLayout();
  }

  double _inset;
  set inset(double value) {
    if (value == _inset) return;
    _inset = value;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _FaceParentData) {
      child.parentData = _FaceParentData();
    }
  }

  RenderBox? get _label => firstChild == null ? null : childAfter(firstChild!);

  Matrix4 _labelTransform(Offset offset) =>
      Matrix4.translationValues(offset.dx, offset.dy, 0)
        ..scaleByDouble(_scale, _scale, 1, 1);

  @override
  void performLayout() {
    final icon = firstChild;
    final label = icon == null ? null : childAfter(icon);
    if (icon == null || label == null) {
      size = constraints.smallest;
      return;
    }
    final width = constraints.maxWidth;
    icon.layout(const BoxConstraints(), parentUsesSize: true);
    // The plan measured each line to fit; the slack absorbs rounding only.
    final room = math.max(0.0, width - 2 * _inset) / _scale + 0.5;
    label.layout(BoxConstraints(maxWidth: room), parentUsesSize: true);
    final shown = label.size * _scale;
    final content = icon.size.height + _FaceLayout.spacing + shown.height;
    final height = constraints.constrainHeight(content + 2 * _ControlPlan.pad);
    final top = (height - content) / 2;
    _place(icon, Offset((width - icon.size.width) / 2, top));
    _place(
      label,
      Offset(
        (width - shown.width) / 2,
        top + icon.size.height + _FaceLayout.spacing,
      ),
    );
    size = constraints.constrain(Size(width, height));
  }

  void _place(RenderBox child, Offset offset) =>
      (child.parentData! as _FaceParentData).offset = offset;

  Offset _offsetOf(RenderBox child) =>
      (child.parentData! as _FaceParentData).offset;

  @override
  void paint(PaintingContext context, Offset offset) {
    final label = _label;
    for (var child = firstChild; child != null; child = childAfter(child)) {
      final box = child;
      if (box == label && _scale != 1) {
        _shrink.layer = context.pushTransform(
          needsCompositing,
          offset,
          _labelTransform(_offsetOf(box)),
          (context, offset) => context.paintChild(box, offset),
          oldLayer: _shrink.layer,
        );
      } else {
        context.paintChild(box, _offsetOf(box) + offset);
      }
    }
    if (_scale == 1) _shrink.layer = null;
  }

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    if (child == _label && _scale != 1) {
      transform.multiply(_labelTransform(_offsetOf(child)));
    } else {
      super.applyPaintTransform(child, transform);
    }
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);

  @override
  void dispose() {
    _shrink.layer = null;
    super.dispose();
  }
}
