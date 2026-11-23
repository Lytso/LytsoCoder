package opencode

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// DefaultBaseSystemPrompt is OpenCode's default initial harness prompt (systemInstruction.parts[0]).
// Used as the preview/default when no live snapshot has been captured from OpenCode yet.
const DefaultBaseSystemPrompt = `You are an AI agent running in OpenCode, a coding agent harness. Help the user accomplish their goals using the tools you have available.

# Harness
- Responses are rendered as GitHub-flavored Markdown.
- ` + "`<system-update>`" + ` blocks are harness instructions, not user-authored content. Read and follow them.
- Background work is reported as harness input: user-role messages containing ` + "`<background-result>`" + ` XML. They are not the user speaking, and never something you write yourself: do not reproduce the XML in your own output. Distinguish them by the ` + "`<background-result>`" + ` opening tag.
- Prefer parallelizing independent tool calls.
- Prefer dedicated tools over shell commands; fall back to the shell when a tool cannot do what you need.
- Do not chain shell commands with separators like ` + "`echo \"====\";`" + ` or ` + "`printf '---'`" + `; the output becomes noisy in a way that makes the user's side of the conversation worse.
- Use the write tool to create files or completely replace their content. Prefer using the edit tool for targeted changes.
- Use the edit tool for targeted changes to existing text files. It replaces the exact text in ` + "`oldString`" + ` with ` + "`newString`" + `, and the values must differ. By default, ` + "`oldString`" + ` must occur exactly once. If it occurs multiple times, include more surrounding context to make it unique or set ` + "`replaceAll`" + ` to true to replace every occurrence.

# Communication
- Use clear file paths when referring to files.
- Keep responses clear and concise, and avoid unnecessary technical jargon.

# Working in codebases
- Keep changes consistent with the structure, naming, style, and patterns of the surrounding code.
- Treat unfamiliar files or changes as potential user work and investigate before deleting or overwriting them.`

const defaultWorktreePromptPart = "When you create a worktree outside the current working directory and intend to use it as your primary working directory, consider using `execute` to call `tools.opencode.session.move` and make the worktree the session's working directory."

