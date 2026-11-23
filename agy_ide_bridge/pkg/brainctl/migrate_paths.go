package brainctl

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"

	_ "modernc.org/sqlite"
)

var (
	// Matches Android app data roots: /data/user/<uid>/<pkg> or /data/data/<pkg>
	androidAppRootRe = regexp.MustCompile(`^(/data/(?:user/\d+|data)/)([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)(/.*)?$`)
)

// DetectAppDataRoot extracts the current Android app root (e.g. "/data/user/0/lytso.code")
// from appDataDir or workspaceDir. Returns "" on non-Android hosts.
func DetectAppDataRoot(appDataDir, workspaceDir string) string {
	for _, candidate := range []string{workspaceDir, appDataDir} {
		p := strings.TrimPrefix(strings.TrimSpace(candidate), "file://")
		if m := androidAppRootRe.FindStringSubmatch(p); m != nil {
			return m[1] + m[2]
		}
	}
	return ""
}

// extractAndroidPackage returns the package name from an Android data path, or "".
func extractAndroidPackage(p string) string {
	clean := strings.TrimPrefix(strings.TrimSpace(p), "file://")
	if m := androidAppRootRe.FindStringSubmatch(clean); m != nil {
		return m[2]
	}
	return ""
}

// isForeignAndroidPath returns true if raw points to a different Android app's data directory
// (e.g. /data/user/0/ai.antigem/... when running under lytso.code).
func isForeignAndroidPath(raw, currentRoot string) bool {
	if currentRoot == "" {
		return false
	}
	curPkg := extractAndroidPackage(currentRoot)
	if curPkg == "" {
		return false
	}
	otherPkg := extractAndroidPackage(raw)
	return otherPkg != "" && otherPkg != curPkg
}

// RewriteWorkspaceURI autonomously rewrites a legacy/foreign Android app workspace URI or path
// (e.g. "file:///data/user/0/ai.antigem/files/home/projects") to the current app's workspace
// (e.g. "file:///data/user/0/lytso.code/files/home/projects").
// If the resulting local path does not exist on disk and fallbackWorkspace is provided,
// it ensures fallbackWorkspace exists and falls back to it so terminals never hang.
func RewriteWorkspaceURI(raw, currentRoot, fallbackWorkspace string) (string, bool) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return raw, false
	}
	hadFileScheme := strings.HasPrefix(trimmed, "file://")
	localPath := strings.TrimPrefix(trimmed, "file://")
	if !strings.HasPrefix(localPath, "/") {
		return raw, false
	}

	changed := false
	rewritten := localPath

	// 1. Rewrite any foreign Android package root (/data/user/<n>/<other.pkg> or /data/data/<other.pkg>)
	if currentRoot != "" {
		if m := androidAppRootRe.FindStringSubmatch(localPath); m != nil {
			curPkg := extractAndroidPackage(currentRoot)
			if curPkg != "" && m[2] != curPkg {
				rewritten = strings.TrimRight(currentRoot, "/") + m[3]
				changed = true
			}
		}
	}

	// 2. Verify the target directory actually exists (when running locally with a fallbackWorkspace).
	// If the path was rewritten OR points to a non-existent/unreadable directory, heal it cleanly.
	fallbackPath := strings.TrimPrefix(strings.TrimSpace(fallbackWorkspace), "file://")
	if fallbackPath != "" {
		fi, err := os.Stat(rewritten)
		if err != nil || !fi.IsDir() {
			// If it's the exact fallback workspace (e.g. ~/projects), create it.
			if filepath.Clean(rewritten) == filepath.Clean(fallbackPath) {
				_ = os.MkdirAll(rewritten, 0755)
			} else if changed || isForeignAndroidPath(localPath, currentRoot) || err != nil {
				// Non-existent workspace directory: fall back to the active app's projects workspace.
				_ = os.MkdirAll(fallbackPath, 0755)
				rewritten = fallbackPath
				changed = true
			}
		}
	}

	if !changed {
		return raw, false
	}
	if hadFileScheme {
		return "file://" + rewritten, true
	}
	return rewritten, true
}

