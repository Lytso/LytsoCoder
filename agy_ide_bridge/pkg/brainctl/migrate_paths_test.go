package brainctl

import (
	"database/sql"
	"os"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

func TestRewriteWorkspaceURI(t *testing.T) {
	tmpDir := t.TempDir()
	// Simulate /data/user/0/lytso.code/files/home/projects inside tmpDir for existence checks
	// and also test pure Android root rewriting.
	fallback := filepath.Join(tmpDir, "projects")
	if err := os.MkdirAll(fallback, 0755); err != nil {
		t.Fatal(err)
	}

	// 1. Legacy ai.antigem URI with non-existent path -> falls back to fallback
	got, changed := RewriteWorkspaceURI(
		"file:///data/user/0/ai.antigem/files/home/projects",
		"/data/user/0/lytso.code",
		fallback,
	)
	if !changed || got != "file://"+fallback {
		t.Fatalf("expected fallback %q, got %q (changed=%v)", "file://"+fallback, got, changed)
	}

	// 2. Legacy /data/data/ai.antigem without fallback (pure string rewrite)
	got2, changed2 := RewriteWorkspaceURI(
		"file:///data/data/ai.antigem/files/home/projects",
		"/data/user/0/lytso.code",
		"",
	)
	if !changed2 || got2 != "file:///data/user/0/lytso.code/files/home/projects" {
		t.Fatalf("expected rewritten URI, got %q (changed=%v)", got2, changed2)
	}

	// 3. Different package name length (e.g. com.example.very.long.pkg -> lytso.code)
	got3, changed3 := RewriteWorkspaceURI(
		"file:///data/user/0/com.example.very.long.pkg/files/home/projects",
		"/data/user/0/lytso.code",
		"",
	)
	if !changed3 || got3 != "file:///data/user/0/lytso.code/files/home/projects" {
		t.Fatalf("expected rewritten URI for different pkg length, got %q (changed=%v)", got3, changed3)
	}

	// 4. Already valid existing directory -> no-op
	got4, changed4 := RewriteWorkspaceURI("file://"+fallback, "/data/user/0/lytso.code", fallback)
	if changed4 || got4 != "file://"+fallback {
		t.Fatalf("expected no-op for valid existing dir, got %q (changed=%v)", got4, changed4)
	}
}

func TestRewriteTrajectoryMetadataBlob(t *testing.T) {
	// Build a realistic trajectory_metadata_blob matching Antigem's exact protobuf schema:
	// field 1 (submsg): { field 1: "file:///data/user/0/ai.antigem/files/home/projects", field 3: "" }
	// field 2 (submsg): { field 1: varint 1795404721, field 2: varint 955667181 }
	// field 3 (string): "1365489b-169c-414e-8178-36e5e746d413"
	// field 6 (string): "97cd2611-21f9-45ef-802e-53621e6225ff"
	// field 7 (string): "file:///data/user/0/ai.antigem/files/home/projects"
	// field 15 (opaque bytes): []byte{0x01, 0x02, 0x03, 0x04}
	wsEntry := encodeFields([]rawField{
		{num: 1, wireType: 2, data: []byte("file:///data/user/0/com.old.antigem.app/files/home/projects")},
		{num: 3, wireType: 2, data: []byte("")},
	})
	ts := encodeFields([]rawField{
		{num: 1, wireType: 0, varint: 1795404721},
		{num: 2, wireType: 0, varint: 955667181},
	})
	opaque := []byte{0xde, 0xad, 0xbe, 0xef}
	orig := encodeFields([]rawField{
		{num: 1, wireType: 2, data: wsEntry},
		{num: 2, wireType: 2, data: ts},
		{num: 3, wireType: 2, data: []byte("1365489b-169c-414e-8178-36e5e746d413")},
		{num: 6, wireType: 2, data: []byte("97cd2611-21f9-45ef-802e-53621e6225ff")},
		{num: 7, wireType: 2, data: []byte("file:///data/user/0/com.old.antigem.app/files/home/projects")},
		{num: 15, wireType: 2, data: opaque},
	})

	updated, changed, err := RewriteTrajectoryMetadataBlob(orig, "/data/user/0/lytso.code", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !changed {
		t.Fatalf("expected blob to be changed")
	}

	fields, err := decodeFields(updated)
	if err != nil {
		t.Fatalf("updated blob failed protobuf decode: %v", err)
	}
	expectedURI := "file:///data/user/0/lytso.code/files/home/projects"
	if got7, ok := firstBytesField(fields, 7); !ok || string(got7) != expectedURI {
		t.Fatalf("field 7 = %q, want %q", string(got7), expectedURI)
	}
	wsRaw, ok := firstBytesField(fields, 1)
	if !ok {
		t.Fatalf("missing field 1")
	}
	sub, err := decodeFields(wsRaw)
	if err != nil {
		t.Fatalf("field 1 submsg decode error: %v", err)
	}
	if got1, ok := firstBytesField(sub, 1); !ok || string(got1) != expectedURI {
		t.Fatalf("field 1.1 = %q, want %q", string(got1), expectedURI)
	}
	if got6, ok := firstBytesField(fields, 6); !ok || string(got6) != "97cd2611-21f9-45ef-802e-53621e6225ff" {
		t.Fatalf("field 6 altered: %q", string(got6))
	}
	if got15, ok := firstBytesField(fields, 15); !ok || string(got15) != string(opaque) {
		t.Fatalf("field 15 opaque bytes altered")
	}
}

func TestHealAllConversationsSQLite(t *testing.T) {
	tmp := t.TempDir()
	appDataDir := filepath.Join(tmp, "antigravity-cli")
	convsDir := filepath.Join(appDataDir, "conversations")
	wsDir := filepath.Join(tmp, "projects")
	if err := os.MkdirAll(convsDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(wsDir, 0755); err != nil {
		t.Fatal(err)
	}

	convID := "test-conv-1234"
	dbPath := filepath.Join(convsDir, convID+".db")
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`CREATE TABLE trajectory_metadata_blob (id text PRIMARY KEY, data blob)`)
	if err != nil {
		t.Fatal(err)
	}
	wsEntry := encodeFields([]rawField{
		{num: 1, wireType: 2, data: []byte("file:///data/user/0/ai.antigem/files/home/projects")},
	})
	blob := encodeFields([]rawField{
		{num: 1, wireType: 2, data: wsEntry},
		{num: 7, wireType: 2, data: []byte("file:///data/user/0/ai.antigem/files/home/projects")},
	})
	if _, err := db.Exec(`INSERT INTO trajectory_metadata_blob (id, data) VALUES ('main', ?)`, blob); err != nil {
		t.Fatal(err)
	}
	db.Close()

	// Create conversation_summaries.db with stale workspace_uris
	sumPath := filepath.Join(appDataDir, "conversation_summaries.db")
	sdb, err := sql.Open("sqlite", sumPath)
	if err != nil {
		t.Fatal(err)
	}
	_, err = sdb.Exec(`CREATE TABLE conversation_summaries (conversation_id text PRIMARY KEY, workspace_uris text NOT NULL, raw_summary BLOB)`)
	if err != nil {
		t.Fatal(err)
	}
	_, err = sdb.Exec(`INSERT INTO conversation_summaries VALUES (?, '["file:///data/user/0/ai.antigem/files/home/projects"]', X'0102')`, convID)
	if err != nil {
		t.Fatal(err)
	}
	sdb.Close()

	healedCount, err := HealAllConversations(appDataDir, wsDir)
	if err != nil {
		t.Fatalf("HealAllConversations error: %v", err)
	}
	if healedCount != 1 {
		t.Fatalf("expected 1 healed conversation, got %d", healedCount)
	}

	// Verify DB blob was rewritten to wsDir
	db2, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer db2.Close()
	var healedBlob []byte
	if err := db2.QueryRow(`SELECT data FROM trajectory_metadata_blob WHERE id = 'main'`).Scan(&healedBlob); err != nil {
		t.Fatal(err)
	}
	if got := extractPrimaryWorkspaceURI(healedBlob); got != "file://"+wsDir {
		t.Fatalf("expected healed URI %q, got %q", "file://"+wsDir, got)
	}

	// Verify conversation_summaries.db was healed and raw_summary cleared
	sdb2, err := sql.Open("sqlite", sumPath)
	if err != nil {
		t.Fatal(err)
	}
	defer sdb2.Close()
	var uris string
	var rawSum []byte
	if err := sdb2.QueryRow(`SELECT workspace_uris, raw_summary FROM conversation_summaries WHERE conversation_id = ?`, convID).Scan(&uris, &rawSum); err != nil {
		t.Fatal(err)
	}
	wantJSON := `["file://` + wsDir + `"]`
	if uris != wantJSON {
		t.Fatalf("summary workspace_uris = %q, want %q", uris, wantJSON)
	}
	if rawSum != nil {
		t.Fatalf("expected raw_summary to be NULL after heal")
	}

	// Second run should be a 0-change no-op
	healedCount2, err := HealAllConversations(appDataDir, wsDir)
	if err != nil || healedCount2 != 0 {
		t.Fatalf("expected second pass to be no-op (0), got %d (err=%v)", healedCount2, err)
	}
}