// systemPromptPluginSource is a zero-dependency OpenCode V2 server plugin that:
//  1. Strips OpenCode's "# Your Model" system part everywhere (alongside "-opencode.prompt.identity").
//  2. Captures the live default OpenCode system parts to prompt_snapshot.json for accurate UI inspection.
//  3. When a custom per-chat or global OpenCode prompt is active in system_prompts.json, REPLACES the entire
//     event.system array with ONLY [{ type: "text", text: effective }] so systemInstruction contains solely
//     the user's prompt (no giant OpenCode boilerplate or extra parts).
//  4. Sanitizes event.messages so system prompts, "# Your Model", or OpenCode system-instruction updates
//     are never duplicated into user input (contents).
const systemPromptPluginSource = `import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const PLUGIN_DIR = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = path.resolve(PLUGIN_DIR, "..", "..");
const PROMPTS_FILE = path.join(DATA_DIR, "system_prompts.json");
const SNAPSHOT_FILE = path.join(DATA_DIR, "prompt_snapshot.json");

const MODEL_IDENTITY_RE = /^\s*#\s*Your Model\b/m;
const MODEL_IDENTITY_BLOCK_RE = /(?:^|\n)#\s*Your Model\r?\n(?:\s*-\s*(?:Name|Provider ID|Model ID):[^\n]*\r?\n?)+/g;
const CHAT_SYS_HEADER = "[Chat system prompt, set by the user for this chat.";

function isModelIdentityPart(part) {
  if (!part || typeof part.text !== "string") return false;
  return MODEL_IDENTITY_RE.test(part.text);
}

function stripModelIdentity(system) {
  if (!Array.isArray(system)) return;
  for (let i = system.length - 1; i >= 0; i--) {
    if (isModelIdentityPart(system[i])) {
      system.splice(i, 1);
    }
  }
}

function stripLegacyChatPromptBlock(text) {
  if (typeof text !== "string" || !text) return text;
  let out = text.replace(MODEL_IDENTITY_BLOCK_RE, "\n");

  // Clean [Chat system prompt...] blocks inside <lytsocoder-expansion ...>...</lytsocoder-expansion>
  out = out.replace(
    /<lytsocoder-expansion\b[^>]*>([\s\S]*?)<\/lytsocoder-expansion>/g,
    (full, inner) => {
      if (!inner.includes(CHAT_SYS_HEADER)) return full;
      const start = inner.indexOf(CHAT_SYS_HEADER);
      const nextStanding = inner.indexOf("[The user's standing instructions", start);
      const before = inner.slice(0, start).trimEnd();
      const after = nextStanding >= 0 ? inner.slice(nextStanding).trimStart() : "";
      const merged = [before, after].filter(Boolean).join("\n\n").trim();
      if (!merged) return "";
      const openTagEnd = full.indexOf(">") + 1;
      return full.slice(0, openTagEnd) + merged + "</lytsocoder-expansion>";
    }
  );

  // Clean any standalone legacy [Chat system prompt...] block if not wrapped in expansion tags
  if (out.includes(CHAT_SYS_HEADER)) {
    const start = out.indexOf(CHAT_SYS_HEADER);
    const nextBlock = out.indexOf("\n\n[The user's standing instructions", start);
    if (nextBlock >= 0) {
      out = (out.slice(0, start) + out.slice(nextBlock + 2)).trimStart();
    }
  }

  return out;
}

function isOpenCodeInstructionDump(text) {
  if (typeof text !== "string") return false;
  if (MODEL_IDENTITY_RE.test(text)) return true;
  if (text.includes("# Code Mode") && text.includes("tools.")) return true;
  if (text.includes("<available_skills>") && text.includes("</available_skills>")) return true;
  if (text.includes("The environment you are running in is now:") && text.includes("<env>")) return true;
  if (text.includes("Today's date is now:")) return true;
  return false;
}

function sanitizeMessages(messages, hasCustomPrompt) {
  if (!Array.isArray(messages)) return;
  for (let i = messages.length - 1; i >= 0; i--) {
    const msg = messages[i];
    if (!msg) continue;

    // Gemini lowers role:"system" messages from event.messages directly into contents[] as role:"user".
    // Always remove "# Your Model" system messages, and when custom prompt is active, drop OpenCode
    // instruction dumps so system instructions are never replicated into user contents.
    if (msg.role === "system") {
      const texts = Array.isArray(msg.content)
        ? msg.content.map((p) => (p && typeof p.text === "string" ? p.text : "")).join("\n")
        : typeof msg.content === "string"
        ? msg.content
        : "";
      if (MODEL_IDENTITY_RE.test(texts) || (hasCustomPrompt && isOpenCodeInstructionDump(texts))) {
        messages.splice(i, 1);
      }
      continue;
    }

    if (msg.role === "user" && Array.isArray(msg.content)) {
      for (let j = 0; j < msg.content.length; j++) {
        const part = msg.content[j];
        if (part && part.type === "text" && typeof part.text === "string") {
          const cleaned = stripLegacyChatPromptBlock(part.text);
          if (cleaned !== part.text) {
            msg.content[j] = { ...part, text: cleaned };
          }
        }
      }
    }
  }
}

function readPrompts() {
  try {
    const raw = fs.readFileSync(PROMPTS_FILE, "utf8");
    const parsed = JSON.parse(raw);
    return {
      global: typeof parsed.global === "string" ? parsed.global.trim() : "",
      chats: parsed.chats && typeof parsed.chats === "object" ? parsed.chats : {},
    };
  } catch {
    return { global: "", chats: {} };
  }
}

function saveSnapshot(sessionID, baseText, extraParts) {
  try {
    let snap = { defaultBase: "", defaultExtra: [], sessions: {} };
    try {
      const prev = JSON.parse(fs.readFileSync(SNAPSHOT_FILE, "utf8"));
      if (prev && typeof prev === "object") snap = prev;
    } catch {}
    if (!snap.sessions || typeof snap.sessions !== "object") snap.sessions = {};
    const prevEntry = sessionID ? snap.sessions[sessionID] : null;
    const sameBase = snap.defaultBase === baseText;
    const sameSession =
      !sessionID ||
      (prevEntry &&
        prevEntry.base === baseText &&
        JSON.stringify(prevEntry.extra) === JSON.stringify(extraParts));
    if (sameBase && sameSession) return;
    snap.defaultBase = baseText;
    snap.defaultExtra = extraParts;
    if (sessionID) {
      snap.sessions[sessionID] = { base: baseText, extra: extraParts, updatedAt: Date.now() };
      const keys = Object.keys(snap.sessions);
      if (keys.length > 100) {
        keys.sort((a, b) => (snap.sessions[a]?.updatedAt || 0) - (snap.sessions[b]?.updatedAt || 0));
        for (let i = 0; i < keys.length - 100; i++) delete snap.sessions[keys[i]];
      }
    }
    const tmp = SNAPSHOT_FILE + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify(snap), { mode: 0o600 });
    fs.renameSync(tmp, SNAPSHOT_FILE);
  } catch {}
}

export default {
  id: "lytsocoder.system-prompt",
  async setup(ctx) {
    await ctx.session.hook("context", (event) => {
      if (!event || !Array.isArray(event.system)) return;
      stripModelIdentity(event.system);

      const defaultBase =
        event.system.length > 0 && typeof event.system[0]?.text === "string"
          ? event.system[0].text
          : "";
      const extraParts = event.system
        .slice(1)
        .map((p) => (p && typeof p.text === "string" ? p.text : ""))
        .filter((t) => t.trim().length > 0);
      if (defaultBase) {
        saveSnapshot(event.sessionID, defaultBase, extraParts);
      }

      const prompts = readPrompts();
      const chatOverride =
        event.sessionID && typeof prompts.chats[event.sessionID] === "string"
          ? prompts.chats[event.sessionID].trim()
          : "";
      const effective = chatOverride || prompts.global;

      if (effective) {
        // Replace the entire systemInstruction with ONLY the user's prompt — no extra OpenCode boilerplate.
        event.system.splice(0, event.system.length, { type: "text", text: effective });
      }

      sanitizeMessages(event.messages, Boolean(effective));
    });

    const stripOnly = (event) => {
      if (!event) return;
      if (Array.isArray(event.system)) stripModelIdentity(event.system);
      if (Array.isArray(event.messages)) sanitizeMessages(event.messages, false);
    };
    await ctx.session.hook("compaction", stripOnly);
    await ctx.session.hook("generate", stripOnly);
  },
};
`

