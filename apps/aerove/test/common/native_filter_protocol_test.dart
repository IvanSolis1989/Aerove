import 'package:fl_clash/core/controller.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/method.dart';
import 'package:flutter_test/flutter_test.dart';

class _Transport extends CoreHandlerInterface {
  CoreMethod? method;
  Object? arguments;
  Duration? timeout;
  Object? result = <dynamic>[1, 0];
  @override
  Future<T?> invokeMethod<T>({
    required CoreMethod method,
    Object? arguments,
    Duration? timeout,
  }) async {
    this.method = method;
    this.arguments = arguments;
    this.timeout = timeout;
    return result as T?;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw StateError('Unexpected core action');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'common controller uses actual method transport and preserves ordered indices',
    () async {
      final transport = _Transport();
      final core = CoreController.scoped(transport);
      expect(
        await core.matchFilters(
          'builder:1',
          ['b', 'a'],
          ['a', 'b', 'b'],
          timeoutMillis: 20,
        ),
        [1, 0],
      );
      expect(transport.method, CoreMethod.matchFilters);
      expect(transport.arguments, {
        'request-id': 'builder:1',
        'patterns': ['b', 'a'],
        'names': ['a', 'b', 'b'],
        'timeout-ms': 20,
      });
      expect(transport.timeout, const Duration(milliseconds: 3020));
      transport.result = true;
      expect(await core.cancelFilterMatch('builder:1'), isTrue);
      expect(transport.method, CoreMethod.cancelFilterMatch);
      expect(transport.arguments, {'request-id': 'builder:1'});
    },
  );
  test(
    'invalid results, byte limits, identities and malformed UTF8 remain failures',
    () async {
      final transport = _Transport();
      for (final result in [
        <dynamic>[3],
        <dynamic>[1, 1],
        <dynamic>['1'],
        null,
      ]) {
        transport.result = result;
        await expectLater(
          transport.matchFilters('test', ['a'], ['a', 'b']),
          throwsA(isA<CoreMethodException>()),
        );
      }
      transport.method = null;
      await expectLater(
        transport.matchFilters('../escape', ['a'], ['a']),
        throwsFormatException,
      );
      await expectLater(
        transport.matchFilters('big', ['a'], ['x' * (4 * 1024 * 1024)]),
        throwsFormatException,
      );
      await expectLater(
        transport.matchFilters('utf8', ['a'], ['\uD800']),
        throwsFormatException,
      );
      expect(transport.method, isNull);
    },
  );
}
