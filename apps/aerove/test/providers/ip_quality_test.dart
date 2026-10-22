import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:fake_async/fake_async.dart';
import 'package:fl_clash/common/exit_snapshot.dart';
import 'package:fl_clash/common/ip_quality.dart';
import 'package:fl_clash/common/request.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/ip_quality.dart';
import 'package:fl_clash/providers/last_exit.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:riverpod/riverpod.dart';

const _ip = '203.0.113.9';
const _cooldown = Duration(milliseconds: 200);

class _Adapter implements HttpClientAdapter {
  _Adapter({required this.succeed});

  final bool succeed;
  int requests = 0;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests++;
    if (succeed && options.uri.host == 'iplocate.io') {
      return ResponseBody.fromString(
        jsonEncode({
          'asn': {'asn': 'AS64500', 'type': 'isp'},
        }),
        200,
      );
    }
    return ResponseBody.fromString('', 503);
  }

  @override
  void close({bool force = false}) {}
}

class _PendingAdapter implements HttpClientAdapter {
  int requests = 0;
  int cancellations = 0;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests++;
    await cancelFuture;
    cancellations++;
    throw DioException.requestCancelled(
      requestOptions: options,
      reason: 'cancelled',
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late HttpClientAdapter originalAdapter;

  setUp(() {
    originalAdapter = request.dio.httpClientAdapter;
  });

  tearDown(() {
    request.dio.httpClientAdapter = originalAdapter;
  });

  ProviderContainer build(HttpClientAdapter adapter, {ExitSnapshot? lastExit}) {
    request.dio.httpClientAdapter = adapter;
    final container = ProviderContainer(
      overrides: [
        ipQualityFailureCooldownProvider.overrideWithValue(_cooldown),
        lastExitProvider.overrideWithBuild((_, _) => lastExit),
      ],
    );
    addTearDown(container.dispose);
    return container;
  }

  final provider = ipQualityProvider(_ip);

  test('hiding cancels the active wave and stops hedged requests', () {
    fakeAsync((clock) {
      final adapter = _PendingAdapter();
      request.dio.httpClientAdapter = adapter;
      final container = ProviderContainer();
      try {
        container.listen(provider, (_, _) {});
        clock.elapse(Duration.zero);
        expect(adapter.requests, 3);

        container.read(appVisibleProvider.notifier).value = false;
        clock.elapse(Duration.zero);
        clock.elapse(const Duration(seconds: 4));
        expect(adapter.requests, 3);
        expect(adapter.cancellations, 3);

        container.read(appVisibleProvider.notifier).value = true;
        clock.elapse(Duration.zero);
        expect(adapter.requests, 6);
        container.read(appVisibleProvider.notifier).value = false;
        clock.elapse(Duration.zero);
        expect(adapter.cancellations, 6);
      } finally {
        container.dispose();
        clock.elapse(const Duration(seconds: 9));
      }
    });
  });

  test(
    'a hidden lookup waits for foreground and retains its success',
    () async {
      final adapter = _Adapter(succeed: true);
      final container = build(adapter);
      container.read(appVisibleProvider.notifier).value = false;
      container.listen(provider, (_, _) {});
      await pumpEventQueue();
      expect(adapter.requests, 0);

      container.read(appVisibleProvider.notifier).value = true;
      final quality = await container.read(provider.future);
      final sent = adapter.requests;
      container.read(appVisibleProvider.notifier).value = false;
      container.read(appVisibleProvider.notifier).value = true;
      await pumpEventQueue();
      expect(container.read(provider).value, quality);
      expect(adapter.requests, sent);
    },
  );

  test('keeps a result after the last listener leaves', () async {
    final adapter = _Adapter(succeed: true);
    final container = build(adapter);
    final subscription = container.listen(provider, (_, _) {});
    final quality = await container.read(provider.future);
    expect(quality.type, IpType.residential);
    final sent = adapter.requests;

    subscription.close();
    await pumpEventQueue();
    container.listen(provider, (_, _) {});

    expect(container.read(provider).value, quality);
    expect(adapter.requests, sent);
  });

  test('a found quality joins the last exit of its address', () async {
    final container = build(
      _Adapter(succeed: true),
      lastExit: const ExitSnapshot(
        profileId: 1,
        proxied: true,
        ip: IpInfo(ip: _ip, countryCode: 'GB'),
      ),
    );
    container.listen(provider, (_, _) {});

    final quality = await container.read(provider.future);

    expect(container.read(lastExitProvider)?.quality, quality);
  });

  test('keeps a failed lookup through the cooldown', () async {
    final adapter = _Adapter(succeed: false);
    final container = build(adapter);
    final subscription = container.listen(provider, (_, _) {});
    await expectLater(
      container.read(provider.future),
      throwsA(isA<IpQualityLookupException>()),
    );
    final sent = adapter.requests;

    await Future<void>.delayed(_cooldown ~/ 2);
    expect(adapter.requests, sent);

    subscription.close();
    await pumpEventQueue();
    container.listen(provider, (_, _) {});
    await expectLater(
      container.read(provider.future),
      throwsA(isA<IpQualityLookupException>()),
    );
    expect(adapter.requests, sent);
  });

  test('asks again once the cooldown has passed', () async {
    final adapter = _Adapter(succeed: false);
    final container = build(adapter);
    final subscription = container.listen(provider, (_, _) {});
    await expectLater(
      container.read(provider.future),
      throwsA(isA<IpQualityLookupException>()),
    );
    final sent = adapter.requests;

    subscription.close();
    await Future<void>.delayed(_cooldown * 2);
    container.listen(provider, (_, _) {});
    await expectLater(
      container.read(provider.future),
      throwsA(isA<IpQualityLookupException>()),
    );
    expect(adapter.requests, sent * 2);
  });

  test('queries again once invalidated', () async {
    final adapter = _Adapter(succeed: true);
    final container = build(adapter);
    container.listen(provider, (_, _) {});
    await container.read(provider.future);
    final sent = adapter.requests;

    container.invalidate(provider);
    await container.read(provider.future);

    expect(adapter.requests, sent * 2);
  });
}
