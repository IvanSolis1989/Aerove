package core

import (
	"context"
	"sync"
	"sync/atomic"

	"github.com/metacubex/mihomo/tunnel/statistic"
)

var uiTelemetry struct {
	mu sync.Mutex
	seq uint64
	active atomic.Bool
	epoch atomic.Uint64
	delayContext context.Context
	cancelDelay context.CancelFunc
}

func hasUiTelemetry() bool {
	return uiTelemetry.active.Load() && hasEventListener()
}

func isUiTelemetryMessage(kind MessageType) bool {
	switch kind {
	case LogMessage, RequestMessage, RequestTrafficMessage, DnsMessage, DelayMessage:
		return true
	default:
		return false
	}
}

func setUiTelemetryActive(active bool, seq uint64) bool {
	uiTelemetry.mu.Lock()
	defer uiTelemetry.mu.Unlock()
	if seq < uiTelemetry.seq {
		return uiTelemetry.active.Load()
	}
	uiTelemetry.seq = seq
	changed := uiTelemetry.active.Swap(active) != active
	if changed {
		uiTelemetry.epoch.Add(1)
	}
	if !active {
		if uiTelemetry.cancelDelay != nil {
			uiTelemetry.cancelDelay()
			uiTelemetry.cancelDelay = nil
			uiTelemetry.delayContext = nil
		}
		stopRequestTrafficWatch()
	} else if changed && hasEventListener() {
		var trackers []statistic.Tracker
		statistic.DefaultManager.Range(func(tracker statistic.Tracker) bool {
			trackers = append(trackers, tracker)
			return true
		})
		for _, tracker := range trackers {
			watchRequestTraffic(tracker)
		}
	}
	syncLogTelemetry()
	return active
}

func uiDelayContext() context.Context {
	uiTelemetry.mu.Lock()
	defer uiTelemetry.mu.Unlock()
	if !uiTelemetry.active.Load() {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		return ctx
	}
	if uiTelemetry.delayContext == nil {
		uiTelemetry.delayContext, uiTelemetry.cancelDelay = context.WithCancel(context.Background())
	}
	return uiTelemetry.delayContext
}

func uiTelemetryHandler(call *MethodCall) (any, error) {
	active, err := decodeArguments[bool](call)
	if err != nil {
		return nil, err
	}
	return setUiTelemetryActive(active, call.seq), nil
}

func filterUiTelemetryMessages(messages []Message) []Message {
	epoch := uiTelemetry.epoch.Load()
	filtered := messages[:0]
	for _, message := range messages {
		if isUiTelemetryMessage(message.Type) && (!hasUiTelemetry() || message.telemetryEpoch != epoch) {
			continue
		}
		filtered = append(filtered, message)
	}
	return filtered
}
