#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -d out $(find src/main src/test -name "*.java")
echo "compiled to out/"
