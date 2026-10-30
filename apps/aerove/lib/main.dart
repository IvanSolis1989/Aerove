import 'dart:io';

import 'package:fl_clash/aerove_client.dart';
import 'package:fl_clash/core/lib.dart';
import 'package:fl_clash/core/service.dart';

import 'standard_product.dart';

void main(List<String> args) => runAeroveClient(
  args,
  product: standardProduct,
  backendCoreId: 'mihomo',
  backendFactory: () => Platform.isAndroid ? CoreLib() : CoreService(),
);
