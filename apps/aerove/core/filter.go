package core

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/dlclark/regexp2"
)

type FilterMatchArgs struct {
	ID        string   `json:"request-id"`
	Patterns  []string `json:"patterns"`
	Names     []string `json:"names"`
	TimeoutMS int      `json:"timeout-ms"`
}
type FilterCancelArgs struct {
	ID string `json:"request-id"`
}
type filterMatchRun struct {
	cancel context.CancelFunc
	done   chan struct{}
}

var filterMatchRegistry = struct {
	sync.Mutex
	active    map[string]*filterMatchRun
	cancelled map[string]time.Time
}{active: map[string]*filterMatchRun{}, cancelled: map[string]time.Time{}}

func filterMatchError(code, field, message string) error {
	return &MethodError{Code: code, Message: message, Details: map[string]any{"field": field}}
}
func validFilterMatchID(id string) bool {
	if len(id) < 1 || len(id) > 64 {
		return false
	}
	for _, c := range id {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune("_:.-", c)) {
			return false
		}
	}
	return true
}
func expireFilterCancellations() {
	for id, at := range filterMatchRegistry.cancelled {
		if time.Since(at) > 30*time.Second {
			delete(filterMatchRegistry.cancelled, id)
		}
	}
}
func handleCancelFilterMatch(args FilterCancelArgs) (bool, error) {
	if !validFilterMatchID(args.ID) {
		return false, filterMatchError("bad_argument", "$.request-id", "Invalid filter request identity")
	}
	filterMatchRegistry.Lock()
	if run := filterMatchRegistry.active[args.ID]; run != nil {
		filterMatchRegistry.Unlock()
		run.cancel()
		<-run.done
		return true, nil
	}
	defer filterMatchRegistry.Unlock()
	expireFilterCancellations()
	if len(filterMatchRegistry.cancelled) >= 256 {
		return false, filterMatchError("busy", "$.request-id", "Filter cancellation ledger is full")
	}
	filterMatchRegistry.cancelled[args.ID] = time.Now()
	return true, nil
}
func handleMatchFilters(args FilterMatchArgs) ([]int, error) {
	if !validFilterMatchID(args.ID) || args.TimeoutMS < 1 || args.TimeoutMS > 10000 {
		return nil, filterMatchError("bad_argument", "$.request-id/timeout-ms", "Invalid filter request or deadline")
	}
	if len(args.Patterns) > 4096 || len(args.Names) > 10000 {
		return nil, filterMatchError("bad_argument", "$.patterns/names", "Filter batch exceeds the item limit")
	}
	encoded, err := json.Marshal(args)
	if err != nil || len(encoded) > 4*1024*1024 {
		return nil, filterMatchError("bad_argument", "$", "Filter batch exceeds 4 MiB")
	}
	for _, values := range [][]string{args.Patterns, args.Names} {
		for _, value := range values {
			if !utf8.ValidString(value) {
				return nil, filterMatchError("bad_argument", "$", "Filter batch must be UTF-8")
			}
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(args.TimeoutMS)*time.Millisecond)
	run := &filterMatchRun{cancel: cancel, done: make(chan struct{})}
	filterMatchRegistry.Lock()
	expireFilterCancellations()
	if _, exists := filterMatchRegistry.cancelled[args.ID]; exists {
		delete(filterMatchRegistry.cancelled, args.ID)
		filterMatchRegistry.Unlock()
		cancel()
		return nil, filterMatchError("cancelled", "$", "Filter matching was cancelled")
	}
	if filterMatchRegistry.active[args.ID] != nil {
		filterMatchRegistry.Unlock()
		cancel()
		return nil, filterMatchError("busy", "$.request-id", "Filter request identity is active")
	}
	filterMatchRegistry.active[args.ID] = run
	filterMatchRegistry.Unlock()
	defer func() {
		cancel()
		filterMatchRegistry.Lock()
		delete(filterMatchRegistry.active, args.ID)
		filterMatchRegistry.Unlock()
		close(run.done)
	}()
	check := func() error {
		if ctx.Err() == context.DeadlineExceeded {
			return filterMatchError("filter_timeout", "$", "Filter matching exceeded its deadline")
		}
		if ctx.Err() != nil {
			return filterMatchError("cancelled", "$", "Filter matching was cancelled")
		}
		return nil
	}
	compiled := []*regexp2.Regexp{}
	for _, pattern := range args.Patterns {
		for _, part := range strings.Split(pattern, "`") {
			if err := check(); err != nil {
				return nil, err
			}
			re, err := regexp2.Compile(part, regexp2.None)
			if err != nil {
				return nil, filterMatchError("bad_argument", "$.patterns", "Invalid regexp2 filter pattern")
			}
			compiled = append(compiled, re)
		}
	}
	indices := []int{}
	if len(args.Patterns) == 0 {
		for i := range args.Names {
			if err := check(); err != nil {
				return nil, err
			}
			indices = append(indices, i)
		}
		return indices, nil
	}
	seen := map[string]bool{}
	for _, re := range compiled {
		for i, name := range args.Names {
			if err := check(); err != nil {
				return nil, err
			}
			if seen[name] {
				continue
			}
			remaining := time.Duration(args.TimeoutMS) * time.Millisecond
			if deadline, ok := ctx.Deadline(); ok {
				remaining = time.Until(deadline)
			}
			if remaining <= 0 {
				return nil, filterMatchError("filter_timeout", "$", "Filter matching exceeded its deadline")
			}
			re.MatchTimeout = remaining
			matched, err := re.MatchString(name)
			if failure := check(); failure != nil {
				return nil, failure
			}
			if err != nil {
				return nil, filterMatchError("filter_timeout", "$.patterns", "Regexp2 matching exceeded its remaining batch deadline")
			}
			if matched {
				seen[name] = true
				indices = append(indices, i)
			}
		}
	}
	return indices, nil
}

// Splits and compiles each value as adapter/outboundgroup reads a filter.
func handleValidateFilters(filters []string) []string {
	results := make([]string, len(filters))
	for i, filter := range filters {
		for _, part := range strings.Split(filter, "`") {
			if _, err := regexp2.Compile(part, regexp2.None); err != nil {
				results[i] = err.Error()
				break
			}
		}
	}
	return results
}
