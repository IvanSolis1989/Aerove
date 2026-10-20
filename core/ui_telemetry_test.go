package core

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

func activateTestTelemetry(t *testing.T) {
	t.Helper()
	previous := uiTelemetry.active.Load()
	setUiTelemetryActive(true, methodCallSeq.Add(1))
	t.Cleanup(func() {
		handleStopLog()
		stopRequestTrafficWatch()
		setUiTelemetryActive(previous, methodCallSeq.Add(1))
	})
}

func TestUiTelemetryPreservesControlEventsAndDropsPreviousGeneration(t *testing.T) {
	activateTestTelemetry(t)
	epoch := uiTelemetry.epoch.Load()
	setUiTelemetryActive(false, methodCallSeq.Add(1))
	messages := []Message{
		{Type: RequestMessage, telemetryEpoch: epoch},
		{Type: DnsMessage, telemetryEpoch: epoch},
		{Type: RequestTrafficMessage, telemetryEpoch: epoch},
		{Type: LogMessage, telemetryEpoch: epoch},
		{Type: DelayMessage, telemetryEpoch: epoch},
		{Type: LoadedMessage},
		{Type: GeoUpdateMessage},
		{Type: DialerLoopMessage},
	}
	filtered := filterUiTelemetryMessages(append([]Message(nil), messages...))
	if len(filtered) != 3 || filtered[0].Type != LoadedMessage || filtered[1].Type != GeoUpdateMessage {
		t.Fatalf("background control events = %v", filtered)
	}
	if sendMessage(DnsMessage, "hidden") || sendMessage(RequestMessage, "hidden") {
		t.Fatal("background telemetry entered the event queue")
	}
	setUiTelemetryActive(true, methodCallSeq.Add(1))
	if got := filterUiTelemetryMessages(messages); len(got) != 3 {
		t.Fatalf("old telemetry was replayed after resume: %v", got)
	}
}

func TestUiTelemetryLatestIntentWins(t *testing.T) {
	activateTestTelemetry(t)
	older := methodCallSeq.Add(1)
	newer := methodCallSeq.Add(1)
	setUiTelemetryActive(false, newer)
	setUiTelemetryActive(true, older)
	if uiTelemetry.active.Load() {
		t.Fatal("an older foreground request reactivated background telemetry")
	}
	setLogRequested(false, newer)
	setLogRequested(true, older)
	logMu.Lock()
	requested := logRequested
	logMu.Unlock()
	if requested {
		t.Fatal("an older log request overwrote the latest stop")
	}
}

func TestUiTelemetryUnsubscribesAndRestoresRequestedLogs(t *testing.T) {
	activateTestTelemetry(t)
	handleStartLog()
	pump := currentLogPump()
	if pump == nil {
		t.Fatal("foreground logs were not subscribed")
	}
	setUiTelemetryActive(false, methodCallSeq.Add(1))
	if currentLogPump() != nil || !pump.stopped.Load() {
		t.Fatal("background logs remained subscribed")
	}
	setUiTelemetryActive(true, methodCallSeq.Add(1))
	if resumed := currentLogPump(); resumed == nil || resumed == pump {
		t.Fatal("requested logs were not restored on resume")
	}
	setUiTelemetryActive(false, methodCallSeq.Add(1))
	handleStopLog()
	setUiTelemetryActive(true, methodCallSeq.Add(1))
	if currentLogPump() != nil {
		t.Fatal("logs disabled in the background were resurrected")
	}
}

func TestUiTelemetryCancelsRequestTrafficWithoutClosingConnections(t *testing.T) {
	activateTestTelemetry(t)
	tracker := newPipeTracker(t)
	watchRequestTraffic(tracker)
	requestTrafficRun.mu.Lock()
	stop := requestTrafficRun.stop
	requestTrafficRun.mu.Unlock()
	if stop == nil {
		t.Fatal("request traffic watch did not start")
	}
	setUiTelemetryActive(false, methodCallSeq.Add(1))
	select {
	case <-stop:
	default:
		t.Fatal("request traffic watch was not canceled")
	}
	currentRequestTraffic.mu.Lock()
	retained := len(currentRequestTraffic.requests)
	currentRequestTraffic.mu.Unlock()
	if retained != 0 {
		t.Fatal("background retained request trackers")
	}
	if _, err := tracker.Write([]byte("still proxied")); err != nil {
		t.Fatalf("background transition closed the connection: %v", err)
	}
	setUiTelemetryActive(true, methodCallSeq.Add(1))
	currentRequestTraffic.mu.Lock()
	_, restored := currentRequestTraffic.requests[tracker.ID()]
	currentRequestTraffic.mu.Unlock()
	if !restored {
		t.Fatal("resume did not watch the surviving connection")
	}
}

func TestUiTelemetryCancelsInFlightUserDelay(t *testing.T) {
	activateTestTelemetry(t)
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
	}))
	defer server.Close()
	tunnel.UpdateProxies(map[string]constant.Proxy{"cancelable": namedProxy("cancelable")}, nil)
	t.Cleanup(func() { tunnel.UpdateProxies(nil, nil) })
	done := make(chan *Delay, 1)
	go func() {
		done <- handleTestDelay(&TestDelayParams{ProxyName: "cancelable", TestUrl: server.URL, Timeout: 5000})
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("delay request never reached the test server")
	}
	setUiTelemetryActive(false, methodCallSeq.Add(1))
	select {
	case result := <-done:
		if result != nil {
			t.Fatalf("canceled delay reported a proxy failure: %v", result)
		}
	case <-time.After(time.Second):
		t.Fatal("background transition did not cancel the delay request")
	}
}
