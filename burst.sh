#!/usr/bin/env bash
# One-command on-sale stampede + correctness audit.
#   ./burst.sh <BASE_URL> [options]        e.g. ./burst.sh http://localhost:8080
# Options: ./burst.sh --help
# Needs only python3 (standard library). Set ADMIN_TOKEN for a deployed instance.
set -euo pipefail
exec python3 "$(dirname "$0")/scripts/burst.py" "$@"
