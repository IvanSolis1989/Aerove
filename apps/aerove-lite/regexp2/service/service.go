package service

import (
	"container/list"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/dlclark/regexp2"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const MaxBytes = 4 * 1024 * 1024
const CacheEntries = 64
const CachePatternBytes = 256 * 1024
const Version = "regexp2-v1.12.0+aerove-cancel1/None"

type Request struct {
	ID           uint64   `json:"id"`
	Include      []string `json:"include"`
	Exclude      []string `json:"exclude"`
	Names        []string `json:"names"`
	DeadlineMS   int      `json:"deadlineMs"`
	ValidateOnly bool     `json:"validateOnly"`
}
type Failure struct {
	Path    string `json:"path"`
	Kind    string `json:"kind"`
	Message string `json:"message"`
}
type Stats struct {
	Hits         int   `json:"hits"`
	Misses       int   `json:"misses"`
	Evictions    int   `json:"evictions"`
	CacheEntries int   `json:"cacheEntries"`
	CacheBytes   int   `json:"cacheBytes"`
	ElapsedNS    int64 `json:"elapsedNs"`
}
type Response struct {
	ID      uint64   `json:"id"`
	Status  string   `json:"status"`
	Indices []int    `json:"indices,omitempty"`
	Error   *Failure `json:"error,omitempty"`
	Stats   Stats    `json:"stats"`
	Engine  string   `json:"engine"`
}
type cacheEntry struct {
	key   string
	regex *regexp2.Regexp
}
type Engine struct {
	mu         sync.Mutex
	cache      map[string]*list.Element
	order      *list.List
	cacheBytes int
	registryMu sync.Mutex
	active     map[uint64]context.CancelFunc
	cancelled  map[uint64]time.Time
}

func New() *Engine {
	return &Engine{cache: map[string]*list.Element{}, order: list.New(), active: map[uint64]context.CancelFunc{}, cancelled: map[uint64]time.Time{}}
}

// Cancel handles requests arriving before or during a batch without an idle timer.
func (e *Engine) Cancel(id uint64) bool {
	e.registryMu.Lock()
	defer e.registryMu.Unlock()
	if cancel, ok := e.active[id]; ok {
		cancel()
		return true
	}
	now := time.Now()
	for key, t := range e.cancelled {
		if now.Sub(t) > 30*time.Second {
			delete(e.cancelled, key)
		}
	}
	if len(e.cancelled) >= 256 {
		var oldest uint64
		var at time.Time
		for key, t := range e.cancelled {
			if at.IsZero() || t.Before(at) {
				oldest = key
				at = t
			}
		}
		delete(e.cancelled, oldest)
	}
	e.cancelled[id] = now
	return false
}

func failed(id uint64, path, kind, message string) Response {
	return Response{ID: id, Status: kind, Error: &Failure{path, kind, message}, Engine: Version}
}
func Decode(input []byte) (Request, error) {
	if len(input) > MaxBytes {
		return Request{}, errors.New("request exceeds 4 MiB")
	}
	if !utf8.Valid(input) {
		return Request{}, errors.New("request is not valid UTF-8")
	}
	if err := checkSurrogates(input); err != nil {
		return Request{}, err
	}
	var request Request
	if err := json.Unmarshal(input, &request); err != nil {
		return Request{}, err
	}
	if request.ID == 0 {
		return Request{}, errors.New("request id must be positive")
	}
	if request.DeadlineMS < 1 || request.DeadlineMS > 10000 {
		return Request{}, errors.New("deadlineMs must be 1..10000")
	}
	return request, nil
}
func checkSurrogates(input []byte) error {
	inString := false
	for i := 0; i < len(input); i++ {
		if input[i] == '"' {
			inString = !inString
			continue
		}
		if !inString || input[i] != '\\' {
			continue
		}
		if i+1 >= len(input) {
			break
		}
		if input[i+1] != 'u' {
			i++
			continue
		}
		if i+5 >= len(input) {
			return errors.New("truncated Unicode escape")
		}
		code, err := strconv.ParseUint(string(input[i+2:i+6]), 16, 16)
		if err != nil {
			return err
		}
		if code >= 0xd800 && code <= 0xdbff {
			if i+11 >= len(input) || input[i+6] != '\\' || input[i+7] != 'u' {
				return errors.New("unpaired high surrogate")
			}
			low, err := strconv.ParseUint(string(input[i+8:i+12]), 16, 16)
			if err != nil || low < 0xdc00 || low > 0xdfff {
				return errors.New("unpaired high surrogate")
			}
			i += 11
		} else if code >= 0xdc00 && code <= 0xdfff {
			return errors.New("unpaired low surrogate")
		} else {
			i += 5
		}
	}
	return nil
}
func (e *Engine) Batch(parent context.Context, request Request) (response Response) {
	started := time.Now()
	stats := Stats{}
	defer func() { response.Stats = stats; response.Stats.ElapsedNS = time.Since(started).Nanoseconds() }()
	ctx, cancel := context.WithTimeout(parent, time.Duration(request.DeadlineMS)*time.Millisecond)
	e.registryMu.Lock()
	if _, duplicate := e.active[request.ID]; duplicate {
		e.registryMu.Unlock()
		cancel()
		return failed(request.ID, "$.id", "duplicate", "request id already active")
	}
	e.active[request.ID] = cancel
	if _, wasCancelled := e.cancelled[request.ID]; wasCancelled {
		cancel()
		delete(e.cancelled, request.ID)
	}
	e.registryMu.Unlock()
	defer func() { cancel(); e.registryMu.Lock(); delete(e.active, request.ID); e.registryMu.Unlock() }()
	e.mu.Lock()
	defer e.mu.Unlock()
	defer func() { stats.CacheEntries = len(e.cache); stats.CacheBytes = e.cacheBytes }()
	failContext := func(path string, err error) Response {
		kind := "failed"
		if errors.Is(err, context.Canceled) {
			kind = "cancelled"
		}
		if errors.Is(err, context.DeadlineExceeded) {
			kind = "deadline"
		}
		return failed(request.ID, path, kind, err.Error())
	}
	if err := ctx.Err(); err != nil {
		return failContext("$", err)
	}
	compile := func(values []string, path string) ([]*regexp2.Regexp, *Response) {
		var regexes []*regexp2.Regexp
		for i, value := range values {
			for partIndex, part := range strings.Split(value, "`") {
				if err := ctx.Err(); err != nil {
					r := failContext(path, err)
					return nil, &r
				}
				var re *regexp2.Regexp
				if item, ok := e.cache[part]; ok {
					e.order.MoveToFront(item)
					re = item.Value.(cacheEntry).regex
					stats.Hits++
				} else {
					stats.Misses++
					var err error
					re, err = regexp2.Compile(part, regexp2.None)
					if err != nil {
						r := failed(request.ID, fmt.Sprintf("%s[%d].parts[%d]", path, i, partIndex), "invalid", err.Error())
						return nil, &r
					}
					if len(part) <= CachePatternBytes {
						for len(e.cache) >= CacheEntries || e.cacheBytes+len(part) > CachePatternBytes {
							last := e.order.Back()
							entry := last.Value.(cacheEntry)
							delete(e.cache, entry.key)
							e.order.Remove(last)
							e.cacheBytes -= len(entry.key)
							stats.Evictions++
						}
						e.cache[part] = e.order.PushFront(cacheEntry{part, re})
						e.cacheBytes += len(part)
					}
				}
				regexes = append(regexes, re)
			}
		}
		return regexes, nil
	}
	include, failure := compile(request.Include, "$.include")
	if failure != nil {
		return *failure
	}
	exclude, failure := compile(request.Exclude, "$.exclude")
	if failure != nil {
		return *failure
	}
	if err := ctx.Err(); err != nil {
		return failContext("$", err)
	}
	all := append(append([]*regexp2.Regexp{}, include...), exclude...)
	for _, re := range all {
		re.CancelCheck = ctx.Err
	}
	defer func() {
		for _, re := range all {
			re.CancelCheck = nil
		}
	}()
	if request.ValidateOnly {
		return Response{ID: request.ID, Status: "ok", Engine: Version}
	}
	matched := make([]bool, len(request.Names))
	indices := make([]int, 0, len(request.Names))
	seenNames := map[string]bool{}
	try := func(re *regexp2.Regexp, index int) (bool, *Response) {
		ok, err := re.MatchString(request.Names[index])
		if err != nil {
			r := failContext(fmt.Sprintf("$.names[%d]", index), err)
			return false, &r
		}
		return ok, nil
	}
	if len(include) == 0 {
		for i := range request.Names {
			indices = append(indices, i)
			matched[i] = true
		}
	} else {
		for _, re := range include {
			for i := range request.Names {
				if matched[i] || seenNames[request.Names[i]] {
					continue
				}
				ok, failure := try(re, i)
				if failure != nil {
					return *failure
				}
				if ok {
					indices = append(indices, i)
					matched[i] = true
					seenNames[request.Names[i]] = true
				}
			}
		}
	}
	output := make([]int, 0, len(indices))
	for _, index := range indices {
		accepted := true
		for _, re := range exclude {
			ok, failure := try(re, index)
			if failure != nil {
				return *failure
			}
			if ok {
				accepted = false
				break
			}
		}
		if accepted {
			output = append(output, index)
		}
	}
	if err := ctx.Err(); err != nil {
		return failContext("$", err)
	}
	return Response{ID: request.ID, Status: "ok", Indices: output, Engine: Version}
}
