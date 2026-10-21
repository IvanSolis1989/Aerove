import 'package:emoji_regex/emoji_regex.dart';
import 'package:flutter/rendering.dart';
import 'package:material_ui/material_ui.dart';

import 'foreground_ink.dart';
import 'glass_foreground.dart';
import 'liquid_glass.dart';
import 'popup.dart';
import 'shape.dart';
import 'surface.dart';
import 'typography.dart';

// Half the height of Material's 44 dp bar, so the bar is a capsule.
const double _barRadius = 22;
const double _desktopScreenPadding = 8;
const double _desktopInset = 8;
const double _desktopMinWidth = 180;
const double _desktopMaxWidth = 280;
const _desktopRowPadding = EdgeInsets.all(12);

/// The menu a field shows over its selection, for `contextMenuBuilder`.
Widget glassEditableTextContextMenu(
  BuildContext context,
  EditableTextState state,
) {
  if (SystemContextMenu.isSupportedByField(state)) {
    return SystemContextMenu.editableText(editableTextState: state);
  }
  return _themedAs(
    state.context,
    GlassTextSelectionToolbar(
      anchors: state.contextMenuAnchors,
      buttonItems: state.contextMenuButtonItems,
    ),
  );
}

/// The menu a `SelectionArea` shows over its selection.
Widget glassSelectableRegionContextMenu(
  BuildContext context,
  SelectableRegionState state,
) => _themedAs(
  state.context,
  GlassTextSelectionToolbar(
    anchors: state.contextMenuAnchors,
    buttonItems: state.contextMenuButtonItems,
  ),
);

// The menu is built in the root overlay, which sees none of the themes a sheet
// or a page sets around the field.
Widget _themedAs(BuildContext field, Widget menu) =>
    InheritedTheme.captureAll(field, menu);

/// A text selection menu on the platter a popup menu stands on: one pane for
/// the bar and its overflow page, with the buttons bare on it.
class GlassTextSelectionToolbar extends StatelessWidget {
  const GlassTextSelectionToolbar({
    super.key,
    required this.anchors,
    required this.buttonItems,
  });

  final TextSelectionToolbarAnchors anchors;
  final List<ContextMenuButtonItem> buttonItems;

  @override
  Widget build(BuildContext context) {
    if (buttonItems.isEmpty) {
      return const SizedBox.shrink();
    }
    final theme = Theme.of(context);
    final foreground = theme.colorScheme.onSurface;
    final bare = ButtonStyle(
      backgroundColor: const WidgetStatePropertyAll(Colors.transparent),
      foregroundColor: WidgetStatePropertyAll(foreground),
      overlayColor: WidgetStatePropertyAll(foreground.withValues(alpha: 0.08)),
      backgroundBuilder: (_, _, child) => child ?? const SizedBox.shrink(),
    );
    final style = AppTextStyle.subheadline
        .of(context)
        .copyWith(color: foreground);
    return TextButtonTheme(
      data: TextButtonThemeData(style: bare),
      child: IconButtonTheme(
        data: IconButtonThemeData(
          style: bare.copyWith(
            shape: const WidgetStatePropertyAll(AppShape.full),
          ),
        ),
        child: switch (theme.platform) {
          TargetPlatform.android ||
          TargetPlatform.iOS ||
          TargetPlatform.fuchsia => _buildBar(context, style),
          TargetPlatform.linux ||
          TargetPlatform.macOS ||
          TargetPlatform.windows => _buildDesktop(context, style),
        },
      ),
    );
  }

  String _labelOf(BuildContext context, ContextMenuButtonItem item) =>
      AdaptiveTextSelectionToolbar.getButtonLabel(context, item);

  Widget _buildBar(BuildContext context, TextStyle style) {
    return TextSelectionToolbar(
      anchorAbove: anchors.primaryAnchor,
      anchorBelow: anchors.secondaryAnchor ?? anchors.primaryAnchor,
      toolbarBuilder: (context, child) =>
          _GlassPlatter(radius: _barRadius, child: child),
      children: [
        for (final (index, item) in buttonItems.indexed)
          TextSelectionToolbarTextButton(
            padding: TextSelectionToolbarTextButton.getPadding(
              index,
              buttonItems.length,
            ),
            alignment: AlignmentDirectional.centerStart,
            onPressed: item.onPressed,
            child: GlassText(_labelOf(context, item), style: style),
          ),
      ],
    );
  }

