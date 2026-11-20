//go:build !unix

package procutil

import (
	"context"
	"os"
	"os/exec"
)

// Interrupt sends os.Interrupt to cmd's process.
func Interrupt(cmd *exec.Cmd) error {
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	return cmd.Process.Signal(os.Interrupt)
}

// Terminate stops cmd's process.
func Terminate(cmd *exec.Cmd) error {
	return Kill(cmd)
}

// Kill forcefully stops cmd's process.
func Kill(cmd *exec.Cmd) error {
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	return cmd.Process.Kill()
}

// TerminateGroup falls back to Terminate on non-Unix platforms.
func TerminateGroup(cmd *exec.Cmd) error {
	return Terminate(cmd)
}

// KillGroup falls back to Kill on non-Unix platforms.
func KillGroup(cmd *exec.Cmd) error {
	return Kill(cmd)
}

// CommandContext returns exec.CommandContext on non-Unix platforms.
func CommandContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, name, args...)
}

// CommandContextGroup returns exec.CommandContext on non-Unix platforms.
func CommandContextGroup(ctx context.Context, name string, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, name, args...)
}
