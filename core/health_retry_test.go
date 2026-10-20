package core

import (
	"testing"
	"time"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/constant"
	cp "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/tunnel"
)

const retryTestURL = "https://retry.test/generate_204"

type fakeRetryTimer struct {
	clock  *retryClock
	due    time.Time
	run    func()
	active bool
}

func (timer *fakeRetryTimer) Stop() bool {
	active := timer.active
	timer.active = false
	return active
}

func (timer *fakeRetryTimer) Reset(d time.Duration) bool {
	active := timer.active
	timer.active = true
	timer.due = timer.clock.now.Add(d)
	return active
}

type retryClock struct {
	now    time.Time
	timers []*fakeRetryTimer
}

func (clock *retryClock) afterFunc(d time.Duration, run func()) retryTimer {
	timer := &fakeRetryTimer{clock: clock, due: clock.now.Add(d), run: run, active: true}
	clock.timers = append(clock.timers, timer)
	return timer
}

func (clock *retryClock) advance(d time.Duration) {
	target := clock.now.Add(d)
	for {
		var next *fakeRetryTimer
		for _, timer := range clock.timers {
			if timer.active && !timer.due.After(target) && (next == nil || timer.due.Before(next.due)) {
				next = timer
			}
		}
		if next == nil {
			break
		}
		clock.now = next.due
		next.active = false
		next.run()
	}
	clock.now = target
}

type checkedProvider struct {
	fakeProxyProvider
	proxies []constant.Proxy
	checks  int
	onCheck func()
}

func (p *checkedProvider) Proxies() []constant.Proxy { return p.proxies }

func (p *checkedProvider) HealthCheck() {
	p.checks++
	if p.onCheck != nil {
		p.onCheck()
	}
}

func checkedProviderOf(name string, members ...string) *checkedProvider {
	proxies := make([]constant.Proxy, 0, len(members))
	for _, member := range members {
		proxies = append(proxies, namedProxy(member))
	}
	return &checkedProvider{
		fakeProxyProvider: fakeProxyProvider{name: name, vehicle: cp.Compatible},
		proxies:           proxies,
	}
}

func newTestRetrier(candidates ...*checkedProvider) (*healthRetrier, *retryClock) {
	clock := &retryClock{now: time.Unix(1_800_000_000, 0)}
	retrier := newHealthRetrier()
	retrier.now = func() time.Time { return clock.now }
	retrier.afterFunc = clock.afterFunc
	retrier.candidates = func() map[string]cp.ProxyProvider {
		providers := make(map[string]cp.ProxyProvider, len(candidates))
		for _, pd := range candidates {
			providers[pd.Name()] = pd
		}
		return providers
	}
	return retrier, clock
}

func reportRound(retrier *healthRetrier, url string, pd *checkedProvider, ok bool) {
	for _, proxy := range pd.proxies {
		retrier.record(url, proxy.Name(), ok)
	}
}

func TestHealthRetryBacksOffAfterAWholeRoundFails(t *testing.T) {
	pd := checkedProviderOf("auto", "a", "b")
	retrier, clock := newTestRetrier(pd)
	pd.onCheck = func() { reportRound(retrier, retryTestURL, pd, false) }

	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(healthRoundSettle + 29*time.Second)
	if pd.checks != 0 {
		t.Fatalf("checks = %d before the first 30s backoff ran out", pd.checks)
	}
	clock.advance(time.Second)
	if pd.checks != 1 {
		t.Fatalf("checks = %d, want the first retry 30s after the failed round", pd.checks)
	}
	clock.advance(healthRoundSettle + 59*time.Second)
	if pd.checks != 1 {
		t.Fatalf("checks = %d before the second 60s backoff ran out", pd.checks)
	}
	clock.advance(time.Second)
	if pd.checks != 2 {
		t.Fatalf("checks = %d, want the second retry 60s after the first one failed", pd.checks)
	}
	clock.advance(healthRoundSettle + 120*time.Second)
	if pd.checks != 3 {
		t.Fatalf("checks = %d, want the third retry 120s after the second one failed", pd.checks)
	}
	clock.advance(time.Hour)
	if pd.checks != 3 {
		t.Fatalf("checks = %d, want no more than three retries for one outage", pd.checks)
	}
}

