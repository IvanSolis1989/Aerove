import 'dart:math';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/widgets/aurora.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

class AuroraSettings extends ConsumerWidget {
  const AuroraSettings({super.key});

  Future<void> _editColor(
    BuildContext context,
    WidgetRef ref, {
    int? index,
  }) async {
    final theme = ref.read(themeSettingProvider);
    if (!theme.auroraEnabled) return;
    if (index == null && theme.auroraColors.length >= 3) {
      return;
    }
    final result = await dialogs.showCommonDialog<int>(
      child: _AuroraPaletteDialog(
        colors: theme.auroraColors,
        seed: theme.auroraSeed,
        index: index,
      ),
    );
    if (result == null || !context.mounted) {
      return;
    }
    ref.read(themeSettingProvider.notifier).update((state) {
      if (!state.auroraEnabled) return state;
      final colors = List<int>.of(state.auroraColors);
      if (index == null) {
        if (colors.length < 3) {
          colors.add(result);
        }
      } else if (index < colors.length) {
        if (result == -1) {
          if (colors.length > 1) {
            colors.removeAt(index);
          }
        } else {
          colors[index] = result;
        }
      }
      return state.copyWith(auroraColors: colors);
    });
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = context.appLocalizations;
    final theme = ref.watch(themeSettingProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (theme.auroraEnabled) ...[
          InfoHeader(
            info: Info(label: strings.auroraColors, glyph: AppGlyphs.palette),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                for (var index = 0; index < theme.auroraColors.length; index++)
                  SizedBox.square(
                    dimension: 48,
                    child: IconButton(
                      tooltip: strings.auroraEditColor(index + 1),
                      style: IconButton.styleFrom(
                        backgroundColor: Color(theme.auroraColors[index]),
                        shape: AppShape.sm,
                      ),
                      onPressed: () => _editColor(context, ref, index: index),
                      icon: const SizedBox.shrink(),
                    ),
                  ),
                if (theme.auroraColors.length < 3)
                  SizedBox.square(
                    dimension: 48,
                    child: IconButton.filledTonal(
                      tooltip: strings.add,
                      onPressed: () => _editColor(context, ref),
                      icon: const GlyphIcon(AppGlyphs.addCircle, fill: 1),
                    ),
                  ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: _AuroraPreview(
              colors: theme.auroraColors,
              seed: theme.auroraSeed,
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Align(
              alignment: AlignmentDirectional.centerEnd,
              child: FilledButton.tonalIcon(
                onPressed: () {
                  ref.read(themeSettingProvider.notifier).update((state) {
                    if (!state.auroraEnabled) return state;
                    final seed =
                        (state.auroraSeed + 1 + Random().nextInt(0x3ffffffe)) %
                        0x7fffffff;
                    return state.copyWith(auroraSeed: seed);
                  });
                },
                icon: const GlyphIcon(AppGlyphs.shuffle, fill: 1),
                label: GlassText(strings.auroraRegenerate),
              ),
            ),
          ),
        ],
      ],
    );
  }
}

class _AuroraPreview extends StatelessWidget {
  const _AuroraPreview({required this.colors, required this.seed});

  final List<int> colors;
  final int seed;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: context.appLocalizations.preview,
      image: true,
      child: AspectRatio(
        aspectRatio: 2.6,
        child: ClipRSuperellipse(
          borderRadius: AppRadius.lg,
          child: SeededAuroraWallpaper(
            colors: colors.map(Color.new).toList(),
            seed: seed,
            brightness: Theme.of(context).brightness,
          ),
        ),
      ),
    );
  }
}

class _AuroraPaletteDialog extends StatefulWidget {
  const _AuroraPaletteDialog({
    required this.colors,
    required this.seed,
    required this.index,
  });

  final List<int> colors;
  final int seed;
  final int? index;

  @override
  State<_AuroraPaletteDialog> createState() => _AuroraPaletteDialogState();
}

class _AuroraPaletteDialogState extends State<_AuroraPaletteDialog> {
  late final _controller = ValueNotifier<Color>(
    Color(widget.index == null ? 0xFFB9CCD1 : widget.colors[widget.index!]),
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = context.appLocalizations;
    return CommonDialog(
      title: strings.auroraColors,
      actions: [
        if (widget.index != null && widget.colors.length > 1)
          TextButton(
            onPressed: () => Navigator.of(context).pop(-1),
            child: GlassText(strings.delete),
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: GlassText(strings.cancel),
        ),
        TextButton(
          onPressed: () =>
              Navigator.of(context).pop(_controller.value.toARGB32()),
          child: GlassText(strings.confirm),
        ),
      ],
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ValueListenableBuilder(
            valueListenable: _controller,
            builder: (context, color, child) {
              final colors = List<int>.of(widget.colors);
              if (widget.index case final index?) {
                colors[index] = color.toARGB32();
              } else {
                colors.add(color.toARGB32());
              }
              return _AuroraPreview(colors: colors, seed: widget.seed);
            },
          ),
          const SizedBox(height: 16),
          Palette(controller: _controller),
        ],
      ),
    );
  }
}