type SystemPromptState struct {
	Global string            `json:"global,omitempty"`
	Chats  map[string]string `json:"chats,omitempty"`
}

type promptSnapshot struct {
	DefaultBase  string `json:"defaultBase"`
	DefaultExtra []string `json:"defaultExtra"`
	Sessions     map[string]struct {
		Base      string   `json:"base"`
		Extra     []string `json:"extra"`
		UpdatedAt int64    `json:"updatedAt"`
	} `json:"sessions"`
}

func (m *Manager) systemPromptsPath() string {
	return filepath.Join(m.dataDir(), "system_prompts.json")
}

func (m *Manager) promptSnapshotPath() string {
	return filepath.Join(m.dataDir(), "prompt_snapshot.json")
}

func (m *Manager) systemPromptPluginDir() string {
	return filepath.Join(m.dataDir(), "plugins", "lytsocoder-system-prompt")
}

func (m *Manager) ensureSystemPromptPlugin() error {
	dir := m.systemPromptPluginDir()
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	target := filepath.Join(dir, "index.js")
	if existing, err := os.ReadFile(target); err == nil && string(existing) == systemPromptPluginSource {
		return nil
	}
	tmp := target + ".tmp"
	if err := os.WriteFile(tmp, []byte(systemPromptPluginSource), 0600); err != nil {
		return err
	}
	return os.Rename(tmp, target)
}

func (m *Manager) loadSystemPromptsLocked() (SystemPromptState, error) {
	data, err := os.ReadFile(m.systemPromptsPath())
	if errors.Is(err, os.ErrNotExist) {
		return SystemPromptState{Chats: map[string]string{}}, nil
	}
	if err != nil {
		return SystemPromptState{}, err
	}
	var st SystemPromptState
	if err := json.Unmarshal(data, &st); err != nil {
		return SystemPromptState{Chats: map[string]string{}}, nil
	}
	if st.Chats == nil {
		st.Chats = map[string]string{}
	}
	return st, nil
}

func (m *Manager) saveSystemPromptsLocked(st SystemPromptState) error {
	st.Global = strings.TrimSpace(st.Global)
	cleanedChats := make(map[string]string, len(st.Chats))
	for k, v := range st.Chats {
		k = strings.TrimSpace(k)
		v = strings.TrimSpace(v)
		if k != "" && v != "" {
			cleanedChats[k] = v
		}
	}
	st.Chats = cleanedChats
	return writePrivate(m.systemPromptsPath(), st)
}

