package brainctl

import (
	"bufio"
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

const (
	stepTypeUserInput       = 14
	stepTypePlannerResponse = 15
)

func validateConvID(convID string) error {
	id := strings.TrimSpace(convID)
	if id == "" || strings.ContainsAny(id, "/\\") || strings.Contains(id, "..") {
		return fmt.Errorf("invalid conversation id %q", convID)
	}
	return nil
}

func openConvDB(appDataDir, convID string) (*sql.DB, string, error) {
	if err := validateConvID(convID); err != nil {
		return nil, "", err
	}
	dbPath := filepath.Join(appDataDir, "conversations", convID+".db")
	if _, err := os.Stat(dbPath); err != nil {
		return nil, "", err
	}
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, "", err
	}
	if _, err := db.Exec(`PRAGMA busy_timeout(5000);`); err != nil {
		db.Close()
		return nil, "", err
	}
	_, _ = db.Exec(`PRAGMA wal_checkpoint(TRUNCATE);`)
	return db, dbPath, nil
}

// EditStep rewrites a USER_INPUT (step_type 14) or PLANNER_RESPONSE (step_type 15) step directly inside
// the conversation SQLite database without deleting subsequent messages, and mirrors the change to JSONL logs.
func EditStep(appDataDir, brainDir, convID string, stepIndex int, role string, newText string, preserveHidden bool) error {
	db, _, err := openConvDB(appDataDir, convID)
	if err != nil {
		return err
	}
	defer db.Close()

	var stepType int
	var payload []byte
	if err := db.QueryRow(`SELECT step_type, step_payload FROM steps WHERE idx = ?`, stepIndex).Scan(&stepType, &payload); err != nil {
		return fmt.Errorf("load step %d: %w", stepIndex, err)
	}

	var updated []byte
	switch {
	case stepType == stepTypeUserInput || strings.EqualFold(role, "user"):
		updated, err = RewriteUserInputStep(payload, newText, preserveHidden)
	case stepType == stepTypePlannerResponse || strings.EqualFold(role, "assistant"):
		updated, err = RewritePlannerResponseStep(payload, newText)
	default:
		return fmt.Errorf("step %d has unsupported type %d for role %q", stepIndex, stepType, role)
	}
	if err != nil {
		return err
	}

	tx, err := db.Begin()
	if err != nil {
		return err
	}
	if _, err := tx.Exec(`UPDATE steps SET step_payload = ? WHERE idx = ?`, updated, stepIndex); err != nil {
		_ = tx.Rollback()
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	_, _ = db.Exec(`PRAGMA wal_checkpoint(TRUNCATE);`)

	if stepIndex == 0 {
		invalidateSummaryCache(appDataDir, convID)
	}
	_ = syncTranscriptFiles(brainDir, convID, func(entry map[string]interface{}) bool {
		idxVal, ok := jsonInt(entry["step_index"])
		if !ok || idxVal != stepIndex {
			return false
		}
		entry["content"] = newText
		return true
	})
	return nil
}

// DeleteThinking strips reasoning/thinking fields from each step in stepIndices inside conversations/<id>.db
// and removes corresponding `thinking` fields from transcript.jsonl / transcript_full.jsonl.
func DeleteThinking(appDataDir, brainDir, convID string, stepIndices []int) (int, error) {
	if len(stepIndices) == 0 {
		return 0, nil
	}
	db, _, err := openConvDB(appDataDir, convID)
	if err != nil {
		return 0, err
	}
	defer db.Close()

	targetSet := make(map[int]bool, len(stepIndices))
	for _, idx := range stepIndices {
		targetSet[idx] = true
	}

	tx, err := db.Begin()
	if err != nil {
		return 0, err
	}
	modified := 0
	for idx := range targetSet {
		var payload []byte
		if err := tx.QueryRow(`SELECT step_payload FROM steps WHERE idx = ?`, idx).Scan(&payload); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				continue
			}
			_ = tx.Rollback()
			return 0, err
		}
		updated, changed, err := StripThinkingFromStep(payload)
		if err != nil {
			_ = tx.Rollback()
			return 0, err
		}
		if changed {
			if _, err := tx.Exec(`UPDATE steps SET step_payload = ? WHERE idx = ?`, updated, idx); err != nil {
				_ = tx.Rollback()
				return 0, err
			}
			modified++
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	_, _ = db.Exec(`PRAGMA wal_checkpoint(TRUNCATE);`)

	_ = syncTranscriptFiles(brainDir, convID, func(entry map[string]interface{}) bool {
		idxVal, ok := jsonInt(entry["step_index"])
		if !ok || !targetSet[idxVal] {
			return false
		}
		if _, hasThinking := entry["thinking"]; hasThinking {
			delete(entry, "thinking")
			return true
		}
		return false
	})
	return modified, nil
}

// DeleteArtifact deletes the artifact file (and companion .metadata.json / .resolved* files) inside brain/<convID>/
// and redacts its written payload inside conversations/<convID>.db so the model no longer sees deleted content.
func DeleteArtifact(appDataDir, brainDir, convID, artifactURI, artifactName string) ([]string, int, error) {
	if err := validateConvID(convID); err != nil {
		return nil, 0, err
	}
	convBrainDir := filepath.Clean(filepath.Join(brainDir, convID))
	rawPath := strings.TrimSpace(strings.TrimPrefix(artifactURI, "file://"))
	var targetPath string
	switch {
	case rawPath == "" && artifactName != "":
		targetPath = filepath.Join(convBrainDir, filepath.Base(artifactName))
	case filepath.IsAbs(rawPath):
		targetPath = filepath.Clean(rawPath)
	case rawPath != "":
		targetPath = filepath.Clean(filepath.Join(convBrainDir, rawPath))
	default:
		return nil, 0, errors.New("artifact uri or name required")
	}

	rel, err := filepath.Rel(convBrainDir, targetPath)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return nil, 0, fmt.Errorf("artifact path %q escapes conversation brain directory", targetPath)
	}
	topComp := strings.Split(filepath.ToSlash(rel), "/")[0]
	if topComp == ".system_generated" {
		return nil, 0, errors.New("refusing to delete internal .system_generated logs")
	}

	if evalBrain, err := filepath.EvalSymlinks(convBrainDir); err == nil {
		if evalTarget, err := filepath.EvalSymlinks(targetPath); err == nil {
			r2, err2 := filepath.Rel(evalBrain, evalTarget)
			if err2 != nil || r2 == "." || r2 == ".." || strings.HasPrefix(r2, ".."+string(os.PathSeparator)) {
				return nil, 0, fmt.Errorf("symlink %q escapes conversation brain directory", targetPath)
			}
		}
	}

	var deleted []string
	candidates := []string{targetPath, targetPath + ".metadata.json"}
	if resolved, err := filepath.Glob(targetPath + ".resolved*"); err == nil {
		candidates = append(candidates, resolved...)
	}
	seenCand := map[string]bool{}
	for _, p := range candidates {
		if seenCand[p] {
			continue
		}
		seenCand[p] = true
		if fi, err := os.Lstat(p); err == nil && !fi.IsDir() {
			if err := os.Remove(p); err == nil {
				deleted = append(deleted, p)
			}
		}
	}

	// Scan and redact matching steps in SQLite
	redactedSteps := 0
	redactedStepSet := map[int]bool{}
	if db, _, err := openConvDB(appDataDir, convID); err == nil {
		defer db.Close()
		rows, qErr := db.Query(`SELECT idx, step_payload FROM steps ORDER BY idx`)
		if qErr == nil {
			type patch struct {
				idx     int
				payload []byte
			}
			var patches []patch
			for rows.Next() {
				var idx int
				var payload []byte
				if scanErr := rows.Scan(&idx, &payload); scanErr != nil {
					continue
				}
				updated, changed, pErr := RedactArtifactInStep(payload, rawPath, artifactName)
				if pErr == nil && changed {
					patches = append(patches, patch{idx: idx, payload: updated})
					redactedStepSet[idx] = true
				}
			}
			rows.Close()
			if len(patches) > 0 {
				if tx, tErr := db.Begin(); tErr == nil {
					for _, p := range patches {
						if _, uErr := tx.Exec(`UPDATE steps SET step_payload = ? WHERE idx = ?`, p.payload, p.idx); uErr == nil {
							redactedSteps++
						}
					}
					_ = tx.Commit()
				}
			}
		}
		_, _ = db.Exec(`PRAGMA wal_checkpoint(TRUNCATE);`)
	}

	if len(redactedStepSet) > 0 {
		label := artifactName
		if label == "" {
			label = filepath.Base(targetPath)
		}
		notice := fmt.Sprintf("[Artifact deleted by user: %s]", label)
		_ = syncTranscriptFiles(brainDir, convID, func(entry map[string]interface{}) bool {
			idxVal, ok := jsonInt(entry["step_index"])
			if !ok || !redactedStepSet[idxVal] {
				return false
			}
			changed := false
			if tcRaw, ok := entry["tool_calls"].([]interface{}); ok {
				for _, item := range tcRaw {
					if m, ok := item.(map[string]interface{}); ok {
						if args, ok := m["arguments"].(string); ok {
							if updated, redacted := redactToolJSONArgs(args, notice); redacted {
								m["arguments"] = updated
								changed = true
							}
						}
					}
				}
			}
			return changed
		})
	}

	return deleted, redactedSteps, nil
}

