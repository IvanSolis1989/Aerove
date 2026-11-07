import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;
import 'native_lite_migration.dart' show nativeLitePrivateDestination;

Future<void> seedSqlRuleRuntimeCache({
  required Directory home,
  required String source,
  required String target,
  required String digest,
}) async {
  final relativeSource = p
      .relative(source, from: home.path)
      .replaceAll('\\', '/');
  final relativeTarget = p
      .relative(target, from: home.path)
      .replaceAll('\\', '/');
  if (!RegExp(r'^providers/rules/[0-9a-f]{32}$').hasMatch(relativeSource) ||
      !RegExp(
        r'^profiles/providers/[0-9]+/rules/[0-9a-f]{32}$',
      ).hasMatch(relativeTarget) ||
      !RegExp(r'^[0-9a-f]{64}$').hasMatch(digest))
    throw StateError('Invalid SQL rule/cache ownership witness');
  final input = File(source);
  final output = File(target);
  await nativeLitePrivateDestination(input, home);
  await nativeLitePrivateDestination(output, home);
  if (await input.length() > 64 * 1024 * 1024 ||
      (await sha256.bind(input.openRead()).first).toString() != digest)
    throw StateError('SQL rule source changed before cold cache capture');
  await output.parent.create(recursive: true);
  await output.create(exclusive: true);
  await input.copy(target);
  final handle = await output.open(mode: FileMode.append);
  try {
    await handle.flush();
  } finally {
    await handle.close();
  }
  await nativeLitePrivateDestination(output, home);
  if ((await sha256.bind(output.openRead()).first).toString() != digest)
    throw StateError(
      'Core-owned rule cache capture differs from its SQL source',
    );
}
