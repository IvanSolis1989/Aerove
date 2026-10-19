import 'dart:async';

import 'package:emoji_regex/emoji_regex.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/ui/ui.dart';
import 'package:flutter/foundation.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:flutter_svg/svg.dart';

const _maxCachedIconBytes = 16 << 20;

// The precache and the widget must resolve equal providers to share one
// ImageCache entry; 192 physical pixels cover a 48 dp icon box at 4x.
const _iconDecodeExtent = 192;
const _precacheIconLimit = 32;

ImageProvider _iconImage(Uint8List bytes) {
  return ResizeImage(
    MemoryImage(bytes),
    width: _iconDecodeExtent,
    height: _iconDecodeExtent,
    policy: ResizeImagePolicy.fit,
  );
}

class _IconBytesCache {
  _IconBytesCache(this.maxBytes);

  final int maxBytes;
  final _entries = <String, Uint8List?>{};
  var _bytes = 0;

  bool contains(String key) => _entries.containsKey(key);

  Uint8List? operator [](String key) {
    if (!_entries.containsKey(key)) {
      return null;
    }
    return _entries[key] = _entries.remove(key);
  }

  void operator []=(String key, Uint8List? value) {
    remove(key);
    final size = value?.lengthInBytes ?? 0;
    if (size > maxBytes) {
      return;
    }
    _entries[key] = value;
    _bytes += size;
    while (_bytes > maxBytes) {
      remove(_entries.keys.first);
    }
  }

  void remove(String key) {
    _bytes -= _entries.remove(key)?.lengthInBytes ?? 0;
  }
}

final _decodedIcons = _IconBytesCache(_maxCachedIconBytes);
final _remoteIcons = _IconBytesCache(_maxCachedIconBytes);

Uint8List? _decodeIcon(String src) {
  if (!src.contains('base64,')) {
    return null;
  }
  if (_decodedIcons.contains(src)) {
    return _decodedIcons[src];
  }
  return _decodedIcons[src] = src.getBase64;
}

class CommonTargetIcon extends StatelessWidget {
  final String src;

  /// Shown while [src] is empty, loading or unreadable.
  final Widget? fallback;

  const CommonTargetIcon({super.key, required this.src, this.fallback});

  Widget _defaultIcon() {
    return fallback ?? const _ImagePlaceholder();
  }

  Widget _buildIcon() {
    if (src.isEmpty) {
      return _defaultIcon();
    }

    final base64 = _decodeIcon(src);
    if (base64 != null) {
      return _BytesImage(
        bytes: base64,
        isSvg: src.isSvg,
        fallback: _defaultIcon(),
      );
    }

    return ImageCacheWidget(src: src, defaultWidget: _defaultIcon());
  }

  @override
  Widget build(BuildContext context) {
    return _buildIcon();
  }
}

class _ImagePlaceholder extends StatelessWidget {
  const _ImagePlaceholder();

  @override
  Widget build(BuildContext context) {
    final size = IconTheme.of(context).size ?? 24;
    final colorScheme = context.colorScheme;
    return SizedBox.square(
      dimension: size,
      child: Center(
        child: GlyphIcon(
          AppGlyphs.photos,
          size: size * 0.7,
          color: Color.alphaBlend(
            colorScheme.onSurfaceVariant.opacity50,
            colorScheme.surface,
          ),
        ),
      ),
    );
  }
}

final _groupEmoji = emojiRegex();
final _groupTitleWhitespace = RegExp(r'\s{2,}');

String groupTitleWithoutEmoji(String name) {
  final text = name.characters
      .where((character) => !_groupEmoji.hasMatch(character))
      .join();
  if (text == name) {
    return name;
  }
  return text.trim().replaceAll(_groupTitleWhitespace, ' ');
}

class GroupMonogram extends StatelessWidget {
  final String name;

  const GroupMonogram(this.name, {super.key});

  static final _letter = RegExp(r'[\p{L}\p{N}]', unicode: true);

  String get _mark {
    for (final character in name.characters) {
      if (_groupEmoji.hasMatch(character)) {
        return character;
      }
    }
    final letter = _letter.firstMatch(name)?.group(0);
    if (letter != null) {
      return letter.toUpperCase();
    }
    return name.characters.firstOrNull ?? '';
  }

  @override
  Widget build(BuildContext context) {
    final iconTheme = IconTheme.of(context);
    final size = iconTheme.size ?? 24;
    final mark = _mark;
    if (mark.isEmpty) {
      return const _ImagePlaceholder();
    }
    return SizedBox.square(
      dimension: size,
      child: Center(
        child: EmojiText(
          mark,
          maxLines: 1,
          style: AppTextStyle.custom(
            context,
            size: size * 0.62,
            leading: size * 0.62,
            weight: FontWeight.w600,
          ).copyWith(color: iconTheme.color == null ? null : iconTheme.color!),
        ),
      ),
    );
  }
}

