import 'package:fl_clash/ui/liquid_glass.dart';
import 'package:material_ui/material_ui.dart';

class SheetSurface extends StatelessWidget {
  const SheetSurface({
    super.key,
    required this.color,
    required this.shape,
    this.animationDuration = const Duration(milliseconds: 200),
    this.occludesGlass = false,
    required this.child,
  });

  final Color color;
  final ShapeBorder shape;
  final Duration animationDuration;
  final bool occludesGlass;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final glass =
        LiquidGlassScope.of(context) && !MediaQuery.highContrastOf(context);
    final enclosed = GlassSurfaceScope.of(context);
    final content = glass && !enclosed
        ? Stack(
            fit: StackFit.passthrough,
            children: [
              Positioned.fill(
                child: IgnorePointer(
                  child: LiquidGlass(
                    style: LiquidGlassStyle(
                      tint: LiquidGlassScope.surfaceColorOf(context, color),
                      blur: 16,
                      refraction: 0,
                      bevel: 0,
                      rim: 0,
                    ),
                    clipper: ShapeBorderClipper(
                      shape: shape,
                      textDirection: Directionality.of(context),
                    ),
                  ),
                ),
              ),
              child,
            ],
          )
        : child;
    final surface = Material(
      animationDuration: animationDuration,
      color: glass ? Colors.transparent : color,
      shape: shape,
      clipBehavior: Clip.antiAlias,
      child: glass
          ? Theme(
              data: Theme.of(
                context,
              ).copyWith(scaffoldBackgroundColor: Colors.transparent),
              child: GlassSurfaceScope(child: content),
            )
          : content,
    );
    return glass && !enclosed && occludesGlass
        ? LiquidGlassOcclusion(shape: shape, child: surface)
        : surface;
  }
}
