class DownloadRedirectException implements Exception {
  const DownloadRedirectException(this.reason);

  final String reason;

  @override
  String toString() => 'Download redirect rejected: $reason';
}

class DownloadRedirectPolicy {
  DownloadRedirectPolicy(Uri initial, {this.maxRedirects = 5})
    : _current = initial.removeFragment(),
      _visited = {initial.removeFragment()};

  final int maxRedirects;
  Uri _current;
  final Set<Uri> _visited;
  var _count = 0;

  ({Uri uri, Map<String, dynamic> headers}) follow(
    String location,
    Map<String, dynamic> headers,
  ) {
    if (_count >= maxRedirects)
      throw const DownloadRedirectException('too many redirects');
    final Uri next;
    try {
      if (location.isEmpty) throw const FormatException();
      next = _current.resolve(location).removeFragment();
      if (!['http', 'https'].contains(next.scheme) ||
          next.host.isEmpty ||
          next.userInfo.isNotEmpty ||
          next.port <= 0 ||
          next.port > 65535)
        throw const FormatException();
    } on FormatException {
      throw const DownloadRedirectException('invalid destination');
    }
    if (_current.scheme == 'https' && next.scheme != 'https')
      throw const DownloadRedirectException('HTTPS downgrade');
    if (_visited.contains(next))
      throw const DownloadRedirectException('redirect loop');
    final sameOrigin =
        _current.scheme == next.scheme &&
        _current.host == next.host &&
        _current.port == next.port;
    final forwarded = Map<String, dynamic>.from(headers)
      ..removeWhere((key, _) {
        final name = key.toLowerCase();
        return name == 'host' ||
            (!sameOrigin &&
                const {
                  'authorization',
                  'www-authenticate',
                  'proxy-authorization',
                  'proxy-authenticate',
                  'cookie',
                }.contains(name));
      });
    _visited.add(next);
    _count++;
    _current = next;
    return (uri: next, headers: forwarded);
  }
}
