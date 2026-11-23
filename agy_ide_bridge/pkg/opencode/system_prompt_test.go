package opencode

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestOpenCodeSystemPromptPluginAndConfig(t *testing.T) {
	home := t.TempDir()
	m := NewManager(home, home, "")

	if err := m.writeConfig(); err != nil {
		t.Fatalf("writeConfig failed: %v", err)
	}

	// Verify plugin file was written
	pluginFile := filepath.Join(m.systemPromptPluginDir(), "index.js")
	pluginBytes, err := os.ReadFile(pluginFile)
	if err != nil {
		t.Fatalf("plugin file missing: %v", err)
	}
	if !strings.Contains(string(pluginBytes), "lytsocoder.system-prompt") ||
		!strings.Contains(string(pluginBytes), "stripModelIdentity") {
		t.Fatalf("unexpected plugin source")
	}

	// Verify opencode.json disables opencode.prompt.identity and loads our plugin dir
	cfgBytes, err := os.ReadFile(m.configPath())
	if err != nil {
		t.Fatalf("opencode.json missing: %v", err)
	}
	var cfg struct {
		Plugins []string `json:"plugins"`
	}
	if err := json.Unmarshal(cfgBytes, &cfg); err != nil {
		t.Fatalf("invalid config json: %v", err)
	}
	if len(cfg.Plugins) != 2 || cfg.Plugins[0] != "-opencode.prompt.identity" || cfg.Plugins[1] != m.systemPromptPluginDir() {
		t.Fatalf("unexpected plugins config: %+v", cfg.Plugins)
	}
}

func TestOpenCodeSystemPromptPrecedenceAndEndpoints(t *testing.T) {
	home := t.TempDir()
	ws := filepath.Join(home, "workspace")
	_ = os.MkdirAll(ws, 0755)
	m := NewManager(home, ws, "")

	// 1. Default (no overrides) returns OpenCode base prompt without Antigravity or "# Your Model"
	res := doJSON(t, m, http.MethodGet, "/api/opencode/system-prompt?sessionId=ses_1&workspaceDir="+ws, "")
	if res["success"] != true || res["source"] != "default" || res["modelIdentityRemoved"] != true {
		t.Fatalf("unexpected default response: %v", res)
	}
	defaultBase, _ := res["defaultBasePrompt"].(string)
	defaultFull, _ := res["defaultFullPrompt"].(string)
	if !strings.Contains(defaultBase, "You are an AI agent running in OpenCode") {
		t.Fatalf("expected OpenCode base prompt, got: %q", defaultBase)
	}
	if strings.Contains(defaultFull, "Antigravity") || strings.Contains(defaultFull, "# Your Model") {
		t.Fatalf("defaultFullPrompt must not contain Antigravity or # Your Model: %q", defaultFull)
	}

	// 2. Global OpenCode prompt replaces entire activeFullPrompt (no OpenCode boilerplate left)
	res = doJSON(t, m, http.MethodPut, "/api/opencode/system-prompt", `{"scope":"global","prompt":"You are OpenCode Global Persona."}`)
	if res["success"] != true {
		t.Fatalf("set global failed: %v", res)
	}
	res = doJSON(t, m, http.MethodGet, "/api/opencode/system-prompt?sessionId=ses_1", "")
	if res["source"] != "global" || res["effectiveBasePrompt"] != "You are OpenCode Global Persona." || res["activeFullPrompt"] != "You are OpenCode Global Persona." {
		t.Fatalf("expected global precedence with exclusive systemInstruction: %v", res)
	}

	// 3. Per-chat override takes priority over global, isolated to that session
	res = doJSON(t, m, http.MethodPut, "/api/opencode/system-prompt", `{"scope":"chat","sessionId":"ses_1","prompt":"Only for ses_1."}`)
	if res["success"] != true {
		t.Fatalf("set chat failed: %v", res)
	}
	res = doJSON(t, m, http.MethodGet, "/api/opencode/system-prompt?sessionId=ses_1", "")
	if res["source"] != "chat" || res["effectiveBasePrompt"] != "Only for ses_1." || res["activeFullPrompt"] != "Only for ses_1." {
		t.Fatalf("expected chat precedence with exclusive systemInstruction: %v", res)
	}
	resOther := doJSON(t, m, http.MethodGet, "/api/opencode/system-prompt?sessionId=ses_2", "")
	if resOther["source"] != "global" || resOther["effectiveBasePrompt"] != "You are OpenCode Global Persona." {
		t.Fatalf("other session should still use global: %v", resOther)
	}

	// 4. Copy on fork
	res = doJSON(t, m, http.MethodPut, "/api/opencode/system-prompt", `{"scope":"copy","fromSessionId":"ses_1","toSessionId":"ses_fork"}`)
	if res["success"] != true {
		t.Fatalf("copy failed: %v", res)
	}
	resFork := doJSON(t, m, http.MethodGet, "/api/opencode/system-prompt?sessionId=ses_fork", "")
	if resFork["source"] != "chat" || resFork["effectiveBasePrompt"] != "Only for ses_1." {
		t.Fatalf("fork did not inherit chat prompt: %v", resFork)
	}

	// 5. Clear chat override falls back to global
	res = doJSON(t, m, http.MethodPut, "/api/opencode/system-prompt", `{"scope":"chat","sessionId":"ses_1","prompt":""}`)
	if res["success"] != true {
		t.Fatalf("clear chat failed: %v", res)
	}
	res = doJSON(t, m, http.MethodGet, "/api/opencode/system-prompt?sessionId=ses_1", "")
	if res["source"] != "global" {
		t.Fatalf("clearing chat should fall back to global: %v", res)
	}

	// 6. Delete fork override
	res = doJSON(t, m, http.MethodPut, "/api/opencode/system-prompt", `{"scope":"delete","sessionId":"ses_fork"}`)
	if res["success"] != true {
		t.Fatalf("delete failed: %v", res)
	}
	resFork = doJSON(t, m, http.MethodGet, "/api/opencode/system-prompt?sessionId=ses_fork", "")
	if resFork["source"] != "global" {
		t.Fatalf("deleted chat should fall back to global: %v", resFork)
	}

	// 7. Live snapshot is preferred when present
	snap := map[string]interface{}{
		"defaultBase":  "Live OpenCode Harness Base",
		"defaultExtra": []string{"# Code Mode\nLive tools", "<env>\nPlatform: linux\n</env>"},
		"sessions":     map[string]interface{}{},
	}
	_ = writePrivate(m.promptSnapshotPath(), snap)
	res = doJSON(t, m, http.MethodPut, "/api/opencode/system-prompt", `{"scope":"global","prompt":""}`)
	res = doJSON(t, m, http.MethodGet, "/api/opencode/system-prompt?sessionId=ses_1", "")
	if res["defaultBasePrompt"] != "Live OpenCode Harness Base" {
		t.Fatalf("expected live snapshot base prompt, got: %v", res["defaultBasePrompt"])
	}
	if full, _ := res["activeFullPrompt"].(string); !strings.Contains(full, "# Code Mode") {
		t.Fatalf("expected live snapshot extra parts in activeFullPrompt: %q", full)
	}
}
