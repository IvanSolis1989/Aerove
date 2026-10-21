import 'package:fl_clash/common/first_frame.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/pages/home.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

class _SentProbe extends LeafRenderObjectWidget {
  const _SentProbe(this.paints);

  final List<bool> paints;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderSentProbe(paints);
}

class _RenderSentProbe extends RenderBox {
  _RenderSentProbe(this.paints);

  final List<bool> paints;

  @override
  bool get sizedByParent => true;

  @override
  Size computeDryLayout(BoxConstraints constraints) => constraints.biggest;

  @override
  void paint(PaintingContext context, Offset offset) {
    paints.add(RendererBinding.instance.sendFramesToEngine);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'the first frame sent to the engine already holds the home page',
    (tester) async {
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final rootPaints = <bool>[];
      final homePaints = <bool>[];
      final container = ProviderContainer(
        overrides: [
          navigationItemsStateProvider.overrideWithValue(
            NavigationItemsState(
              value: [
                NavigationItem(
                  glyph: AppGlyphs.dashboard,
                  label: PageLabel.dashboard,
                  builder: (_) => _SentProbe(homePaints),
                ),
                NavigationItem(
                  glyph: AppGlyphs.tools,
                  label: PageLabel.tools,
                  builder: (_) => const SizedBox.expand(),
                ),
              ],
            ),
          ),
        ],
      );
      addTearDown(container.dispose);
      globalState.container = container;
      tester.binding.resetFirstFrameSent();
      final gate = FirstFrameGate.untilViewSize(
        container,
        binding: tester.binding,
      );
      addTearDown(gate.release);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: TestApp(
            includeNavigatorKey: false,
            child: Stack(
              children: [
                _SentProbe(rootPaints),
                const ThemeManager(child: HomePage()),
              ],
            ),
          ),
        ),
      );
      expect(rootPaints.first, isFalse, reason: 'the empty frame is held back');
      expect(homePaints, isEmpty);

      await tester.pump();
      expect(container.read(viewSizeProvider), const Size(400, 800));
      expect(homePaints.first, isTrue);
      expect(tester.binding.sendFramesToEngine, isTrue);
    },
  );

  testWidgets('an empty view size keeps the frame held', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    tester.binding.resetFirstFrameSent();
    final gate = FirstFrameGate.untilViewSize(
      container,
      binding: tester.binding,
    );

    container.read(viewSizeProvider.notifier).value = Size.zero;
    container.read(viewSizeProvider.notifier).value = const Size(0, 800);
    expect(tester.binding.sendFramesToEngine, isFalse);

    container.read(viewSizeProvider.notifier).value = const Size(400, 800);
    expect(tester.binding.sendFramesToEngine, isTrue);
    gate.release();
    expect(tester.binding.sendFramesToEngine, isTrue);
  });

  testWidgets('a view size that never arrives releases the frame in time', (
    tester,
  ) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    tester.binding.resetFirstFrameSent();
    FirstFrameGate.untilViewSize(
      container,
      binding: tester.binding,
      timeout: const Duration(milliseconds: 500),
    );

    await tester.pump(const Duration(milliseconds: 499));
    expect(tester.binding.sendFramesToEngine, isFalse);
    await tester.pump(const Duration(milliseconds: 1));
    expect(tester.binding.sendFramesToEngine, isTrue);
  });
}
