package core

import (
	"fmt"
	"testing"
	"time"

	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/component/geodata/router"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

func storedGeoSiteList(list string) []*router.Domain {
	stored := true
	domains, _, _ := geoSiteLists.Do(list, func() ([]*router.Domain, error) {
		stored = false
		return nil, nil
	})
	if !stored {
		geoSiteLists.Forget(list)
	}
	return domains
}

func stubLoadMemoryRelease(t *testing.T) <-chan struct{} {
	t.Helper()
	stopTimer := func() {
		loadReleaseMu.Lock()
		if loadReleaseTimer != nil {
			loadReleaseTimer.Stop()
		}
		loadReleaseMu.Unlock()
	}
	stopTimer()
	released := make(chan struct{}, 1)
	delay, release := loadReleaseDelay, releaseLoadMemory
	loadReleaseDelay = 10 * time.Millisecond
	releaseLoadMemory = func() {
		select {
		case released <- struct{}{}:
		default:
		}
	}
	t.Cleanup(func() {
		stopTimer()
		loadReleaseDelay, releaseLoadMemory = delay, release
	})
	return released
}

func TestLoadMemoryReleaseDropsGeoSiteListsButKeepsTheirMatchers(t *testing.T) {
	released := stubLoadMemoryRelease(t)
	list := fmt.Sprintf("aerove-probe-%d", time.Now().UnixNano())
	const host = "probe.aerove.test"
	seeded := []*router.Domain{{Type: router.Domain_Full, Value: host}}
	geoSiteLists.Do(list, func() ([]*router.Domain, error) { return seeded, nil })
	t.Cleanup(func() { geoSiteLists.Forget(list) })

	matcher, err := geodata.LoadGeoSiteMatcher(list)
	if err != nil {
		t.Fatalf("mihomo did not see the list stored through geoSiteLists, so the linkname no longer binds loadGeoSiteMatcherListSF: %v", err)
	}

	scheduleLoadMemoryRelease()
	select {
	case <-released:
	case <-time.After(2 * time.Second):
		t.Fatal("the load-memory release never ran")
	}

	if len(storedGeoSiteList(list)) != 0 {
		t.Error("the decoded list outlived the load-memory release")
	}
	again, err := geodata.LoadGeoSiteMatcher(list)
	if err != nil {
		t.Fatalf("the matcher went with the list, so the next GEOSITE match decodes it again: %v", err)
	}
	if again != matcher {
		t.Error("LoadGeoSiteMatcher built a new matcher after the release; want the cached one")
	}
	if !again.ApplyDomain(host) {
		t.Errorf("the cached matcher no longer matches %s", host)
	}
}

type geoSiteRuleProbe struct {
	constant.Rule
	rebuilt bool
}

func (r *geoSiteRuleProbe) GetDomainMatcher() (router.DomainMatcher, error) {
	r.rebuilt = true
	return nil, nil
}

func TestGeoSiteUpdateRebuildsRuleMatchersAndDropsTheirLists(t *testing.T) {
	released := stubLoadMemoryRelease(t)
	list := fmt.Sprintf("aerove-update-%d", time.Now().UnixNano())
	geoSiteLists.Do(list, func() ([]*router.Domain, error) {
		return []*router.Domain{{Type: router.Domain_Full, Value: "update.aerove.test"}}, nil
	})
	t.Cleanup(func() { geoSiteLists.Forget(list) })
	probe := &geoSiteRuleProbe{}
	tunnel.UpdateRules([]constant.Rule{probe}, nil, nil)
	t.Cleanup(func() { tunnel.UpdateRules(nil, nil, nil) })
	t.Cleanup(settleMessageBatcher)

	onGeoUpdate("GEOSITE", false, false, nil)

	if !probe.rebuilt {
		t.Error("the update left the GEOSITE rule to rebuild its matcher on a later match, which can come after the release")
	}
	select {
	case <-released:
	case <-time.After(2 * time.Second):
		t.Fatal("a GEOSITE update never scheduled the load-memory release")
	}
	if len(storedGeoSiteList(list)) != 0 {
		t.Error("a list decoded after the GEOSITE update outlived the release")
	}
}
