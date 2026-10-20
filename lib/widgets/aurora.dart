import 'dart:async';
import 'dart:ui' show ImageFilter, Vertices, VertexMode;

import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/common/aurora_motion.dart';
import 'package:fl_clash/ui/aurora.dart';
import 'package:fl_clash/ui/frame_pacer.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/typography.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/surface.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

export 'package:fl_clash/ui/aurora.dart';

class AuroraAppSurface extends ConsumerStatefulWidget {
  const AuroraAppSurface({super.key, this.motion = true, required this.child});

  final bool motion;
  final Widget child;

  @override
  ConsumerState<AuroraAppSurface> createState() => _AuroraAppSurfaceState();
}

class _AuroraAppSurfaceState extends ConsumerState<AuroraAppSurface>
    with WidgetsBindingObserver {
  late bool _foreground;
  late bool _visible;

  @override
  void initState() {
    super.initState();
    _foreground = switch (WidgetsBinding.instance.lifecycleState) {
      null || AppLifecycleState.resumed => true,
      _ => false,
    };
    _visible = switch (WidgetsBinding.instance.lifecycleState) {
      null || AppLifecycleState.resumed || AppLifecycleState.inactive => true,
      _ => false,
    };
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final foreground = state == AppLifecycleState.resumed;
    final visible = foreground || state == AppLifecycleState.inactive;
    if (_foreground == foreground && _visible == visible) return;
    setState(() {
      _foreground = foreground;
      _visible = visible;
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final settings = ref.watch(
      themeSettingProvider.select(
        (state) => (
          enabled: state.auroraEnabled,
          colors: state.auroraColors,
          seed: state.auroraSeed,
          pureBlack: state.pureBlack,
          liquidGlass: state.liquidGlass,
        ),
      ),
    );
    final opaque =
        MediaQuery.highContrastOf(context) ||
        (settings.pureBlack && theme.brightness == Brightness.dark);
    final enabled =
        theme.platform == TargetPlatform.android && settings.enabled && !opaque;
    final glassColors = auroraColorScheme(theme.brightness);
    final glassTheme = theme
        .withNeutralTextColors(glassColors)
        .copyWith(
          colorScheme: glassColors,
          extensions: [
            for (final extension in theme.extensions.values)
              if (extension is ElevatedColorScheme)
                ElevatedColorScheme(glassColors.elevated)
              else
                extension,
          ],
        )
        .withAppSurfaces;
    final glassMode = settings.liquidGlass;
    final glassEnabled = glassMode != LiquidGlassMode.none && !opaque;
    final surfaceTheme = enabled ? glassTheme : theme;
    final surfaceColors = surfaceTheme.colorScheme;
    final glassPane = LiquidGlass.buttonStyle(
      (_) => LiquidGlassStyle.regular(surfaceColors),
    );
    ButtonStyle neutralGlass(ButtonStyle? original, {bool outlined = false}) {
      final style = glassPane
          .merge(original)
          .copyWith(
            backgroundBuilder: (context, states, child) => LiquidGlass(
              style: LiquidGlassStyle.regular(surfaceColors),
              clipper: ShapeBorderClipper(
                shape: original?.shape?.resolve(states) ?? AppShape.full,
                textDirection: Directionality.of(context),
              ),
              pressed: states.contains(WidgetState.pressed),
              child: child,
            ),
          );
      if (!outlined) return style;
      return style.copyWith(
        side: WidgetStateProperty.resolveWith((states) {
          return original?.side?.resolve(states) ??
              (states.contains(WidgetState.focused)
                  ? focusRingSide(surfaceColors)
                  : BorderSide.none);
        }),
      );
    }

    final glassButtons =
        LiquidGlass.buttonStyle(
          (_) => LiquidGlassStyle.regular(surfaceColors),
        ).merge(
          FilledButton.styleFrom(
            backgroundColor: Colors.transparent,
            foregroundColor: surfaceColors.onSurface,
          ),
        );
    final effectiveTheme = glassEnabled
        ? surfaceTheme.copyWith(
            scaffoldBackgroundColor: Colors.transparent,
            filledButtonTheme: FilledButtonThemeData(
              style: glassButtons.merge(surfaceTheme.filledButtonTheme.style),
            ),
            outlinedButtonTheme: OutlinedButtonThemeData(
              style: neutralGlass(
                surfaceTheme.outlinedButtonTheme.style,
                outlined: true,
              ),
            ),
            textButtonTheme: TextButtonThemeData(
              style: neutralGlass(surfaceTheme.textButtonTheme.style),
            ),
            iconButtonTheme: IconButtonThemeData(
              style: neutralGlass(surfaceTheme.iconButtonTheme.style),
            ),
            elevatedButtonTheme: ElevatedButtonThemeData(
              style: neutralGlass(surfaceTheme.elevatedButtonTheme.style),
            ),
            floatingActionButtonTheme: theme.floatingActionButtonTheme.copyWith(
              backgroundColor: surfaceColors.surfaceContainer.withValues(
                alpha: 0.28,
              ),
              foregroundColor: surfaceColors.onSurface,
            ),
          )
        : enabled
        ? surfaceTheme.copyWith(scaffoldBackgroundColor: Colors.transparent)
        : theme;
    final surface = LiquidGlassScope(
      enabled: glassEnabled,
      quality: glassMode == LiquidGlassMode.ultra
          ? LiquidGlassQuality.ultra
          : LiquidGlassQuality.standard,
      child: LiquidGlassHost(
        enabled: glassEnabled,
        wallpaperOnly: true,
        child: AuroraAppearance(
          enabled: enabled,
          child: Theme(
            data: effectiveTheme,
            child: Stack(
              fit: StackFit.expand,
              children: [
                Positioned.fill(
                  child: LiquidGlassColorField(
                    child: enabled
                        ? IgnorePointer(
                            child: AnimatedSwitcher(
                              duration: MediaQuery.disableAnimationsOf(context)
                                  ? Duration.zero
                                  : const Duration(milliseconds: 350),
                              child: SizedBox.expand(
                                key: ValueKey((
                                  settings.seed,
                                  Object.hashAll(settings.colors),
                                  theme.brightness,
                                )),
                                child: SeededAuroraWallpaper(
                                  colors: [
                                    for (final color in settings.colors)
                                      Color(color),
                                  ],
                                  seed: settings.seed,
                                  brightness: theme.brightness,
                                  motion: widget.motion,
                                ),
                              ),
                            ),
                          )
                        : ColoredBox(color: theme.scaffoldBackgroundColor),
                  ),
                ),
                widget.child,
              ],
            ),
          ),
        ),
      ),
    );
    return TickerMode(
      enabled: _foreground,
      child: AuroraMotionActivity(
        enabled: enabled && widget.motion,
        child: Offstage(offstage: !_visible, child: surface),
      ),
    );
  }
}

class AuroraMotionActivity extends StatefulWidget {
  const AuroraMotionActivity({
    super.key,
    this.enabled = true,
    required this.child,
  });

  final bool enabled;
  final Widget child;

  static ValueListenable<int>? rateOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<_AuroraActivityRate>()?.rate;

  @override
  State<AuroraMotionActivity> createState() => _AuroraMotionActivityState();
}

class _AuroraMotionActivityState extends State<AuroraMotionActivity>
    with WidgetsBindingObserver {
  static const _idleDelay = Duration(milliseconds: 350);

  static const _idleRate = 15;
  static const _activeRate = 10;

  late final _rate = ValueNotifier(_idleRate);
  final _pointers = <int>{};
  final _scrolls = <BuildContext>{};
  Timer? _idleTimer;
  bool _foreground = true;
  bool _accepting = false;

  @override
  void initState() {
    super.initState();
    _foreground = switch (WidgetsBinding.instance.lifecycleState) {
      null || AppLifecycleState.resumed => true,
      _ => false,
    };
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncEnabled();
  }

  @override
  void didUpdateWidget(AuroraMotionActivity oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncEnabled();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _syncEnabled();
  }

  void _syncEnabled() {
    _accepting =
        widget.enabled &&
        _foreground &&
        TickerMode.valuesOf(context).enabled &&
        !(MediaQuery.maybeOf(context)?.disableAnimations ?? false);
    if (!_accepting) {
      _idleTimer?.cancel();
      _idleTimer = null;
      _pointers.clear();
      _scrolls.clear();
      _rate.value = _idleRate;
    } else {
      _rate.value =
          _pointers.isNotEmpty || _scrolls.isNotEmpty || _idleTimer != null
          ? _activeRate
          : _idleRate;
    }
  }

  void _activate() {
    _idleTimer?.cancel();
    _idleTimer = null;
    _rate.value = _activeRate;
  }

  void _release() {
    if (_rate.value == _idleRate || _pointers.isNotEmpty) return;
    _idleTimer?.cancel();
    _idleTimer = Timer(_idleDelay, () {
      _idleTimer = null;
      // Disposed scrollables may never send ScrollEndNotification.
      _scrolls.removeWhere((source) => !source.mounted);
      if (_scrolls.isEmpty) {
        _rate.value = _idleRate;
      } else {
        _release();
      }
    });
  }

  bool _onScroll(ScrollNotification notification) {
    if (!_accepting) return false;
    final source = notification.context ?? context;
    if (notification is ScrollStartNotification ||
        notification is ScrollUpdateNotification ||
        notification is OverscrollNotification) {
      if (_scrolls.add(source)) {
        _activate();
        _release();
      }
    } else if (notification is ScrollEndNotification) {
      _scrolls.remove(source);
      _release();
    }
    return false;
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _idleTimer?.cancel();
    _rate.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return _AuroraActivityRate(
      rate: _rate,
      child: Listener(
        behavior: HitTestBehavior.translucent,
        onPointerDown: (event) {
          if (!_accepting) return;
          _pointers.add(event.pointer);
          _activate();
        },
        onPointerUp: (event) {
          if (!_accepting) return;
          _pointers.remove(event.pointer);
          _release();
        },
        onPointerCancel: (event) {
          if (!_accepting) return;
          _pointers.remove(event.pointer);
          _release();
        },
        onPointerSignal: (_) {
          if (!_accepting) return;
          _activate();
          _release();
        },
        child: NotificationListener<ScrollNotification>(
          onNotification: _onScroll,
          child: widget.child,
        ),
      ),
    );
  }
}

class _AuroraActivityRate extends InheritedWidget {
  const _AuroraActivityRate({required this.rate, required super.child});

  final ValueListenable<int> rate;

  @override
  bool updateShouldNotify(_AuroraActivityRate oldWidget) =>
      !identical(rate, oldWidget.rate);
}

class SeededAuroraWallpaper extends StatefulWidget {
  const SeededAuroraWallpaper({
    super.key,
    required this.colors,
    required this.seed,
    required this.brightness,
    this.motion = false,
    this.motionTime,
  });

  final List<Color> colors;
  final int seed;
  final Brightness brightness;
  final bool motion;
  final Duration? motionTime;

  @override
  State<SeededAuroraWallpaper> createState() => _SeededAuroraWallpaperState();
}

class _SeededAuroraWallpaperState extends State<SeededAuroraWallpaper>
    with WidgetsBindingObserver {
  ValueListenable<int>? _activityRate;

  late _AuroraPainter _painter;
  late final _pacer = FramePacer(_publish, framesPerSecond: 15);
  late final ValueNotifier<({bool square, Duration elapsed})> _frame;
  Duration _runBase = Duration.zero;
  bool _foreground = true;

  bool get _square => widget.motion || widget.motionTime != null;

  @override
  void initState() {
    super.initState();
    _foreground = switch (WidgetsBinding.instance.lifecycleState) {
      null || AppLifecycleState.resumed => true,
      _ => false,
    };
    _frame = ValueNotifier((
      square: _square,
      elapsed: widget.motionTime ?? Duration.zero,
    ));
    WidgetsBinding.instance.addObserver(this);
    _updatePainter();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final rate = AuroraMotionActivity.rateOf(context);
    if (!identical(_activityRate, rate)) {
      _activityRate?.removeListener(_onRateChanged);
      _activityRate = rate;
      _activityRate?.addListener(_onRateChanged);
      _onRateChanged();
    }
    _syncMotion();
  }

  void _onRateChanged() {
    _pacer.framesPerSecond = _activityRate?.value ?? 15;
  }

  void _publish(Duration elapsed) {
    _frame.value = (square: true, elapsed: _runBase + elapsed);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _syncMotion();
  }

  void _syncMotion() {
    final running =
        widget.motion &&
        widget.motionTime == null &&
        _foreground &&
        TickerMode.valuesOf(context).enabled &&
        !(MediaQuery.maybeOf(context)?.disableAnimations ?? false);
    if (running && !_pacer.isActive) {
      _runBase = _frame.value.elapsed;
      _pacer.start();
    } else if (!running && _pacer.isActive) {
      _pacer.stop();
    }
  }

  void _updatePainter() {
    _painter = _AuroraPainter(
      colors: normalizeAuroraColors(widget.colors),
      seed: widget.seed,
      brightness: widget.brightness,
    );
  }

  @override
  void didUpdateWidget(SeededAuroraWallpaper oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.seed != oldWidget.seed && _pacer.isActive) {
      _pacer.stop();
    }
    if (widget.seed != oldWidget.seed ||
        widget.brightness != oldWidget.brightness ||
        !listEquals(widget.colors, oldWidget.colors)) {
      _painter.dispose();
      _updatePainter();
    }
    _frame.value = (
      square: _square,
      elapsed:
          widget.motionTime ??
          (widget.seed == oldWidget.seed
              ? _frame.value.elapsed
              : Duration.zero),
    );
    _syncMotion();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _activityRate?.removeListener(_onRateChanged);
    _pacer.dispose();
    _painter.dispose();
    _frame.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return RepaintBoundary(
      child: _AuroraMotionTransform(
        seed: widget.seed,
        frame: _frame,
        child: LiquidGlassColorSource(
          child: CustomPaint(painter: _painter, isComplex: true),
        ),
      ),
    );
  }
}

class _AuroraMotionTransform extends SingleChildRenderObjectWidget {
  const _AuroraMotionTransform({
    required this.seed,
    required this.frame,
    required super.child,
  });

  final int seed;
  final ValueListenable<({bool square, Duration elapsed})> frame;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderAuroraMotion(seed, frame);

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderAuroraMotion renderObject,
  ) {
    renderObject
      ..seed = seed
      ..frame = frame;
  }
}

