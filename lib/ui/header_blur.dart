import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:material_ui/material_ui.dart';

class HeaderBlur extends StatefulWidget {
  const HeaderBlur({super.key, required this.fadeStart});

  final double fadeStart;

  @override
  State<HeaderBlur> createState() => _HeaderBlurState();
}

class _HeaderBlurState extends State<HeaderBlur> {
  static const _sigma = 12.0;
  static const _fallbackBands = 16;

  ui.FragmentShader? _shader;

  @override
  void initState() {
    super.initState();
    if (ui.ImageFilter.isShaderFilterSupported) {
      unawaited(_loadShader());
    }
  }

  Future<void> _loadShader() async {
    final program = await _HeaderBlurProgram.load();
    if (!mounted || program == null) return;
    setState(() {
      _shader = program.fragmentShader();
    });
  }

  @override
  void dispose() {
    _shader?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final start = widget.fadeStart.clamp(0.0, 0.99);
    return LayoutBuilder(
      builder: (_, constraints) {
        if (constraints.maxHeight <= 0) return const SizedBox.shrink();
        if (_shader case final shader?) {
          shader
            ..setFloat(2, _sigma / constraints.maxHeight)
            ..setFloat(3, start);
          return ClipRect(
            child: BackdropFilter(
              filter: ui.ImageFilter.shader(shader),
              blendMode: BlendMode.src,
              child: const SizedBox.expand(),
            ),
          );
        }
        return BackdropGroup(
          child: Stack(
            children: [
              for (var band = 0; band < _fallbackBands; band++)
                Positioned(
                  top: constraints.maxHeight * band / _fallbackBands,
                  height: constraints.maxHeight / _fallbackBands,
                  left: 0,
                  right: 0,
                  child: ClipRect(
                    child: BackdropFilter.grouped(
                      filter: ui.ImageFilter.blur(
                        sigmaX: _sigmaAt((band + 0.5) / _fallbackBands, start),
                        sigmaY: _sigmaAt((band + 0.5) / _fallbackBands, start),
                        tileMode: TileMode.clamp,
                      ),
                      blendMode: BlendMode.src,
                      child: const SizedBox.expand(),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  static double _sigmaAt(double y, double start) {
    final t = ((y - start) / (1 - start)).clamp(0.0, 1.0);
    return _sigma * (1 - t * t * (3 - 2 * t));
  }
}

abstract final class _HeaderBlurProgram {
  static ui.FragmentProgram? _program;
  static Future<ui.FragmentProgram?>? _loading;
  static var _failed = false;

  static Future<ui.FragmentProgram?> load() {
    if (_program != null || _failed) return SynchronousFuture(_program);
    return _loading ??= _load();
  }

  static Future<ui.FragmentProgram?> _load() async {
    try {
      return _program = await ui.FragmentProgram.fromAsset(
        'shaders/header_blur.frag',
      );
    } catch (error, stack) {
      _failed = true;
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stack,
          library: 'header blur',
        ),
      );
      return null;
    }
  }
}
