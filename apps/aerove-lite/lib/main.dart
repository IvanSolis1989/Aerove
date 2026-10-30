import 'package:fl_clash/aerove_client.dart';
import 'package:fl_clash/core/meow.dart';

import 'lite_product.dart';

void main(List<String> args) => runAeroveClient(
  args,
  product: liteProduct,
  backendCoreId: 'meow-rs',
  backendFactory: MeowCoreHandler.new,
);
