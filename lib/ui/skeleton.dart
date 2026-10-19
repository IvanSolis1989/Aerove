import 'package:material_ui/material_ui.dart';

/// Holds transparent space for one line of [style] text.
class SkeletonText extends StatelessWidget {
  const SkeletonText({super.key, required this.width, this.style});

  final double width;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    final style = DefaultTextStyle.of(context).style.merge(this.style);
    return ExcludeSemantics(
      child: SizedBox(
        width: width,
        child: Stack(
          children: [
            Opacity(opacity: 0, child: Text(' ', style: style)),
            Positioned.fill(
              child: Center(
                child: SkeletonPill(
                  width: double.infinity,
                  height: MediaQuery.textScalerOf(
                    context,
                  ).scale(style.fontSize ?? 14),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Holds transparent space for a block of a known size.
class SkeletonPill extends StatelessWidget {
  const SkeletonPill({super.key, required this.width, required this.height});

  final double width;
  final double height;

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: SizedBox(width: width, height: height),
    );
  }
}
