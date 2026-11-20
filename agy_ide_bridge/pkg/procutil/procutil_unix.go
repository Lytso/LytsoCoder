//go:build unix

package procutil

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"syscall"
)

// Signal delivers sig directly via syscall.Kill instead of os.Process.Signal, avoiding Go 1.23+'s
// pidfd_send_signal (syscall 424 on arm64), which Android 11 seccomp-BPF blocks with SIGSYS.
func Signal(cmd *exec.Cmd, sig syscall.Signal) error {
	if cmd == nil || cmd.Process == nil || cmd.Process.Pid <= 0 {
		return nil
	}
	err := syscall.Kill(cmd.Process.Pid, sig)
	if errors.Is(err, syscall.ESRCH) {
		return os.ErrProcessDone
	}
	return err
}

// Interrupt sends SIGINT to cmd's process via syscall.Kill.
func Interrupt(cmd *exec.Cmd) error {
	return Signal(cmd, syscall.SIGINT)
}

// Terminate sends SIGTERM to cmd's process via syscall.Kill.
func Terminate(cmd *exec.Cmd) error {
	return Signal(cmd, syscall.SIGTERM)
}

// Kill sends SIGKILL to cmd's process via syscall.Kill.
func Kill(cmd *exec.Cmd) error {
	return Signal(cmd, syscall.SIGKILL)
}

// SignalGroup delivers sig to cmd's entire process group when it runs in its own group, falling back
// to the single process otherwise and never signaling the bridge's own process group.
func SignalGroup(cmd *exec.Cmd, sig syscall.Signal) error {
	if cmd == nil || cmd.Process == nil || cmd.Process.Pid <= 0 {
		return nil
	}
	pid := cmd.Process.Pid
	pgid, err := syscall.Getpgid(pid)
	if err == nil && pgid > 0 && pgid != os.Getpgrp() {
		err = syscall.Kill(-pgid, sig)
		if errors.Is(err, syscall.ESRCH) {
			return os.ErrProcessDone
		}
		return err
	}
	return Signal(cmd, sig)
}

// TerminateGroup sends SIGTERM to cmd's process group (or process).
func TerminateGroup(cmd *exec.Cmd) error {
	return SignalGroup(cmd, syscall.SIGTERM)
}

// KillGroup sends SIGKILL to cmd's process group (or process).
func KillGroup(cmd *exec.Cmd) error {
	return SignalGroup(cmd, syscall.SIGKILL)
}

// CommandContext creates an exec.Cmd whose context cancellation uses seccomp-safe syscall.Kill
// instead of Go's default cmd.Process.Kill (which uses pidfd_send_signal on Linux 5.3+).
func CommandContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Cancel = func() error {
		return Kill(cmd)
	}
	return cmd
}

// CommandContextGroup behaves like CommandContext and puts the child in its own process group so
// cancellation terminates child trees reliably.
func CommandContextGroup(ctx context.Context, name string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		return KillGroup(cmd)
	}
	return cmd
}
