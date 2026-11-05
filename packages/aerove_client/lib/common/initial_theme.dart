import 'dart:ui' as ui;

import 'package:fl_clash/common/print.dart';
import 'package:fl_clash/common/system.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter/services.dart';

const _performanceChannel = MethodChannel('dev.aerove.client/performance');

/// A conservative first-install recommendation, not a frame-rate benchmark.
ThemeProps initialThemeForDevice({
  required int? memoryMiB,
  required bool lowRamDevice,
  required int mediaPerformanceClass,
  required bool shaderFilterSupported,
}) {
  final memory = memoryMiB ?? 0;
  if (lowRamDevice ||
      memory < 4 * 1024 ||
      (mediaPerformanceClass != 0 && mediaPerformanceClass < 30)) {
    return defaultThemeProps.copyWith(
      liquidGlass: LiquidGlassMode.none,
      auroraEnabled: false,
    );
  }
  // RAM or core count alone cannot qualify the most expensive combination.
  final highTier =
      mediaPerformanceClass >= 33 &&
      memory >= 6 * 1024 &&
      shaderFilterSupported;
  return defaultThemeProps.copyWith(
    liquidGlass: highTier ? LiquidGlassMode.ultra : LiquidGlassMode.standard,
    auroraEnabled: highTier,
  );
}

/// Resolved only while creating the initial configuration, before UI mounts.
Future<ThemeProps> resolveInitialTheme() async {
  var performanceClass = 0;
  if (system.isAndroid) {
    try {
      performanceClass =
          await _performanceChannel.invokeMethod<int>(
            'getMediaPerformanceClass',
          ) ??
          0;
    } on MissingPluginException {
      commonPrint.log(
        'Initial appearance performance class is unavailable',
        logLevel: LogLevel.warning,
      );
    } on PlatformException catch (error) {
      commonPrint.log(
        'Initial appearance performance class failed: ${error.code}',
        logLevel: LogLevel.warning,
      );
    }
  }
  return initialThemeForDevice(
    memoryMiB: system.physicalMemoryMiB,
    lowRamDevice: system.isLowRamDevice,
    mediaPerformanceClass: performanceClass,
    shaderFilterSupported: ui.ImageFilter.isShaderFilterSupported,
  );
}
