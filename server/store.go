package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

func timeNowUnixNano() int64 { return time.Now().UnixNano() }
func nowMs() int64           { return time.Now().UnixMilli() }

// ReportPayload is the minimal device status snapshot. Fields are collected
// automatically by the Android app; nothing beyond what the feature needs is
// ever uploaded (no user input, no screen text, no clipboard).
type ReportPayload struct {
	ForegroundPackage string `json:"foreground_package,omitempty"`
	ForegroundApp     string `json:"foreground_app,omitempty"`
	ScreenOn          bool   `json:"screen_on"`
	BatteryLevel      int    `json:"battery_level"`  // 0-100, -1 unknown
	BatteryStatus     string `json:"battery_status"` // charging|full|discharging|unknown
	Charging          bool   `json:"charging"`
	ChargingSource    string `json:"charging_source"` // ac|usb|wireless|none
	NetworkType       string `json:"network_type"`    // wifi|mobile|none
	NetworkDetail     string `json:"network_detail,omitempty"`
	DeviceModel       string `json:"device_model,omitempty"`
	AppVersion        string `json:"app_version,omitempty"`
}

// Report is one status submission.
type Report struct {
	Timestamp int64         `json:"timestamp"` // ms
	RequestID string        `json:"request_id,omitempty"`
	Payload   ReportPayload `json:"payload"`
}

type NameChange struct {
	Name      string `json:"name"`
	ChangedAt int64  `json:"changed_at"`
}

type HistoryEntry struct {
	At      int64  `json:"at"`
	App     string `json:"app"`
	Battery int    `json:"battery"`
}

// Device is the persistent per-device record.
type Device struct {
	DeviceID      string         `json:"device_id"`
	DeviceName    string         `json:"device_name"`
	Model         string         `json:"model,omitempty"`
	FirstSeen     int64          `json:"first_seen"`
	LastHeartbeat int64          `json:"last_heartbeat"`
	LastReportAt  int64          `json:"last_report_at"`
	LastReport    *Report        `json:"last_report,omitempty"`
	NameHistory   []NameChange   `json:"name_history,omitempty"`
	ReportCount   int64          `json:"report_count"`
	FreshCount    int64          `json:"fresh_count"`
	Recent        []HistoryEntry `json:"recent,omitempty"` // capped ring of recent reports
}

// DeviceView is what the API returns: persistent record + live online flag.
type DeviceView struct {
	Device
	Online bool `json:"online"`
}

// Store is an embedded JSON-file backed device table. It satisfies the
// "SQLite or embedded KV" requirement with zero external dependencies: the
// whole table lives in memory behind a RWMutex and is flushed atomically to
// data/devices.json (tmp file + rename) when dirty.
type Store struct {
	mu       sync.RWMutex
	cfg      *Config
	file     string
	devices  map[string]*Device
	dirty    bool
	closed   bool
	stopOnce sync.Once
	stopCh   chan struct{}
}

const maxRecent = 50

func OpenStore(cfg *Config) (*Store, error) {
	s := &Store{
		cfg:     cfg,
		file:    filepath.Join(cfg.DataDir, "devices.json"),
		devices: map[string]*Device{},
		stopCh:  make(chan struct{}),
	}
	if err := os.MkdirAll(cfg.DataDir, 0750); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(s.file)
	if err == nil {
		if err := json.Unmarshal(data, &s.devices); err != nil {
			// keep a backup of the corrupted file, start fresh
			backup := s.file + ".corrupt." + fmt.Sprint(timeNowUnixNano())
			_ = os.Rename(s.file, backup)
			log.Printf("[store] 设备表损坏，已备份到 %s 并重建", backup)
			s.devices = map[string]*Device{}
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	n := len(s.devices)
	if n > 0 {
		log.Printf("[store] 已加载 %d 台设备记录", n)
	}
	s.pruneExpired()
	return s, nil
}

// ---- device operations ----

// UpsertFromRegister creates or refreshes a device on registration.
func (s *Store) UpsertFromRegister(deviceID, deviceName, model string) *Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.devices[deviceID]
	if d == nil {
		d = &Device{
			DeviceID:  deviceID,
			FirstSeen: nowMs(),
		}
		s.devices[deviceID] = d
	}
	if deviceName != "" && deviceName != d.DeviceName {
		if d.DeviceName != "" {
			d.NameHistory = append(d.NameHistory, NameChange{Name: d.DeviceName, ChangedAt: nowMs()})
			if len(d.NameHistory) > 20 {
				d.NameHistory = d.NameHistory[len(d.NameHistory)-20:]
			}
		}
		d.DeviceName = deviceName
	}
	if model != "" {
		d.Model = model
	}
	d.LastHeartbeat = nowMs()
	s.dirty = true
	return cloneDevice(d)
}

// ApplyReport stores a report, tracking name changes and request-matched
// fresh reports. It returns the updated device.
func (s *Store) ApplyReport(deviceID, deviceName string, rep *Report) *Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := s.devices[deviceID]
	if d == nil {
		d = &Device{DeviceID: deviceID, FirstSeen: nowMs()}
		s.devices[deviceID] = d
	}
	if deviceName != "" && deviceName != d.DeviceName {
		if d.DeviceName != "" {
			d.NameHistory = append(d.NameHistory, NameChange{Name: d.DeviceName, ChangedAt: nowMs()})
			if len(d.NameHistory) > 20 {
				d.NameHistory = d.NameHistory[len(d.NameHistory)-20:]
			}
		}
		d.DeviceName = deviceName
	}
	if rep.Payload.DeviceModel != "" {
		d.Model = rep.Payload.DeviceModel
	}
	d.LastReportAt = rep.Timestamp
	if d.LastReportAt == 0 {
		d.LastReportAt = nowMs()
	}
	d.LastReport = rep
	d.ReportCount++
	if rep.RequestID != "" {
		d.FreshCount++
	}
	d.Recent = append(d.Recent, HistoryEntry{At: rep.Timestamp, App: rep.Payload.ForegroundApp, Battery: rep.Payload.BatteryLevel})
	if len(d.Recent) > maxRecent {
		d.Recent = d.Recent[len(d.Recent)-maxRecent:]
	}
	d.LastHeartbeat = nowMs()
	s.dirty = true
	return cloneDevice(d)
}

