class _FixedBuffer<T> {
  _FixedBuffer(int capacity) : items = List<T?>.filled(capacity, null);

  final List<T?> items;
  var head = 0;
  var length = 0;
  var mutations = 0;

  int offset(int index) => (head + index) % items.length;

  void add(T item) {
    if (length == items.length) {
      items[head] = item;
      head = (head + 1) % items.length;
    } else {
      items[offset(length++)] = item;
    }
    mutations++;
  }

  void clear() {
    for (var index = 0; index < length; index++) {
      items[offset(index)] = null;
    }
    head = 0;
    length = 0;
    mutations++;
  }
}

class FixedList<T> {
  final int maxLength;
  final _FixedBuffer<T> _buffer;
  final int _revision;
  List<T>? _snapshot;

  FixedList(this.maxLength, {List<T>? list})
    : assert(maxLength > 0, 'FixedList without a cap grows without bound'),
      _buffer = _FixedBuffer<T>(maxLength),
      _revision = 0 {
    if (list != null) {
      final start = list.length > maxLength ? list.length - maxLength : 0;
      for (var index = start; index < list.length; index++) {
        _buffer.add(list[index]);
      }
    }
  }

  FixedList._(this.maxLength, this._buffer, this._revision);

  int get revision => _revision;

  /// Lets indexed consumers notice legacy in-place add/clear as well.
  int get mutations => _buffer.mutations;

  void add(T item) {
    _buffer.add(item);
    _snapshot = null;
  }

  void clear() {
    _buffer.clear();
    _snapshot = null;
  }

  FixedList<T> append(T item) {
    add(item);
    return FixedList._(maxLength, _buffer, _revision + 1);
  }

  FixedList<T> replace(Map<int, T> items) {
    items.forEach((index, item) {
      RangeError.checkValidIndex(index, this, 'index', length);
      _buffer.items[_buffer.offset(index)] = item;
    });
    _buffer.mutations++;
    _snapshot = null;
    return FixedList._(maxLength, _buffer, _revision + 1);
  }

  List<T> get list => _snapshot ??= List.unmodifiable([
    for (var index = 0; index < length; index++) this[index],
  ]);

  /// A copy at this revision that later mutations of the shared ring never reach.
  FixedList<T> frozen() {
    final copy = _FixedBuffer<T>(maxLength);
    for (var index = 0; index < length; index++) {
      copy.add(this[index]);
    }
    return FixedList._(maxLength, copy, _revision);
  }

  int get length => _buffer.length;

  T operator [](int index) {
    RangeError.checkValidIndex(index, this, 'index', length);
    return _buffer.items[_buffer.offset(index)] as T;
  }

  FixedList<T> copyWith() => FixedList(
    maxLength,
    list: [for (var index = 0; index < length; index++) this[index]],
  );

  @override
  bool operator ==(Object other) =>
      other is FixedList<T> &&
      identical(other._buffer, _buffer) &&
      other._revision == _revision;

  @override
  int get hashCode => Object.hash(identityHashCode(_buffer), _revision);
}

/// Restores the head that [latest] trimmed off [current] so a paused view
/// stays anchored; the result is capped at [limit] from the tail.
List<T> retainTrimmedHead<T>(
  List<T> current,
  List<T> latest,
  int limit, {
  Object Function(T item)? keyOf,
}) {
  if (current.isEmpty || latest.isEmpty) {
    return latest;
  }
  final first = latest.first;
  final index = keyOf == null
      ? current.indexWhere((item) => identical(item, first))
      : current.indexWhere((item) => keyOf(item) == keyOf(first));
  if (index == 0) {
    return latest;
  }
  final head = index > 0 ? current.sublist(0, index) : current;
  return List.unmodifiable([...head, ...latest].take(limit));
}
