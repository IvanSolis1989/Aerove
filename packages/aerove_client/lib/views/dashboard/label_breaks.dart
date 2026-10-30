import 'package:material_ui/material_ui.dart';

const _hint = 0x200B;

enum _Kind { space, hint, punctuation, han, kana, hangul, word }

_Kind _kindOf(String grapheme) {
  final rune = grapheme.runes.first;
  if (rune == _hint) return _Kind.hint;
  if (rune == 0x20 || rune == 0x09 || rune == 0x3000) return _Kind.space;
  if (rune >= 0x3001 && rune <= 0x303F || rune >= 0xFF01 && rune <= 0xFF65) {
    return _Kind.punctuation;
  }
  if (rune >= 0x3400 && rune <= 0x4DBF ||
      rune >= 0x4E00 && rune <= 0x9FFF ||
      rune >= 0xF900 && rune <= 0xFAFF ||
      rune >= 0x20000 && rune <= 0x3134F) {
    return _Kind.han;
  }
  if (rune >= 0x3040 && rune <= 0x30FF ||
      rune >= 0x31F0 && rune <= 0x31FF ||
      rune >= 0xFF66 && rune <= 0xFF9F) {
    return _Kind.kana;
  }
  if (rune >= 0xAC00 && rune <= 0xD7AF ||
      rune >= 0x1100 && rune <= 0x11FF ||
      rune >= 0x3130 && rune <= 0x318F) {
    return _Kind.hangul;
  }
  return _Kind.word;
}

bool _cjk(_Kind kind) =>
    kind == _Kind.han || kind == _Kind.kana || kind == _Kind.hangul;

/// U+200B hints override automatic Han breaks; words and kana stay whole.
List<int> labelBreaks(String text) {
  final hinted = text.runes.contains(_hint);
  final breaks = <int>[];
  _Kind? previous;
  var offset = 0;
  for (final grapheme in text.characters) {
    final kind = _kindOf(grapheme);
    if (previous != null) {
      final open = switch ((previous, kind)) {
        (_Kind.space || _Kind.hint, _) => kind != _Kind.space,
        (_, _Kind.space || _Kind.hint) => false,
        (_Kind.punctuation, _) || (_, _Kind.punctuation) => false,
        (_Kind.han, _Kind.han) => !hinted,
        (final before, final after) => _cjk(before) != _cjk(after),
      };
      if (open) breaks.add(offset);
    }
    previous = kind;
    offset += grapheme.length;
  }
  return breaks;
}

String _trimmed(String line) =>
    line.replaceAll(String.fromCharCode(_hint), '').trim();

({String text, double width})? balancedLabel(
  String text,
  double Function(String line) widthOf,
) {
  ({String text, double width})? best;
  for (final at in labelBreaks(text)) {
    final first = _trimmed(text.substring(0, at));
    final second = _trimmed(text.substring(at));
    if (first.isEmpty || second.isEmpty) continue;
    final firstWidth = widthOf(first);
    final width = firstWidth > widthOf(second) ? firstWidth : widthOf(second);
    // Even splits favour the longer first line, as a reader expects.
    if (best == null || width <= best.width) {
      best = (text: '$first\n$second', width: width);
    }
  }
  return best;
}

double longestPiece(String text, double Function(String piece) widthOf) {
  var start = 0;
  var widest = 0.0;
  for (final at in [...labelBreaks(text), text.length]) {
    final piece = _trimmed(text.substring(start, at));
    if (piece.isNotEmpty) {
      final width = widthOf(piece);
      if (width > widest) widest = width;
    }
    start = at;
  }
  return widest;
}
