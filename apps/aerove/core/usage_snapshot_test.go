//go:build !cgo && !android

package core

import (
	"encoding/json"
	"testing"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func TestNativeUsageSnapshotUsesTheSameRealManagerAsLegacyTraffic(t *testing.T) {
	m := statistic.DefaultManager
	m.ResetStatistic()
	m.PushUploaded(false, 5); m.PushDownloaded(true, 7)
	read := func() statistic.UsageSnapshot {
		frame := handleMethodCall(&MethodCall{ID: "usage", Method: getNativeUsageSnapshotMethod})
		var response struct { Result statistic.UsageSnapshot `json:"result"`; Error *MethodError `json:"error"` }
		if err := json.Unmarshal(frame, &response); err != nil { t.Fatal(err) }
		if response.Error != nil { t.Fatal(response.Error) }
		return response.Result
	}
	first := read()
	if first.All != (statistic.UsageCounters{Up: "5", Down: "7"}) || first.Proxy != (statistic.UsageCounters{Up: "5", Down: "0"}) { t.Fatal(first) }
	if legacy := handleGetTotalTraffic(false); legacy.Up != 5 || legacy.Down != 7 { t.Fatal(legacy) }
	m.ResetStatistic()
	second := read()
	if second.ProcessInstance != first.ProcessInstance || second.CounterEpoch == first.CounterEpoch { t.Fatal("reset receipt identity mismatch") }
	if second.All != (statistic.UsageCounters{Up: "0", Down: "0"}) { t.Fatal(second) }
}
