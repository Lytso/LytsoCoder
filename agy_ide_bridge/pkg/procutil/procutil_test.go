package procutil

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"testing"
	"time"
)

func init() {
	if os.Getenv("LYTSO_PROCUTIL_HELPER") == "1" {
		time.Sleep(30 * time.Second)
		os.Exit(0)
	}
}

func TestCommandContextCancelTerminatesChildWithoutPidfd(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
	defer cancel()

	cmd := CommandContext(ctx, exe)
	cmd.Env = append(os.Environ(), "LYTSO_PROCUTIL_HELPER=1")

	start := time.Now()
	err = cmd.Run()
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("expected error after context timeout")
	}
	if elapsed > 3*time.Second {
		t.Fatalf("command cancellation took too long: %v", elapsed)
	}
}

func TestSignalHelpersKillAndHandleExitedProcess(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	cmd := CommandContextGroup(ctx, exe)
	cmd.Env = append(os.Environ(), "LYTSO_PROCUTIL_HELPER=1")
	if err := cmd.Start(); err != nil {
		t.Fatalf("start helper: %v", err)
	}

	if err := Interrupt(cmd); err != nil {
		_ = KillGroup(cmd)
		_ = cmd.Wait()
		t.Fatalf("Interrupt failed: %v", err)
	}
	if err := KillGroup(cmd); err != nil && !errors.Is(err, os.ErrProcessDone) {
		_ = cmd.Wait()
		t.Fatalf("KillGroup failed: %v", err)
	}
	_ = cmd.Wait()

	// Calling Kill/TerminateGroup on an already-reaped command must not panic or fail unexpectedly.
	if err := TerminateGroup(cmd); err != nil && !errors.Is(err, os.ErrProcessDone) {
		t.Fatalf("TerminateGroup after Wait: %v", err)
	}
	var nilCmd *exec.Cmd
	if err := Kill(nilCmd); err != nil {
		t.Fatalf("Kill(nil) returned error: %v", err)
	}
}
