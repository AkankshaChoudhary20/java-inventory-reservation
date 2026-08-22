#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
java -ea -cp out inventory.InventoryServiceTest
