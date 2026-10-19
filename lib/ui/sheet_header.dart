import 'package:fl_clash/ui/context.dart';
import 'package:fl_clash/ui/metrics.dart';
import 'package:fl_clash/ui/scroll_behavior.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';

import 'header_blur.dart';
import 'inherited.dart';
import 'liquid_glass.dart';

class SheetDragHandle extends StatelessWidget {
  const SheetDragHandle({super.key});

  static const height = _topPadding + _thickness;

  static const _topPadding = 6.0;
  static const _thickness = 4.0;
  static const _width = 28.0;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: _topPadding),
      child: Container(
        alignment: Alignment.center,
        height: _thickness,
        width: _width,
        decoration: ShapeDecoration(
          color: context.colorScheme.onSurfaceVariant,
          shape: AppShape.all(_thickness / 2),
        ),
      ),
    );
  }
}

/// A drag handle, or its space, over [appBar], together exactly
/// [sheetAppBarHeight] tall.
class SheetToolBar extends StatelessWidget {
  const SheetToolBar({super.key, required this.appBar, this.dragHandle = true});

  static const _trailing =
      sheetAppBarHeight - SheetDragHandle.height - sheetToolbarHeight;

  final Widget appBar;
  final bool dragHandle;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        dragHandle
            ? const SheetDragHandle()
            : const SizedBox(height: SheetDragHandle.height),
        appBar,
        const SizedBox(height: _trailing),
      ],
    );
  }
}

/// Fades the surface behind [child] toward its foot so content reads through,
/// easing out over [overhang] past the foot so the fade shows no edge.
class FloatingHeader extends StatelessWidget {
  const FloatingHeader({
    super.key,
    required this.backgroundColor,
    this.fadeStart = 0.5,
    this.overhang = 0,
    this.blur = false,
    this.liquidGlass = false,
    this.showBackground = true,
    required this.child,
  });

  static const _alpha = 0.9;
  static const _fadeSteps = 8;

  final Color backgroundColor;
  final double fadeStart;
  final double overhang;
  final bool blur;
  final bool liquidGlass;
  final bool showBackground;
  final Widget child;

  Gradient _scrimOf(double height) {
    final start = (height - overhang) * fadeStart / height;
    final stops = <double>[0];
    final colors = <Color>[backgroundColor.withValues(alpha: _alpha)];
    for (var i = 0; i <= _fadeSteps; i++) {
      final t = i / _fadeSteps;
      stops.add(start + (1 - start) * t);
      colors.add(
        backgroundColor.withValues(
          alpha: _alpha * (1 - Curves.easeInOut.transform(t)),
        ),
      );
    }
    return LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      stops: stops,
      colors: colors,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      clipBehavior: Clip.none,
      children: [
        if (showBackground)
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            bottom: -overhang,
            child: IgnorePointer(
              child: LayoutBuilder(
                builder: (_, constraints) => blur
                    ? HeaderBlur(
                        fadeHeight: constraints.maxHeight,
                        fullStrengthHeight:
                            (constraints.maxHeight - overhang) * 0.35,
                        liquidGlass: liquidGlass,
                      )
                    : DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: _scrimOf(constraints.maxHeight),
                        ),
                      ),
              ),
            ),
          ),
        child,
      ],
    );
  }
}

/// Floats [header] over [body], fading it out so content reads through it
/// as it scrolls past; [footer] sits on its own surface.
class FloatingHeaderBody extends StatefulWidget {
  const FloatingHeaderBody({
    super.key,
    required this.backgroundColor,
    required this.header,
    required this.body,
    this.footer,
  });

  final Color backgroundColor;
  final Widget header;
  final Widget body;
  final Widget? footer;

  @override
  State<FloatingHeaderBody> createState() => _FloatingHeaderBodyState();
}

class _FloatingHeaderBodyState extends State<FloatingHeaderBody> {
  bool _scrolledUnder = false;
  bool _pendingScrolledUnder = false;
  bool _scrollUpdateScheduled = false;

  void _updateScroll(ScrollMetrics metrics) {
    if (metrics.axis != Axis.vertical) return;
    _pendingScrolledUnder = metrics.extentBefore > 0;
    if (_scrollUpdateScheduled || _scrolledUnder == _pendingScrolledUnder)
      return;
    if (SchedulerBinding.instance.schedulerPhase !=
        SchedulerPhase.persistentCallbacks) {
      setState(() => _scrolledUnder = _pendingScrolledUnder);
      return;
    }
    _scrollUpdateScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollUpdateScheduled = false;
      if (!mounted || _scrolledUnder == _pendingScrolledUnder) return;
      setState(() => _scrolledUnder = _pendingScrolledUnder);
    });
  }

  @override
  Widget build(BuildContext context) {
    final enabled = LiquidGlassScope.of(context);
    final glass = enabled && !MediaQuery.highContrastOf(context);
    return Stack(
      children: [
        NotificationListener<ScrollNotification>(
          onNotification: (notification) {
            if (notification.depth == 0) _updateScroll(notification.metrics);
            return false;
          },
          child: NotificationListener<ScrollMetricsNotification>(
            onNotification: (notification) {
              if (notification.depth == 0) _updateScroll(notification.metrics);
              return false;
            },
            child: ScrollConfiguration(
              behavior: const ShowBarScrollBehavior(),
              child: widget.body,
            ),
          ),
        ),
        Positioned(
          top: 0,
          left: 0,
          right: 0,
          child: FloatingHeader(
            backgroundColor: widget.backgroundColor,
            blur: enabled,
            liquidGlass: glass,
            showBackground: !glass || _scrolledUnder,
            child: widget.header,
          ),
        ),
        if (widget.footer != null)
          Positioned(
            bottom: 0,
            left: 0,
            right: 0,
            child: SheetOverhangLift(
              child: Material(
                type: MaterialType.transparency,
                child: widget.footer,
              ),
            ),
          ),
      ],
    );
  }
}

/// Holds its child clear of the part of the sheet that hangs below the screen
/// while the sheet is dragged below its shortest detent.
class SheetOverhangLift extends SingleChildRenderObjectWidget {
  const SheetOverhangLift({super.key, super.child});

  @override
  RenderObject createRenderObject(BuildContext context) {
    return _RenderSheetOverhangLift(overhang: SheetOverhangScope.of(context));
  }

  @override
  void updateRenderObject(BuildContext context, RenderObject renderObject) {
    (renderObject as _RenderSheetOverhangLift).overhang = SheetOverhangScope.of(
      context,
    );
  }
}

class _RenderSheetOverhangLift extends RenderTransform {
  _RenderSheetOverhangLift({required ValueListenable<double>? overhang})
    : _overhang = overhang,
      super(transform: Matrix4.identity(), transformHitTests: true) {
    _overhang?.addListener(_syncTransform);
  }

  ValueListenable<double>? _overhang;

  set overhang(ValueListenable<double>? value) {
    if (_overhang == value) {
      return;
    }
    _overhang?.removeListener(_syncTransform);
    _overhang = value;
    _overhang?.addListener(_syncTransform);
    _syncTransform();
  }

  @override
  void performLayout() {
    super.performLayout();
    _syncTransform();
  }

  @override
  void dispose() {
    _overhang?.removeListener(_syncTransform);
    super.dispose();
  }

  void _syncTransform() {
    transform = Matrix4.translationValues(0, -(_overhang?.value ?? 0), 0);
  }
}
