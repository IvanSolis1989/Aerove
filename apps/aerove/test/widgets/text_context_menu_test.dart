import 'package:fl_clash/common/app_localizations.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/manager/theme_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../helpers/test_app.dart';

const _size = Size(390, 700);
final _android = TargetPlatformVariant.only(TargetPlatform.android);
const _standard = ['Cut', 'Copy', 'Paste', 'Share', 'Select all'];
const _processText = ['询问 ChatGPT', '翻译', '发送至 OneNote', '朗读', '百度地图'];

final _field = find.byKey(const ValueKey('field'));
final _platter = find.byType(PopupMenuCard);

/// Pumps [home] in the app's own surface, glass theme and host, as
/// `Application` builds them above its navigator.
Future<ThemeData> _pumpApp(
  WidgetTester tester,
  Widget home, {
  Brightness brightness = Brightness.light,
  LiquidGlassMode mode = LiquidGlassMode.ultra,
  bool highContrast = false,
}) async {
  tester.view.physicalSize = _size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  late ThemeData appTheme;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        viewSizeProvider.overrideWithBuild((_, _) => _size),
        themeSettingProvider.overrideWithBuild(
          (_, _) => ThemeProps(liquidGlass: mode),
        ),
      ],
      child: Consumer(
        builder: (_, ref, _) {
          appTheme = watchAppTheme(ref, brightness);
          return MaterialApp(
            theme: appTheme,
            themeAnimationStyle: AnimationStyle.noAnimation,
            localizationsDelegates: const [
              AppLocalizations.delegate,
              AppUiLocalizations.delegate,
              ...GlobalMaterialLocalizations.delegates,
            ],
            supportedLocales: AppLocalizations.delegate.supportedLocales,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context).copyWith(highContrast: highContrast),
              child: wrapAppScopes(
                context,
                AuroraAppSurface(motion: false, child: child!),
              ),
            ),
            home: Scaffold(body: Center(child: home)),
          );
        },
      ),
    ),
  );
  await tester.pump();
  return appTheme;
}

EditableTextState _editable(WidgetTester tester) => tester.state(
  find.descendant(of: _field, matching: find.byType(EditableText)),
);

Future<void> _showToolbar(WidgetTester tester) async {
  final state = _editable(tester);
  state.widget.focusNode.requestFocus();
  await tester.pump();
  state.userUpdateTextEditingValue(
    state.textEditingValue.copyWith(
      selection: const TextSelection(baseOffset: 0, extentOffset: 5),
    ),
    SelectionChangedCause.longPress,
  );
  await tester.pump();
  LiquidGlassHost.debugLastCapture = null;
  expect(state.showToolbar(), isTrue);
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 300));
}

/// The panes the host drew within the platter, in a view at one pixel a dp.
Iterable<Rect> _panesIn(WidgetTester tester) {
  final platter = tester.getRect(_platter).inflate(1);
  return [
    for (final pane in LiquidGlassHost.debugLastCapture?.panes ?? const [])
      if (platter.contains(pane.pane.topLeft) &&
          platter.contains(pane.pane.bottomRight))
        pane.pane,
  ];
}

void _expectOneBarePane(WidgetTester tester) {
  expect(_platter, findsOneWidget);
  expect(tester.widget<PopupMenuCard>(_platter).glass, isNotNull);
  expect(_panesIn(tester), hasLength(1));
  expect(
    find.ancestor(of: _platter, matching: find.byType(LiquidGlassBody)),
    findsOneWidget,
  );
  for (final button in [
    find.byType(TextSelectionToolbarTextButton),
    find.byType(IconButton),
  ]) {
    expect(
      find.descendant(
        of: find.descendant(of: _platter, matching: button),
        matching: find.byType(LiquidGlass),
      ),
      findsNothing,
    );
  }
}

