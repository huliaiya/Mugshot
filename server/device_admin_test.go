package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func newDeviceAdminAPI(t *testing.T) (*API, *Store, *Hub) {
	t.Helper()
	cfg, _ := newTestConfig(t)
	store, err := OpenStore(cfg)
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(store.Close)
	hub := NewHub(cfg, store)
	return NewAPI(cfg, store, hub), store, hub
}

func doDeviceAction(t *testing.T, api *API, body map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	raw, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/devices", bytes.NewReader(raw))
	req.Header.Set("Authorization", "Bearer test-unified-token")
	rec := httptest.NewRecorder()
	api.handleDevices(rec, req)
	return rec
}

func TestDeviceHideKeepsHistoryAndReappears(t *testing.T) {
	api, store, _ := newDeviceAdminAPI(t)
	store.UpsertFromRegister("dev-1", "客厅手机", "Pixel")

	if n := len(store.ListDevices()); n != 1 {
		t.Fatalf("visible devices = %d, want 1", n)
	}
	if rec := doDeviceAction(t, api, map[string]string{"device": "dev-1", "action": "hide"}); rec.Code != http.StatusOK {
		t.Fatalf("hide status = %d body=%s", rec.Code, rec.Body.String())
	}
	if n := len(store.ListDevices()); n != 0 {
		t.Fatalf("visible after hide = %d, want 0", n)
	}
	if n := len(store.ListAllDevices()); n != 1 {
		t.Fatalf("all after hide = %d, want 1 (history kept)", n)
	}
	// 设备再次上报后重新出现
	store.ApplyReport("dev-1", "客厅手机", &Report{Timestamp: nowMs(), Payload: ReportPayload{BatteryLevel: 50}})
	if n := len(store.ListDevices()); n != 1 {
		t.Fatalf("visible after new report = %d, want 1", n)
	}
}

func TestDeviceDeleteRemovesRecordAndHistory(t *testing.T) {
	api, store, _ := newDeviceAdminAPI(t)
	store.UpsertFromRegister("dev-2", "卧室手机", "Mi 14")
	store.ApplyReport("dev-2", "卧室手机", &Report{Timestamp: nowMs(), Payload: ReportPayload{BatteryLevel: 90}})

	if rec := doDeviceAction(t, api, map[string]string{"device": "卧室手机", "action": "delete"}); rec.Code != http.StatusOK {
		t.Fatalf("delete status = %d body=%s", rec.Code, rec.Body.String())
	}
	if n := len(store.ListAllDevices()); n != 0 {
		t.Fatalf("all after delete = %d, want 0", n)
	}
	if d := store.GetDeviceByID("dev-2"); d != nil {
		t.Fatalf("device still present after delete: %+v", d)
	}
}

func TestDeviceActionRejectsUnknown(t *testing.T) {
	api, store, _ := newDeviceAdminAPI(t)
	store.UpsertFromRegister("dev-3", "书房手机", "")
	if rec := doDeviceAction(t, api, map[string]string{"device": "dev-3", "action": "explode"}); rec.Code != http.StatusBadRequest {
		t.Fatalf("unknown action status = %d, want 400", rec.Code)
	}
	if rec := doDeviceAction(t, api, map[string]string{"device": "nope", "action": "hide"}); rec.Code != http.StatusNotFound {
		t.Fatalf("missing device status = %d, want 404", rec.Code)
	}
}
