package core

import (
	"fmt"
	"os"
	"sync"
	"sync/atomic"

	"github.com/metacubex/mihomo/common/observable"
	"github.com/metacubex/mihomo/log"
)

var debugStderr = os.Getenv("FLCLASH_CORE_DEBUG") != ""

func logError(format string, args ...any) {
	log.Errorln(format, args...)
	if debugStderr {
		fmt.Fprintf(os.Stderr, "[ERROR] "+format+"\n", args...)
	}
}

// mihomo emits to a subscriber under its own lock and blocks once the buffer
// is full, so the pump reads until the subscription is closed; one that stopped
// early would wedge every logger, the unsubscribe included.
type logPump struct {
	subscription observable.Subscription[log.Event]
	stopped      atomic.Bool
}

func (pump *logPump) run() {
	for event := range pump.subscription {
		if pump.stopped.Load() || event.LogLevel < log.Level() {
			continue
		}
		sendMessage(LogMessage, event)
	}
}

func (pump *logPump) stop() {
	pump.stopped.Store(true)
	log.UnSubscribe(pump.subscription)
}

var (
	logMu         sync.Mutex
	activeLog     *logPump
	logRequested  bool
	logRequestSeq uint64
)

func handleStartLog() {
	setLogRequested(true, methodCallSeq.Add(1))
}

func setLogRequested(requested bool, seq uint64) {
	logMu.Lock()
	defer logMu.Unlock()
	if seq < logRequestSeq {
		return
	}
	logRequestSeq = seq
	logRequested = requested
	if activeLog != nil {
		activeLog.stop()
		activeLog = nil
	}
	if !requested || !hasUiTelemetry() {
		return
	}
	activeLog = &logPump{subscription: log.Subscribe()}
	go activeLog.run()
}

func handleStopLog() {
	setLogRequested(false, methodCallSeq.Add(1))
}

func logRequestHandler(requested bool) methodHandler {
	return func(call *MethodCall) (any, error) {
		setLogRequested(requested, call.seq)
		return true, nil
	}
}

func syncLogTelemetry() {
	logMu.Lock()
	defer logMu.Unlock()
	if !hasUiTelemetry() || !logRequested {
		if activeLog != nil {
			activeLog.stop()
			activeLog = nil
		}
		return
	}
	if activeLog == nil {
		activeLog = &logPump{subscription: log.Subscribe()}
		go activeLog.run()
	}
}
