package core

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/config"
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

func newTestRetrier(t *testing.T, candidates ...*checkedProvider) (*healthRetrier, *retryClock) {
	t.Helper()
	running := isRunning.Load()
	isRunning.Store(true)
	t.Cleanup(func() { isRunning.Store(running) })

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

func useHealthRetrier(t *testing.T, retrier *healthRetrier) {
	t.Helper()
	previous := healthRetries
	healthRetries = retrier
	t.Cleanup(func() { healthRetries = previous })
}

func pendingHealthRetry(t *testing.T) *healthRetrier {
	t.Helper()
	pd := checkedProviderOf("auto", "a")
	retrier, clock := newTestRetrier(t, pd)
	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(healthRoundSettle)
	if len(retrier.retries) != 1 {
		t.Fatalf("retries = %v, want one armed by the failed round", retrier.retries)
	}
	useHealthRetrier(t, retrier)
	return retrier
}

func reportRound(retrier *healthRetrier, url string, pd *checkedProvider, ok bool) {
	for _, proxy := range pd.proxies {
		retrier.record(url, proxy.Name(), ok)
	}
}

// staggerCheck fails every node on each URL in turn, one result per step, as
// mihomo's errgroup capped at 10 works through a large provider.
func staggerCheck(retrier *healthRetrier, clock *retryClock, pd *checkedProvider, step time.Duration, urls ...string) {
	at := time.Duration(0)
	for _, url := range urls {
		for _, proxy := range pd.proxies {
			at += step
			name := proxy.Name()
			clock.afterFunc(at, func() { retrier.record(url, name, false) })
		}
	}
}

func TestHealthRetryBacksOffAfterAWholeRoundFails(t *testing.T) {
	pd := checkedProviderOf("auto", "a", "b")
	retrier, clock := newTestRetrier(t, pd)
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
	retrier, clock := newTestRetrier(t, pd)
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
	retrier, clock := newTestRetrier(t, auto)

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
	retrier, clock := newTestRetrier(t, pd)

	reportRound(retrier, retryTestURL, pd, false)
	reportRound(retrier, "https://extra.test", pd, false)
	clock.advance(healthRoundSettle + healthRetryBackoff[0])

	if pd.checks != 1 {
		t.Fatalf("checks = %d, want one round failing on two URLs to count once", pd.checks)
	}
}

func TestHealthRetryWaitsOutEveryURLOfACheck(t *testing.T) {
	pd := checkedProviderOf("auto", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
	retrier, clock := newTestRetrier(t, pd)
	check := func() { staggerCheck(retrier, clock, pd, 5*time.Second, retryTestURL, "https://extra.test") }
	pd.onCheck = func() {
		if len(retrier.rounds) != 0 {
			t.Errorf("retry %d ran at %v while a round of the last check was still open", pd.checks, clock.now)
		}
		check()
	}

	check()
	clock.advance(time.Hour)

	if pd.checks != len(healthRetryBackoff) {
		t.Fatalf("checks = %d, want every backoff step to retry when a check runs its URLs one after the other", pd.checks)
	}
}

func TestHealthRetryResetDropsPendingWork(t *testing.T) {
	pd := checkedProviderOf("auto", "a", "b")
	retrier, clock := newTestRetrier(t, pd)

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

func TestHealthRetryStaysSpentUntilANodeAnswers(t *testing.T) {
	pd := checkedProviderOf("auto", "a")
	retrier, clock := newTestRetrier(t, pd)
	pd.onCheck = func() { reportRound(retrier, retryTestURL, pd, false) }

	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(time.Hour)
	if pd.checks != len(healthRetryBackoff) {
		t.Fatalf("checks = %d, want the outage to exhaust its retries", pd.checks)
	}

	for range 4 {
		reportRound(retrier, retryTestURL, pd, false)
		clock.advance(900 * time.Second)
	}
	if pd.checks != len(healthRetryBackoff) {
		t.Fatalf("checks = %d, want a power-save group's own failed rounds to add no retries", pd.checks)
	}
}

func TestHealthRetryStartsAgainAfterANodeAnswered(t *testing.T) {
	pd := checkedProviderOf("auto", "a")
	retrier, clock := newTestRetrier(t, pd)
	pd.onCheck = func() { reportRound(retrier, retryTestURL, pd, false) }

	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(time.Hour)
	reportRound(retrier, retryTestURL, pd, true)
	clock.advance(time.Hour)
	pd.onCheck = nil
	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(healthRoundSettle + healthRetryBackoff[0])

	if pd.checks != len(healthRetryBackoff)+1 {
		t.Fatalf("checks = %d, want a failure after a node answered to start a new series", pd.checks)
	}
}

func TestHealthRetryLeavesAStoppedCoreAlone(t *testing.T) {
	pd := checkedProviderOf("auto", "a", "b")
	retrier, clock := newTestRetrier(t, pd)

	isRunning.Store(false)
	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(time.Hour)
	if pd.checks != 0 || len(retrier.retries) != 0 {
		t.Fatalf("checks = %d, retries = %v, want a round failed while stopped to arm nothing", pd.checks, retrier.retries)
	}

	isRunning.Store(true)
	reportRound(retrier, retryTestURL, pd, false)
	clock.advance(healthRoundSettle)
	isRunning.Store(false)
	clock.advance(time.Hour)
	if pd.checks != 0 {
		t.Fatalf("checks = %d, want a retry that came due after the core stopped to probe nothing", pd.checks)
	}
}

func TestUrlTestHookFeedsTheHealthRetrier(t *testing.T) {
	retrier, _ := newTestRetrier(t)
	useHealthRetrier(t, retrier)
	const refusedURL = "http://127.0.0.1:1"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, _ = namedProxy("probe").URLTest(ctx, refusedURL, nil)

	round := retrier.rounds[refusedURL]
	if round == nil {
		t.Fatalf("rounds = %v, want one for the probed URL", retrier.rounds)
	}
	if _, failed := round.failed["probe"]; !failed {
		t.Errorf("failed = %v, want the refused probe", round.failed)
	}
}

func TestStopListenerDropsPendingHealthRetries(t *testing.T) {
	retrier := pendingHealthRetry(t)
	generation := retrier.generation

	handleStopListener()

	if retrier.generation == generation || len(retrier.retries) != 0 {
		t.Errorf("generation %d -> %d, retries = %v; stopping the proxy kept its retries", generation, retrier.generation, retrier.retries)
	}
}

func TestApplyConfigDropsHealthRetriesBeforeTheNewProviders(t *testing.T) {
	retrier := pendingHealthRetry(t)
	// On a running core the apply would bind the profile's listeners.
	isRunning.Store(false)
	home := t.TempDir()
	previousHome := constant.Path.HomeDir()
	constant.SetHomeDir(home)
	t.Cleanup(func() { constant.SetHomeDir(previousHome) })
	if err := os.WriteFile(filepath.Join(home, "config.yaml"), []byte("rules:\n  - MATCH,REJECT\n"), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	withCurrentConfig(t, currentConfig)
	pendingAtApply := -1
	previousHub := applyHubConfig
	applyHubConfig = func(*config.Config) { pendingAtApply = len(retrier.retries) }
	t.Cleanup(func() {
		applyHubConfig = previousHub
		// The apply arms the real post-load memory release.
		loadReleaseMu.Lock()
		if loadReleaseTimer != nil {
			loadReleaseTimer.Stop()
		}
		loadReleaseMu.Unlock()
	})

	if err := applyConfig(&SetupParams{}); err != nil {
		t.Fatalf("applyConfig = %v", err)
	}

	if pendingAtApply != 0 {
		t.Errorf("retries at the hub apply = %d, want the old profile's dropped before its providers are replaced", pendingAtApply)
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