class _RenderAuroraMotion extends RenderProxyBox {
  _RenderAuroraMotion(
    int seed,
    ValueListenable<({bool square, Duration elapsed})> frame,
  ) : _seed = seed,
      _motion = AuroraMotion(seed),
      _frame = frame,
      _square = frame.value.square;

  final _clip = LayerHandle<ClipRectLayer>();
  final _transform = LayerHandle<TransformLayer>();
  AuroraMotion _motion;
  int _seed;
  bool _square;

  set seed(int value) {
    if (_seed == value) return;
    _seed = value;
    _motion = AuroraMotion(value);
    markNeedsPaint();
  }

  ValueListenable<({bool square, Duration elapsed})> _frame;
  set frame(ValueListenable<({bool square, Duration elapsed})> value) {
    if (identical(_frame, value)) return;
    if (attached) _frame.removeListener(_onFrame);
    _frame = value;
    if (attached) _frame.addListener(_onFrame);
    _onFrame();
  }

  void _onFrame() {
    if (_square != _frame.value.square) {
      _square = _frame.value.square;
      markNeedsLayout();
    } else {
      markNeedsPaint();
    }
  }

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _frame.addListener(_onFrame);
  }

  @override
  void detach() {
    _frame.removeListener(_onFrame);
    super.detach();
  }

  @override
  void dispose() {
    _clip.layer = null;
    _transform.layer = null;
    super.dispose();
  }

  @override
  Size computeDryLayout(BoxConstraints constraints) =>
      constraints.biggest.isFinite ? constraints.biggest : constraints.smallest;

  @override
  void performLayout() {
    size = computeDryLayout(constraints);
    child?.layout(
      BoxConstraints.tight(_square ? Size.square(size.longestSide) : size),
      parentUsesSize: true,
    );
  }

  Matrix4 get _matrix {
    if (!_square || size.isEmpty) return Matrix4.identity();
    final pose = _motion.at(_frame.value.elapsed, size);
    final side = size.longestSide;
    final scale = pose.side / side;
    return Matrix4.translationValues(
        size.width / 2 + pose.translation.dx,
        size.height / 2 + pose.translation.dy,
        0,
      )
      ..rotateZ(pose.angle)
      ..scaleByDouble(scale, scale, 1, 1)
      ..translateByDouble(-side / 2, -side / 2, 0, 1);
  }

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    transform.multiply(_matrix);
  }

  @override
  Rect? describeApproximatePaintClip(RenderObject child) =>
      _square ? Offset.zero & size : null;

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    final child = this.child;
    if (child == null) return false;
    return result.addWithPaintTransform(
      transform: _matrix,
      position: position,
      hitTest: (result, position) => child.hitTest(result, position: position),
    );
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    if (child == null || size.isEmpty) return;
    if (!_square) {
      _clip.layer = null;
      _transform.layer = null;
      super.paint(context, offset);
      return;
    }
    _clip.layer = context.pushClipRect(
      needsCompositing,
      offset,
      Offset.zero & size,
      (context, offset) {
        _transform.layer = context.pushTransform(
          needsCompositing,
          offset,
          _matrix,
          super.paint,
          oldLayer: _transform.layer,
        );
      },
      oldLayer: _clip.layer,
    );
  }
}

