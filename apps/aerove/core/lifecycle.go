package core

import (
	"runtime/debug"
	"runtime/metrics"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/adapter/inbound"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/component/updater"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/features"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/listener"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

var (
	isInit     atomic.Bool
	isRunning  atomic.Bool
	sdkVersion atomic.Int32
)

func handleInitClash(params *InitParams) bool {
	configMu.Lock()
	defer configMu.Unlock()
	sdkVersion.Store(int32(params.Version))
	constant.SetHomeDir(params.HomeDir)
	initOwnership(params.HomeDir)
	clearSpilledResponses()
	isInit.Store(true)
	return true
}

func handleGetIsInit() bool {
	return isInit.Load()
}

func handleStartListener() bool {
	configMu.Lock()
	defer configMu.Unlock()
	isRunning.Store(true)
	updateListeners(currentConfig)
	resolver.ResetConnection()
	// A probe already out left before the TUN routes and over the DNS
	// connections just dropped, so its answer must not stand for this route.
	bumpRouteEpoch()
	return true
}

func handleStopListener() bool {
	configMu.Lock()
	defer configMu.Unlock()
	isRunning.Store(false)
	healthRetries.reset()
	listener.StopListener()
	resolver.ResetConnection()
	return true
}

func updateListeners(cfg *config.Config) {
	if cfg == nil || !isRunning.Load() {
		return
	}
	general := cfg.General
	listener.PatchInboundListeners(cfg.Listeners, tunnel.Tunnel, true)

	listener.SetAllowLan(general.AllowLan)
	inbound.SetSkipAuthPrefixes(general.SkipAuthPrefixes)
	inbound.SetAllowedIPs(general.LanAllowedIPs)
	inbound.SetDisAllowedIPs(general.LanDisAllowedIPs)

	listener.SetBindAddress(general.BindAddress)
	listener.ReCreateHTTP(general.Port, tunnel.Tunnel)
	listener.ReCreateSocks(general.SocksPort, tunnel.Tunnel)
	listener.ReCreateRedir(general.RedirPort, tunnel.Tunnel)
	listener.ReCreateTProxy(general.TProxyPort, tunnel.Tunnel)
	listener.ReCreateMixed(general.MixedPort, tunnel.Tunnel)
	listener.ReCreateShadowSocks(general.ShadowSocksConfig, tunnel.Tunnel)
	listener.ReCreateVmess(general.VmessConfig, tunnel.Tunnel)
	listener.ReCreateTuic(general.TuicServer, tunnel.Tunnel)
	if !features.Android {
		listener.ReCreateTun(general.Tun, tunnel.Tunnel)
	}
}

func handleShutdown() bool {
	handleStopLog()
	stopRouteWatch()

	configMu.Lock()
	isRunning.Store(false)
	listener.StopListener()
	updater.StopGeoUpdater()
	healthRetries.reset()
	executor.Shutdown()
	currentConfig = nil
	appliedSetup = nil
	isInit.Store(false)
	configMu.Unlock()

	handleForceGC()
	return true
}

func handleForceGC() {
	tunnel.InvalidateAllProxies()
	debug.FreeOSMemory()
}

// mihomo loads providers without waiting for them, so its own GC misses their decode buffers.
var (
	loadReleaseDelay  = 2 * time.Second
	releaseLoadMemory = debug.FreeOSMemory

	loadReleaseMu    sync.Mutex
	loadReleaseTimer *time.Timer
)

func scheduleLoadMemoryRelease() {
	loadReleaseMu.Lock()
	defer loadReleaseMu.Unlock()
	if loadReleaseTimer == nil {
		loadReleaseTimer = time.AfterFunc(loadReleaseDelay, func() {
			geoSiteLists.Reset()
			releaseLoadMemory()
		})
		return
	}
	loadReleaseTimer.Reset(loadReleaseDelay)
}

type memoryClass int

const (
	heapObjectsClass memoryClass = iota
	heapUnusedClass
	heapFreeClass
	heapStacksClass
	mspanInuseClass
	mcacheInuseClass
	profilingBucketsClass
	metadataOtherClass
	otherClass
	memoryClassCount
)

var memoryClassMetrics = [memoryClassCount]string{
	heapObjectsClass:      "/memory/classes/heap/objects:bytes",
	heapUnusedClass:       "/memory/classes/heap/unused:bytes",
	heapFreeClass:         "/memory/classes/heap/free:bytes",
	heapStacksClass:       "/memory/classes/heap/stacks:bytes",
	mspanInuseClass:       "/memory/classes/metadata/mspan/inuse:bytes",
	mcacheInuseClass:      "/memory/classes/metadata/mcache/inuse:bytes",
	profilingBucketsClass: "/memory/classes/profiling/buckets:bytes",
	metadataOtherClass:    "/memory/classes/metadata/other:bytes",
	otherClass:            "/memory/classes/other:bytes",
}

// runtime.ReadMemStats stops the world and flushes every P's cache on each
// dashboard poll; these classes add up to the same MemStats fields without it.
func readMemoryClasses() [memoryClassCount]uint64 {
	var samples [memoryClassCount]metrics.Sample
	for class, name := range memoryClassMetrics {
		samples[class].Name = name
	}
	metrics.Read(samples[:])
	var bytes [memoryClassCount]uint64
	for class, sample := range samples {
		if sample.Value.Kind() == metrics.KindUint64 {
			bytes[class] = sample.Value.Uint64()
		}
	}
	return bytes
}

func handleGetMemoryStats() MemoryStats {
	bytes := readMemoryClasses()
	runtimeOther := bytes[mspanInuseClass] + bytes[mcacheInuseClass] + bytes[profilingBucketsClass] +
		bytes[metadataOtherClass] + bytes[otherClass]
	return MemoryStats{
		Rss:          statistic.DefaultManager.Memory(),
		HeapInuse:    bytes[heapObjectsClass] + bytes[heapUnusedClass],
		HeapIdle:     bytes[heapFreeClass],
		StackInuse:   bytes[heapStacksClass],
		RuntimeOther: runtimeOther,
	}
}
