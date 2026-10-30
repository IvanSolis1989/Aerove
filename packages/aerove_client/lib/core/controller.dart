import 'package:fl_clash/product.dart';

import 'dart:async';
import 'dart:io';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/core.dart';
import 'package:fl_clash/core/interface.dart';
import 'package:fl_clash/core/spill.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:flutter/services.dart';
import 'package:path/path.dart';

class CoreController {
  static CoreController? _instance;
  late CoreHandlerInterface _interface;

  CoreController._internal() {
    _interface = ClientProduct.createBackend();
    if (ClientProduct.current.expectedCoreId != 'mihomo' &&
        (_interface is CoreLib || _interface is CoreService)) {
      throw StateError(
        'Mihomo backend cannot serve ${ClientProduct.current.id}',
      );
    }
  }

  @visibleForTesting
  CoreController.test(this._interface) {
    _instance = this;
  }

  @visibleForTesting
  CoreController.scoped(this._interface);

  @visibleForTesting
  static void resetInstance() {
    _instance = null;
  }

  factory CoreController() {
    _instance ??= CoreController._internal();
    return _instance!;
  }

  Future<CoreLifecycleResult> start() {
    _invalidateConnections();
    return _interface.start().whenComplete(_invalidateConnections);
  }

  Future<CoreLifecycleResult> restart() {
    _invalidateConnections();
    return _interface.restart().whenComplete(_invalidateConnections);
  }

  Future<CoreLifecycleResult> stop() {
    _invalidateConnections();
    return _interface.stop().whenComplete(_invalidateConnections);
  }

  Future<CoreLifecycleResult> close() {
    _invalidateConnections();
    return _interface.close().whenComplete(_invalidateConnections);
  }

  static Future<void> ensureHomeDir() async {
    final homePath = await appPath.homeDirPath;
    final homeDir = Directory(homePath);
    final isExists = await homeDir.exists();
    if (!isExists) {
      await homeDir.create(recursive: true);
    }
    await system.grantHomeDirAccess(homePath);
  }

  static Future<void> initGeo() async {
    final homePath = await appPath.homeDirPath;
    final geoAssets = ClientProduct.current.resources.geoAssets;
    try {
      for (final entry in geoAssets.entries) {
        final geoFileName = entry.key;
        final geoFile = File(join(homePath, geoFileName));
        final isExists = await geoFile.exists();
        if (isExists) {
          continue;
        }
        final data = await rootBundle.load(entry.value);
        final List<int> bytes = data.buffer.asUint8List();
        await geoFile.writeAsBytes(bytes, flush: true);
      }
    } catch (e) {
      commonPrint.log(
        'Failed to initialize geo data: $e',
        logLevel: LogLevel.error,
      );
      rethrow;
    }
  }

  Future<bool> init(int version) async {
    await ensureHomeDir();
    await initGeo();
    final homeDirPath = await appPath.homeDirPath;
    return _interface.init(InitParams(homeDir: homeDirPath, version: version));
  }

  FutureOr<bool> get isInit => _interface.isInit;

  Future<String> validateConfig(String path) async {
    final res = await _interface.validateConfig(path);
    return res;
  }

  /// An age-encrypted config is rewritten in place into its plaintext, and a
  /// share-link list into a config of its proxies.
  Future<String> validateProfile(
    String path, [
    String ageSecretKey = '',
  ]) async {
    final file = File(path);
    if (await file.isAgeEncrypted) {
      if (ageSecretKey.isEmpty) {
        throw const AgeSecretKeyRequiredException();
      }
      await file.safeWriteAsString(
        await _interface.decryptConfig(path, ageSecretKey),
      );
    }
    final message = await validateConfig(path);
    if (message.isEmpty) {
      return message;
    }
    final List<Map<String, dynamic>> proxies;
    try {
      proxies = await _interface.convertProxies(path);
    } on Exception {
      return message;
    }
    if (proxies.isEmpty) {
      return message;
    }
    await file.safeWriteAsString(await encodeYamlTask({'proxies': proxies}));
    return validateConfig(path);
  }

