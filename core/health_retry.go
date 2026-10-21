package core

import (
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant"
	cp "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/tunnel"
)

const (
	// Longer than one probe's timeout, so the pause between the batches of a
	// health check never splits its round in two.
	healthRoundSettle = 15 * time.Second
)

var healthRetryBackoff = []time.Duration{30 * time.Second, 60 * time.Second, 120 * time.Second}

type retryTimer interface {
	Stop() bool
	Reset(d time.Duration) bool
}

type healthRound struct {
	started   time.Time
	failed    map[string]struct{}
	succeeded bool
	settle    retryTimer
}

type healthRetry struct {
	provider    cp.ProxyProvider
	names       map[string]struct{}
	scheduled   int
	lastSettled time.Time
	timer       retryTimer
	seq         uint64
}

func (retry *healthRetry) stop() {
	retry.seq++
	if retry.timer != nil {
		retry.timer.Stop()
		retry.timer = nil
	}
}

// healthRetrier re-runs the health check of a provider whose whole round
// failed, as a round run before the network came back does.
type healthRetrier struct {
	mu         sync.Mutex
	generation uint64
	rounds     map[string]*healthRound
	retries    map[string]*healthRetry

	now        func() time.Time
	afterFunc  func(time.Duration, func()) retryTimer
	candidates func() map[string]cp.ProxyProvider
}

var healthRetries = newHealthRetrier()

func newHealthRetrier() *healthRetrier {
	return &healthRetrier{
		rounds:  map[string]*healthRound{},
		retries: map[string]*healthRetry{},
		now:     time.Now,
		afterFunc: func(d time.Duration, run func()) retryTimer {
			return time.AfterFunc(d, func() { safeGo("health retry", run) })
		},
		candidates: autoCheckedProviders,
	}
}

func (r *healthRetrier) record(url, name string, ok bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	round := r.rounds[url]
	if round == nil {
		round = &healthRound{started: r.now(), failed: map[string]struct{}{}}
		r.rounds[url] = round
		generation := r.generation
		round.settle = r.afterFunc(healthRoundSettle, func() { r.settle(url, round, generation) })
	} else {
		round.settle.Reset(healthRoundSettle)
	}
	if !ok {
		round.failed[name] = struct{}{}
		return
	}
	round.succeeded = true
	for providerName, retry := range r.retries {
		if _, member := retry.names[name]; member {
			retry.stop()
			delete(r.retries, providerName)
		}
	}
}

func (r *healthRetrier) settle(url string, round *healthRound, generation uint64) {
	r.mu.Lock()
	if r.generation != generation || r.rounds[url] != round {
		r.mu.Unlock()
		return
	}
	delete(r.rounds, url)
	r.mu.Unlock()
	if round.succeeded || len(round.failed) == 0 || !isRunning.Load() {
		return
	}

	var lost []cp.ProxyProvider
	for _, pd := range r.candidates() {
		if failedEveryProxy(round.failed, pd.Proxies()) {
			lost = append(lost, pd)
		}
	}
	if len(lost) == 0 {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.generation != generation {
		return
	}
	for _, pd := range lost {
		r.roundFailedLocked(pd, round.started, generation)
	}
}

// mihomo's own failed rounds count too, so a group whose interval is shorter
// than the backoff is never probed more often than it probes itself.
func (r *healthRetrier) roundFailedLocked(pd cp.ProxyProvider, started time.Time, generation uint64) {
	name := pd.Name()
	now := r.now()
	retry := r.retries[name]
	if retry != nil && retry.provider != pd {
		retry.stop()
		retry = nil
	}
	if retry == nil {
		retry = &healthRetry{provider: pd}
		r.retries[name] = retry
	} else if started.Before(retry.lastSettled) {
		retry.lastSettled = now
		if retry.timer != nil {
			r.armLocked(name, retry, healthRetryBackoff[retry.scheduled-1], generation)
		}
		return
	}
	retry.lastSettled = now
	retry.names = proxyNameSet(pd.Proxies())
	retry.stop()
	if retry.scheduled == len(healthRetryBackoff) {
		return
	}
	retry.scheduled++
	r.armLocked(name, retry, healthRetryBackoff[retry.scheduled-1], generation)
}

func (r *healthRetrier) armLocked(name string, retry *healthRetry, d time.Duration, generation uint64) {
	retry.stop()
	seq := retry.seq
	retry.timer = r.afterFunc(d, func() { r.fire(name, retry, seq, generation) })
}

// mihomo joins a HealthCheck to the one in flight, which would probe nothing.
func (r *healthRetrier) fire(name string, retry *healthRetry, seq, generation uint64) {
	r.mu.Lock()
	current := r.generation == generation && r.retries[name] == retry && retry.seq == seq
	running := isRunning.Load()
	if current && running && r.reportingLocked(retry.names) {
		r.armLocked(name, retry, healthRoundSettle, generation)
		current = false
	}
	if current {
		retry.timer = nil
	}
	r.mu.Unlock()
	if !current || !running || r.candidates()[name] != retry.provider {
		return
	}
	retry.provider.HealthCheck()
}

func (r *healthRetrier) reportingLocked(names map[string]struct{}) bool {
	for _, round := range r.rounds {
		for name := range round.failed {
			if _, member := names[name]; member {
				return true
			}
		}
	}
	return false
}

func (r *healthRetrier) reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.generation++
	for _, round := range r.rounds {
		round.settle.Stop()
	}
	for _, retry := range r.retries {
		retry.stop()
	}
	clear(r.rounds)
	clear(r.retries)
}

func failedEveryProxy(failed map[string]struct{}, proxies []constant.Proxy) bool {
	if len(proxies) == 0 {
		return false
	}
	for _, proxy := range proxies {
		if _, ok := failed[proxy.Name()]; !ok {
			return false
		}
	}
	return true
}

func proxyNameSet(proxies []constant.Proxy) map[string]struct{} {
	names := make(map[string]struct{}, len(proxies))
	for _, proxy := range proxies {
		names[proxy.Name()] = struct{}{}
	}
	return names
}

// A selector has no automatic check, so its providers are only probed when the
// user asks.
func autoCheckedProviders() map[string]cp.ProxyProvider {
	providers := map[string]cp.ProxyProvider{}
	for _, proxy := range tunnel.AllProxies() {
		switch proxy.Type() {
		case constant.URLTest, constant.Fallback, constant.LoadBalance:
		default:
			continue
		}
		group, ok := adapterAs[outboundgroup.ProxyGroup](proxy)
		if !ok {
			continue
		}
		for _, pd := range group.Providers() {
			providers[pd.Name()] = pd
		}
	}
	return providers
}
