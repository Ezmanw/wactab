#!/usr/bin/env bash
# Convenience launcher for the wactab Linux daemon: builds it on first run
# (needs cargo/rustc), then execs it, forwarding any arguments you pass here
# straight through to wactab-daemon — e.g.:
#
#   ./wactab.sh                  # default: plain-pointer mode (works everywhere)
#   ./wactab.sh --tablet-mode    # real pressure/tilt/hover (needs a compositor
#                                # with working Wayland tablet-v2 support, or X11)
#   ./wactab.sh --port 8000
#
# See README.md for what each mode means and how to confine the tablet to one
# monitor under X11.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/daemon"

if ! command -v cargo >/dev/null 2>&1; then
    echo "error: cargo (Rust toolchain) not found on PATH. Install it from https://rustup.rs" >&2
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "error: adb not found on PATH. Install android-tools / platform-tools first." >&2
    exit 1
fi

if [ ! -x target/release/wactab-daemon ]; then
    echo "Building wactab-daemon (first run only)..."
    cargo build --release
fi

exec ./target/release/wactab-daemon "$@"
