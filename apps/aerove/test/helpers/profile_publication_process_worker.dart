import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:fl_clash/common/file.dart';

import 'package:flutter_test/flutter_test.dart';

import 'profile_publication_fixture.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('profile publication survives an owned process restart', () async {
    final env = Platform.environment;
    final home = Directory(env['AEROVE_PROFILE_HOME']!);
    final f = ProfilePublicationFixture(home);
    f.owner.onPhase = (phase) async {
      if (phase != env['AEROVE_PROFILE_PHASE']) return;
      await File('${home.path}/ready.json').writeAsStringAtomically(
        jsonEncode({
          'nonce': env['AEROVE_PROFILE_NONCE'],
          'pid': pid,
          'phase': phase,
        }),
      );
      await Completer<void>().future;
    };
    try {
      final distinct = env['AEROVE_PROFILE_INPUT'] != 'delete';
      if (env['AEROVE_PROFILE_MODE'] == 'publish') {
        await f.seed();
        await f.publish(prepared: await f.prepare(distinctInput: distinct));
      } else {
        await f.recover();
        await f.expectVersion(
          env['AEROVE_PROFILE_EXPECT'] == 'after',
          distinctInput: distinct,
        );
        await f.recover();
      }
    } finally {
      await f.close();
    }
  }, timeout: const Timeout(Duration(minutes: 3)));
}
