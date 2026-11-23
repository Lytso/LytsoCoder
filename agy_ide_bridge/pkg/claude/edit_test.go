package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func setupTestClaudeSession(t *testing.T, lines []string) (*Manager, string, string) {
	t.Helper()
	home := t.TempDir()
	projDir := filepath.Join(home, ".claude", "projects", "-tmp-proj")
	if err := os.MkdirAll(projDir, 0o755); err != nil {
		t.Fatal(err)
	}
	m := NewManager(home, tmpDirFallback(home), "")
	sid := "11111111-1111-4111-8111-111111111111"
	p := filepath.Join(projDir, sid+".jsonl")
	if err := os.WriteFile(p, []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return m, sid, p
}

func tmpDirFallback(home string) string { return home }

func TestEditMessageNonDestructiveUserAndAssistant(t *testing.T) {
	lines := []string{
		`{"type":"user","uuid":"u1","parentUuid":null,"sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"user","content":"<lytsocoder-expansion command=\"\">\nCTX\n</lytsocoder-expansion>first prompt"}}`,
		`{"type":"assistant","uuid":"a1","parentUuid":"u1","sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"assistant","content":[{"type":"thinking","thinking":"step think"},{"type":"text","text":"first reply"}]}}`,
		`{"type":"user","uuid":"u2","parentUuid":"a1","sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"user","content":"second prompt"}}`,
	}
	m, sid, _ := setupTestClaudeSession(t, lines)

	if err := m.EditMessage(sid, "u1", "edited first prompt", true); err != nil {
		t.Fatalf("EditMessage user: %v", err)
	}
	if err := m.EditMessage(sid, "a1", "edited reply", false); err != nil {
		t.Fatalf("EditMessage assistant: %v", err)
	}

	hist, err := m.History(sid, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(hist) != 3 {
		t.Fatalf("expected all 3 messages preserved, got %d", len(hist))
	}
	var u1 struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	}
	if err := json.Unmarshal(hist[0], &u1); err != nil {
		t.Fatalf("unmarshal u1: %v", err)
	}
	wantU1 := "<lytsocoder-expansion command=\"\">\nCTX\n</lytsocoder-expansion>edited first prompt"
	if u1.Message.Content != wantU1 {
		t.Fatalf("expected hidden context preserved in u1: got %q want %q", u1.Message.Content, wantU1)
	}
	if !strings.Contains(string(hist[1]), "step think") || !strings.Contains(string(hist[1]), "edited reply") {
		t.Fatalf("expected thinking preserved and text edited in a1, got: %s", string(hist[1]))
	}
	if !strings.Contains(string(hist[2]), "second prompt") {
		t.Fatalf("later prompt u2 was unexpectedly altered: %s", string(hist[2]))
	}
}

func TestDeleteThinkingRemovesTargetBlockAndKeepsTextAndToolUse(t *testing.T) {
	lines := []string{
		`{"type":"user","uuid":"u1","parentUuid":null,"sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"user","content":"go"}}`,
		`{"type":"assistant","uuid":"a1","parentUuid":"u1","sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"assistant","content":[{"type":"thinking","thinking":"loop thought","signature":"abc"},{"type":"tool_use","id":"t1","name":"Bash","input":{}},{"type":"text","text":"answer"}]}}`,
	}
	m, sid, _ := setupTestClaudeSession(t, lines)

	removed, err := m.DeleteThinking(sid, "a1", 0)
	if err != nil {
		t.Fatalf("DeleteThinking: %v", err)
	}
	if removed != 1 {
		t.Fatalf("expected 1 block removed, got %d", removed)
	}

	hist, err := m.History(sid, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	var entry struct {
		Message struct {
			Content []map[string]interface{} `json:"content"`
		} `json:"message"`
	}
	if err := json.Unmarshal(hist[1], &entry); err != nil {
		t.Fatal(err)
	}
	if len(entry.Message.Content) != 2 {
		t.Fatalf("expected 2 remaining blocks (tool_use + text), got %v", entry.Message.Content)
	}
	if entry.Message.Content[0]["type"] != "tool_use" || entry.Message.Content[1]["text"] != "answer" {
		t.Fatalf("unexpected remaining blocks: %v", entry.Message.Content)
	}
}

func TestForkSessionAtOlderMessageTruncatesBranchOnly(t *testing.T) {
	lines := []string{
		`{"type":"user","uuid":"u1","parentUuid":null,"sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"user","content":"first"}}`,
		`{"type":"assistant","uuid":"a1","parentUuid":"u1","sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"assistant","content":[{"type":"text","text":"reply 1"}]}}`,
		`{"type":"user","uuid":"u2","parentUuid":"a1","sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"user","content":"second"}}`,
		`{"type":"assistant","uuid":"a2","parentUuid":"u2","sessionId":"11111111-1111-4111-8111-111111111111","message":{"role":"assistant","content":[{"type":"text","text":"reply 2"}]}}`,
	}
	m, sid, _ := setupTestClaudeSession(t, lines)
	newID := "22222222-2222-4222-8222-222222222222"

	if err := m.ForkSessionAt(sid, newID, "Branch from reply 1", "a1"); err != nil {
		t.Fatalf("ForkSessionAt: %v", err)
	}

	origHist, _ := m.History(sid, 0, nil)
	forkHist, err := m.History(newID, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(origHist) != 4 {
		t.Fatalf("original session was modified: expected 4 messages, got %d", len(origHist))
	}
	if len(forkHist) != 2 {
		t.Fatalf("forked session expected 2 messages (u1, a1), got %d", len(forkHist))
	}
	if !strings.Contains(string(forkHist[1]), newID) || !strings.Contains(string(forkHist[1]), "reply 1") {
		t.Fatalf("forked reply missing new sessionId or content: %s", string(forkHist[1]))
	}
}
