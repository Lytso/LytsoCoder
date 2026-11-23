package brainctl

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	_ "modernc.org/sqlite"
)

func createTestConvDB(t *testing.T, appDataDir, convID string) string {
	t.Helper()
	convsDir := filepath.Join(appDataDir, "conversations")
	if err := os.MkdirAll(convsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	dbPath := filepath.Join(convsDir, convID+".db")
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	_, err = db.Exec(`CREATE TABLE steps (
		idx integer PRIMARY KEY,
		step_type integer NOT NULL DEFAULT 0,
		status integer NOT NULL DEFAULT 3,
		has_subtrajectory numeric NOT NULL DEFAULT false,
		metadata blob,
		error_details blob,
		permissions blob,
		task_details blob,
		render_info blob,
		step_payload blob,
		step_format integer NOT NULL DEFAULT 0
	);`)
	if err != nil {
		t.Fatal(err)
	}
	return dbPath
}

func readStepPayload(t *testing.T, dbPath string, idx int) []byte {
	t.Helper()
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var payload []byte
	if err := db.QueryRow(`SELECT step_payload FROM steps WHERE idx = ?`, idx).Scan(&payload); err != nil {
		t.Fatal(err)
	}
	return payload
}

func TestWireRoundTripPreservesUnknownFields(t *testing.T) {
	orig := encodeFields([]rawField{
		{num: 1, wireType: 0, varint: 15},
		{num: 4, wireType: 0, varint: 3},
		{num: 99, wireType: 2, data: []byte("preserve-me")},
	})
	decoded, err := decodeFields(orig)
	if err != nil {
		t.Fatalf("decodeFields: %v", err)
	}
	reencoded := encodeFields(decoded)
	if !bytes.Equal(orig, reencoded) {
		t.Fatalf("round-trip mismatch: got %x want %x", reencoded, orig)
	}
}

func TestEditStepUserInputAndPlannerResponse(t *testing.T) {
	tmp := t.TempDir()
	appDir := filepath.Join(tmp, "app")
	brainDir := filepath.Join(tmp, "brain")
	convID := "conv-edit-test"
	dbPath := createTestConvDB(t, appDir, convID)

	hidden := `<lytsocoder-expansion command="">` + "\nGlobal rules here\n" + `</lytsocoder-expansion>`
	userPayload := encodeFields([]rawField{
		{num: 1, wireType: 0, varint: stepTypeUserInput},
		{num: 4, wireType: 0, varint: 3},
		{num: 19, wireType: 2, data: encodeFields([]rawField{
			{num: 2, wireType: 2, data: []byte(hidden + "original prompt")},
			{num: 3, wireType: 2, data: encodeFields([]rawField{
				{num: 1, wireType: 2, data: []byte(hidden + "original prompt")},
			})},
			{num: 16, wireType: 2, data: []byte("client_msg:123")},
		})},
	})
	plannerPayload := encodeFields([]rawField{
		{num: 1, wireType: 0, varint: stepTypePlannerResponse},
		{num: 4, wireType: 0, varint: 3},
		{num: 20, wireType: 2, data: encodeFields([]rawField{
			{num: 1, wireType: 2, data: []byte("old assistant reply")},
			{num: 3, wireType: 2, data: []byte("looping thought")},
			{num: 7, wireType: 2, data: encodeFields([]rawField{{num: 2, wireType: 2, data: []byte("read_file")}})},
			{num: 8, wireType: 2, data: []byte("stale modified response")},
		})},
	})

	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO steps (idx, step_type, step_payload) VALUES (0, 14, ?), (1, 15, ?)`, userPayload, plannerPayload)
	db.Close()
	if err != nil {
		t.Fatal(err)
	}

	if err := EditStep(appDir, brainDir, convID, 0, "user", "updated visible prompt", true); err != nil {
		t.Fatalf("EditStep user: %v", err)
	}
	gotUser := readStepPayload(t, dbPath, 0)
	if !strings.Contains(string(gotUser), hidden+"updated visible prompt") {
		t.Fatalf("expected hidden wrapper preserved with updated text, got: %q", string(gotUser))
	}
	if strings.Contains(string(gotUser), "original prompt") {
		t.Fatalf("old prompt still present: %q", string(gotUser))
	}

	if err := EditStep(appDir, brainDir, convID, 1, "assistant", "clean edited response", false); err != nil {
		t.Fatalf("EditStep assistant: %v", err)
	}
	gotPlan := readStepPayload(t, dbPath, 1)
	if !strings.Contains(string(gotPlan), "clean edited response") ||
		!strings.Contains(string(gotPlan), "looping thought") ||
		!strings.Contains(string(gotPlan), "read_file") ||
		strings.Contains(string(gotPlan), "stale modified response") {
		t.Fatalf("unexpected planner payload after edit: %q", string(gotPlan))
	}
}

func TestDeleteThinkingRemovesReasoningAndSyncsTranscript(t *testing.T) {
	tmp := t.TempDir()
	appDir := filepath.Join(tmp, "app")
	brainDir := filepath.Join(tmp, "brain")
	convID := "conv-think-test"
	dbPath := createTestConvDB(t, appDir, convID)

	plannerPayload := encodeFields([]rawField{
		{num: 1, wireType: 0, varint: stepTypePlannerResponse},
		{num: 20, wireType: 2, data: encodeFields([]rawField{
			{num: 1, wireType: 2, data: []byte("final answer stays intact")},
			{num: 3, wireType: 2, data: []byte("bad loop thought")},
			{num: 4, wireType: 2, data: []byte("sig")},
			{num: 11, wireType: 2, data: encodeFields([]rawField{{num: 1, wireType: 0, varint: 2}})},
			{num: 14, wireType: 2, data: []byte("rawsig")},
			{num: 16, wireType: 2, data: []byte("raw looping thought")},
		})},
	})

	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO steps (idx, step_type, step_payload) VALUES (3, 15, ?)`, plannerPayload)
	db.Close()
	if err != nil {
		t.Fatal(err)
	}

	logsDir := filepath.Join(brainDir, convID, ".system_generated", "logs")
	if err := os.MkdirAll(logsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	tPath := filepath.Join(logsDir, "transcript.jsonl")
	_ = os.WriteFile(tPath, []byte(`{"step_index":3,"type":"PLANNER_RESPONSE","content":"final answer stays intact","thinking":"bad loop thought"}`+"\n"), 0o600)

	count, err := DeleteThinking(appDir, brainDir, convID, []int{3})
	if err != nil {
		t.Fatalf("DeleteThinking: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected 1 modified step, got %d", count)
	}

	got := readStepPayload(t, dbPath, 3)
	if strings.Contains(string(got), "bad loop thought") || strings.Contains(string(got), "rawsig") {
		t.Fatalf("thinking bytes survived stripping: %q", string(got))
	}
	if !strings.Contains(string(got), "final answer stays intact") {
		t.Fatalf("response text was lost: %q", string(got))
	}

	tb, _ := os.ReadFile(tPath)
	var lineObj map[string]interface{}
	if err := json.Unmarshal(bytes.TrimSpace(tb), &lineObj); err != nil {
		t.Fatal(err)
	}
	if _, exists := lineObj["thinking"]; exists {
		t.Fatalf("expected thinking key removed from transcript.jsonl: %s", string(tb))
	}
}

func TestDeleteArtifactDeletesFileRedactsDBAndRejectsTraversal(t *testing.T) {
	tmp := t.TempDir()
	appDir := filepath.Join(tmp, "app")
	brainDir := filepath.Join(tmp, "brain")
	convID := "conv-art-test"
	dbPath := createTestConvDB(t, appDir, convID)

	convBrain := filepath.Join(brainDir, convID)
	if err := os.MkdirAll(convBrain, 0o755); err != nil {
		t.Fatal(err)
	}
	artPath := filepath.Join(convBrain, "old_plan.md")
	metaPath := artPath + ".metadata.json"
	if err := os.WriteFile(artPath, []byte("# Huge obsolete plan"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(metaPath, []byte(`{"summary":"old"}`), 0o600); err != nil {
		t.Fatal(err)
	}

	writeStepPayload := encodeFields([]rawField{
		{num: 1, wireType: 0, varint: 132},
		{num: 5, wireType: 2, data: encodeFields([]rawField{
			{num: 4, wireType: 2, data: encodeFields([]rawField{
				{num: 1, wireType: 2, data: []byte("call_1")},
				{num: 2, wireType: 2, data: []byte("write_to_file")},
				{num: 3, wireType: 2, data: []byte(`{"TargetFile":"` + artPath + `","CodeContent":"# Huge obsolete plan"}`)},
			})},
		})},
		{num: 23, wireType: 2, data: encodeFields([]rawField{
			{num: 1, wireType: 2, data: []byte("file://" + artPath)},
			{num: 2, wireType: 2, data: []byte("# Huge obsolete plan")},
			{num: 3, wireType: 2, data: []byte("huge diff bytes")},
		})},
	})

	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO steps (idx, step_type, step_payload) VALUES (9, 132, ?)`, writeStepPayload)
	db.Close()
	if err != nil {
		t.Fatal(err)
	}

	if _, _, err := DeleteArtifact(appDir, brainDir, convID, filepath.Join(tmp, "outside.txt"), "outside.txt"); err == nil {
		t.Fatalf("expected path traversal rejection")
	}

	deleted, redacted, err := DeleteArtifact(appDir, brainDir, convID, "file://"+artPath, "old_plan.md")
	if err != nil {
		t.Fatalf("DeleteArtifact: %v", err)
	}
	if len(deleted) != 2 || redacted != 1 {
		t.Fatalf("expected 2 files deleted and 1 step redacted, got %d files, %d steps", len(deleted), redacted)
	}
	if _, err := os.Stat(artPath); !os.IsNotExist(err) {
		t.Fatalf("artifact file still exists")
	}
	got := readStepPayload(t, dbPath, 9)
	if strings.Contains(string(got), "Huge obsolete plan") || strings.Contains(string(got), "huge diff bytes") {
		t.Fatalf("obsolete artifact content survived redaction: %q", string(got))
	}
	if !strings.Contains(string(got), "[Artifact deleted by user: old_plan.md]") {
		t.Fatalf("redacted placeholder missing: %q", string(got))
	}
}
