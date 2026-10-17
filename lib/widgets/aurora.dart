import 'dart:ui' show ImageFilter;

import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/ui/aurora.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/surface.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

export 'package:fl_clash/ui/aurora.dart';

LiquidGlassMode effectiveAuroraGlassMode(LiquidGlassMode stored, bool gradient) =>
    gradient && stored == LiquidGlassMode.none ? LiquidGlassMode.standard : stored;

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
    final enabled =
        theme.platform == TargetPlatform.android &&
        settings.enabled &&
        !MediaQuery.highContrastOf(context) &&
        !(settings.pureBlack && theme.brightness == Brightness.dark);
    final glassColors = auroraColorScheme(theme.brightness);
    final glassTheme = theme.copyWith(
      colorScheme: glassColors,
      extensions: [
        for (final extension in theme.extensions.values)
          if (extension is ElevatedColorScheme)
            ElevatedColorScheme(glassColors.elevated)
          else
            extension,
      ],
    ).withAppSurfaces;
    final glassMode = effectiveAuroraGlassMode(settings.liquidGlass, enabled);
    final glassEnabled = glassMode != LiquidGlassMode.none;
    final glassButtons = (glassEnabled
        ? LiquidGlass.buttonStyle(
            (_) => LiquidGlassStyle.regular(glassColors),
          )
        : const ButtonStyle()).merge(FilledButton.styleFrom(
      backgroundColor: Colors.transparent,
      foregroundColor: glassColors.onSurface,
    ));
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
        data: enabled
            ? glassTheme.copyWith(
                scaffoldBackgroundColor: Colors.transparent,
                filledButtonTheme: FilledButtonThemeData(
                  style: glassButtons.merge(glassTheme.filledButtonTheme.style),
                ),
                floatingActionButtonTheme: theme.floatingActionButtonTheme.copyWith(
                  backgroundColor: glassColors.surfaceContainer.withValues(alpha: 0.28),
                  foregroundColor: glassColors.onSurface,
                ),
              )
            : theme,
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
                              for (final color in settings.colors) Color(color),
                            ],
                            seed: settings.seed,
                            brightness: theme.brightness,
                          ),
                        ),
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
            child,
          ],
        ),
      ),
        ),
      ),
    );
  }
}

class SeededAuroraWallpaper extends StatelessWidget {
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
  Widget build(BuildContext context) {
    return RepaintBoundary(
      child: CustomPaint(
        painter: _AuroraPainter(
          colors: normalizeAuroraColors(colors),
          seed: seed,
          brightness: brightness,
        ),
        isComplex: true,
      ),
    );
  }
}

class _AuroraPainter extends CustomPainter {
  _AuroraPainter({
    required this.colors,
    required this.seed,
    required this.brightness,
  }) : clouds = generateAuroraClouds(seed, colorCount: colors.length),
       tints = [for (final color in colors) auroraTint(color, brightness)];

  final List<Color> colors;
  final int seed;
  final Brightness brightness;
  final List<AuroraCloud> clouds;
  final List<Color> tints;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final base = auroraBaseColor(brightness);
    final bounds = Offset.zero & size;
    canvas.drawRect(
      bounds,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.centerLeft,
          end: Alignment.centerRight,
          colors: [
            for (final tint in tints) Color.lerp(base, tint, 0.65)!,
            if (tints.length == 1) Color.lerp(base, tints.single, 0.65)!,
          ],
        ).createShader(bounds),
    );
    canvas.save();
    canvas.clipRect(bounds);
    canvas.scale(size.width, size.height);
    for (var i = 0; i < clouds.length; i++) {
      final cloud = clouds[i];
      final tint = tints[i % tints.length];
      canvas.save();
      canvas.translate(cloud.x, cloud.y);
      canvas.rotate(cloud.angle);
      canvas.scale(cloud.radiusX, cloud.radiusY);
      canvas.drawCircle(
        Offset.zero,
        1,
        Paint()
          ..shader = RadialGradient(
            colors: [
              tint.withValues(alpha: cloud.strength + 0.25),
              tint.withValues(alpha: (cloud.strength + 0.25) * 0.5),
              tint.withValues(alpha: 0),
            ],
            stops: const [0, 0.5, 1],
          ).createShader(const Rect.fromLTRB(-1, -1, 1, 1)),
      );
      canvas.restore();
    }
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
