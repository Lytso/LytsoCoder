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
	"sort"
	"strconv"
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

// DeleteSteps permanently removes the specified step indices from:
//  1. conversations/<convID>.db (SQLite `steps` table, compacting remaining `idx` values 0..N-1 atomically),
//  2. brain/<convID>/.system_generated/steps/<idx>/ directories (deleting removed ones and reindexing remaining),
//  3. brain/<convID>/.system_generated/logs/transcript*.jsonl (dropping deleted entries and remapping step_index),
//  4. conversation_summaries.db cache invalidation.
func DeleteSteps(appDataDir, brainDir, convID string, stepIndices []int) (int, error) {
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
		if idx >= 0 {
			targetSet[idx] = true
		}
	}
	if len(targetSet) == 0 {
		return 0, nil
	}

	tx, err := db.Begin()
	if err != nil {
		return 0, err
	}

	// Read all existing step indices in order before deletion
	rows, err := tx.Query(`SELECT idx FROM steps ORDER BY idx ASC`)
	if err != nil {
		_ = tx.Rollback()
		return 0, err
	}
	var existing []int
	for rows.Next() {
		var idx int
		if scanErr := rows.Scan(&idx); scanErr == nil {
			existing = append(existing, idx)
		}
	}
	rows.Close()

	deletedCount := 0
	for idx := range targetSet {
		res, delErr := tx.Exec(`DELETE FROM steps WHERE idx = ?`, idx)
		if delErr != nil {
			_ = tx.Rollback()
			return 0, delErr
		}
		if n, _ := res.RowsAffected(); n > 0 {
			deletedCount += int(n)
		}
	}

	if deletedCount == 0 {
		_ = tx.Rollback()
		// Still prune any leftover brain step directories / transcript entries if present
		pruneBrainStepDirs(brainDir, convID, targetSet, nil)
		_ = pruneTranscriptSteps(brainDir, convID, targetSet, nil)
		return 0, nil
	}

	// Build oldIdx -> newIdx compaction map for remaining steps (0..N-1)
	remap := make(map[int]int, len(existing))
	var remaining []int
	for _, oldIdx := range existing {
		if targetSet[oldIdx] {
			continue
		}
		newIdx := len(remaining)
		remap[oldIdx] = newIdx
		remaining = append(remaining, oldIdx)
	}

	// Two-phase reindex using negative temp keys to avoid PRIMARY KEY collisions
	for _, oldIdx := range remaining {
		newIdx := remap[oldIdx]
		if oldIdx == newIdx {
			continue
		}
		tempIdx := -(newIdx + 1)
		if _, err := tx.Exec(`UPDATE steps SET idx = ? WHERE idx = ?`, tempIdx, oldIdx); err != nil {
			_ = tx.Rollback()
			return 0, fmt.Errorf("reindex phase 1 (%d -> %d): %w", oldIdx, tempIdx, err)
		}
	}
	for _, oldIdx := range remaining {
		newIdx := remap[oldIdx]
		if oldIdx == newIdx {
			continue
		}
		tempIdx := -(newIdx + 1)
		if _, err := tx.Exec(`UPDATE steps SET idx = ? WHERE idx = ?`, newIdx, tempIdx); err != nil {
			_ = tx.Rollback()
			return 0, fmt.Errorf("reindex phase 2 (%d -> %d): %w", tempIdx, newIdx, err)
		}
	}

	if err := tx.Commit(); err != nil {
		return 0, err
	}
	_, _ = db.Exec(`PRAGMA wal_checkpoint(TRUNCATE);`)
	invalidateSummaryCache(appDataDir, convID)

	// Remove deleted step folders and reindex remaining ones in brain/<convID>/.system_generated/steps/
	pruneBrainStepDirs(brainDir, convID, targetSet, remap)

	// Drop deleted steps and remap step_index in transcript.jsonl / transcript_full.jsonl
	_ = pruneTranscriptSteps(brainDir, convID, targetSet, remap)

	return deletedCount, nil
}

func pruneBrainStepDirs(brainDir, convID string, targetSet map[int]bool, remap map[int]int) {
	if brainDir == "" {
		return
	}
	stepsDir := filepath.Join(brainDir, convID, ".system_generated", "steps")
	if fi, err := os.Stat(stepsDir); err != nil || !fi.IsDir() {
		return
	}
	for idx := range targetSet {
		_ = os.RemoveAll(filepath.Join(stepsDir, strconv.Itoa(idx)))
	}
	if len(remap) == 0 {
		return
	}
	// Two-phase rename for remaining step dirs whose index shifted
	var toMove []int
	for oldIdx, newIdx := range remap {
		if oldIdx != newIdx {
			toMove = append(toMove, oldIdx)
		}
	}
	sort.Ints(toMove)
	for _, oldIdx := range toMove {
		src := filepath.Join(stepsDir, strconv.Itoa(oldIdx))
		if _, err := os.Stat(src); err == nil {
			_ = os.Rename(src, filepath.Join(stepsDir, fmt.Sprintf(".tmp_reindex_%d", remap[oldIdx])))
		}
	}
	for _, oldIdx := range toMove {
		newIdx := remap[oldIdx]
		tmp := filepath.Join(stepsDir, fmt.Sprintf(".tmp_reindex_%d", newIdx))
		if _, err := os.Stat(tmp); err == nil {
			_ = os.Rename(tmp, filepath.Join(stepsDir, strconv.Itoa(newIdx)))
		}
	}
}

func pruneTranscriptSteps(brainDir, convID string, targetSet map[int]bool, remap map[int]int) error {
	if brainDir == "" {
		return nil
	}
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
			trimmed := bytes.TrimSpace(sc.Bytes())
			if len(trimmed) == 0 {
				continue
			}
			var obj map[string]interface{}
			if json.Unmarshal(trimmed, &obj) == nil {
				if idxVal, ok := jsonInt(obj["step_index"]); ok {
					if targetSet[idxVal] {
						anyChanged = true
						continue // drop deleted step completely
					}
					if newIdx, shifted := remap[idxVal]; shifted && newIdx != idxVal {
						obj["step_index"] = newIdx
						anyChanged = true
						if enc, err := json.Marshal(obj); err == nil {
							out.Write(enc)
							out.WriteByte('\n')
							continue
						}
					}
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
