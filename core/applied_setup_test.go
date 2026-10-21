package core

import (
	"encoding/json"
	"errors"
	"testing"
)

func withAppliedSetup(t *testing.T, record json.RawMessage) {
	t.Helper()
	previous := appliedSetup
	appliedSetup = record
	t.Cleanup(func() { appliedSetup = previous })
}

func withApplyConfigLocked(t *testing.T, apply func(*SetupParams) error) {
	t.Helper()
	previous := applyConfigLocked
	applyConfigLocked = apply
	t.Cleanup(func() { applyConfigLocked = previous })
}

func withLifecycleFlags(t *testing.T, initialized, running bool) {
	t.Helper()
	previousInit, previousRunning := isInit.Load(), isRunning.Load()
	isInit.Store(initialized)
	isRunning.Store(running)
	t.Cleanup(func() {
		isInit.Store(previousInit)
		isRunning.Store(previousRunning)
	})
}

func paramsWithRecord(record string) *SetupParams {
	params := defaultSetupParams()
	params.Applied = json.RawMessage(record)
	return params
}

func TestApplyConfigKeepsTheRecordOfAConfigThatApplied(t *testing.T) {
	withAppliedSetup(t, nil)
	withApplyConfigLocked(t, func(*SetupParams) error { return nil })

	if err := applyConfig(paramsWithRecord(`{"digest":"d","yaml-md5":"m"}`)); err != nil {
		t.Fatalf("applyConfig = %v, want no error", err)
	}

	if string(appliedSetup) != `{"digest":"d","yaml-md5":"m"}` {
		t.Errorf("appliedSetup = %s, want the record the host sent", appliedSetup)
	}
}

// A failed apply leaves the core on the built-in default, which no host built,
// so a host that came back would adopt a config it never asked for.
func TestApplyConfigDropsTheRecordWhenTheConfigFailsToApply(t *testing.T) {
	withAppliedSetup(t, json.RawMessage(`{"digest":"old"}`))
	withApplyConfigLocked(t, func(*SetupParams) error { return errors.New("bad profile") })

	if err := applyConfig(paramsWithRecord(`{"digest":"new"}`)); err == nil {
		t.Fatal("applyConfig = nil, want the apply error")
	}

	if appliedSetup != nil {
		t.Errorf("appliedSetup = %s after a failed apply, want none", appliedSetup)
	}
}

func TestApplyConfigWithoutARecordDropsTheOldOne(t *testing.T) {
	withAppliedSetup(t, json.RawMessage(`{"digest":"old"}`))
	withApplyConfigLocked(t, func(*SetupParams) error { return nil })

	if err := applyConfig(defaultSetupParams()); err != nil {
		t.Fatalf("applyConfig = %v, want no error", err)
	}

	if appliedSetup != nil {
		t.Errorf("appliedSetup = %s, want none once a setup without a record replaced the config", appliedSetup)
	}
}

func TestSetupParamsDecodeTheRecordAsItWasSent(t *testing.T) {
	var params SetupParams

	if err := json.Unmarshal([]byte(`{"applied":{"digest":"d","rules":3}}`), &params); err != nil {
		t.Fatalf("decode error: %v", err)
	}

	if string(params.Applied) != `{"digest":"d","rules":3}` {
		t.Errorf("Applied = %s, want the record verbatim", params.Applied)
	}
}

func TestGetAppliedSetupAnswersWithTheRecordAsStructuredJSON(t *testing.T) {
	withAppliedSetup(t, json.RawMessage(`{"digest":"d","rules":3}`))
	withLifecycleFlags(t, true, true)

	frame := handleMethodCall(&MethodCall{ID: "1", Method: getAppliedSetupMethod})

	var envelope struct {
		Result AppliedSetup `json:"result"`
		Error  *MethodError `json:"error"`
	}
	if err := json.Unmarshal(frame, &envelope); err != nil {
		t.Fatalf("response is not valid JSON: %v", err)
	}
	if envelope.Error != nil {
		t.Fatalf("error = %+v, want a result", envelope.Error)
	}
	if !envelope.Result.Init || !envelope.Result.Running {
		t.Errorf("init/running = %t/%t, want both reported", envelope.Result.Init, envelope.Result.Running)
	}
	if got := string(envelope.Result.Applied); got != `{"digest":"d","rules":3}` {
		t.Errorf("applied = %s, want the record as an object, not an encoded string", got)
	}
}

func TestGetAppliedSetupReportsAMissingRecordAsNull(t *testing.T) {
	withAppliedSetup(t, nil)
	withLifecycleFlags(t, true, false)

	frame := handleMethodCall(&MethodCall{ID: "1", Method: getAppliedSetupMethod})

	var envelope struct {
		Result map[string]json.RawMessage `json:"result"`
	}
	if err := json.Unmarshal(frame, &envelope); err != nil {
		t.Fatalf("response is not valid JSON: %v", err)
	}
	if got := string(envelope.Result["applied"]); got != "null" {
		t.Errorf("applied = %s, want null", got)
	}
	if got := string(envelope.Result["running"]); got != "false" {
		t.Errorf("running = %s, want false", got)
	}
}
