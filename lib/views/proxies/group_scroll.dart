import 'dart:math';

import 'package:fl_clash/ui/scroll_behavior.dart';
import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

class ProxyGroupScrollController extends ScrollController
    with SheetScrollController {
  ProxyGroupScrollController({required this.outer, required this.anchor});

  final ScrollController outer;
  final double Function() anchor;
  double _lastPixels = 0;

  double get contentOffset =>
      hasClients && position.hasPixels ? offset : _lastPixels;

  @override
  ValueListenable<bool> get sheetOpen {
    final controller = outer;
    return controller is SheetScrollController
        ? controller.sheetOpen
        : const AlwaysStoppedAnimation(true);
  }

  @override
  void detach(ScrollPosition position) {
    if (position.hasPixels) _lastPixels = position.pixels;
    super.detach(position);
  }

  @override
  ScrollPosition createScrollPosition(
    ScrollPhysics physics,
    ScrollContext context,
    ScrollPosition? oldPosition,
  ) => _GroupScrollPosition(
    physics: physics,
    context: context,
    oldPosition: oldPosition,
    outer: outer,
    anchor: anchor,
    initialPixels: _lastPixels,
  );
}

class _GroupScrollPosition extends ScrollPositionWithSingleContext {
  _GroupScrollPosition({
    required super.physics,
    required super.context,
    super.oldPosition,
    super.initialPixels,
    required this.outer,
    required this.anchor,
  }) : super(keepScrollOffset: false);

  final ScrollController outer;
  final double Function() anchor;
  double? _ballisticPixels;
  bool _pointerScrolling = false;
  DragStartDetails? _dragStart;
  DragUpdateDetails? _dragUpdate;
  Drag? _outerDrag;
  bool _lastMoveWasOuter = false;

  double _moveInner(double delta) {
    final target = (pixels + delta).clamp(minScrollExtent, maxScrollExtent);
    final before = pixels;
    if (target != before) _lastMoveWasOuter = false;
    if (_pointerScrolling) {
      if (target != before) {
        forcePixels(target);
        didUpdateScrollPositionBy(target - before);
      }
    } else {
      super.setPixels(target);
    }
    return delta - (pixels - before);
  }

  double _moveOuter(double delta, double limit, {bool atBoundary = false}) {
    final position = outer.position;
    final target = (position.pixels + delta).clamp(
      min(position.pixels, limit),
      max(position.pixels, limit),
    );
    final before = position.pixels;
    final consumed = target - before;
    if (consumed != 0 || atBoundary) {
      _lastMoveWasOuter = true;
      if (_pointerScrolling) {
        final amount = atBoundary ? delta : consumed;
        position.pointerScroll(amount);
        return delta - amount;
      } else if (_dragStart case final start?) {
        _outerDrag ??= position.drag(start, () => _outerDrag = null);
        final amount = atBoundary ? delta : consumed;
        _outerDrag!.update(
          DragUpdateDetails(
            sourceTimeStamp: _dragUpdate?.sourceTimeStamp,
            delta: Offset(0, -amount),
            primaryDelta: -amount,
            globalPosition: _dragUpdate?.globalPosition ?? start.globalPosition,
            localPosition: _dragUpdate?.localPosition ?? start.localPosition,
          ),
        );
        return delta - amount;
      } else if (consumed != 0) {
        position.jumpTo(target.toDouble());
      }
    }
    return delta - (position.pixels - before);
  }

  double _consume(double delta) {
    if (!hasPixels || !hasContentDimensions) return delta;
    if (!outer.hasClients ||
        !outer.position.hasPixels ||
        !outer.position.hasContentDimensions) {
      return _moveInner(delta);
    }
    final position = outer.position;
    final controller = outer;
    if ((_dragStart != null || _pointerScrolling) &&
        controller is SheetScrollController &&
        !controller.sheetOpen.value) {
      return _moveOuter(
        delta,
        delta > 0 ? position.maxScrollExtent : position.minScrollExtent,
        atBoundary: true,
      );
    }
    final pinned = anchor().clamp(
      position.minScrollExtent,
      position.maxScrollExtent,
    );
    var remaining = delta;
    if ((delta > 0 && position.pixels < pinned) ||
        (delta < 0 && position.pixels > pinned)) {
      remaining = _moveOuter(remaining, pinned);
    }
    remaining = _moveInner(remaining);
    if (remaining != 0) {
      remaining = _moveOuter(
        remaining,
        remaining > 0 ? position.maxScrollExtent : position.minScrollExtent,
        atBoundary: true,
      );
    }
    return remaining;
  }