class _BytesImage extends StatelessWidget {
  const _BytesImage({
    required this.bytes,
    required this.isSvg,
    required this.fallback,
    this.onError,
  });

  final Uint8List bytes;
  final bool isSvg;
  final Widget fallback;
  final VoidCallback? onError;

  Widget _buildFallback() {
    onError?.call();
    return fallback;
  }

  @override
  Widget build(BuildContext context) {
    return isSvg
        ? SvgPicture.memory(bytes, errorBuilder: (_, _, _) => _buildFallback())
        : Image(
            image: _iconImage(bytes),
            gaplessPlayback: true,
            errorBuilder: (_, _, _) => _buildFallback(),
          );
  }
}

final _cacheManager = CacheManager(
  Config(
    DefaultCacheManager.key,
    fileService: HttpFileService()..concurrentFetches = maxConcurrentIconLoads,
  ),
);

Stream<Uint8List> _readCachedIcon(String src) {
  late final StreamController<Uint8List> controller;
  late final Future<void> reading;
  var cancelled = false;
  Future<void> read() async {
    try {
      FileInfo? cached;
      try {
        cached = await _cacheManager.getFileFromCache(src);
      } catch (error) {
        commonPrint.log('Failed to read cached icon $src: $error');
      }
      if (cancelled) return;
      if (cached != null) {
        final bytes = await cached.file.readAsBytes();
        if (!cancelled) controller.add(bytes);
      }
      if (cancelled) return;
      if (cached == null || cached.validTill.isBefore(DateTime.now())) {
        final downloaded = await _cacheManager.downloadFile(src);
        if (cancelled) return;
        final bytes = await downloaded.file.readAsBytes();
        if (!cancelled) controller.add(bytes);
      }
    } catch (error, stack) {
      if (!cancelled) controller.addError(error, stack);
    } finally {
      unawaited(controller.close());
    }
  }

  controller = StreamController(
    onListen: () => reading = read(),
    onCancel: () {
      cancelled = true;
      // File reads and cache-manager downloads retain their slot until settled.
      return reading;
    },
  );
  return controller.stream;
}

var _readRemoteIcon = _readCachedIcon;
final _iconReads = <String, _IconRead>{};
final _iconPrecachePool = TaskPool(maxConcurrentIconLoads);
final _iconPrecaches = <String, Future<void>>{};

@visibleForTesting
set readRemoteIcon(Stream<Uint8List> Function(String src)? value) {
  _readRemoteIcon = value ?? _readCachedIcon;
}

@visibleForTesting
({int active, int pending, int sources}) get debugIconReads => (
  active: _IconRead.active,
  pending: _IconRead.pending.length,
  sources: _iconReads.length,
);

class _IconRead {
  _IconRead(this.src) {
    controller = StreamController.broadcast(
      sync: true,
      onListen: () {
        if (_closed || _active || pending.contains(this)) return;
        pending.add(this);
        _drain();
      },
      onCancel: () => _finish(cancel: true),
    );
  }

  static final pending = <_IconRead>{};
  static int active = 0;
  static bool _draining = false;

  final String src;
  late final StreamController<Uint8List> controller;
  StreamSubscription<Uint8List>? _subscription;
  bool _active = false;
  bool _closed = false;

  static void _drain() {
    if (_draining) return;
    _draining = true;
    try {
      while (active < maxConcurrentIconLoads && pending.isNotEmpty) {
        final read = pending.first;
        pending.remove(read);
        read._start();
      }
    } finally {
      _draining = false;
    }
  }

  void _start() {
    if (_closed) return;
    _active = true;
    active++;
    try {
      _subscription = _readRemoteIcon(src).listen(
        (bytes) {
          if (_closed) return;
          final current = _remoteIcons[src];
          final next = current != null && listEquals(current, bytes)
              ? current
              : bytes;
          _remoteIcons[src] = next;
          controller.add(next);
        },
        onError: (Object error, StackTrace stack) {
          if (!_closed) controller.addError(error, stack);
        },
        onDone: _finish,
      );
    } catch (error, stack) {
      controller.addError(error, stack);
      _finish();
    }
  }

  void _finish({bool cancel = false}) {
    if (_closed) return;
    _closed = true;
    pending.remove(this);
    if (identical(_iconReads[src], this)) _iconReads.remove(src);
    final subscription = _subscription;
    _subscription = null;
    if (cancel && subscription != null) {
      unawaited(_cancel(subscription));
    } else {
      _release();
    }
    unawaited(controller.close());
  }

  Future<void> _cancel(StreamSubscription<Uint8List> subscription) async {
    try {
      await subscription.cancel();
    } catch (error) {
      commonPrint.log('Failed to cancel icon read $src: $error');
    } finally {
      _release();
    }
  }

  void _release() {
    if (!_active) return;
    _active = false;
    active--;
    _drain();
  }
}

