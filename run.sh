#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
java -cp out inventory.Main "${1:-8080}"