func invalidateSummaryCache(appDataDir, convID string) {
	summaryDb := filepath.Join(appDataDir, "conversation_summaries.db")
	if _, err := os.Stat(summaryDb); err != nil {
		return
	}
	db, err := sql.Open("sqlite", summaryDb)
	if err != nil {
		return
	}
	defer db.Close()
	_, _ = db.Exec(`PRAGMA busy_timeout(3000);`)
	now := time.Now().UTC().Format("2006-01-02 15:04:05.999999999+00:00")
	_, _ = db.Exec(`UPDATE conversation_summaries SET raw_summary = NULL, last_modified_time = ? WHERE conversation_id = ?`, now, convID)
}

func jsonInt(v interface{}) (int, bool) {
	f, ok := v.(float64)
	if !ok {
		return 0, false
	}
	return int(f), true
}

func syncTranscriptFiles(brainDir, convID string, mutate func(map[string]interface{}) bool) error {
	logsDir := filepath.Join(brainDir, convID, ".system_generated", "logs")
	for _, name := range []string{"transcript.jsonl", "transcript_full.jsonl"} {
		p := filepath.Join(logsDir, name)
		data, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		var out bytes.Buffer
		anyChanged := false
		sc := bufio.NewScanner(bytes.NewReader(data))
		sc.Buffer(make([]byte, 64<<10), 64<<20)
		for sc.Scan() {
			line := sc.Bytes()
			trimmed := bytes.TrimSpace(line)
			if len(trimmed) == 0 {
				continue
			}
			var obj map[string]interface{}
			if json.Unmarshal(trimmed, &obj) == nil && mutate(obj) {
				anyChanged = true
				if enc, err := json.Marshal(obj); err == nil {
					out.Write(enc)
					out.WriteByte('\n')
					continue
				}
			}
			out.Write(trimmed)
			out.WriteByte('\n')
		}
		if anyChanged {
			tmp := p + ".tmp"
			if err := os.WriteFile(tmp, out.Bytes(), 0o600); err == nil {
				_ = os.Rename(tmp, p)
			}
		}
	}
	return nil
}
