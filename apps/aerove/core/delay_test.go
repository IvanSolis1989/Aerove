package core

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

func TestLatestDelaysReportTheLastResultOfEveryGroupedNode(t *testing.T) {
	var refuse atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if refuse.Load() {
			panic(http.ErrAbortHandler)
		}
		time.Sleep(5 * time.Millisecond)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	const refusedURL = "http://127.0.0.1:1"
	group := selectorGroup(t, "pick", "up", "down", "untested")
	tunnel.UpdateProxies(map[string]constant.Proxy{"pick": group}, nil)
	t.Cleanup(func() { tunnel.UpdateProxies(nil, nil) })
	selector, _ := adapterAs[outboundgroup.ProxyGroup](group)
	members := map[string]constant.Proxy{}
	for _, member := range selector.Proxies() {
		members[member.Name()] = member
	}
	probe := func(name, url string) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = members[name].URLTest(ctx, url, nil)
	}

	probe("up", server.URL)
	probe("up", refusedURL)
	probe("down", server.URL)
	refuse.Store(true)
	probe("down", server.URL)

	got := map[string]int32{}
	for _, delay := range handleGetLatestDelays() {
		got[delay.Name+" "+delay.Url] = delay.Value
	}
	if len(got) != 3 {
		t.Fatalf("delays = %v, want up and down on the server and up on the refused port", got)
	}
	if value := got["up "+server.URL]; value <= 0 {
		t.Errorf("up = %d, want the delay it answered with", value)
	}
	if value := got["up "+refusedURL]; value != delayFailed {
		t.Errorf("up on the refused port = %d, want %d", value, delayFailed)
	}
	if value := got["down "+server.URL]; value != delayFailed {
		t.Errorf("down = %d, want its last result, a failure, over the earlier answer", value)
	}
}
