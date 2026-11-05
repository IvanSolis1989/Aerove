import 'package:fl_clash/common/initial_theme.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('low or unknown capacity starts with plain classic appearance', () {
    for (final memory in <int?>[null, 0, 4095]) {
      final theme = initialThemeForDevice(
        memoryMiB: memory,
        lowRamDevice: false,
        mediaPerformanceClass: 37,
        shaderFilterSupported: true,
      );
      expect(theme.liquidGlass, LiquidGlassMode.none);
      expect(theme.auroraEnabled, isFalse);
    }
    final lowRam = initialThemeForDevice(
      memoryMiB: 8192,
      lowRamDevice: true,
      mediaPerformanceClass: 37,
      shaderFilterSupported: true,
    );
    expect(lowRam.liquidGlass, LiquidGlassMode.none);
    expect(lowRam.auroraEnabled, isFalse);
    final declaredLow = initialThemeForDevice(
      memoryMiB: 16384,
      lowRamDevice: false,
      mediaPerformanceClass: 29,
      shaderFilterSupported: true,
    );
    expect(declaredLow.liquidGlass, LiquidGlassMode.none);
    expect(declaredLow.auroraEnabled, isFalse);
  });

  test(
    'large RAM alone or an unsupported shader does not select clear aurora',
    () {
      for (final profile in [
        (memory: 4096, mediaClass: 0, shader: true),
        (memory: 16384, mediaClass: 0, shader: true),
        (memory: 8192, mediaClass: 31, shader: true),
        (memory: 6143, mediaClass: 33, shader: true),
        (memory: 8192, mediaClass: 33, shader: false),
      ]) {
        final theme = initialThemeForDevice(
          memoryMiB: profile.memory,
          lowRamDevice: false,
          mediaPerformanceClass: profile.mediaClass,
          shaderFilterSupported: profile.shader,
        );
        expect(theme.liquidGlass, LiquidGlassMode.standard);
        expect(theme.auroraEnabled, isFalse);
      }
    },
  );

  test(
    'qualified capacity and rendering support select the full clear aurora preset',
    () {
      final theme = initialThemeForDevice(
        memoryMiB: 6144,
        lowRamDevice: false,
        mediaPerformanceClass: 33,
        shaderFilterSupported: true,
      );
      expect(theme.liquidGlass, LiquidGlassMode.ultra);
      expect(theme.auroraEnabled, isTrue);
    },
  );
}
