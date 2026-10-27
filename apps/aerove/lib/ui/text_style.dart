import 'package:material_ui/material_ui.dart';

import 'color.dart';

extension TextStyleExtension on TextStyle {
  TextStyle withFontSize(double size) => copyWith(fontSize: size);

  TextStyle get toLight => copyWith(color: color?.opacity80);

  TextStyle get toLighter => copyWith(color: color?.opacity60);

  TextStyle get toTabular =>
      copyWith(fontFeatures: const [FontFeature.tabularFigures()]);
}
