import 'dart:ui' as ui;

import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:fl_clash/ui/shape.dart';
import 'package:material_ui/material_ui.dart';

class HeaderBlur extends StatelessWidget {
  const HeaderBlur({
    super.key,
    required this.fadeHeight,
    this.fullStrengthHeight = 0,
    this.liquidGlass = false,
  });

  static const _sigma = 12.0;

  final double fadeHeight;
  final double fullStrengthHeight;
  final bool liquidGlass;

  @override
  Widget build(BuildContext context) {
    if (fadeHeight <= 0) return const SizedBox.shrink();
    final glass =
        LiquidGlassScope.of(context) && !MediaQuery.highContrastOf(context);
    final coveredHeight = fullStrengthHeight.clamp(0.0, fadeHeight);
    return Align(
      alignment: Alignment.topCenter,
      child: SizedBox(
        width: double.infinity,
        height: fadeHeight,
        child: ClipRect(
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (glass && !liquidGlass && coveredHeight > 0)
                Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  height: coveredHeight,
                  child: const LiquidGlassOcclusion(
                    shape: AppShape.none,
                    bottomFeather: 12,
                    mode: LiquidGlassOcclusionMode.plain,
                    child: SizedBox.expand(),
                  ),
                ),
              if (glass && liquidGlass)
                LiquidGlassOcclusion(
                  shape: AppShape.none,
                  bottomFeather: 12,
                  mode: LiquidGlassOcclusionMode.plain,
                  captureContent: true,
                  child: ShaderMask(
                    blendMode: BlendMode.dstIn,
                    shaderCallback: (bounds) => _HeaderBlurMask.gradient(
                      (fullStrengthHeight / fadeHeight).clamp(0.0, 1.0),
                    ).createShader(bounds),
                    child: const LiquidGlass(
                      style: LiquidGlassStyle(
                        tint: Colors.transparent,
                        blur: _sigma,
                        refraction: 12,
                        bevel: 12,
                        rim: 0,
                      ),
                      clipper: ShapeBorderClipper(shape: AppShape.none),
                    ),
                  ),
                )
              else
                BackdropFilter(
                  filter: ui.ImageFilter.blur(
                    sigmaX: _sigma,
                    sigmaY: _sigma,
                    tileMode: TileMode.clamp,
                  ),
                  blendMode: BlendMode.srcOver,
                  child: CustomPaint(
                    painter: _HeaderBlurMask(
                      (fullStrengthHeight / fadeHeight).clamp(0.0, 1.0),
                    ),
                    child: const SizedBox.expand(),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _HeaderBlurMask extends CustomPainter {
  const _HeaderBlurMask(this.fullStrengthFraction);

  final double fullStrengthFraction;

  static const _steps = 16;
  static final _colors = List<Color>.generate(_steps + 1, (index) {
    final t = index / _steps;
    return Colors.white.withValues(alpha: 1 - t * t * (3 - 2 * t));
  }, growable: false);

  static LinearGradient gradient(double fullStrengthFraction) => LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: _colors,
    stops: [
      for (var index = 0; index <= _steps; index++)
        fullStrengthFraction + (1 - fullStrengthFraction) * index / _steps,
    ],
  );

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.drawRect(
      rect,
      Paint()
        ..blendMode = BlendMode.dstIn
        ..shader = gradient(fullStrengthFraction).createShader(rect),
    );
  }

  @override
  bool shouldRepaint(_HeaderBlurMask oldDelegate) =>
      fullStrengthFraction != oldDelegate.fullStrengthFraction;
}