class _AuroraPainter extends CustomPainter {
  _AuroraPainter({
    required this.colors,
    required this.seed,
    required this.brightness,
  }) {
    final mesh = generateAuroraMesh(
      seed,
      colors: colors,
      brightness: brightness,
    );
    _vertices = Vertices(
      VertexMode.triangles,
      mesh.positions,
      colors: mesh.colors,
      indices: mesh.indices,
    );
  }

  final List<Color> colors;
  final int seed;
  final Brightness brightness;
  late final Vertices _vertices;
  final _paint = Paint()..isAntiAlias = false;

  void dispose() => _vertices.dispose();

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    canvas.save();
    canvas.scale(size.width, size.height);
    canvas.drawVertices(_vertices, BlendMode.dst, _paint);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_AuroraPainter oldDelegate) =>
      seed != oldDelegate.seed ||
      brightness != oldDelegate.brightness ||
      !listEquals(colors, oldDelegate.colors);
}

class AuroraBackdrop extends StatelessWidget {
  const AuroraBackdrop({
    super.key,
    this.shape = AppShape.full,
    required this.child,
  });

  final ShapeBorder shape;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (!AuroraAppearance.enabledOf(context)) return child;
    final dark = Theme.of(context).brightness == Brightness.dark;
    return ClipPath(
      clipper: ShapeBorderClipper(shape: shape),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
        child: DecoratedBox(
          position: DecorationPosition.foreground,
          decoration: ShapeDecoration(
            shape: shape is OutlinedBorder
                ? (shape as OutlinedBorder).copyWith(
                    side: BorderSide(
                      color: Colors.white.withValues(alpha: dark ? 0.12 : 0.38),
                      width: 0.5,
                    ),
                  )
                : shape,
          ),
          child: child,
        ),
      ),
    );
  }
}
