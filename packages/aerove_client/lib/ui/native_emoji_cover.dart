import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:material_ui/material_ui.dart';

class EmojiInkReadyNotification extends Notification {}

class NativeEmojiCover extends StatefulWidget {
  const NativeEmojiCover(
    this.emoji, {
    super.key,
    required this.size,
    required this.fallback,
  });

  final String emoji;
  final double size;
  final Widget fallback;

  @visibleForTesting
  static String? debugFontFamily;

  @override
  State<NativeEmojiCover> createState() => _NativeEmojiCoverState();
}

class _NativeEmojiCoverState extends State<NativeEmojiCover> {
  Object? _key;
  ui.Picture? _picture;
  Rect? _ink;

  @override
  void initState() {
    super.initState();
    _NativeEmojiInk.instance.addListener(_fontsChanged);
  }

  void _fontsChanged() => setState(() => _key = null);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _prepare();
  }

  @override
  void didUpdateWidget(NativeEmojiCover oldWidget) {
    super.didUpdateWidget(oldWidget);
    _prepare();
  }

  void _prepare() {
    final cache = _NativeEmojiInk.instance;
    final locale = Localizations.maybeLocaleOf(context);
    final key = (widget.emoji, locale, cache.generation, _emojiFontFamily());
    if (key == _key) return;
    _key = key;
    _ink = null;
    _picture?.dispose();
    _picture = _recordEmoji(widget.emoji, locale).$1;
    cache.bounds(widget.emoji, locale).then((ink) {
      if (!mounted || _key != key || ink == null) return;
      setState(() => _ink = ink);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _key == key) {
          EmojiInkReadyNotification().dispatch(context);
        }
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    _prepare();
    return SizedBox.square(
      dimension: widget.size,
      child: _ink == null
          ? widget.fallback
          : CustomPaint(painter: _EmojiInkPainter(_picture!, _ink!)),
    );
  }

  @override
  void dispose() {
    _NativeEmojiInk.instance.removeListener(_fontsChanged);
    _picture?.dispose();
    super.dispose();
  }
}

String? _emojiFontFamily() {
  String? fontFamily;
  assert(() {
    fontFamily = NativeEmojiCover.debugFontFamily;
    return true;
  }());
  return fontFamily;
}

(ui.Picture, Size) _recordEmoji(String emoji, Locale? locale) {
  final text = TextPainter(
    text: TextSpan(
      text: emoji,
      style: TextStyle(
        inherit: false,
        fontFamily: _emojiFontFamily(),
        fontSize: 256,
        height: 1,
      ),
    ),
    textDirection: TextDirection.ltr,
    locale: locale,
    maxLines: 1,
  )..layout();
  final size = Size(
    (text.width + 512).ceilToDouble(),
    (text.height + 512).ceilToDouble(),
  );
  final recorder = ui.PictureRecorder();
  text.paint(Canvas(recorder), const Offset(256, 256));
  text.dispose();
  return (recorder.endRecording(), size);
}

class _NativeEmojiInk extends ChangeNotifier {
  _NativeEmojiInk() {
    PaintingBinding.instance.systemFonts.addListener(() {
      generation++;
      _entries.clear();
      notifyListeners();
    });
  }

  static final instance = _NativeEmojiInk();
  final _entries = <Object, Future<Rect?>>{};
  Future<void> _tail = Future.value();
  var _pending = 0;
  var generation = 0;

  Future<Rect?> bounds(String emoji, Locale? locale) {
    final key = (emoji, locale, generation, _emojiFontFamily());
    final existing = _entries.remove(key);
    if (existing != null) return _entries[key] = existing;
    if (_pending >= 8) return Future.value();
    _pending++;
    final result = _tail.then((_) => _measure(emoji, locale));
    _tail = result.then<void>(
      (_) {
        _pending--;
      },
      onError: (Object error, StackTrace stack) {
        _pending--;
      },
    );
    _entries[key] = result;
    if (_entries.length > 16) _entries.remove(_entries.keys.first);
    return result;
  }

  Future<Rect?> _measure(String emoji, Locale? locale) async {
    ui.Picture? picture;
    ui.Image? image;
    try {
      final recording = _recordEmoji(emoji, locale);
      picture = recording.$1;
      final size = recording.$2;
      if (size.longestSide > 1536) return null;
      image = await picture.toImage(size.width.toInt(), size.height.toInt());
      final bytes = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      if (bytes == null) return null;
      final bounds = await compute(_alphaBounds, (
        bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes),
        image.width,
        image.height,
      ));
      return bounds == null
          ? null
          : Rect.fromLTRB(
              bounds[0].toDouble(),
              bounds[1].toDouble(),
              bounds[2].toDouble(),
              bounds[3].toDouble(),
            );
    } catch (_) {
      return null;
    } finally {
      image?.dispose();
      picture?.dispose();
    }
  }
}

List<int>? _alphaBounds((Uint8List, int, int) input) {
  final (bytes, width, height) = input;
  var left = width, top = height, right = -1, bottom = -1;
  for (var y = 0; y < height; y++) {
    for (var x = 0; x < width; x++) {
      if (bytes[(y * width + x) * 4 + 3] == 0) continue;
      if (x < left) left = x;
      if (x > right) right = x;
      if (y < top) top = y;
      if (y > bottom) bottom = y;
    }
  }
  if (left <= 0 ||
      top <= 0 ||
      right < left ||
      bottom < top ||
      right >= width - 1 ||
      bottom >= height - 1) {
    return null;
  }
  return [left, top, right + 1, bottom + 1];
}

class _EmojiInkPainter extends CustomPainter {
  const _EmojiInkPainter(this.picture, this.ink);
  final ui.Picture picture;
  final Rect ink;

  @override
  void paint(Canvas canvas, Size size) {
    final scale = size.shortestSide / ink.shortestSide;
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.scale(scale);
    canvas.translate(-ink.center.dx, -ink.center.dy);
    canvas.drawPicture(picture);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_EmojiInkPainter oldDelegate) =>
      oldDelegate.picture != picture || oldDelegate.ink != ink;
}
