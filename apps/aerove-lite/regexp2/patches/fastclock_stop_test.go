// SPDX-License-Identifier: GPL-3.0-only
package regexp2

import (
	"sync"
	"testing"
	"time"
)

func TestAeroveStopInterruptsSleepingClock(t *testing.T) {
	StopTimeoutClock()
	previous := clockPeriod
	clockPeriod = time.Hour
	t.Cleanup(func() { clockPeriod = previous })
	makeDeadline(10 * time.Hour)
	done := make(chan struct{})
	go func() { StopTimeoutClock(); close(done) }()
	select {
	case <-done:
	case <-time.After(100 * time.Millisecond):
		t.Fatal("stop waited for the clock period instead of waking and joining its worker")
	}
	fast.mu.Lock()
	running := fast.running
	fast.mu.Unlock()
	if running {
		t.Fatal("stop returned before the worker retired")
	}
}

func TestAeroveConcurrentStopsAndRestart(t *testing.T) {
	for round := 0; round < 2; round++ {
		deadline := makeDeadline(10 * time.Second)
		if deadline.reached() {
			t.Fatal("restarted clock returned an expired deadline")
		}
		var joined sync.WaitGroup
		for caller := 0; caller < 16; caller++ {
			joined.Add(1)
			go func() { defer joined.Done(); StopTimeoutClock() }()
		}
		joined.Wait()
		fast.mu.Lock()
		running := fast.running
		fast.mu.Unlock()
		if running {
			t.Fatal("concurrent stops did not join the worker")
		}
	}
}
