package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// AiPermissionPolicy mirrors the user's modular AI capability switches stored at ~/.lytsocoder/ai_permissions.json.
type AiPermissionPolicy struct {
	AllowRoot      bool `json:"allowRoot"`
	AllowAllFiles  bool `json:"allowAllFiles"`
	AllowShellExec bool `json:"allowShellExec"`
	AllowFileWrite bool `json:"allowFileWrite"`
	AllowNetwork   bool `json:"allowNetwork"`
}

func defaultAiPermissionPolicy() AiPermissionPolicy {
	return AiPermissionPolicy{
		AllowRoot:      false,
		AllowAllFiles:  false,
		AllowShellExec: true,
		AllowFileWrite: true,
		AllowNetwork:   true,
	}
}

func aiPermissionsFilePath() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return ""
	}
	return filepath.Join(home, ".lytsocoder", "ai_permissions.json")
}

// LoadAiPermissionPolicy reads ~/.lytsocoder/ai_permissions.json, falling back to safe defaults (AllowRoot=false).
func LoadAiPermissionPolicy() AiPermissionPolicy {
	p := defaultAiPermissionPolicy()
	path := aiPermissionsFilePath()
	if path == "" {
		return p
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return p
	}
	_ = json.Unmarshal(raw, &p)
	return p
}

func saveAiPermissionPolicy(p AiPermissionPolicy) error {
	path := aiPermissionsFilePath()
	if path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

// DetectRealRootAccess probes actual su binaries outside ai_bin_guard to verify whether this app has granted root.
func DetectRealRootAccess() (binaryFound bool, rootGranted bool, suPath string) {
	candidates := []string{
		"/system/bin/su",
		"/system/xbin/su",
		"/sbin/su",
		"/su/bin/su",
		"/debug_ramdisk/su",
		"/data/adb/ksu/bin/su",
		"/data/adb/ap/bin/su",
		"/data/adb/magisk/su",
	}
	for _, c := range candidates {
		if info, err := os.Stat(c); err == nil && !info.IsDir() {
			binaryFound = true
			suPath = c
			break
		}
	}
	if !binaryFound {
		return false, false, ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, suPath, "-c", "id").CombinedOutput()
	if err == nil && strings.Contains(string(out), "uid=0") {
		return true, true, suPath
	}
	return true, false, suPath
}

// AiPermissionsHandler serves GET/POST /api/ai/permissions for modular AI permission and real root access control.
func (h *Handler) AiPermissionsHandler(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		policy := LoadAiPermissionPolicy()
		binFound, granted, suPath := DetectRealRootAccess()
		if policy.AllowRoot && !granted {
			policy.AllowRoot = false
			_ = saveAiPermissionPolicy(policy)
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"policy":      policy,
			"rootBinary":  binFound,
			"rootGranted": granted,
			"suPath":      suPath,
		})
	case http.MethodPost:
		var next AiPermissionPolicy
		if err := json.NewDecoder(r.Body).Decode(&next); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid json"})
			return
		}
		prev := LoadAiPermissionPolicy()
		binFound, granted, suPath := DetectRealRootAccess()
		if next.AllowRoot && !granted {
			writeJSON(w, http.StatusForbidden, map[string]interface{}{
				"success":     false,
				"error":       "Root permission is not granted to LytsoCoder on this device",
				"rootBinary":  binFound,
				"rootGranted": false,
				"suPath":      suPath,
			})
			return
		}
		if err := saveAiPermissionPolicy(next); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}
		restarted := false
		if prev.AllowRoot != next.AllowRoot && h.HubManager != nil {
			rStarted, _ := h.HubManager.WithPausedHub(func() error { return nil })
			restarted = rStarted
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"success":     true,
			"policy":      next,
			"rootBinary":  binFound,
			"rootGranted": granted,
			"suPath":      suPath,
			"restarted":   restarted,
			"csrfToken":   h.currentHubCsrf(),
		})
	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
}
