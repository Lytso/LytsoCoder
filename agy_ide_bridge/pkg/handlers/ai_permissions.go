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
		AllowAllFiles:  true,
		AllowShellExec: true,
		AllowFileWrite: true,
		AllowNetwork:   true,
	}
}

func aiRootEnabledFlagPath() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return ""
	}
	return filepath.Join(home, ".lytsocoder", "root_enabled")
}

// SyncAiRootGuardFiles writes the hot-reloadable dynamic shims in ~/.lytsocoder/ai_bin_guard
// and toggles ~/.lytsocoder/root_enabled atomically so root access changes take effect in 0ms
// for all running shells/agents without restarting agy --hub.
func SyncAiRootGuardFiles(allowRoot bool, suPath string) {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return
	}
	guardDir := filepath.Join(home, ".lytsocoder", "ai_bin_guard")
	_ = os.MkdirAll(guardDir, 0o700)

	// Dynamic su shim: reads ~/.lytsocoder/root_enabled on every invocation (instant hot-reload!)
	suShim := `#!/system/bin/sh
FLAG="$HOME/.lytsocoder/root_enabled"
if [ ! -f "$FLAG" ]; then
  echo "[LytsoCoder Policy] Root access is DISABLED for AI agents. Enable it in Settings -> AI Permissions & Root." >&2
  exit 126
fi
REAL_SU="$(cat "$FLAG" 2>/dev/null | tr -d '\r\n')"
if [ -z "$REAL_SU" ] || [ ! -x "$REAL_SU" ]; then
  for cand in /debug_ramdisk/su /sbin/su /su/bin/su /system/bin/su /system/xbin/su /vendor/bin/su /data/adb/ksu/bin/su /data/adb/ap/bin/su /data/adb/magisk/su; do
    if [ -x "$cand" ]; then
      REAL_SU="$cand"
      break
    fi
  done
fi
if [ -z "$REAL_SU" ] || [ ! -x "$REAL_SU" ]; then
  echo "[LytsoCoder Root] su binary not found on this device." >&2
  exit 127
fi
exec "$REAL_SU" "$@"
`
	sudoShim := `#!/system/bin/sh
FLAG="$HOME/.lytsocoder/root_enabled"
if [ ! -f "$FLAG" ]; then
  echo "[LytsoCoder Policy] Root access (sudo) is DISABLED for AI agents. Enable it in Settings -> AI Permissions & Root." >&2
  exit 126
fi
REAL_SU="$(cat "$FLAG" 2>/dev/null | tr -d '\r\n')"
if [ -z "$REAL_SU" ] || [ ! -x "$REAL_SU" ]; then
  for cand in /debug_ramdisk/su /sbin/su /su/bin/su /system/bin/su /system/xbin/su /vendor/bin/su /data/adb/ksu/bin/su /data/adb/ap/bin/su /data/adb/magisk/su; do
    if [ -x "$cand" ]; then
      REAL_SU="$cand"
      break
    fi
  done
fi
if [ -z "$REAL_SU" ] || [ ! -x "$REAL_SU" ]; then
  echo "[LytsoCoder Root] su binary not found on this device." >&2
  exit 127
fi
if [ "$#" -eq 0 ]; then
  exec "$REAL_SU"
fi
exec "$REAL_SU" -c "$*"
`
	tsuShim := `#!/system/bin/sh
FLAG="$HOME/.lytsocoder/root_enabled"
if [ ! -f "$FLAG" ]; then
  echo "[LytsoCoder Policy] Root access (tsu) is DISABLED for AI agents. Enable it in Settings -> AI Permissions & Root." >&2
  exit 126
fi
if [ -n "$PREFIX" ] && [ -x "$PREFIX/bin/tsu" ]; then
  exec "$PREFIX/bin/tsu" "$@"
fi
exec "$HOME/.lytsocoder/ai_bin_guard/su" "$@"
`
	_ = os.WriteFile(filepath.Join(guardDir, "su"), []byte(suShim), 0o700)
	_ = os.WriteFile(filepath.Join(guardDir, "sudo"), []byte(sudoShim), 0o700)
	_ = os.WriteFile(filepath.Join(guardDir, "tsu"), []byte(tsuShim), 0o700)

	flagPath := aiRootEnabledFlagPath()
	if flagPath == "" {
		return
	}
	if allowRoot {
		_ = os.WriteFile(flagPath, []byte(strings.TrimSpace(suPath)+"\n"), 0o600)
	} else {
		_ = os.Remove(flagPath)
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
		"/debug_ramdisk/su",
		"/sbin/su",
		"/su/bin/su",
		"/system/bin/su",
		"/system/xbin/su",
		"/vendor/bin/su",
		"/data/adb/ksu/bin/su",
		"/data/adb/ap/bin/su",
		"/data/adb/magisk/su",
	}
	var foundPaths []string
	for _, c := range candidates {
		if info, err := os.Stat(c); err == nil && !info.IsDir() {
			binaryFound = true
			foundPaths = append(foundPaths, c)
		}
	}
	if !binaryFound {
		return false, false, ""
	}
	for _, cand := range foundPaths {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		out, err := exec.CommandContext(ctx, cand, "-c", "id").CombinedOutput()
		cancel()
		if err == nil && strings.Contains(string(out), "uid=0") {
			return true, true, cand
		}
	}
	return true, false, foundPaths[0]
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
		SyncAiRootGuardFiles(policy.AllowRoot && granted, suPath)
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
		binFound, granted, suPath := DetectRealRootAccess()
		if next.AllowRoot && !granted {
			SyncAiRootGuardFiles(false, suPath)
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
		// Hot-reload immediately via dynamic ai_bin_guard shims + root_enabled flag (0ms, no hub restart needed!)
		SyncAiRootGuardFiles(next.AllowRoot && granted, suPath)
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"success":     true,
			"policy":      next,
			"rootBinary":  binFound,
			"rootGranted": granted,
			"suPath":      suPath,
			"restarted":   false,
			"csrfToken":   h.currentHubCsrf(),
		})
	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
}
