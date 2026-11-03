package core

import (
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestMatchFiltersKeepsPatternAndNodeOrderAndDeduplicatesNames(t *testing.T) {
	result, err := handleMatchFilters(FilterMatchArgs{ID: "ordered", Patterns: []string{"(?i)b`a", "a"}, Names: []string{"a", "B", "B", "a"}, TimeoutMS: 1000})
	if err != nil || !reflect.DeepEqual(result, []int{1, 0}) {
		t.Fatalf("ordered matching: indices=%v err=%v", result, err)
	}
	result, err = handleMatchFilters(FilterMatchArgs{ID: "all", Names: []string{"a", "a", "b"}, TimeoutMS: 1000})
	if err != nil || !reflect.DeepEqual(result, []int{0, 1, 2}) {
		t.Fatalf("empty include: indices=%v err=%v", result, err)
	}
}

func TestMatchFiltersRejectsInvalidAndOversizedInputWithoutPartialResults(t *testing.T) {
	for _, args := range []FilterMatchArgs{
		{ID: "invalid", Patterns: []string{"a", "("}, Names: []string{"a"}, TimeoutMS: 1000},
		{ID: "oversized", Names: make([]string, 10001), TimeoutMS: 1000},
		{ID: "bad-deadline", TimeoutMS: 0},
	} {
		result, err := handleMatchFilters(args)
		if err == nil || result != nil {
			t.Fatalf("expected an atomic failure: %v", err)
		}
		if err.(*MethodError).Code != "bad_argument" {
			t.Fatalf("unexpected code: %v", err)
		}
	}
}

func TestMatchFiltersCancellationJoinsAndPreCancellationDoesNotStart(t *testing.T) {
	if _, err := handleCancelFilterMatch(FilterCancelArgs{ID: "before"}); err != nil {
		t.Fatal(err)
	}
	result, err := handleMatchFilters(FilterMatchArgs{ID: "before", Patterns: []string{"a"}, Names: []string{"a"}, TimeoutMS: 1000})
	if result != nil || err == nil || err.(*MethodError).Code != "cancelled" {
		t.Fatalf("pre-cancel: %v", err)
	}
	finished := make(chan error, 1)
	go func() {
		_, err := handleMatchFilters(FilterMatchArgs{ID: "active", Patterns: make([]string, 4096), Names: []string{strings.Repeat("x", 1000)}, TimeoutMS: 2000})
		finished <- err
	}()
	deadline := time.Now().Add(time.Second)
	for {
		filterMatchRegistry.Lock()
		active := filterMatchRegistry.active["active"] != nil
		filterMatchRegistry.Unlock()
		if active {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("matching did not enter its owned request")
		}
		runtime.Gosched()
	}
	if _, err := handleCancelFilterMatch(FilterCancelArgs{ID: "active"}); err != nil {
		t.Fatal(err)
	}
	filterMatchRegistry.Lock()
	active := filterMatchRegistry.active["active"] != nil
	filterMatchRegistry.Unlock()
	if active {
		t.Fatal("cancellation returned before request release")
	}
	if err := <-finished; err == nil || err.(*MethodError).Code != "cancelled" {
		t.Fatalf("active cancellation: %v", err)
	}
}

func TestMatchFiltersDeadlineRejectsCatastrophicPattern(t *testing.T) {
	result, err := handleMatchFilters(FilterMatchArgs{ID: "deadline", Patterns: []string{"^(a+)+$"}, Names: []string{strings.Repeat("a", 10000) + "!"}, TimeoutMS: 1})
	if result != nil || err == nil || err.(*MethodError).Code != "filter_timeout" {
		t.Fatalf("deadline: %v", err)
	}
}
