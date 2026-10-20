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
	healthRoundSettle  = 15 * time.Second
	healthRetryEpisode = 10 * time.Minute
)

var healthRetryBackoff = []time.Duration{30 * time.Second, 60 * time.Second, 120 * time.Second}

type retryTimer interface {
	Stop() bool
	Reset(d time.Duration) bool
}

type healthRound struct {
	failed    map[string]struct{}
	succeeded bool
	settle    retryTimer
}

type healthRetry struct {
	provider    cp.ProxyProvider
	names       map[string]struct{}
	failures    int
	lastFailure time.Time
	timer       retryTimer
}

func (retry *healthRetry) stop() {
	if retry.timer != nil {
		retry.timer.Stop()
		retry.timer = nil
	}
}

// healthRetrier re-runs the health check of a provider whose whole round
// failed, as a round run before the network came back does. mihomo's lazy
// check skips a provider unused for an interval, so such a round implies use.
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
		round = &healthRound{failed: map[string]struct{}{}}
		r.rounds[url] = round
		generation := r.generation
		round.settle = r.afterFunc(healthRoundSettle, func() { r.settle(url, round, generation) })
	} else {
		round.settle.Reset(healthRoundSettle)
	}
	if !ok {
		if !round.succeeded {
			round.failed[name] = struct{}{}
		}
		return
	}
	round.succeeded = true
	round.failed = nil
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
	failed := round.failed
	r.mu.Unlock()
	if len(failed) == 0 {
		return
	}

	var lost []cp.ProxyProvider
	for _, pd := range r.candidates() {
		if failedEveryProxy(failed, pd.Proxies()) {
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
		r.roundFailedLocked(pd, generation)
	}
}

// mihomo's own failed rounds count too, so a group whose interval is shorter
// than the backoff is never probed more often than it probes itself.
func (r *healthRetrier) roundFailedLocked(pd cp.ProxyProvider, generation uint64) {
	name := pd.Name()
	now := r.now()
	retry := r.retries[name]
	if retry != nil && (retry.provider != pd || now.Sub(retry.lastFailure) > healthRetryEpisode) {
		retry.stop()
		retry = nil
	}
	if retry == nil {
		retry = &healthRetry{provider: pd}
		r.retries[name] = retry
	} else if now.Sub(retry.lastFailure) < healthRoundSettle {
		return
	}
	retry.lastFailure = now
	retry.failures++
	retry.names = proxyNameSet(pd.Proxies())
	retry.stop()
	if retry.failures > len(healthRetryBackoff) {
		return
	}
	retry.timer = r.afterFunc(healthRetryBackoff[retry.failures-1], func() {
		r.fire(name, retry, generation)
	})
}

func (r *healthRetrier) fire(name string, retry *healthRetry, generation uint64) {
	r.mu.Lock()
	current := r.generation == generation && r.retries[name] == retry
	if current {
		retry.timer = nil
	}
	r.mu.Unlock()
	if !current || r.candidates()[name] != retry.provider {
		return
	}
	retry.provider.HealthCheck()
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