void _expectSolidPlatter(WidgetTester tester) {
  expect(_platter, findsOneWidget);
  expect(find.byType(LiquidGlass), findsNothing);
  final card = tester.widget<PopupMenuCard>(_platter);
  final colorScheme = Theme.of(tester.element(_platter)).colorScheme;
  final fill = SurfaceLevel.floatingOf(colorScheme).colorOf(colorScheme);
  expect(card.glass, isNull);
  expect(card.fill, fill);
  expect(fill.a, 1);
  expect(card.shadows, menuShadows(colorScheme));
  final expectedPaint = paints;
  for (final shadow in card.shadows) {
    expectedPaint.rsuperellipse(
      color: shadow.color,
      hasMaskFilter: shadow.toPaint().maskFilter != null,
    );
  }
  expectedPaint.path(color: fill);
  expect(tester.renderObject(_platter), expectedPaint);
}

Widget _textField() => SizedBox(
  width: 300,
  child: TextField(
    key: const ValueKey('field'),
    controller: TextEditingController(text: 'hello glass world'),
    contextMenuBuilder: glassEditableTextContextMenu,
    magnifierConfiguration: glassTextMagnifierConfiguration,
  ),
);

void main() {
  tearDown(ContextMenuController.removeAny);

  for (final brightness in Brightness.values) {
    for (final mode in [LiquidGlassMode.standard, LiquidGlassMode.ultra]) {
      testWidgets(
        'a field shows its menu on one bare pane $brightness $mode',
        variant: _android,
        (tester) async {
          final appTheme = await _pumpApp(
            tester,
            _textField(),
            brightness: brightness,
            mode: mode,
          );
          await _showToolbar(tester);
          _expectOneBarePane(tester);
          expect(tester.widget<PopupMenuCard>(_platter).radius, 22);

          final field = tester.element(find.byType(EditableText));
          final seed = appTheme.colorScheme.primary;
          expect(
            Theme.of(field).colorScheme.primary,
            auroraColorScheme(brightness).primary,
          );
          expect(seed, isNot(auroraColorScheme(brightness).primary));
          final selection = TextSelectionTheme.of(field);
          expect(selection.selectionHandleColor, seed);
          expect(selection.cursorColor, seed);
          expect(
            DefaultSelectionStyle.of(field).selectionColor,
            seed.withValues(alpha: 0.3),
          );
        },
      );

      testWidgets(
        'the overflow page keeps the one pane $brightness $mode',
        variant: _android,
        (tester) async {
          await _pumpApp(
            tester,
            _textField(),
            brightness: brightness,
            mode: mode,
          );
          final state = _editable(tester);
          state.widget.focusNode.requestFocus();
          await tester.pump();
          final controller = ContextMenuController();
          addTearDown(controller.remove);
          controller.show(
            context: state.context,
            contextMenuBuilder: (_) => InheritedTheme.captureAll(
              state.context,
              GlassTextSelectionToolbar(
                anchors: state.contextMenuAnchors,
                buttonItems: [
                  for (final label in [..._standard, ..._processText])
                    ContextMenuButtonItem(label: label, onPressed: () {}),
                ],
              ),
            ),
          );
          await tester.pump();
          LiquidGlassHost.debugLastCapture = null;
          await tester.tap(find.byKey(StandardComponentType.moreButton.key));
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 300));
          expect(find.text('朗读'), findsOneWidget);
          final back = find.byKey(StandardComponentType.backButton.key);
          expect(back, findsOneWidget);
          final ink = tester.widget<GlassInk>(
            find.ancestor(
              of: find.descendant(of: back, matching: find.byType(Icon)),
              matching: find.byType(GlassInk),
            ),
          );
          expect(ink.role, GlassInkRole.icon);
          expect(
            ink.source,
            Theme.of(tester.element(back)).colorScheme.onSurface,
          );
          _expectOneBarePane(tester);
        },
      );
    }
  }

  for (final (mode, highContrast) in [
    (LiquidGlassMode.none, false),
    (LiquidGlassMode.ultra, true),
  ]) {
    testWidgets(
      'without glass the menu stands on the opaque floating fill '
      '$mode high contrast $highContrast',
      variant: _android,
      (tester) async {
        await _pumpApp(
          tester,
          _textField(),
          mode: mode,
          highContrast: highContrast,
        );
        await _showToolbar(tester);
        _expectSolidPlatter(tester);
      },
    );
  }

  testWidgets(
    'a desktop menu lists its items as rows on the same platter',
    variant: TargetPlatformVariant.only(TargetPlatform.windows),
    (tester) async {
      await _pumpApp(tester, _textField());
      await _showToolbar(tester);
      _expectOneBarePane(tester);
      expect(find.byType(TextSelectionToolbar), findsNothing);
      expect(
        find.descendant(of: _platter, matching: find.byType(InkWell)),
        findsWidgets,
      );
    },
  );

  testWidgets('no items build no menu', variant: _android, (tester) async {
    await _pumpApp(
      tester,
      const GlassTextSelectionToolbar(
        anchors: TextSelectionToolbarAnchors(primaryAnchor: Offset.zero),
        buttonItems: [],
      ),
    );
    expect(_platter, findsNothing);
    expect(
      find.descendant(
        of: find.byType(GlassTextSelectionToolbar),
        matching: find.byType(SizedBox),
      ),
      findsOneWidget,
    );
  });

  testWidgets('selectable text shows the same menu', variant: _android, (
    tester,
  ) async {
    await _pumpApp(
      tester,
      const SelectableText(
        'hello glass world',
        key: ValueKey('field'),
        contextMenuBuilder: glassEditableTextContextMenu,
      ),
    );
    await _showToolbar(tester);
    _expectOneBarePane(tester);
  });

  testWidgets('a selection area shows the same menu', variant: _android, (
    tester,
  ) async {
    await _pumpApp(
      tester,
      const SelectionArea(
        contextMenuBuilder: glassSelectableRegionContextMenu,
        child: Text('hello glass world'),
      ),
    );
    LiquidGlassHost.debugLastCapture = null;
    await tester.longPress(find.text('hello glass world'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    _expectOneBarePane(tester);
  });

  testWidgets(
    'inked selectable text highlights its selection beneath the ink',
    variant: _android,
    (tester) async {
      await _pumpApp(
        tester,
        const GlassSelectableText(
          TextSpan(text: 'hello glass world'),
          key: ValueKey('field'),
        ),
      );
      final ink = find.descendant(of: _field, matching: find.byType(GlassInk));
      expect(ink, findsOneWidget);
      final text = tester.widget<SelectableText>(
        find.descendant(of: ink, matching: find.byType(SelectableText)),
      );
      expect(text.selectionColor, Colors.transparent);
      await _showToolbar(tester);
      final accent = DefaultSelectionStyle.of(
        tester.element(find.byType(EditableText)),
      ).selectionColor;
      expect(accent, isNotNull);
      final highlight = find
          .ancestor(of: ink, matching: find.byType(CustomPaint))
          .first;
      expect(tester.renderObject(highlight), paints..rect(color: accent));
    },
  );

  testWidgets(
    'dragging over a field shows the superellipse loupe in the accent',
    variant: _android,
    (tester) async {
      final appTheme = await _pumpApp(tester, _textField());
      final gesture = await tester.startGesture(tester.getCenter(_field));
      await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
      await tester.pump();
      expect(find.byType(TextMagnifier), findsNothing);
      expect(
        tester.widget<TextLoupe>(find.byType(TextLoupe)).borderColor,
        appTheme.colorScheme.primary,
      );
      await gesture.up();
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byType(TextLoupe), findsNothing);
    },
  );

  testWidgets(
    'text with a coloured span stays plain and keeps its own highlight',
    variant: _android,
    (tester) async {
      await _pumpApp(
        tester,
        const GlassSelectableText(
          TextSpan(
            children: [
              TextSpan(text: 'hello '),
              TextSpan(
                text: 'red',
                style: TextStyle(color: Color(0xFFFF0000)),
              ),
            ],
          ),
          key: ValueKey('field'),
        ),
      );
      expect(
        find.descendant(of: _field, matching: find.byType(GlassInk)),
        findsNothing,
      );
      expect(
        tester
            .widget<SelectableText>(find.byType(SelectableText))
            .selectionColor,
        isNull,
      );
    },
  );

  testWidgets('selectable text is no stop on the way to a button', (
    tester,
  ) async {
    await _pumpApp(
      tester,
      Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const GlassSelectableText(TextSpan(text: 'hello glass world')),
          TextButton(onPressed: () {}, child: const Text('OK')),
        ],
      ),
    );
    final button = tester.element(find.text('OK'));
    FocusScope.of(button).nextFocus();
    await tester.pump();
    expect(Focus.of(button).hasPrimaryFocus, isTrue);
  });
}
