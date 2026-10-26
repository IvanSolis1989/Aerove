import 'package:fl_clash/ui/inherited.dart';
import 'package:fl_clash/ui/route_motion_hold.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

class _Feed extends StatefulWidget {
  const _Feed({super.key, required this.source, required this.builds});

  final ValueNotifier<int> source;
  final List<int> builds;

  @override
  State<_Feed> createState() => _FeedState();
}

class _FeedState extends State<_Feed> with RouteMotionHoldMixin<_Feed> {
  late int _shown = widget.source.value;

  @override
  void initState() {
    super.initState();
    widget.source.addListener(_handleSource);
  }

  @override
  void dispose() {
    widget.source.removeListener(_handleSource);
    super.dispose();
  }

  void _handleSource() {
    updateWhenRouteSettled(() => setState(() => _shown = widget.source.value));
  }

  @override
  Widget build(BuildContext context) {
    widget.builds.add(_shown);
    return Text('shown $_shown');
  }
}

void main() {
  late ValueNotifier<int> source;
  late List<int> builds;

  setUp(() {
    source = ValueNotifier(0);
    builds = [];
  });

  tearDown(() => source.dispose());

  Finder shown(int value) => find.text('shown $value', skipOffstage: false);

  testWidgets('a feed on an inactive tab builds nothing until it is shown, '
      'and its first frame back shows the latest', (tester) async {
    final active = ValueNotifier(true);
    addTearDown(active.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: ValueListenableBuilder<bool>(
          valueListenable: active,
          builder: (_, isActive, child) => PageActivityScope(
            isActive: isActive,
            child: TickerMode(enabled: isActive, child: child!),
          ),
          child: Scaffold(
            body: _Feed(source: source, builds: builds),
          ),
        ),
      ),
    );

    active.value = false;
    await tester.pump();
    builds.clear();
    for (var value = 1; value <= 3; value++) {
      source.value = value;
      await tester.pump(const Duration(milliseconds: 300));
    }

    expect(builds, isEmpty);
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(shown(0), findsOneWidget);

    active.value = true;
    await tester.pump();

    expect(builds, [3]);
    expect(find.text('shown 3'), findsOneWidget);
  });

  testWidgets('flipping a tab past a feed with nothing held rebuilds nothing', (
    tester,
  ) async {
    final active = ValueNotifier(true);
    addTearDown(active.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: ValueListenableBuilder<bool>(
          valueListenable: active,
          builder: (_, isActive, child) => PageActivityScope(
            isActive: isActive,
            child: TickerMode(
              enabled: isActive,
              child: ExcludeFocus(excluding: !isActive, child: child!),
            ),
          ),
          child: Scaffold(
            body: _Feed(source: source, builds: builds),
          ),
        ),
      ),
    );
    builds.clear();

    for (var flip = 0; flip < 3; flip++) {
      active.value = false;
      await tester.pump();
      active.value = true;
      await tester.pump();
    }

    expect(builds, isEmpty);
  });

  testWidgets('a feed moved from a hidden tab into a shown one follows the '
      'tab it lands on', (tester) async {
    final feedKey = GlobalKey();
    final onFirst = ValueNotifier(true);
    addTearDown(onFirst.dispose);
    Widget tab({required bool isActive, required bool holdsFeed}) =>
        PageActivityScope(
          isActive: isActive,
          child: TickerMode(
            enabled: isActive,
            child: holdsFeed
                ? _Feed(key: feedKey, source: source, builds: builds)
                : const SizedBox.shrink(),
          ),
        );
    await tester.pumpWidget(
      MaterialApp(
        home: ValueListenableBuilder<bool>(
          valueListenable: onFirst,
          builder: (_, first, _) => Column(
            children: [
              tab(isActive: false, holdsFeed: first),
              tab(isActive: true, holdsFeed: !first),
            ],
          ),
        ),
      ),
    );
    builds.clear();

    source.value = 1;
    await tester.pump();
    expect(builds, isEmpty);

    onFirst.value = false;
    await tester.pump();
    expect(find.text('shown 1'), findsOneWidget);
    source.value = 2;
    await tester.pump();
    expect(find.text('shown 2'), findsOneWidget);

    onFirst.value = true;
    await tester.pump();
    builds.clear();
    source.value = 3;
    await tester.pump();
    expect(builds, isEmpty);
  });

  testWidgets('an inactive page that still ticks holds its feed too', (
    tester,
  ) async {
    final active = ValueNotifier(false);
    addTearDown(active.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: ValueListenableBuilder<bool>(
          valueListenable: active,
          builder: (_, isActive, child) =>
              PageActivityScope(isActive: isActive, child: child!),
          child: _Feed(source: source, builds: builds),
        ),
      ),
    );
    builds.clear();

    source.value = 4;
    await tester.pump();
    expect(builds, isEmpty);

    active.value = true;
    await tester.pump();
    expect(builds, [4]);
  });

  testWidgets('a feed under a settled opaque route stays put, and holds on '
      'while the route above it pops', (tester) async {
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigator,
        home: Scaffold(
          body: _Feed(source: source, builds: builds),
        ),
      ),
    );
    navigator.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold()),
    );
    await tester.pumpAndSettle();
    builds.clear();

    for (var value = 1; value <= 3; value++) {
      source.value = value;
      await tester.pump(const Duration(milliseconds: 300));
    }
    expect(builds, isEmpty);
    expect(shown(0), findsOneWidget);

    navigator.currentState!.pop();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('shown 0'), findsOneWidget);

    await tester.pumpAndSettle();
    expect(find.text('shown 3'), findsOneWidget);
    expect(builds.where((value) => value == 3), isNotEmpty);
    expect(builds.toSet(), {0, 3});
  });
}