func (m *Manager) loadPromptSnapshot() *promptSnapshot {
	data, err := os.ReadFile(m.promptSnapshotPath())
	if err != nil {
		return nil
	}
	var snap promptSnapshot
	if err := json.Unmarshal(data, &snap); err != nil {
		return nil
	}
	return &snap
}

func (m *Manager) buildFallbackExtraParts(ctx context.Context, workspaceDir string) []string {
	ws := strings.TrimSpace(workspaceDir)
	ws = strings.TrimPrefix(ws, "file://")
	if ws == "" {
		ws = m.WorkspaceDir
	}
	if ws == "" {
		ws = m.HomeDir
	}
	isGitRepo := "no"
	if ws != "" {
		if _, err := os.Stat(filepath.Join(ws, ".git")); err == nil {
			isGitRepo = "yes"
		}
	}
	platform := runtime.GOOS
	if platform == "android" {
		platform = "linux"
	}

	var skillsBlock strings.Builder
	if m.Running() {
		var res struct {
			Data []struct {
				ID          string `json:"id"`
				Name        string `json:"name"`
				Description string `json:"description"`
			} `json:"data"`
		}
		cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		q := ""
		if ws != "" {
			q = "?directory=" + url.QueryEscape(ws)
		}
		if err := m.Call(cctx, http.MethodGet, "/api/skill"+q, nil, &res); err == nil && len(res.Data) > 0 {
			skillsBlock.WriteString("<available_skills>\n")
			for _, sk := range res.Data {
				skillsBlock.WriteString(fmt.Sprintf("  <skill>\n    <id>%s</id>\n    <name>%s</name>\n    <description>%s</description>\n  </skill>\n",
					sk.ID, sk.Name, strings.TrimSpace(sk.Description)))
			}
			skillsBlock.WriteString("</available_skills>\n\n")
		}
	}

	envPart := fmt.Sprintf("%sToday's date: %s\n\nHere is some useful information about the environment you are running in:\n<env>\n  Working directory: %s\n  Workspace root folder: %s\n  Is directory a git repo: %s\n  Platform: %s\n</env>",
		skillsBlock.String(),
		time.Now().Format("Mon Jan 02 2006"),
		ws, ws, isGitRepo, platform,
	)
	return []string{envPart, defaultWorktreePromptPart}
}

func (m *Manager) compileOpenCodePrompt(ctx context.Context, sessionID, workspaceDir, agentID string) (defaultBase string, extraParts []string) {
	snap := m.loadPromptSnapshot()
	if snap != nil {
		if sessionID != "" {
			if s, ok := snap.Sessions[sessionID]; ok && strings.TrimSpace(s.Base) != "" {
				return strings.TrimSpace(s.Base), s.Extra
			}
		}
		if strings.TrimSpace(snap.DefaultBase) != "" {
			return strings.TrimSpace(snap.DefaultBase), snap.DefaultExtra
		}
	}

	if m.Running() && agentID != "" && agentID != "build" {
		var agentResp struct {
			Data struct {
				System string `json:"system"`
			} `json:"data"`
		}
		cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		if err := m.Call(cctx, http.MethodGet, "/api/agent/"+agentID, nil, &agentResp); err == nil && strings.TrimSpace(agentResp.Data.System) != "" {
			return strings.TrimSpace(agentResp.Data.System), m.buildFallbackExtraParts(ctx, workspaceDir)
		}
	}

	return DefaultBaseSystemPrompt, m.buildFallbackExtraParts(ctx, workspaceDir)
}

type systemPromptUpdateReq struct {
	Scope         string  `json:"scope"`
	SessionID     string  `json:"sessionId"`
	Prompt        *string `json:"prompt"`
	Global        *string `json:"global"`
	Chat          *string `json:"chat"`
	FromSessionID string  `json:"fromSessionId"`
	ToSessionID   string  `json:"toSessionId"`
}

