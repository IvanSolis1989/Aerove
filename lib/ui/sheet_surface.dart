import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:material_ui/material_ui.dart';

class SheetSurface extends StatelessWidget {
  const SheetSurface({super.key, required this.color, required this.shape,
    this.animationDuration = const Duration(milliseconds: 200),
    required this.child});

  final Color color;
  final ShapeBorder shape;
  final Duration animationDuration;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final glass = LiquidGlassScope.of(context) &&
        !MediaQuery.highContrastOf(context);
    final enclosed = context.dependOnInheritedWidgetOfExactType<_SheetGlassScope>() != null;
    final content = glass && !enclosed
        ? Stack(fit: StackFit.passthrough, children: [
            Positioned.fill(child: IgnorePointer(child: LiquidGlass(
              style: LiquidGlassStyle(
                tint: LiquidGlassScope.surfaceColorOf(context, color),
                blur: 16, refraction: 0, bevel: 0, rim: 0,
              ),
              clipper: ShapeBorderClipper(shape: shape,
                textDirection: Directionality.of(context)),
            ))),
            child,
          ])
        : child;
    return Material(
      animationDuration: animationDuration,
      color: glass ? Colors.transparent : color,
      shape: shape,
      clipBehavior: Clip.antiAlias,
      child: glass
          ? Theme(
              data: Theme.of(context).copyWith(scaffoldBackgroundColor: Colors.transparent),
              child: _SheetGlassScope(child: content),
            )
          : content,
    );
  }
}

class _SheetGlassScope extends InheritedWidget {
  const _SheetGlassScope({required super.child});

  @override
  bool updateShouldNotify(_SheetGlassScope oldWidget) => false;
}