// RewriteTrajectoryMetadataBlob decodes a conversation's protobuf trajectory_metadata_blob
// and rewrites any legacy/foreign or non-existent workspace URIs (in field 1 workspace entries,
// field 7 primary workspace URI, and any other UTF-8 path fields) while preserving all other
// protobuf fields (timestamps, UUIDs, unknown fields) verbatim.
func RewriteTrajectoryMetadataBlob(blob []byte, currentRoot, fallbackWorkspace string) ([]byte, bool, error) {
	if len(blob) == 0 {
		return blob, false, nil
	}
	fields, err := decodeFields(blob)
	if err != nil {
		return blob, false, err
	}
	anyChanged := false
	for i := range fields {
		f := &fields[i]
		if f.wireType != 2 || len(f.data) == 0 {
			continue
		}
		// Field 1 is repeated WorkspaceMetadata submessage: { 1: workspace_uri, 2: ..., 3: ... }
		if f.num == 1 {
			subFields, subErr := decodeFields(f.data)
			if subErr == nil && len(subFields) > 0 {
				subChanged := false
				for j := range subFields {
					sf := &subFields[j]
					if sf.wireType == 2 && len(sf.data) > 0 && utf8.Valid(sf.data) {
						s := string(sf.data)
						if strings.HasPrefix(s, "file:///") || strings.HasPrefix(s, "/data/") {
							if updated, ok := RewriteWorkspaceURI(s, currentRoot, fallbackWorkspace); ok {
								sf.data = []byte(updated)
								subChanged = true
							}
						}
					}
				}
				if subChanged {
					f.data = encodeFields(subFields)
					anyChanged = true
				}
				continue
			}
		}

		// Top-level UTF-8 string fields (e.g. field 7 primary workspace URI)
		if utf8.Valid(f.data) {
			s := string(f.data)
			if strings.HasPrefix(s, "file:///") || strings.HasPrefix(s, "/data/") {
				if updated, ok := RewriteWorkspaceURI(s, currentRoot, fallbackWorkspace); ok {
					f.data = []byte(updated)
					anyChanged = true
				}
			}
		}
	}
	if !anyChanged {
		return blob, false, nil
	}
	return encodeFields(fields), true, nil
}

// HealConversationWorkspace inspects a single conversation's SQLite DB (trajectory_metadata_blob)
// and conversation_summaries.db, rewriting any legacy/foreign or non-existent workspace URIs
// to the current app's valid workspace. Safe and fast (no-op if already healthy).
func HealConversationWorkspace(appDataDir, convID, workspaceDir string) (bool, string, error) {
	if err := validateConvID(convID); err != nil {
		return false, "", err
	}
	currentRoot := DetectAppDataRoot(appDataDir, workspaceDir)
	fallback := strings.TrimSpace(workspaceDir)
	if fallback != "" {
		_ = os.MkdirAll(strings.TrimPrefix(fallback, "file://"), 0755)
	}

	healed := false
	resolvedURI := ""
	if fallback != "" {
		if strings.HasPrefix(fallback, "file://") {
			resolvedURI = fallback
		} else {
			resolvedURI = "file://" + fallback
		}
	}

	// 1. Heal conversations/<convID>.db -> trajectory_metadata_blob
	dbPath := filepath.Join(appDataDir, "conversations", convID+".db")
	if fi, err := os.Stat(dbPath); err == nil && !fi.IsDir() {
		db, _, openErr := openConvDB(appDataDir, convID)
		if openErr != nil {
			return false, resolvedURI, openErr
		}
		defer db.Close()

		rows, qErr := db.Query(`SELECT id, data FROM trajectory_metadata_blob`)
		if qErr == nil {
			type blobPatch struct {
				id   string
				data []byte
			}
			var patches []blobPatch
			for rows.Next() {
				var id string
				var data []byte
				if scanErr := rows.Scan(&id, &data); scanErr != nil {
					continue
				}
				updated, changed, pErr := RewriteTrajectoryMetadataBlob(data, currentRoot, fallback)
				if pErr == nil && changed {
					patches = append(patches, blobPatch{id: id, data: updated})
				}
				// Extract primary URI from updated (or existing) blob for return value
				if uri := extractPrimaryWorkspaceURI(updated); uri != "" {
					resolvedURI = uri
				}
			}
			rows.Close()

			if len(patches) > 0 {
				tx, txErr := db.Begin()
				if txErr != nil {
					return false, resolvedURI, txErr
				}
				for _, p := range patches {
					if _, uErr := tx.Exec(`UPDATE trajectory_metadata_blob SET data = ? WHERE id = ?`, p.data, p.id); uErr != nil {
						_ = tx.Rollback()
						return false, resolvedURI, uErr
					}
				}
				if cErr := tx.Commit(); cErr != nil {
					return false, resolvedURI, cErr
				}
				_, _ = db.Exec(`PRAGMA wal_checkpoint(TRUNCATE);`)
				healed = true
			}
		}
	}

	// 2. Heal conversation_summaries.db row for convID
	if sumChanged := healSummaryRow(appDataDir, convID, currentRoot, fallback); sumChanged {
		healed = true
	}

	return healed, resolvedURI, nil
}

