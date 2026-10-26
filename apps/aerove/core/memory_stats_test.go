package core

import (
	"runtime"
	"runtime/metrics"
	"testing"
)

// Reading before and after ReadMemStats brackets steady drift; this absorbs
// the few spans another goroutine may take or return in between.
const memoryStatsTolerance = 256 << 10

const parkedStackFrames = 128

func TestMemoryClassesAreUint64Metrics(t *testing.T) {
	kinds := make(map[string]metrics.ValueKind)
	for _, description := range metrics.All() {
		kinds[description.Name] = description.Kind
	}
	for _, name := range memoryClassMetrics {
		if kind, ok := kinds[name]; !ok || kind != metrics.KindUint64 {
			t.Errorf("%s is not a uint64 metric of this runtime, so its class reads as zero", name)
		}
	}
}

func TestMemoryStatsLeaveTheWorldRunning(t *testing.T) {
	before := nonGCPauses(t)
	for range 10 {
		handleGetMemoryStats()
	}
	if after := nonGCPauses(t); after != before {
		t.Errorf("ten memory stats reads stopped the world %d times", after-before)
	}
}

func nonGCPauses(t *testing.T) uint64 {
	t.Helper()
	samples := []metrics.Sample{{Name: "/sched/pauses/total/other:seconds"}}
	metrics.Read(samples)
	if samples[0].Value.Kind() != metrics.KindFloat64Histogram {
		t.Fatal("this runtime does not count stop-the-world pauses outside GC")
	}
	var count uint64
	for _, bucket := range samples[0].Value.Float64Histogram().Counts {
		count += bucket
	}
	return count
}

func TestMemoryStatsMatchReadMemStats(t *testing.T) {
	release := parkGrownStacks(8)
	defer release()
	runtime.GC()

	before := handleGetMemoryStats()
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	after := handleGetMemoryStats()

	heapIdle := stats.HeapIdle - stats.HeapReleased
	runtimeOther := stats.MSpanInuse + stats.MCacheInuse + stats.BuckHashSys + stats.GCSys + stats.OtherSys
	if stats.StackInuse < 4*memoryStatsTolerance {
		t.Fatalf("the parked goroutines hold only %d stack bytes, too few to tell stack classes apart", stats.StackInuse)
	}
	expectBracketed(t, "HeapInuse", before.HeapInuse, after.HeapInuse, stats.HeapInuse)
	expectBracketed(t, "HeapIdle", before.HeapIdle, after.HeapIdle, heapIdle)
	expectBracketed(t, "StackInuse", before.StackInuse, after.StackInuse, stats.StackInuse)
	expectBracketed(t, "RuntimeOther", before.RuntimeOther, after.RuntimeOther, runtimeOther)
}

func expectBracketed(t *testing.T, field string, before, after, want uint64) {
	t.Helper()
	low, high := min(before, after), max(before, after)
	if want+memoryStatsTolerance < low || want > high+memoryStatsTolerance {
		t.Errorf("%s read %d and then %d, but ReadMemStats reports %d", field, before, after, want)
	}
}

func parkGrownStacks(count int) (release func()) {
	ready := make(chan struct{})
	done := make(chan struct{})
	for range count {
		go parkDeep(parkedStackFrames, ready, done)
	}
	for range count {
		<-ready
	}
	return func() { close(done) }
}

func parkDeep(depth int, ready chan<- struct{}, done <-chan struct{}) byte {
	var frame [1024]byte
	frame[depth%len(frame)] = byte(depth)
	if depth == 0 {
		ready <- struct{}{}
		<-done
		return frame[0]
	}
	return parkDeep(depth-1, ready, done) + frame[depth%len(frame)]
}
