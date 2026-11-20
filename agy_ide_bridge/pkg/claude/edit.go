package claude

import (
	"bufio"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"time"

	"gemini-server/pkg/brainctl"
)

// EditMessage updates a user or assistant message in place inside the Claude JSONL transcript
// without discarding subsequent messages or conversation history.
func (m *Manager) EditMessage(id, targetUUID, newText string, preserveHidden bool) error {
	src := m.FindTranscript(id)
	if src == "" {
		return os.ErrNotExist
	}
	if targetUUID == "" {
		return errors.New("uuid required")
	}
	if s := m.Existing(id); s != nil {
		s.KillAndWait(6 * time.Second)
	}

	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}

	sc := bufio.NewScanner(strings.NewReader(string(data)))
	sc.Buffer(make([]byte, 64<<10), 256<<20)
	var out strings.Builder
	found := false

	for sc.Scan() {
		raw := strings.TrimSpace(sc.Text())
		if raw == "" {
			continue
		}
		if !found && strings.HasPrefix(raw, "{") {
			var obj map[string]json.RawMessage
			if json.Unmarshal([]byte(raw), &obj) == nil {
				var uid string
				_ = json.Unmarshal(obj["uuid"], &uid)
				if uid == targetUUID {
					if err := rewriteEntryText(obj, newText, preserveHidden); err != nil {
						return err
					}
					if b, err := json.Marshal(obj); err == nil {
						out.Write(b)
						out.WriteByte('\n')
						found = true
						continue
					}
				}
			}
		}
		out.WriteString(raw)
		out.WriteByte('\n')
	}
	if !found {
		return errors.New("message uuid not found in transcript")
	}

	tmp := src + ".tmp"
	if err := os.WriteFile(tmp, []byte(out.String()), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, src)
}

func rewriteEntryText(obj map[string]json.RawMessage, newText string, preserveHidden bool) error {
	var msg map[string]json.RawMessage
	if err := json.Unmarshal(obj["message"], &msg); err != nil {
		return err
	}
	rawContent := strings.TrimSpace(string(msg["content"]))
	if strings.HasPrefix(rawContent, `"`) {
		var existing string
		if err := json.Unmarshal(msg["content"], &existing); err != nil {
			return err
		}
		finalText := newText
		if preserveHidden {
			finalText = brainctl.PreserveHiddenPromptWrappers(existing, newText)
		}
		enc, _ := json.Marshal(finalText)
		msg["content"] = enc
		encMsg, _ := json.Marshal(msg)
		obj["message"] = encMsg
		return nil
	}

	var blocks []map[string]interface{}
	if err := json.Unmarshal(msg["content"], &blocks); err != nil {
		return err
	}
	replaced := false
	cleaned := make([]map[string]interface{}, 0, len(blocks)+1)
	for _, b := range blocks {
		bType, _ := b["type"].(string)
		if bType == "text" {
			if !replaced {
				existing, _ := b["text"].(string)
				finalText := newText
				if preserveHidden {
					finalText = brainctl.PreserveHiddenPromptWrappers(existing, newText)
				}
				b["text"] = finalText
				cleaned = append(cleaned, b)
				replaced = true
			}
			continue
		}
		cleaned = append(cleaned, b)
	}
	if !replaced {
		cleaned = append(cleaned, map[string]interface{}{"type": "text", "text": newText})
	}
	encContent, _ := json.Marshal(cleaned)
	msg["content"] = encContent
	encMsg, _ := json.Marshal(msg)
	obj["message"] = encMsg
	return nil
}

// DeleteThinking removes thinking/redacted_thinking content block(s) from the specified transcript entry UUID
// so Claude stops seeing stale or looping reasoning on subsequent turns.
func (m *Manager) DeleteThinking(id, targetUUID string, contentIndex int) (int, error) {
	src := m.FindTranscript(id)
	if src == "" {
		return 0, os.ErrNotExist
	}
	if targetUUID == "" {
		return 0, errors.New("uuid required")
	}
	if s := m.Existing(id); s != nil {
		s.KillAndWait(6 * time.Second)
	}

	data, err := os.ReadFile(src)
	if err != nil {
		return 0, err
	}

	sc := bufio.NewScanner(strings.NewReader(string(data)))
	sc.Buffer(make([]byte, 64<<10), 256<<20)
	var out strings.Builder
	found := false
	removed := 0

	for sc.Scan() {
		raw := strings.TrimSpace(sc.Text())
		if raw == "" {
			continue
		}
		if !found && strings.HasPrefix(raw, "{") {
			var obj map[string]json.RawMessage
			if json.Unmarshal([]byte(raw), &obj) == nil {
				var uid string
				_ = json.Unmarshal(obj["uuid"], &uid)
				if uid == targetUUID {
					var msg map[string]json.RawMessage
					if json.Unmarshal(obj["message"], &msg) == nil {
						var blocks []map[string]interface{}
						if json.Unmarshal(msg["content"], &blocks) == nil {
							newBlocks := make([]map[string]interface{}, 0, len(blocks))
							for idx, b := range blocks {
								bType, _ := b["type"].(string)
								isThink := bType == "thinking" || bType == "redacted_thinking"
								if isThink && (contentIndex < 0 || idx == contentIndex) {
									removed++
									continue
								}
								newBlocks = append(newBlocks, b)
							}
							if len(newBlocks) == 0 {
								newBlocks = append(newBlocks, map[string]interface{}{"type": "text", "text": ""})
							}
							encContent, _ := json.Marshal(newBlocks)
							msg["content"] = encContent
							encMsg, _ := json.Marshal(msg)
							obj["message"] = encMsg
							if b, err := json.Marshal(obj); err == nil {
								out.Write(b)
								out.WriteByte('\n')
								found = true
								continue
							}
						}
					}
				}
			}
		}
		out.WriteString(raw)
		out.WriteByte('\n')
	}
	if !found {
		return 0, errors.New("message uuid not found in transcript")
	}

	tmp := src + ".tmp"
	if err := os.WriteFile(tmp, []byte(out.String()), 0o600); err != nil {
		return 0, err
	}
	return removed, os.Rename(tmp, src)
}
