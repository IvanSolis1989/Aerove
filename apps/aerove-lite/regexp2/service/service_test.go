package service

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"
)

func request(id uint64, include []string, names []string) Request {
	return Request{ID: id, Include: include, Names: names, DeadlineMS: 1000}
}
func TestOrderedPartsExcludeNulUnicode(t *testing.T) {
	e := New()
	r := request(1, []string{"^SG`^JP`^SG"}, []string{"JP\x0001", "SG😀", "日本", "US"})
	r.Exclude = []string{"01$"}
	got := e.Batch(context.Background(), r)
	if got.Status != "ok" || !reflect.DeepEqual(got.Indices, []int{1}) {
		t.Fatalf("%+v", got)
	}
	r = request(2, []string{"\\x00"}, []string{"JP\x0001", "SG😀"})
	got = e.Batch(context.Background(), r)
	if !reflect.DeepEqual(got.Indices, []int{0}) {
		t.Fatalf("NUL lost: %+v", got)
	}
}
func TestSameEngineSubtractionAndCaptureNumbers(t *testing.T) {
	e := New()
	got := e.Batch(context.Background(), request(1, []string{"^[a-z-[aeiou]]$"}, []string{"a", "b", "-"}))
	if !reflect.DeepEqual(got.Indices, []int{1}) {
		t.Fatal(got)
	}
	got = e.Batch(context.Background(), request(2, []string{`^(?<N>a)(b)\1$`}, []string{"abb", "aba"}))
	if !reflect.DeepEqual(got.Indices, []int{0}) {
		t.Fatal(got)
	}
}
func TestInvalidNeverReturnsPartialMatches(t *testing.T) {
	got := New().Batch(context.Background(), request(1, []string{"JP", "["}, []string{"JP"}))
	if got.Status != "invalid" || len(got.Indices) != 0 || got.Error.Path != "$.include[1].parts[0]" {
		t.Fatalf("%+v", got)
	}
}
func TestCancellationInterruptsActualBacktracking(t *testing.T) {
	e := New()
	ch := make(chan Response, 1)
	r := request(11, []string{"(a+)+$"}, []string{strings.Repeat("a", 20000) + "!"})
	go func() { ch <- e.Batch(context.Background(), r) }()
	time.Sleep(20 * time.Millisecond)
	started := time.Now()
	e.Cancel(11)
	select {
	case got := <-ch:
		if got.Status != "cancelled" || len(got.Indices) != 0 {
			t.Fatalf("%+v", got)
		}
		t.Logf("cancel-to-return=%s", time.Since(started))
	case <-time.After(time.Second):
		t.Fatal("cancellation did not interrupt matcher")
	}
}
func TestDeadlineIsExplicitErrorAndCreatesNoClockWorker(t *testing.T) {
	e := New()
	before := runtime.NumGoroutine()
	r := request(1, []string{"(a+)+$"}, []string{strings.Repeat("a", 20000) + "!"})
	r.DeadlineMS = 10
	got := e.Batch(context.Background(), r)
	if got.Status != "deadline" || len(got.Indices) != 0 {
		t.Fatalf("%+v", got)
	}
	time.Sleep(20 * time.Millisecond)
	if after := runtime.NumGoroutine(); after > before {
		t.Fatalf("background goroutine count grew %d→%d", before, after)
	}
}
func TestCancelBeforeBatchAndCacheBound(t *testing.T) {
	e := New()
	e.Cancel(5)
	if got := e.Batch(context.Background(), request(5, []string{"JP"}, []string{"JP"})); got.Status != "cancelled" {
		t.Fatal(got)
	}
	patterns := make([]string, 100)
	for i := range patterns {
		patterns[i] = strings.Repeat("x", i+1)
	}
	got := e.Batch(context.Background(), request(6, patterns, []string{"x"}))
	if got.Status != "ok" || got.Stats.CacheEntries > CacheEntries || got.Stats.CacheBytes > CachePatternBytes || got.Stats.Evictions == 0 {
		t.Fatalf("%+v", got)
	}
	got = e.Batch(context.Background(), request(7, []string{patterns[99]}, []string{"x"}))
	if got.Stats.Hits != 1 {
		t.Fatal(got)
	}
}
func TestStrictUtf8AndRequestBounds(t *testing.T) {
	if _, err := Decode([]byte{0xff}); err == nil {
		t.Fatal("invalid UTF-8 accepted")
	}
	if _, err := Decode([]byte(`{"id":1,"deadlineMs":0}`)); err == nil {
		t.Fatal("unbounded deadline accepted")
	}
	if _, err := Decode([]byte(`{"id":1,"deadlineMs":10,"names":["\ud800"]}`)); err == nil {
		t.Fatal("unpaired surrogate accepted")
	}
	if _, err := Decode([]byte(`{"id":1,"deadlineMs":10,"names":["\ud83d\ude00","\u0000"]}`)); err != nil {
		t.Fatal(err)
	}
}
func TestAssessmentCorpusRemainsExact(t *testing.T) {
	input, err := os.ReadFile("../../regexp2-assessment/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	expectedBytes, err := os.ReadFile("../../regexp2-assessment/regexp2-v1.12.0.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name, Pattern string
		Samples       []string
	}
	if err = json.Unmarshal(input, &cases); err != nil {
		t.Fatal(err)
	}
	var expected []struct {
		Name    string
		Matches map[string]bool
	}
	if err = json.Unmarshal(expectedBytes, &expected); err != nil {
		t.Fatal(err)
	}
	e := New()
	for i, c := range cases {
		got := e.Batch(context.Background(), request(uint64(i+1), []string{c.Pattern}, c.Samples))
		if got.Status != "ok" {
			t.Fatalf("%s: %+v", c.Name, got)
		}
		matches := map[int]bool{}
		for _, index := range got.Indices {
			matches[index] = true
		}
		for j, s := range c.Samples {
			if matches[j] != expected[i].Matches[s] {
				t.Fatalf("%s/%q diverged", c.Name, s)
			}
		}
	}
}
func TestValidateOnlyDoesNotPublishLateCompileSuccess(t *testing.T) {
	r := request(1, []string{strings.Repeat("a?", 50000)}, nil)
	r.ValidateOnly = true
	r.DeadlineMS = 1
	got := New().Batch(context.Background(), r)
	if got.Status != "deadline" {
		t.Fatalf("late compile reported %+v", got)
	}
}