// HealAllConversations scans all conversations/*.db and conversation_summaries.db
// and autonomously heals any chat carrying a legacy/foreign or non-existent workspace path.
func HealAllConversations(appDataDir, workspaceDir string) (int, error) {
	healedCount := 0
	convsDir := filepath.Join(appDataDir, "conversations")
	entries, err := os.ReadDir(convsDir)
	if err == nil {
		for _, e := range entries {
			if e.IsDir() || !strings.HasSuffix(e.Name(), ".db") {
				continue
			}
			convID := strings.TrimSuffix(e.Name(), ".db")
			if convID == "" {
				continue
			}
			if changed, _, hErr := HealConversationWorkspace(appDataDir, convID, workspaceDir); hErr == nil && changed {
				healedCount++
				fmt.Printf("[WorkspaceHeal] Automatically migrated workspace paths for conversation %s\n", convID)
			}
		}
	}

	// Also sweep conversation_summaries.db for any orphaned summary rows with legacy workspace_uris
	currentRoot := DetectAppDataRoot(appDataDir, workspaceDir)
	if currentRoot != "" {
		sumPath := filepath.Join(appDataDir, "conversation_summaries.db")
		if _, sErr := os.Stat(sumPath); sErr == nil {
			if db, dErr := sql.Open("sqlite", sumPath); dErr == nil {
				defer db.Close()
				_, _ = db.Exec(`PRAGMA busy_timeout(3000);`)
				rows, qErr := db.Query(`SELECT conversation_id, workspace_uris FROM conversation_summaries`)
				if qErr == nil {
					var toFix []string
					for rows.Next() {
						var id, uris string
						if rows.Scan(&id, &uris) == nil && uris != "" {
							if _, changed := rewriteWorkspaceURIsJSON(uris, currentRoot, workspaceDir); changed {
								toFix = append(toFix, id)
							}
						}
					}
					rows.Close()
					for _, id := range toFix {
						if healSummaryRow(appDataDir, id, currentRoot, workspaceDir) {
							healedCount++
						}
					}
				}
			}
		}
	}

	return healedCount, nil
}

func healSummaryRow(appDataDir, convID, currentRoot, fallbackWorkspace string) bool {
	sumPath := filepath.Join(appDataDir, "conversation_summaries.db")
	if _, err := os.Stat(sumPath); err != nil {
		return false
	}
	db, err := sql.Open("sqlite", sumPath)
	if err != nil {
		return false
	}
	defer db.Close()
	_, _ = db.Exec(`PRAGMA busy_timeout(3000);`)

	var urisStr string
	if err := db.QueryRow(`SELECT workspace_uris FROM conversation_summaries WHERE conversation_id = ?`, convID).Scan(&urisStr); err != nil {
		return false
	}
	updated, changed := rewriteWorkspaceURIsJSON(urisStr, currentRoot, fallbackWorkspace)
	if !changed {
		return false
	}
	if _, err := db.Exec(`UPDATE conversation_summaries SET workspace_uris = ?, raw_summary = NULL WHERE conversation_id = ?`, updated, convID); err != nil {
		return false
	}
	_, _ = db.Exec(`PRAGMA wal_checkpoint(TRUNCATE);`)
	return true
}

func rewriteWorkspaceURIsJSON(rawJSON, currentRoot, fallbackWorkspace string) (string, bool) {
	trimmed := strings.TrimSpace(rawJSON)
	if trimmed == "" {
		return rawJSON, false
	}
	var list []string
	if err := json.Unmarshal([]byte(trimmed), &list); err != nil {
		// Fallback: single raw URI string
		if updated, ok := RewriteWorkspaceURI(trimmed, currentRoot, fallbackWorkspace); ok {
			return updated, true
		}
		return rawJSON, false
	}
	changed := false
	for i, u := range list {
		if updated, ok := RewriteWorkspaceURI(u, currentRoot, fallbackWorkspace); ok {
			list[i] = updated
			changed = true
		}
	}
	if !changed {
		return rawJSON, false
	}
	out, err := json.Marshal(list)
	if err != nil {
		return rawJSON, false
	}
	return string(out), true
}

func extractPrimaryWorkspaceURI(blob []byte) string {
	fields, err := decodeFields(blob)
	if err != nil {
		return ""
	}
	if b, ok := firstBytesField(fields, 7); ok && utf8.Valid(b) && len(b) > 0 {
		return string(b)
	}
	if wsRaw, ok := firstBytesField(fields, 1); ok {
		if sub, sErr := decodeFields(wsRaw); sErr == nil {
			if b, ok := firstBytesField(sub, 1); ok && utf8.Valid(b) && len(b) > 0 {
				return string(b)
			}
		}
	}
	return ""
}
