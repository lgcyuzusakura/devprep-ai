#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
if command -v python3 >/dev/null 2>&1; then
    exec python3 "$SCRIPT_DIR/start.py" "$@"
fi
if command -v python >/dev/null 2>&1; then
    exec python "$SCRIPT_DIR/start.py" "$@"
fi
printf '%s\n' 'Python 3.11+ is required. Install Python and add it to PATH.' >&2
exit 1
