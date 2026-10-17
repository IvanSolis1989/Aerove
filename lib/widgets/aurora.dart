import 'dart:ui' show ImageFilter;

import 'package:fl_clash/common/aurora.dart';
import 'package:fl_clash/common/shape.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

class AuroraAppearance extends InheritedWidget {
  const AuroraAppearance({
    super.key,
    required this.enabled,
    required super.child,
  });

  final bool enabled;

  static bool enabledOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AuroraAppearance>()?.enabled ??
      false;

  static Color surfaceColorOf(
    BuildContext context,
    Color fallback, {
    double opacity = 0.28,
  }) {
    if (!enabledOf(context)) return fallback;
    final dark = Theme.of(context).brightness == Brightness.dark;
    return (dark ? fallback : Colors.white).withValues(
      alpha: dark ? (opacity + 0.12).clamp(0, 1) : opacity,
    );
  }

  @override
  bool updateShouldNotify(AuroraAppearance oldWidget) =>
      enabled != oldWidget.enabled;
}

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
        ),
      ),
    );
    final enabled =
        theme.platform == TargetPlatform.android &&
        settings.enabled &&
        !MediaQuery.highContrastOf(context) &&
        !(settings.pureBlack && theme.brightness == Brightness.dark);
    return AuroraAppearance(
      enabled: enabled,
      child: Theme(
        data: enabled
            ? theme.copyWith(scaffoldBackgroundColor: Colors.transparent)
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
  }) : clouds = generateAuroraClouds(seed);

  final List<Color> colors;
  final int seed;
  final Brightness brightness;
  final List<AuroraCloud> clouds;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final dark = brightness == Brightness.dark;
    final base = dark ? const Color(0xFF111419) : const Color(0xFFF7F8FA);
    final bounds = Offset.zero & size;
    canvas.drawRect(bounds, Paint()..color = base);
    canvas.save();
    canvas.clipRect(bounds);
    canvas.scale(size.width, size.height);
    for (var i = 0; i < clouds.length; i++) {
      final cloud = clouds[i];
      final input = HSLColor.fromColor(colors[i % colors.length]);
      final softened = input
          .withSaturation(input.saturation.clamp(0.08, 0.28))
          .withLightness(dark ? 0.52 : 0.76)
          .toColor();
      final tint = Color.lerp(base, softened, dark ? 0.25 : 0.3)!;
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
              tint.withValues(alpha: cloud.strength),
              tint.withValues(alpha: cloud.strength * 0.3),
              tint.withValues(alpha: 0),
            ],
            stops: const [0, 0.5, 1],
          ).createShader(const Rect.fromLTRB(-1, -1, 1, 1)),
      );
      canvas.restore();
    }
    canvas.restore();
    canvas.drawRect(
      bounds,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [base.withValues(alpha: 0.7), base.withValues(alpha: 0)],
        ).createShader(bounds),
    );
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