Stream<Uint8List> _loadRemoteIcon(String src) {
  return Stream.multi((controller) {
    final cached = _remoteIcons[src];
    final pending = _iconReads[src];
    if (cached != null) controller.addSync(cached);
    if (cached != null && pending == null) {
      controller.closeSync();
      return;
    }
    final read = pending ?? (_iconReads[src] = _IconRead(src));
    final subscription = read.controller.stream.listen(
      controller.addSync,
      onError: controller.addErrorSync,
      onDone: controller.closeSync,
    );
    controller.onCancel = subscription.cancel;
  });
}

Future<void> _decodeAhead(Uint8List bytes) {
  final completer = Completer<void>();
  final stream = _iconImage(bytes).resolve(ImageConfiguration.empty);
  late final ImageStreamListener listener;
  void finish() {
    stream.removeListener(listener);
    completer.complete();
  }

  listener = ImageStreamListener(
    (_, _) => finish(),
    onError: (_, _) => finish(),
  );
  stream.addListener(listener);
  return completer.future;
}

Future<void> precacheTargetIcons(Iterable<String> srcs) async {
  final pending = srcs
      .toSet()
      .where((src) => src.isNotEmpty && !src.contains('base64,'))
      .take(_precacheIconLimit)
      .where((src) => !_remoteIcons.contains(src))
      .map((src) {
        return _iconPrecaches[src] ??= _iconPrecachePool.run(() async {
          try {
            if (_remoteIcons.contains(src)) return;
            await for (final bytes in _loadRemoteIcon(src)) {
              if (!src.isSvg) {
                await _decodeAhead(bytes);
              }
            }
          } catch (error) {
            commonPrint.log('Failed to precache icon $src: $error');
          } finally {
            _iconPrecaches.remove(src);
          }
        });
      });
  await Future.wait(pending);
}

class ImageCacheWidget extends StatefulWidget {
  final String src;
  final Widget defaultWidget;

  const ImageCacheWidget({
    super.key,
    required this.src,
    required this.defaultWidget,
  });

  @override
  State<ImageCacheWidget> createState() => _ImageCacheWidgetState();
}

class _ImageCacheWidgetState extends State<ImageCacheWidget> {
  late final ValueNotifier<Uint8List?> _bytesNotifier;
  StreamSubscription? _streamSubscription;
  var _loadEnded = false;

  @override
  void initState() {
    super.initState();
    _bytesNotifier = ValueNotifier(_remoteIcons[widget.src]);
    _getImageFormCache();
  }

  @override
  void didUpdateWidget(covariant ImageCacheWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.src != widget.src) {
      _bytesNotifier.value = _remoteIcons[widget.src];
      _loadEnded = false;
      _getImageFormCache();
    }
  }

  void _getImageFormCache() {
    final src = widget.src;
    _streamSubscription?.cancel();
    _streamSubscription = null;
    if (src.isEmpty) {
      return;
    }
    _streamSubscription = _loadRemoteIcon(src).listen(
      (bytes) {
        if (mounted) {
          _bytesNotifier.value = bytes;
        }
      },
      onError: (Object error) {
        commonPrint.log('Failed to read icon $src: $error');
      },
      onDone: () {
        if (mounted && _bytesNotifier.value == null) {
          setState(() => _loadEnded = true);
        }
      },
    );
  }

  @override
  void dispose() {
    _streamSubscription?.cancel();
    _bytesNotifier.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Uint8List?>(
      valueListenable: _bytesNotifier,
      builder: (context, bytes, _) {
        if (bytes == null) {
          return _loadEnded || widget.src.isEmpty
              ? widget.defaultWidget
              : SizedBox.square(dimension: IconTheme.of(context).size ?? 24);
        }
        return _BytesImage(
          bytes: bytes,
          isSvg: widget.src.isSvg,
          fallback: widget.defaultWidget,
          onError: () => _remoteIcons.remove(widget.src),
        );
      },
    );
  }
}

class PackageIcon extends StatefulWidget {
  final String packageName;
  final double size;

  const PackageIcon({super.key, required this.packageName, required this.size});

  @override
  State<PackageIcon> createState() => _PackageIconState();
}

class _PackageIconState extends State<PackageIcon> {
  ImageProvider? _icon;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _showCachedIcon();
    _loadIcon();
  }

  @override
  void didUpdateWidget(covariant PackageIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.packageName != widget.packageName) {
      _showCachedIcon();
      _loadIcon();
    }
  }

  void _showCachedIcon() {
    _generation++;
    _icon = app?.getCachedPackageIcon(widget.packageName);
  }

  void _loadIcon() {
    final generation = _generation;
    app?.getPackageIcon(widget.packageName).then((icon) {
      if (!mounted ||
          generation != _generation ||
          icon == null ||
          icon == _icon) {
        return;
      }
      setState(() {
        _icon = icon;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final icon = _icon;
    if (icon == null) {
      return SizedBox(width: widget.size, height: widget.size);
    }
    return Image(
      image: icon,
      gaplessPlayback: true,
      width: widget.size,
      height: widget.size,
    );
  }
}
