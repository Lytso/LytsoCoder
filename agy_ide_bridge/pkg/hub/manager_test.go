package hub

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

func init() {
	if os.Getenv("LYTSO_FAKE_HUB_MODE") != "1" {
		return
	}
	port := "0"
	for _, arg := range os.Args[1:] {
		if strings.HasPrefix(arg, "--hub-port=") {
			port = strings.TrimPrefix(arg, "--hub-port=")
		}
	}
	csrf := fmt.Sprintf("fake-csrf-%d", os.Getpid())
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("x-codeium-csrf-token", csrf)
		w.WriteHeader(http.StatusOK)
		_, _ = fmt.Fprintf(w, `{"csrfToken":%q}`, csrf)
	})
	ln, err := net.Listen("tcp", "127.0.0.1:"+port)
	if err != nil {
		os.Exit(2)
	}
	srv := &http.Server{Handler: mux}
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-sigCh
		_ = srv.Close()
	}()
	_ = srv.Serve(ln)
	os.Exit(0)
}

func freeLocalPort(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("alloc free port: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	return strconv.Itoa(port)
}

func TestWithPausedHubRestartsCleanlyWithoutStoppedBroadcast(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}

	tmp := t.TempDir()
	port := freeLocalPort(t)
	t.Setenv("LYTSO_FAKE_HUB_MODE", "1")

	m := NewHubManager(port, tmp, tmp, "", exe)

	var statusMu sync.Mutex
	var statuses []string
	m.OnStatusChange = func(status, csrfToken, errorMsg string, logs []string) {
		statusMu.Lock()
		statuses = append(statuses, status)
		statusMu.Unlock()
	}

	if err := m.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer m.Stop()

	csrf1 := m.GetCsrfToken()
	if !strings.HasPrefix(csrf1, "fake-csrf-") {
		t.Fatalf("expected initial CSRF from fake hub, got %q", csrf1)
	}

	// Clear recorded initial startup statuses so we observe only pause + restart behavior.
	time.Sleep(50 * time.Millisecond)
	statusMu.Lock()
	statuses = nil
	statusMu.Unlock()

	portDownInsideFn := false
	restarted, err := m.WithPausedHub(func() error {
		portDownInsideFn = !m.IsPortActive()
		return nil
	})
	if err != nil {
		t.Fatalf("WithPausedHub: %v", err)
	}
	if !restarted {
		t.Fatal("expected restarted=true")
	}
	if !portDownInsideFn {
		t.Fatal("expected hub port to be down inside WithPausedHub callback")
	}

	csrf2 := m.GetCsrfToken()
	if !strings.HasPrefix(csrf2, "fake-csrf-") || csrf2 == csrf1 {
		t.Fatalf("expected new CSRF from restarted hub, got %q (initial was %q)", csrf2, csrf1)
	}

	time.Sleep(50 * time.Millisecond)
	statusMu.Lock()
	recorded := append([]string(nil), statuses...)
	statusMu.Unlock()
	for _, st := range recorded {
		if st == HubStatusStopped || st == HubStatusError || st == HubStatusStarting {
			t.Fatalf("unexpected transient status broadcast during WithPausedHub: %v", recorded)
		}
	}
}
