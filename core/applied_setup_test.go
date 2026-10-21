package core

import (
	"encoding/json"
	"errors"
	"testing"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
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

const refreshedTestURL = "https://example.test/generate_204"

func withRefreshableSetup(t *testing.T, record json.RawMessage) {
	t.Helper()
	withCurrentConfig(t, &config.Config{General: &config.General{}})
	withAppliedSetup(t, record)
	withSkipCertVerify(t, false)
	previousDefault, previousStored := constant.DefaultTestURL, testURL.Load()
	t.Cleanup(func() {
		constant.DefaultTestURL = previousDefault
		testURL.Store(previousStored)
	})
}

func refreshParams(record string) *SetupParams {
	params := paramsWithRecord(record)
	params.TestURL = refreshedTestURL
	params.SkipCertVerify = true
	return params
}

func TestRefreshAppliedSetupTakesANewerRecordOfTheSameConfig(t *testing.T) {
	withRefreshableSetup(t, json.RawMessage(`{"digest":"old","yaml-md5":"m"}`))

	if !handleRefreshAppliedSetup(refreshParams(`{"digest":"new","yaml-md5":"m"}`)) {
		t.Fatal("refresh = false, want the record of the config the core runs replaced")
	}

	if string(appliedSetup) != `{"digest":"new","yaml-md5":"m"}` {
		t.Errorf("appliedSetup = %s, want the newer record", appliedSetup)
	}
	if got := currentTestURL(); got != refreshedTestURL {
		t.Errorf("currentTestURL = %q, want the test URL the newer record covers", got)
	}
	if !skipCertVerify.Load() {
		t.Error("skipCertVerify stayed off, so the record would describe a value the core does not run")
	}
}

// Another engine may have pushed a different YAML since this host read the record.
func TestRefreshAppliedSetupLeavesTheRecordOfAnotherConfig(t *testing.T) {
	withRefreshableSetup(t, json.RawMessage(`{"digest":"old","yaml-md5":"m"}`))

	if handleRefreshAppliedSetup(refreshParams(`{"digest":"new","yaml-md5":"other"}`)) {
		t.Fatal("refresh = true for a YAML the core does not run")
	}

	if string(appliedSetup) != `{"digest":"old","yaml-md5":"m"}` {
		t.Errorf("appliedSetup = %s, want the record left alone", appliedSetup)
	}
	if skipCertVerify.Load() {
		t.Error("a refused refresh still changed skipCertVerify")
	}
}

func TestRefreshAppliedSetupNeedsARecordAndAConfig(t *testing.T) {
	for name, setup := range map[string]func(t *testing.T){
		"no record": func(t *testing.T) {
			withRefreshableSetup(t, nil)
		},
		"a null record": func(t *testing.T) {
			withRefreshableSetup(t, json.RawMessage(`null`))
		},
		"no config": func(t *testing.T) {
			withRefreshableSetup(t, json.RawMessage(`{"digest":"old","yaml-md5":"m"}`))
			withCurrentConfig(t, nil)
		},
	} {
		t.Run(name, func(t *testing.T) {
			setup(t)
			before := string(appliedSetup)

			if handleRefreshAppliedSetup(refreshParams(`{"digest":"new","yaml-md5":"m"}`)) {
				t.Fatal("refresh = true with nothing the host applied to refresh")
			}
			if string(appliedSetup) != before {
				t.Errorf("appliedSetup = %s, want %s", appliedSetup, before)
			}
		})
	}
}

func TestRefreshAppliedSetupDecodesTheHostParams(t *testing.T) {
	withRefreshableSetup(t, json.RawMessage(`{"digest":"old","yaml-md5":"m"}`))
	arguments := `{"selected-map":{},"test-url":"` + refreshedTestURL +
		`","skip-cert-verify":true,"applied":{"digest":"new","yaml-md5":"m"}}`

	frame := handleMethodCall(&MethodCall{
		ID:        "1",
		Method:    refreshAppliedSetupMethod,
		Arguments: json.RawMessage(arguments),
	})

	var envelope struct {
		Result bool         `json:"result"`
		Error  *MethodError `json:"error"`
	}
	if err := json.Unmarshal(frame, &envelope); err != nil {
		t.Fatalf("response is not valid JSON: %v", err)
	}
	if envelope.Error != nil || !envelope.Result {
		t.Fatalf("result = %t, error = %+v, want the refresh taken", envelope.Result, envelope.Error)
	}
	if string(appliedSetup) != `{"digest":"new","yaml-md5":"m"}` {
		t.Errorf("appliedSetup = %s, want the record verbatim", appliedSetup)
	}
}
