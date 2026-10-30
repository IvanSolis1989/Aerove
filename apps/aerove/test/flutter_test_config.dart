import 'dart:async';
import 'dart:io';

import 'package:aerove_standard/standard_product.dart';
import 'package:fl_clash/core/lib.dart';
import 'package:fl_clash/core/service.dart';
import 'package:fl_clash/product.dart';

Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  ClientProduct.configure(
    product: standardProduct,
    backendCoreId: 'mihomo',
    backendFactory: () => Platform.isAndroid ? CoreLib() : CoreService(),
  );
  await testMain();
}
