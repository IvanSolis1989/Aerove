import 'dart:ui' as ui;

import 'package:material_ui/material_ui.dart';

class HeaderBlur extends StatelessWidget {
  const HeaderBlur({super.key, required this.fadeHeight});

  static const _sigma = 12.0;

  final double fadeHeight;

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
            child: const CustomPaint(
              painter: _HeaderBlurMask(),
              child: SizedBox.expand(),
            ),
          ),
        ),
      ),
    );
  }
}

class _HeaderBlurMask extends CustomPainter {
  const _HeaderBlurMask();

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
        ).createShader(rect),
    );
  }

  @override
  bool shouldRepaint(_HeaderBlurMask oldDelegate) => false;
}