func (s *Store) TouchHeartbeat(deviceID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if d := s.devices[deviceID]; d != nil {
		d.LastHeartbeat = nowMs()
		s.dirty = true
	}
}

// GetDeviceByID returns a copy of the device record (nil when absent).
func (s *Store) GetDeviceByID(deviceID string) *Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if d := s.devices[deviceID]; d != nil {
		return cloneDevice(d)
	}
	return nil
}

// GetDevice resolves a device by id or name (exact id > exact name >
// case-insensitive > unique prefix). Returns the device view plus a hint when
// the query was ambiguous.
func (s *Store) GetDevice(idOrName string) (*DeviceView, string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	q := strings.TrimSpace(idOrName)
	if q == "" {
		return nil, "", fmt.Errorf("设备标识为空")
	}
	if d, ok := s.devices[q]; ok {
		return s.viewLocked(d), "", nil
	}
	if d := s.findByNameLocked(q, false); d != nil {
		return s.viewLocked(d), "", nil
	}
	if d := s.findByNameLocked(q, true); d != nil {
		return s.viewLocked(d), "", nil
	}
	// prefix / substring matches
	var matches []*Device
	for _, d := range s.devices {
		if strings.Contains(strings.ToLower(d.DeviceName), strings.ToLower(q)) ||
			strings.HasPrefix(strings.ToLower(d.DeviceID), strings.ToLower(q)) {
			matches = append(matches, d)
		}
	}
	if len(matches) == 1 {
		return s.viewLocked(matches[0]), "", nil
	}
	if len(matches) > 1 {
		names := make([]string, 0, len(matches))
		for _, m := range matches {
			names = append(names, m.DeviceName)
		}
		sort.Strings(names)
		return nil, "", fmt.Errorf("匹配到多台设备：%s，请使用完整设备名", strings.Join(names, "、"))
	}
	return nil, "", fmt.Errorf("未找到设备 %q", q)
}

func (s *Store) findByNameLocked(q string, foldCase bool) *Device {
	var found *Device
	for _, d := range s.devices {
		if d.DeviceName == "" {
			continue
		}
		if foldCase {
			if strings.EqualFold(d.DeviceName, q) {
				if found != nil {
					return nil // ambiguous
				}
				found = d
			}
		} else if d.DeviceName == q {
			return d
		}
	}
	return found
}

// ListDevices returns all devices sorted by name.
func (s *Store) ListDevices() []*DeviceView {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]*DeviceView, 0, len(s.devices))
	for _, d := range s.devices {
		out = append(out, s.viewLocked(d))
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Online != out[j].Online {
			return out[i].Online
		}
		return out[i].DeviceName < out[j].DeviceName
	})
	return out
}

// The Online flag is left false here; the hub fills it in afterwards
// (FillOnline) to avoid cross-locking between store and hub.
func (s *Store) viewLocked(d *Device) *DeviceView {
	return &DeviceView{Device: *cloneDevice(d)}
}

func cloneDevice(d *Device) *Device {
	c := *d
	if d.LastReport != nil {
		r := *d.LastReport
		c.LastReport = &r
	}
	if d.NameHistory != nil {
		c.NameHistory = append([]NameChange(nil), d.NameHistory...)
	}
	if d.Recent != nil {
		c.Recent = append([]HistoryEntry(nil), d.Recent...)
	}
	return &c
}

// ---- persistence ----

func (s *Store) FlushLoop() {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-s.stopCh:
			s.flush()
			return
		case <-t.C:
			s.flush()
		}
	}
}

func (s *Store) flush() {
	s.mu.Lock()
	if !s.dirty || s.closed {
		s.mu.Unlock()
		return
	}
	s.dirty = false
	data, err := json.MarshalIndent(s.devices, "", " ")
	s.mu.Unlock()
	if err != nil {
		log.Printf("[store] 序列化失败: %v", err)
		return
	}
	tmp := s.file + ".tmp"
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		log.Printf("[store] 写入失败: %v", err)
		return
	}
	if err := os.Rename(tmp, s.file); err != nil {
		log.Printf("[store] 原子替换失败: %v", err)
	}
}

func (s *Store) PruneLoop() {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-t.C:
			s.pruneExpired()
		}
	}
}

// pruneExpired removes devices not seen for retention days (data retention).
func (s *Store) pruneExpired() {
	s.mu.Lock()
	defer s.mu.Unlock()
	cutoff := nowMs() - int64(s.cfg.RetentionDays)*24*3600*1000
	removed := 0
	for id, d := range s.devices {
		last := d.LastHeartbeat
		if d.LastReportAt > last {
			last = d.LastReportAt
		}
		if last > 0 && last < cutoff {
			delete(s.devices, id)
			removed++
		}
	}
	if removed > 0 {
		s.dirty = true
		log.Printf("[store] 已按保留策略清理 %d 台超过 %d 天未上报的设备", removed, s.cfg.RetentionDays)
	}
}

func (s *Store) Close() {
	s.stopOnce.Do(func() { close(s.stopCh) })
	s.mu.Lock()
	s.closed = true
	s.dirty = true
	s.mu.Unlock()
	s.flush()
}