  Future<List<String>> validateProxies(List<Map<String, dynamic>> proxies) {
    if (proxies.isEmpty) {
      return Future.value(const []);
    }
    return _interface.validateProxies(proxies);
  }

  Future<List<String>> validateFilters(List<String> filters) {
    if (filters.isEmpty) {
      return Future.value(const []);
    }
    return _interface.validateFilters(filters);
  }

  Future<List<String>> encodeShareLinks(List<Map<String, dynamic>> proxies) {
    if (proxies.isEmpty) {
      return Future.value(const []);
    }
    return _interface.encodeShareLinks(proxies);
  }

  Future<List<List<Map<String, dynamic>>>> decodeShareLinks(
    List<String> lines,
  ) {
    if (lines.isEmpty) {
      return Future.value(const []);
    }
    return _interface.decodeShareLinks(lines);
  }

  Future<String> validateConfigWithData(List<int> data) async {
    final path = await appPath.tempFilePath;
    final file = File(path);
    await file.safeWriteAsBytes(data);
    final res = await _interface.validateConfig(path);
    await File(path).safeDelete();
    return res;
  }

  Future<String> updateConfig(UpdateParams updateParams) async {
    return _interface.updateConfig(updateParams);
  }

  Future<String> setupConfig({
    required SetupParams params,
    Future<void> Function()? preloadInvoke,
  }) async {
    if (preloadInvoke == null) {
      return _interface.setupConfig(params);
    }
    final (result, _) = await (
      _interface.setupConfig(params),
      preloadInvoke(),
    ).wait;
    return result;
  }

  Future<AppliedSetup?> getAppliedSetup() => _interface.getAppliedSetup();

  Future<bool> refreshAppliedSetup(SetupParams params) =>
      _interface.refreshAppliedSetup(params);

  Future<List<Group>> getProxiesGroups({
    required ProxiesSortType sortType,
    required DelayMap delayMap,
    required Map<String, String> selectedMap,
    required String defaultTestUrl,
  }) async {
    final proxiesData = await _interface.getProxies();
    return toGroupsTask(
      ComputeGroupsState(
        proxiesData: proxiesData,
        sortType: sortType,
        delayMap: delayMap,
        selectedMap: selectedMap,
        defaultTestUrl: defaultTestUrl,
      ),
    );
  }

  Future<ChangeProxyResult> changeProxy(ChangeProxyParams changeProxyParams) {
    return _interface.changeProxy(changeProxyParams);
  }

  Future<RouteSnapshot?> watchRoute(bool watch) {
    return _interface.watchRoute(watch);
  }

  Future<RouteCheck?> checkRoutes(CheckRoutesParams params) {
    return _interface.checkRoutes(params);
  }

  Future<void> setUiTelemetryActive(bool active) {
    return _interface.setUiTelemetryActive(active);
  }

  List<TrackerInfo>? _connections;
  final _connectionsClock = Stopwatch()..start();
  Duration? _connectionsRead;
  Future<List<TrackerInfo>>? _connectionsPending;
  int _connectionsRevision = 0;

  void _invalidateConnections() {
    _interface.clearConnectionCache();
    _connectionsRevision++;
    _connections = null;
    _connectionsRead = null;
    _connectionsPending = null;
  }

  Future<List<TrackerInfo>> getConnections() {
    final cached = _connections;
    final read = _connectionsRead;
    if (cached != null &&
        read != null &&
        _connectionsClock.elapsed - read < const Duration(seconds: 1)) {
      return Future.value(cached);
    }
    return _connectionsPending ??= _readConnections(_connectionsRevision);
  }

  Future<List<TrackerInfo>> _readConnections(int revision) async {
    try {
      final items = await _interface.getConnections();
      if (revision == _connectionsRevision) {
        _connections = List.unmodifiable(items);
        _connectionsRead = _connectionsClock.elapsed;
      }
      return items;
    } finally {
      if (revision == _connectionsRevision) _connectionsPending = null;
    }
  }

  Future<int> getConnectionCount() async {
    return _interface.getConnectionCount();
  }

  Future<void> closeConnection(String id) async {
    _invalidateConnections();
    try {
      await _interface.closeConnection(id);
    } finally {
      _invalidateConnections();
    }
  }

