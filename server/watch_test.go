package main

import (
	"encoding/json"
	"testing"
)

func TestReportPayloadWatchRoundTrip(t *testing.T) {
	sed := true
	raw := []byte(`{
		"battery_level": 66,
		"watch": {
			"connected": true,
			"name": "HryFine W1",
			"battery": 82,
			"firmware": "1.2.3",
			"steps": 5820,
			"distance_m": 3860,
			"kcal": 215,
			"heart_rate": 72,
			"blood_pressure": "118/76",
			"spo2": 98,
			"sleep_min": 440,
			"sedentary_on": true,
			"updated_at": 1700000000000
		}
	}`)
	var p ReportPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if p.Watch == nil {
		t.Fatal("watch is nil")
	}
	w := p.Watch
	if !w.Connected || w.Battery != 82 || w.Steps != 5820 || w.DistanceM != 3860 || w.Kcal != 215 {
		t.Fatalf("unexpected watch values: %+v", w)
	}
	if w.HeartRate != 72 || w.BloodPressure != "118/76" || w.SpO2 != 98 || w.SleepMin != 440 {
		t.Fatalf("unexpected vitals: %+v", w)
	}
	if w.SedentaryOn == nil || *w.SedentaryOn != sed || w.RaiseWristOn != nil {
		t.Fatalf("unexpected toggles: %+v", w)
	}

	out, err := json.Marshal(p)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var back ReportPayload
	if err := json.Unmarshal(out, &back); err != nil {
		t.Fatalf("re-unmarshal: %v", err)
	}
	if back.Watch == nil || back.Watch.Name != "HryFine W1" || back.Watch.UpdatedAt != 1700000000000 {
		t.Fatalf("round trip lost watch: %+v", back.Watch)
	}
}

func TestReportPayloadWithoutWatchOmitsField(t *testing.T) {
	p := ReportPayload{BatteryLevel: 50}
	out, err := json.Marshal(p)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(out, &m); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if _, ok := m["watch"]; ok {
		t.Fatalf("empty watch should be omitted: %s", out)
	}
}

func TestReportPayloadGlucoseRoundTrip(t *testing.T) {
	raw := []byte(`{
		"battery_level": 42,
		"glucose": {"value": 5.6, "unit": "mmol/L", "status": 2, "measured_at": 1700000000000}
	}`)
	var p ReportPayload
	if err := json.Unmarshal(raw, &p); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if p.Glucose == nil {
		t.Fatal("glucose is nil")
	}
	if p.Glucose.Value != 5.6 || p.Glucose.Status != 2 || p.Glucose.MeasuredAt != 1700000000000 {
		t.Fatalf("unexpected glucose: %+v", p.Glucose)
	}
	out, err := json.Marshal(p)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var back ReportPayload
	if err := json.Unmarshal(out, &back); err != nil {
		t.Fatalf("re-unmarshal: %v", err)
	}
	if back.Glucose == nil || back.Glucose.Value != 5.6 {
		t.Fatalf("round trip lost glucose: %+v", back.Glucose)
	}
}

func TestReportPayloadWithoutGlucoseOmitsField(t *testing.T) {
	out, err := json.Marshal(ReportPayload{BatteryLevel: 50})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(out, &m); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if _, ok := m["glucose"]; ok {
		t.Fatalf("empty glucose should be omitted: %s", out)
	}
}
