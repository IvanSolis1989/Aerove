import 'dart:ui' show ImageFilter, Vertices, VertexMode;

import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/ui/aurora.dart';
import 'package:fl_clash/ui/glass_foreground.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/surface.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

export 'package:fl_clash/ui/aurora.dart';

LiquidGlassMode effectiveAuroraGlassMode(
  LiquidGlassMode stored,
  bool gradient,
) =>
    gradient && stored == LiquidGlassMode.none ? LiquidGlassMode.ultra : stored;

class AuroraAppSurface extends ConsumerWidget {
  const AuroraAppSurface({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
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
    final glassMode = effectiveAuroraGlassMode(settings.liquidGlass, enabled);
    final glassEnabled = glassMode != LiquidGlassMode.none && !opaque;
    final surfaceTheme = enabled ? glassTheme : theme;
    final surfaceColors = surfaceTheme.colorScheme;
    final glassButtons = LiquidGlass.buttonStyle(
      (_) => LiquidGlassStyle.regular(surfaceColors),
    ).merge(
      FilledButton.styleFrom(
        backgroundColor: Colors.transparent,
        foregroundColor: surfaceColors.onSurface,
      ),
    );
    final effectiveTheme = glassEnabled
        ? GlassForeground.theme(
            surfaceTheme.copyWith(
              scaffoldBackgroundColor: Colors.transparent,
              filledButtonTheme: FilledButtonThemeData(
                style: (surfaceTheme.filledButtonTheme.style ??
                        const ButtonStyle())
                    .merge(glassButtons),
              ),
              floatingActionButtonTheme: theme.floatingActionButtonTheme.copyWith(
                backgroundColor: surfaceColors.surfaceContainer.withValues(
                  alpha: 0.28,
                ),
                foregroundColor: surfaceColors.onSurface,
              ),
            ),
          )
        : theme;
    return LiquidGlassScope(
      enabled: glassEnabled,
      quality: glassMode == LiquidGlassMode.ultra
          ? LiquidGlassQuality.ultra
          : LiquidGlassQuality.standard,
      child: LiquidGlassHost(
        enabled: glassEnabled,
        child: AuroraAppearance(
          enabled: enabled,
          child: Theme(
            data: effectiveTheme,
            child: GlassForegroundText(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Positioned.fill(
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
                                ),
                              ),
                            ),
                          )
                        : ColoredBox(color: theme.scaffoldBackgroundColor),
                  ),
                  child,
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class SeededAuroraWallpaper extends StatefulWidget {
  const SeededAuroraWallpaper({
    super.key,
    required this.colors,
    required this.seed,
    required this.brightness,
  });

  final List<Color> colors;
  final int seed;
  final Brightness brightness;

  @override
  State<SeededAuroraWallpaper> createState() => _SeededAuroraWallpaperState();
}

class _SeededAuroraWallpaperState extends State<SeededAuroraWallpaper> {
  late _AuroraPainter _painter;

  @override
  void initState() {
    super.initState();
    _updatePainter();
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
    if (widget.seed != oldWidget.seed ||
        widget.brightness != oldWidget.brightness ||
        !listEquals(widget.colors, oldWidget.colors)) {
      _painter.dispose();
      _updatePainter();
    }
  }

  @override
  void dispose() {
    _painter.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return RepaintBoundary(
      child: CustomPaint(painter: _painter, isComplex: true),
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
