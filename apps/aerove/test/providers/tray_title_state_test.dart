import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:riverpod/riverpod.dart';

void main() {
  late ProviderContainer container;
  late List<TrayTitleState> notified;

  setUp(() {
    container = ProviderContainer();
    notified = [];
    container.listen(trayTitleStateProvider, (_, next) => notified.add(next));
  });

  tearDown(() {
    container.dispose();
  });

  Future<void> showTrayTitle(bool value) {
    container
        .read(appSettingProvider.notifier)
        .update((state) => state.copyWith(showTrayTitle: value));
    return container.pump();
  }

  Future<void> addTraffic(int up, int down) {
    container
        .read(trafficsProvider.notifier)
        .addTraffic(Traffic(up: up, down: down));
    return container.pump();
  }

  test('a hidden tray title ignores every traffic sample', () async {
    await showTrayTitle(false);
    notified.clear();

    for (var i = 1; i <= 5; i++) {
      await addTraffic(i * 1024, i * 2048);
    }

    expect(notified, isEmpty);
    expect(
      container.read(trayTitleStateProvider),
      const TrayTitleState(showTrayTitle: false, traffic: Traffic()),
    );
  });

  test('a shown tray title follows the newest sample', () async {
    await addTraffic(1, 2);
    await showTrayTitle(true);

    expect(
      container.read(trayTitleStateProvider),
      const TrayTitleState(
        showTrayTitle: true,
        traffic: Traffic(up: 1, down: 2),
      ),
    );

    await addTraffic(3, 4);
    await addTraffic(5, 6);

    expect(notified.map((state) => state.traffic), [
      const Traffic(up: 1, down: 2),
      const Traffic(up: 3, down: 4),
      const Traffic(up: 5, down: 6),
    ]);
    expect(notified.map((state) => state.showTrayTitle), everyElement(isTrue));
  });

  test('hiding the title clears it once and then stays quiet', () async {
    await showTrayTitle(true);
    await addTraffic(7, 8);
    notified.clear();

    await showTrayTitle(false);
    await addTraffic(9, 10);
    await addTraffic(11, 12);

    expect(notified, [
      const TrayTitleState(showTrayTitle: false, traffic: Traffic()),
    ]);
  });
}
