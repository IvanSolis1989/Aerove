import 'dart:io';

import 'package:path/path.dart' as p;

const _clientRoot = '../../packages/aerove_client';

String _physicalPath(String path) {
  if (path == 'host/lib') return 'lib';
  if (path == 'lib' ||
      path.startsWith('lib/') ||
      path == 'arb' ||
      path.startsWith('arb/')) {
    return p.join(_clientRoot, path);
  }
  if (path.startsWith('packages/fl_clash/')) {
    return p.join(_clientRoot, path.substring('packages/fl_clash/'.length));
  }
  return path;
}

Directory clientSourceDirectory(String path) => Directory(_physicalPath(path));

File clientSourceFile(String path) => File(_physicalPath(path));

String clientSourcePath(String path) {
  final absolute = p.absolute(path);
  final client = p.absolute(_clientRoot);
  final relative = p.isWithin(client, absolute)
      ? p.relative(absolute, from: client)
      : p.isWithin(p.absolute('lib'), absolute)
      ? p.join('host', p.relative(absolute))
      : p.relative(absolute);
  return p.posix.joinAll(p.split(relative));
}