  @override
  void applyUserOffset(double delta) {
    updateUserScrollDirection(
      delta > 0 ? ScrollDirection.forward : ScrollDirection.reverse,
    );
    final remaining = _consume(-physics.applyPhysicsToUserOffset(this, delta));
    if (remaining != 0) didOverscrollBy(remaining);
  }

  @override
  void pointerScroll(double delta) {
    if (delta == 0) return;
    goIdle();
    updateUserScrollDirection(
      delta < 0 ? ScrollDirection.forward : ScrollDirection.reverse,
    );
    _pointerScrolling = true;
    isScrollingNotifier.value = true;
    didStartScroll();
    try {
      _consume(delta);
    } finally {
      _pointerScrolling = false;
      didEndScroll();
      goIdle();
    }
  }

  @override
  Drag drag(DragStartDetails details, VoidCallback dragCancelCallback) {
    _dragStart = details;
    _lastMoveWasOuter = false;
    return _GroupDrag(this, super.drag(details, dragCancelCallback));
  }

  @override
  void dispose() {
    _outerDrag?.cancel();
    _outerDrag = null;
    super.dispose();
  }

  @override
  double setPixels(double newPixels) {
    if (!hasPixels || !hasContentDimensions) {
      goIdle();
      return hasPixels ? newPixels - pixels : 0;
    }
    if (activity is BallisticScrollActivity && _ballisticPixels != null) {
      // The simulation advances a combined distance, not the inner offset.
      final delta = newPixels - _ballisticPixels!;
      _ballisticPixels = newPixels;
      final remaining = _consume(delta);
      if (outer.hasClients &&
          outer.position.hasPixels &&
          outer.position.hasContentDimensions &&
          outer.position.hasViewportDimension &&
          ((delta > 0 && pixels >= maxScrollExtent) ||
              (delta < 0 && pixels <= minScrollExtent))) {
        final velocity = activity!.velocity;
        goIdle();
        final position = outer.position;
        if (position is ScrollPositionWithSingleContext) {
          position.goBallistic(velocity);
        }
      }
      return remaining;
    }
    return super.setPixels(newPixels);
  }

  @override
  void goBallistic(double velocity) {
    _ballisticPixels = null;
    if (!hasPixels || !hasContentDimensions || !hasViewportDimension) {
      goIdle();
      return;
    }
    if (!outer.hasClients ||
        !outer.position.hasPixels ||
        !outer.position.hasContentDimensions ||
        !outer.position.hasViewportDimension) {
      super.goBallistic(velocity);
      return;
    }
    final position = outer.position;
    final metrics = FixedScrollMetrics(
      minScrollExtent:
          minScrollExtent - pixels + position.minScrollExtent - position.pixels,
      maxScrollExtent:
          maxScrollExtent - pixels + position.maxScrollExtent - position.pixels,
      pixels: 0,
      viewportDimension: viewportDimension,
      axisDirection: axisDirection,
      devicePixelRatio: devicePixelRatio,
    );
    final simulation = physics.createBallisticSimulation(metrics, velocity);
    if (simulation == null) {
      goIdle();
      return;
    }
    _ballisticPixels = 0;
    beginActivity(
      BallisticScrollActivity(this, simulation, context.vsync, true),
    );
  }
}

class _GroupDrag implements Drag {
  _GroupDrag(this.position, this.inner);

  final _GroupScrollPosition position;
  final Drag inner;

  @override
  void update(DragUpdateDetails details) {
    position._dragUpdate = details;
    inner.update(details);
  }

  @override
  void end(DragEndDetails details) {
    final outer = position._outerDrag;
    position._outerDrag = null;
    position._dragStart = null;
    if (outer != null && position._lastMoveWasOuter) {
      inner.end(DragEndDetails(primaryVelocity: 0));
      outer.end(details);
    } else {
      outer?.end(DragEndDetails(primaryVelocity: 0));
      inner.end(details);
    }
  }

  @override
  void cancel() {
    position._outerDrag?.cancel();
    position._outerDrag = null;
    position._dragStart = null;
    inner.cancel();
  }
}
