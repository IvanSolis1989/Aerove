import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

/// Writes to [visible] whether [child] overlaps the nearest viewport, true
/// without one, from the end of the first frame that lays [child] out.
class ViewportVisibility extends StatefulWidget {
  const ViewportVisibility({
    super.key,
    required this.visible,
    required this.child,
  });

  final ValueNotifier<bool> visible;
  final Widget child;

  @override
  State<ViewportVisibility> createState() => _ViewportVisibilityState();
}

class _ViewportVisibilityState extends State<ViewportVisibility> {
  ScrollPosition? _position;
  var _ticking = true;
  var _scheduled = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final position = Scrollable.maybeOf(context)?.position;
    if (position != _position) {
      _position?.removeListener(_schedule);
      _position = position?..addListener(_schedule);
    }
    _ticking = TickerMode.valuesOf(context).enabled;
    _schedule();
  }

  @override
  void didUpdateWidget(ViewportVisibility oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.visible != widget.visible) _schedule();
  }

  @override
  void dispose() {
    _position?.removeListener(_schedule);
    super.dispose();
  }

  void _schedule() {
    if (_scheduled) return;
    _scheduled = true;
    SchedulerBinding.instance.addPostFrameCallback(_measure);
  }

  void _measure(Duration _) {
    _scheduled = false;
    if (!mounted) return;
    if (context.findRenderObject() case final _ViewportVisibilityBox box) {
      if (box.inViewport case final visible?) widget.visible.value = visible;
    }
    // A card above can move this one without repainting it or scrolling, so
    // each frame drawn while the page ticks measures again; none is asked for.
    if (_ticking) _schedule();
  }

  @override
  Widget build(BuildContext context) =>
      _ViewportVisibilityProxy(onPaint: _schedule, child: widget.child);
}

class _ViewportVisibilityProxy extends SingleChildRenderObjectWidget {
  const _ViewportVisibilityProxy({required this.onPaint, required super.child});

  final VoidCallback onPaint;

  @override
  _ViewportVisibilityBox createRenderObject(BuildContext context) =>
      _ViewportVisibilityBox(onPaint);

  @override
  void updateRenderObject(
    BuildContext context,
    _ViewportVisibilityBox renderObject,
  ) => renderObject.onPaint = onPaint;
}

class _ViewportVisibilityBox extends RenderProxyBox {
  _ViewportVisibilityBox(this.onPaint);

  VoidCallback onPaint;

  bool? get inViewport {
    if (!attached || !hasSize) return null;
    if (RenderAbstractViewport.maybeOf(this) case final RenderBox viewport
        when viewport.hasSize) {
      return MatrixUtils.transformRect(
        getTransformTo(viewport),
        paintBounds,
      ).overlaps(Offset.zero & viewport.size);
    }
    return true;
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    onPaint();
    super.paint(context, offset);
  }
}
