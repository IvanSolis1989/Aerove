import 'package:fl_clash/common/client_assets.dart';

import 'dart:io';

import 'package:fl_clash/ui/font.dart';
import 'package:flutter/services.dart';

Future<void>? _desktopEmojiLoad;

Future<void> loadDesktopEmojiFont() {
  if (!Platform.isWindows && !Platform.isLinux && !Platform.isMacOS) {
    return Future.value();
  }
  return _desktopEmojiLoad ??= (FontLoader(
    desktopEmojiFontFamily,
  )..addFont(rootBundle.load(ClientAssets.desktopEmojiFont))).load();
}