func (m *Manager) handleSystemPrompt(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		sessionID := strings.TrimSpace(r.URL.Query().Get("sessionId"))
		if sessionID == "" {
			sessionID = strings.TrimSpace(r.URL.Query().Get("conversationId"))
		}
		workspaceDir := strings.TrimSpace(r.URL.Query().Get("workspaceDir"))
		agentID := strings.TrimSpace(r.URL.Query().Get("agent"))

		m.sysPromptMu.Lock()
		st, err := m.loadSystemPromptsLocked()
		m.sysPromptMu.Unlock()
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}

		defaultBase, extraParts := m.compileOpenCodePrompt(r.Context(), sessionID, workspaceDir, agentID)
		chatPrompt := ""
		if sessionID != "" {
			chatPrompt = strings.TrimSpace(st.Chats[sessionID])
		}
		globalPrompt := strings.TrimSpace(st.Global)

		joinParts := func(base string, extras []string) string {
			parts := make([]string, 0, 1+len(extras))
			if strings.TrimSpace(base) != "" {
				parts = append(parts, strings.TrimSpace(base))
			}
			for _, ex := range extras {
				if t := strings.TrimSpace(ex); t != "" {
					parts = append(parts, t)
				}
			}
			return strings.Join(parts, "\n\n---\n\n")
		}

		defaultFull := joinParts(defaultBase, extraParts)
		effectiveBase := defaultBase
		activeFull := defaultFull
		source := "default"
		if chatPrompt != "" {
			effectiveBase = chatPrompt
			activeFull = chatPrompt
			source = "chat"
		} else if globalPrompt != "" {
			effectiveBase = globalPrompt
			activeFull = globalPrompt
			source = "global"
		}

		writeJSON(w, http.StatusOK, map[string]interface{}{
			"success":              true,
			"agent":                "opencode",
			"sessionId":            sessionID,
			"defaultBasePrompt":    defaultBase,
			"defaultFullPrompt":    defaultFull,
			"activeFullPrompt":     activeFull,
			"globalPrompt":         globalPrompt,
			"chatPrompt":           chatPrompt,
			"effectiveBasePrompt":  effectiveBase,
			"source":               source,
			"modelIdentityRemoved": true,
		})
		return

	case http.MethodPut, http.MethodPost:
		var req systemPromptUpdateReq
		if err := json.NewDecoder(io.LimitReader(r.Body, 2<<20)).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "invalid JSON: " + err.Error()})
			return
		}

		pluginMissing := false
		if _, err := os.Stat(filepath.Join(m.systemPromptPluginDir(), "index.js")); err != nil {
			pluginMissing = true
		}

		m.sysPromptMu.Lock()
		st, err := m.loadSystemPromptsLocked()
		if err != nil {
			m.sysPromptMu.Unlock()
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}

		switch strings.ToLower(strings.TrimSpace(req.Scope)) {
		case "global":
			if req.Prompt != nil {
				st.Global = strings.TrimSpace(*req.Prompt)
			} else if req.Global != nil {
				st.Global = strings.TrimSpace(*req.Global)
			} else {
				st.Global = ""
			}
		case "chat":
			sid := strings.TrimSpace(req.SessionID)
			if sid == "" {
				m.sysPromptMu.Unlock()
				writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "sessionId is required"})
				return
			}
			val := ""
			if req.Prompt != nil {
				val = strings.TrimSpace(*req.Prompt)
			} else if req.Chat != nil {
				val = strings.TrimSpace(*req.Chat)
			}
			if val == "" {
				delete(st.Chats, sid)
			} else {
				st.Chats[sid] = val
			}
		case "sync":
			if req.Global != nil {
				st.Global = strings.TrimSpace(*req.Global)
			}
			if sid := strings.TrimSpace(req.SessionID); sid != "" && req.Chat != nil {
				val := strings.TrimSpace(*req.Chat)
				if val == "" {
					delete(st.Chats, sid)
				} else {
					st.Chats[sid] = val
				}
			}
		case "copy":
			from := strings.TrimSpace(req.FromSessionID)
			to := strings.TrimSpace(req.ToSessionID)
			if from != "" && to != "" {
				if existing := strings.TrimSpace(st.Chats[from]); existing != "" {
					st.Chats[to] = existing
				}
			}
		case "delete":
			if sid := strings.TrimSpace(req.SessionID); sid != "" {
				delete(st.Chats, sid)
			}
		default:
			m.sysPromptMu.Unlock()
			writeJSON(w, http.StatusBadRequest, map[string]interface{}{"success": false, "error": "unknown scope"})
			return
		}

		err = m.saveSystemPromptsLocked(st)
		m.sysPromptMu.Unlock()
		if err != nil {
			writeJSON(w, http.StatusOK, map[string]interface{}{"success": false, "error": err.Error()})
			return
		}

		if pluginMissing {
			if err := m.writeConfig(); err == nil {
				m.reloadQuietly(r.Context())
			}
		}

		writeJSON(w, http.StatusOK, map[string]interface{}{
			"success": true,
			"global":  st.Global,
		})
		return
	}

	writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{"success": false, "error": "method not allowed"})
}