func TestHealthRetryStopsAtTheFirstSuccess(t *testing.T) {
	pd := checkedProviderOf("auto", "a", "b")
	retrier, clock := newTestRetrier(pd)
	pd.onCheck = func() {
		retrier.record(retryTestURL, "a", true)
		retrier.record(retryTestURL, "b", false)
	}

	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(healthRoundSettle + healthRetryBackoff[0])
	clock.advance(time.Hour)
	if pd.checks != 1 {
		t.Fatalf("checks = %d, want the retry that reached a node to be the last", pd.checks)
	}

	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(healthRoundSettle)
	retrier.record("https://other.test", "b", true)
	clock.advance(time.Hour)
	if pd.checks != 1 {
		t.Fatalf("checks = %d, want a node answering elsewhere to cancel the pending retry", pd.checks)
	}
}

func TestHealthRetryLeavesRoundsThatDidNotFailEveryNode(t *testing.T) {
	auto := checkedProviderOf("auto", "a", "b")
	picked := checkedProviderOf("picked", "c")
	retrier, clock := newTestRetrier(auto)

	retrier.record(retryTestURL, "a", false)
	retrier.record(retryTestURL, "b", true)
	clock.advance(healthRoundSettle)
	retrier.record(retryTestURL, "a", false)
	clock.advance(healthRoundSettle)
	reportRound(retrier, retryTestURL, picked, false)
	clock.advance(time.Hour)

	if auto.checks != 0 || picked.checks != 0 {
		t.Fatalf("checks = %d/%d, want no retry without a whole failed round of a checked provider", auto.checks, picked.checks)
	}
	if len(retrier.retries) != 0 {
		t.Fatalf("retries = %v, want none", retrier.retries)
	}
}

func TestHealthRetryCountsARoundOnceAcrossItsURLs(t *testing.T) {
	pd := checkedProviderOf("auto", "a", "b")
	retrier, clock := newTestRetrier(pd)

	reportRound(retrier, retryTestURL, pd, false)
	reportRound(retrier, "https://extra.test", pd, false)
	clock.advance(healthRoundSettle + healthRetryBackoff[0])

	if pd.checks != 1 {
		t.Fatalf("checks = %d, want one round failing on two URLs to count once", pd.checks)
	}
}

func TestHealthRetryResetDropsPendingWork(t *testing.T) {
	pd := checkedProviderOf("auto", "a", "b")
	retrier, clock := newTestRetrier(pd)

	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(healthRoundSettle)
	reportRound(retrier, "https://extra.test", pd, false)
	retrier.reset()
	clock.advance(time.Hour)

	if pd.checks != 0 {
		t.Fatalf("checks = %d, want a config reload to drop the scheduled retry", pd.checks)
	}
	if len(retrier.rounds) != 0 || len(retrier.retries) != 0 {
		t.Fatalf("rounds = %v, retries = %v, want both empty", retrier.rounds, retrier.retries)
	}
}

func TestHealthRetryStartsAgainAfterAQuietSpell(t *testing.T) {
	pd := checkedProviderOf("auto", "a")
	retrier, clock := newTestRetrier(pd)
	pd.onCheck = func() { reportRound(retrier, retryTestURL, pd, false) }

	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(time.Hour)
	if pd.checks != len(healthRetryBackoff) {
		t.Fatalf("checks = %d, want the outage to exhaust its retries", pd.checks)
	}

	clock.advance(healthRetryEpisode)
	pd.onCheck = nil
	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(healthRoundSettle + healthRetryBackoff[0])
	if pd.checks != len(healthRetryBackoff)+1 {
		t.Fatalf("checks = %d, want a failure long after the last one to retry again", pd.checks)
	}
}

func TestAutoCheckedProvidersSkipsSelectors(t *testing.T) {
	members := []constant.Proxy{namedProxy("a")}
	health := provider.NewHealthCheck(members, "", 0, 0, true, nil)
	auto, err := provider.NewCompatibleProvider("auto-provider", members, health)
	if err != nil {
		t.Fatalf("NewCompatibleProvider: %v", err)
	}
	group, err := outboundgroup.NewURLTest(
		outboundgroup.GroupCommonOption{Name: "auto"},
		outboundgroup.URLTestOption{},
		namedProxy("COMPATIBLE"),
		[]cp.ProxyProvider{auto},
	)
	if err != nil {
		t.Fatalf("NewURLTest: %v", err)
	}
	tunnel.UpdateProxies(map[string]constant.Proxy{
		"auto": adapter.NewProxy(group),
		"pick": selectorGroup(t, "pick", "b"),
	}, nil)
	t.Cleanup(func() { tunnel.UpdateProxies(nil, nil) })

	providers := autoCheckedProviders()

	if len(providers) != 1 || providers["auto-provider"] != cp.ProxyProvider(auto) {
		t.Fatalf("providers = %v, want only the url-test group's provider", providers)
	}
}
