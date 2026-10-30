#!/usr/bin/env bash
# Foreground SSH tunnel. No public forwarding or saved password.
set -euo pipefail
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 admin@nas [ssh options: -i /path/to/key ...]" >&2
  exit 2
fi
remote="$1"
shift
exec ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -L 127.0.0.1:13000:127.0.0.1:13000 "$@" "$remote"
