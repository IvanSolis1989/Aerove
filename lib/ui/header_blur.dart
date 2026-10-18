import 'dart:ui' as ui;

import 'package:material_ui/material_ui.dart';

class HeaderBlur extends StatelessWidget {
  const HeaderBlur({
    super.key,
    required this.fadeHeight,
    this.fullStrengthHeight = 0,
  });

  static const _sigma = 12.0;

  final double fadeHeight;
  final double fullStrengthHeight;

  @override
  Widget build(BuildContext context) {
    if (fadeHeight <= 0) return const SizedBox.shrink();
    return Align(
      alignment: Alignment.topCenter,
      child: SizedBox(
        width: double.infinity,
        height: fadeHeight,
        child: ClipRect(
          child: BackdropFilter(
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

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.drawRect(
      rect,
      Paint()
        ..blendMode = BlendMode.dstIn
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: _colors,
          stops: [
            for (var index = 0; index <= _steps; index++)
              fullStrengthFraction +
                  (1 - fullStrengthFraction) * index / _steps,
          ],
        ).createShader(rect),
    );
  }

  @override
  bool shouldRepaint(_HeaderBlurMask oldDelegate) =>
      fullStrengthFraction != oldDelegate.fullStrengthFraction;
}
