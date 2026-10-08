#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
if command -v node >/dev/null 2>&1; then
    exec node "$SCRIPT_DIR/start.mjs" "$@"
fi
printf '%s\n' 'Node.js 20+ and JDK 17+ are required. Install Node.js and add it to PATH.' >&2
exit 1