  Future<void> closeConnections() async {
    _invalidateConnections();
    try {
      await _interface.closeConnections();
    } finally {
      _invalidateConnections();
    }
  }

  Future<void> resetConnections() async {
    _invalidateConnections();
    try {
      await _interface.resetConnections();
    } finally {
      _invalidateConnections();
    }
  }

  Future<bool> clearDnsCache() async {
    return _interface.clearDnsCache();
  }

  Future<List<ExternalProvider>> getExternalProviders() async {
    return _interface.getExternalProviders();
  }

  Future<ExternalProvider?> getExternalProvider(
    String externalProviderName,
  ) async {
    return _interface.getExternalProvider(externalProviderName);
  }

  Future<String> updateGeoData(String type) {
    return _interface.updateGeoData(type);
  }

  Future<String> sideLoadExternalProvider({
    required String providerName,
    required String data,
  }) {
    return _interface.sideLoadExternalProvider(
      providerName: providerName,
      data: data,
    );
  }

  Future<String> dumpRuleSet(String path) {
    return _interface.dumpRuleSet(path);
  }

  Future<RuleSetInfo> compileRuleSet(String name, {String url = ''}) {
    return _interface.compileRuleSet(name, url: url);
  }

  Future<String> updateExternalProvider({required String providerName}) async {
    return _interface.updateExternalProvider(providerName);
  }

  Future<bool> startListener() async {
    return _interface.startListener();
  }

  Future<bool> stopListener() async {
    return _interface.stopListener();
  }

  Future<bool> parkListener() async {
    _invalidateConnections();
    try {
      return await _interface.parkListener();
    } finally {
      _invalidateConnections();
    }
  }

  Future<bool> adoptListener() => _interface.adoptListener();

  Future<Delay?> getDelay(
    String url,
    String proxyName,
    Duration timeout,
  ) async {
    return _interface.asyncTestDelay(url, proxyName, timeout);
  }

  Future<List<Delay>> getLatestDelays() => _interface.getLatestDelays();

  Future<ProbeResult?> probe(ProbeParams params) => _interface.probe(params);

  Future<OutboundIpResult?> outboundIp(OutboundIpParams params) =>
      _interface.outboundIp(params);

  Future<List<ServiceCheckItem>> serviceCheck(ServiceCheckParams params) =>
      _interface.serviceCheck(params);

  /// The profile as the JSON text a script reads.
  Future<String> getConfigJson(int id) async {
    final path = await appPath.getProfilePath(id.toString());
    return readSpilledText(await _interface.getConfig(path));
  }

  Future<Map<String, dynamic>> getProfileOutbounds(int id) async {
    return _interface.getProfileOutbounds(
      await appPath.getProfilePath(id.toString()),
    );
  }

  Future<Map<String, dynamic>?> getProxyDefinition(String name) {
    return _interface.getProxyDefinition(name);
  }

  Future<String> buildProfile(SetupBase base) async {
    return readSpilledText(await _interface.buildProfile(base));
  }

  Future<({ProfileBase base, String path})> getProfileBase(int id) async {
    final path = await appPath.getProfilePath(id.toString());
    final base = await _interface.getProfileBase(path);
    final config = Map<String, dynamic>.from(base.config)..remove('rule');
    return (base: base.copyWith(config: config), path: path);
  }

  Future<Traffic> getTraffic(bool onlyStatisticsProxy) async {
    return _interface.getTraffic(onlyStatisticsProxy);
  }

  Future<Traffic> getTotalTraffic(bool onlyStatisticsProxy) async {
    return _interface.getTotalTraffic(onlyStatisticsProxy);
  }

  Future<CoreMemoryStats?> getMemoryStats() async {
    return _interface.getMemoryStats();
  }

  void resetTraffic() {
    _interface.resetTraffic();
  }

  void startLog() {
    _interface.startLog();
  }

  void stopLog() {
    _interface.stopLog();
  }

  Future<void> requestGc() async {
    await _interface.forceGc();
  }

  Future<void> crash() async {
    await _interface.crash();
  }

  Future<String> clearEffect(int profileId) async {
    return _interface.clearEffect(profileId);
  }
}

final coreController = CoreController();