  Widget _buildDesktop(BuildContext context, TextStyle style) {
    final paddingAbove =
        MediaQuery.paddingOf(context).top + _desktopScreenPadding;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        _desktopScreenPadding,
        paddingAbove,
        _desktopScreenPadding,
        _desktopScreenPadding,
      ),
      child: CustomSingleChildLayout(
        delegate: DesktopTextSelectionToolbarLayoutDelegate(
          anchor:
              anchors.primaryAnchor -
              Offset(_desktopScreenPadding, paddingAbove),
        ),
        child: _GlassPlatter(
          child: ConstrainedBox(
            constraints: const BoxConstraints(
              minWidth: _desktopMinWidth,
              maxWidth: _desktopMaxWidth,
            ),
            child: IntrinsicWidth(
              child: Padding(
                padding: const EdgeInsets.all(_desktopInset),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (final item in buttonItems)
                      InkWell(
                        customBorder: AppShape.md,
                        splashFactory: NoSplash.splashFactory,
                        onTap: item.onPressed,
                        child: Padding(
                          padding: _desktopRowPadding,
                          child: GlassText(
                            _labelOf(context, item),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: style,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _GlassPlatter extends StatelessWidget {
  const _GlassPlatter({this.radius, required this.child});

  final double? radius;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final glass = LiquidGlassScope.of(context);
    return LiquidGlassBody(
      child: PopupMenuCard(
        grow: 1,
        origin: null,
        bare: false,
        dim: Colors.transparent,
        radius: radius,
        glass: glass ? LiquidGlassStyle.menu(colorScheme) : null,
        rim: glass ? LiquidGlassRim.menu(colorScheme) : null,
        shadows: glass
            ? glassMenuShadows(colorScheme)
            : popupCardShadows(colorScheme),
        fill: SurfaceLevel.floatingOf(colorScheme).colorOf(colorScheme),
        child: Material(type: MaterialType.transparency, child: child),
      ),
    );
  }
}

/// Selectable text in the glass ink, as [GlassText] inks plain text. The ink
/// recolours every pixel under it, so the selection is highlighted beneath it
/// and keeps its accent.
class GlassSelectableText extends StatefulWidget {
  const GlassSelectableText(
    this.span, {
    super.key,
    this.style,
    this.inkRole = GlassInkRole.body,
  });

  final TextSpan span;
  final TextStyle? style;
  final GlassInkRole inkRole;

  @override
  State<GlassSelectableText> createState() => _GlassSelectableTextState();
}

class _GlassSelectableTextState extends State<GlassSelectableText> {
  final _canvas = GlobalKey();
  final _focusNode = FocusNode(skipTraversal: true);
  final _selection = ValueNotifier<TextSelection?>(null);

  @override
  void dispose() {
    _focusNode.dispose();
    _selection.dispose();
    super.dispose();
  }

  RenderEditable? _editable() {
    RenderEditable? found;
    void visit(RenderObject child) {
      if (found != null) {
        return;
      }
      if (child is RenderEditable) {
        found = child;
      } else {
        child.visitChildren(visit);
      }
    }

    _canvas.currentContext?.findRenderObject()?.visitChildren(visit);
    return found;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final source =
        DefaultTextStyle.of(context).style.merge(widget.style).color ??
        theme.colorScheme.onSurface;
    final inked =
        GlassForeground.colorsInk(context, source) &&
        _plain(context, widget.span);
    final text = SelectableText.rich(
      widget.span,
      style: widget.style,
      focusNode: _focusNode,
      selectionColor: inked ? Colors.transparent : null,
      contextMenuBuilder: glassEditableTextContextMenu,
      onSelectionChanged: (selection, _) => _selection.value = selection,
    );
    if (!inked) {
      return text;
    }
    return CustomPaint(
      key: _canvas,
      painter: _SelectionPainter(
        this,
        DefaultSelectionStyle.of(context).selectionColor ??
            theme.colorScheme.primary.withValues(alpha: 0.4),
      ),
      child: GlassInk(source: source, role: widget.inkRole, child: text),
    );
  }
}

final _emoji = emojiRegex();

bool _plain(BuildContext context, InlineSpan span) {
  if (span is! TextSpan) {
    return false;
  }
  final style = span.style;
  final color = style?.color;
  if (style?.foreground != null ||
      style?.background != null ||
      style?.backgroundColor != null ||
      (color != null && !GlassForeground.colorsInk(context, color)) ||
      _emoji.hasMatch(span.text ?? '')) {
    return false;
  }
  return span.children?.every((child) => _plain(context, child)) ?? true;
}

class _SelectionPainter extends CustomPainter {
  _SelectionPainter(this.state, this.color)
    : super(repaint: Listenable.merge([state._selection, state._focusNode]));

  final _GlassSelectableTextState state;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final editable = state._editable();
    final canvasBox = state._canvas.currentContext?.findRenderObject();
    final selection = editable?.selection;
    if (editable == null ||
        canvasBox == null ||
        selection == null ||
        !selection.isValid ||
        selection.isCollapsed) {
      return;
    }
    final transform = editable.getTransformTo(canvasBox);
    final paint = Paint()..color = color;
    for (final box in editable.getBoxesForSelection(selection)) {
      canvas.drawRect(
        MatrixUtils.transformRect(transform, box.toRect()),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(_SelectionPainter oldDelegate) =>
      oldDelegate.color != color;
}
