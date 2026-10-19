import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/widgets/icon.dart';
import 'package:flutter_svg/svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() => readRemoteIcon = null);

  test('concurrent prewarms share a URL and memory hits avoid reads', () async {
    final source = StreamController<Uint8List>();
    var reads = 0;
    readRemoteIcon = (_) {
      reads++;
      return source.stream;
    };
    const url = 'https://example.com/loading/shared.svg';
    final first = precacheTargetIcons([url]);
    final second = precacheTargetIcons([url]);
    await Future<void>.delayed(Duration.zero);
    expect(reads, 1);
    source.add(Uint8List.fromList([1]));
    await source.close();
    await Future.wait([first, second]);
    await precacheTargetIcons([url]);
    expect(reads, 1);
  });

  test('failed and empty reads can be retried', () async {
    var reads = 0;
    readRemoteIcon = (_) {
      reads++;
      return switch (reads) {
        1 => Stream.error(StateError('unavailable')),
        2 => const Stream.empty(),
        _ => Stream.value(Uint8List.fromList([2])),
      };
    };
    const url = 'https://example.com/loading/retry.svg';
    await precacheTargetIcons([url]);
    await precacheTargetIcons([url]);
    await precacheTargetIcons([url]);
    await precacheTargetIcons([url]);
    expect(reads, 3);
  });

  test(
    'large overlapping prewarms bound candidates and active reads',
    () async {
      var active = 0;
      var peak = 0;
      final reads = <String, int>{};
      readRemoteIcon = (url) async* {
        reads.update(url, (value) => value + 1, ifAbsent: () => 1);
        active++;
        if (active > peak) peak = active;
        try {
          await Future<void>.delayed(Duration.zero);
          yield Uint8List.fromList([3]);
        } finally {
          active--;
        }
      };
      final urls = List.generate(
        1000,
        (index) => 'https://example.com/loading/large/$index.svg',
      );
      await Future.wait([precacheTargetIcons(urls), precacheTargetIcons(urls)]);
      expect(reads.length, 32);
      expect(reads.values, everyElement(1));
      expect(peak, lessThanOrEqualTo(maxConcurrentIconLoads));
      expect(active, 0);
      await precacheTargetIcons(urls);
      expect(reads.length, 32);
    },
  );

  testWidgets('visible icons join a prewarm and cached mounts avoid IO', (
    tester,
  ) async {
    var reads = 0;
    final source = StreamController<Uint8List>();
    readRemoteIcon = (_) {
      reads++;
      return source.stream;
    };
    const url = 'https://example.com/loading/visible.svg';
    final warming = precacheTargetIcons([url]);
    await tester.pumpWidget(const TestApp(child: CommonTargetIcon(src: url)));
    await tester.pump();
    expect(reads, 1);
    source.add(
      Uint8List.fromList(
        utf8.encode(
          '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1">'
          '<path d="M0 0h1v1H0z"/></svg>',
        ),
      ),
    );
    final closed = source.close();
    await tester.pump();
    await tester.runAsync(() => Future.wait([warming, closed]));
    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(const TestApp(child: CommonTargetIcon(src: url)));
    await tester.pump();
    expect(reads, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'departed consumers discard queued reads before new visible work',
    (tester) async {
      final reads = <String>[];
      final cancellations = <String, Completer<void>>{};
      var active = 0;
      var peak = 0;
      readRemoteIcon = (url) {
        reads.add(url);
        active++;
        if (active > peak) peak = active;
        final cancelled = cancellations[url] = Completer<void>();
        return StreamController<Uint8List>(
          onCancel: () async {
            await cancelled.future;
            active--;
          },
        ).stream;
      };
      final held = List.generate(
        maxConcurrentIconLoads,
        (index) => 'https://example.com/loading/held/$index.svg',
      );
      final leaving = List.generate(
        1000,
        (index) => 'https://example.com/loading/leaving/$index.svg',
      );
      await tester.pumpWidget(_icons(held));
      await tester.pump();
      expect(active, maxConcurrentIconLoads);
      await tester.pumpWidget(_icons([...held, ...leaving]));
      await tester.pump();
      expect(debugIconReads.pending, 1000);

      const visible = 'https://example.com/loading/next-visible.svg';
      await tester.pumpWidget(_icons([visible]));
      await tester.pump();
      expect(debugIconReads, (active: 8, pending: 1, sources: 1));
      expect(reads, held);
      expect(active, maxConcurrentIconLoads);

      cancellations[held.first]!.complete();
      await tester.pump();
      expect(reads, [...held, visible]);
      expect(debugIconReads.pending, 0);
      expect(active, maxConcurrentIconLoads);
      expect(peak, maxConcurrentIconLoads);
      for (final url in held.skip(1)) {
        cancellations[url]!.complete();
      }
      await tester.pumpWidget(const SizedBox());
      cancellations[visible]!.complete();
      await tester.pump();
      expect(debugIconReads, (active: 0, pending: 0, sources: 0));
      expect(active, 0);
    },
  );

  testWidgets('one departing consumer does not cancel a shared read', (
    tester,
  ) async {
    var reads = 0;
    var cancelled = false;
    final source = StreamController<Uint8List>(
      onCancel: () => cancelled = true,
    );
    readRemoteIcon = (_) {
      reads++;
      return source.stream;
    };
    const url = 'https://example.com/loading/shared-departure.svg';
    final warming = precacheTargetIcons([url]);
    await tester.pumpWidget(_icons([url]));
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(reads, 1);
    expect(cancelled, isFalse);
    source.add(Uint8List.fromList([4]));
    final closed = source.close();
    await tester.pump();
    await tester.runAsync(() => Future.wait([warming, closed]));
    expect(debugIconReads, (active: 0, pending: 0, sources: 0));
  });

  testWidgets('late consumers receive cached bytes and the live refresh', (
    tester,
  ) async {
    var reads = 0;
    final source = StreamController<Uint8List>(sync: true);
    readRemoteIcon = (_) {
      reads++;
      return source.stream;
    };
    const url = 'https://example.com/loading/stale-fresh.svg';
    Widget icons(int count) => TestApp(
      child: Stack(
        children: [
          for (var index = 0; index < count; index++)
            CommonTargetIcon(key: ValueKey(index), src: url),
        ],
      ),
    );
    Uint8List svg(String color) => Uint8List.fromList(
      utf8.encode(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1">'
        '<path fill="$color" d="M0 0h1v1H0z"/></svg>',
      ),
    );
    Iterable<Uint8List> shownBytes() => tester
        .widgetList<SvgPicture>(find.byType(SvgPicture))
        .map((widget) => (widget.bytesLoader as SvgBytesLoader).bytes);

    await tester.pumpWidget(icons(1));
    final stale = svg('red');
    source.add(stale);
    await tester.pump();
    await tester.pumpWidget(icons(2));
    await tester.pump();
    expect(reads, 1);
    expect(shownBytes(), hasLength(2));
    expect(shownBytes(), everyElement(same(stale)));

    final fresh = svg('blue');
    source.add(fresh);
    await tester.pump();
    expect(reads, 1);
    expect(shownBytes(), hasLength(2));
    expect(shownBytes().map(utf8.decode), everyElement(utf8.decode(fresh)));
    expect(shownBytes(), everyElement(same(fresh)));
    final closed = source.close();
    await tester.pump();
    await tester.runAsync(() => closed);
    await tester.pumpWidget(const SizedBox());
    expect(debugIconReads, (active: 0, pending: 0, sources: 0));
  });
}

Widget _icons(Iterable<String> urls) => TestApp(
  child: Stack(
    children: [
      for (final url in urls) CommonTargetIcon(key: ValueKey(url), src: url),
    ],
  ),
);
