abstract final class ClientAssets {
  static const packagePrefix = 'packages/fl_clash/';
  static const liquidGlassShader = '${packagePrefix}shaders/liquid_glass.frag';
  static const foregroundInkShader =
      '${packagePrefix}shaders/foreground_ink.frag';
  static const desktopEmojiFont =
      '${packagePrefix}assets/fonts/Twemoji.Mozilla.ttf';

  static String key(String path) => '$packagePrefix$path';
}
