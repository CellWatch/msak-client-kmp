#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

"$ROOT_DIR/scripts/ensure-msak-docker.sh"

adb_bin="${ADB_BIN:-/Users/jeff/Library/Android/sdk/platform-tools/adb}"
if [ ! -x "$adb_bin" ]; then
  echo "adb not found at $adb_bin" >&2
  exit 1
fi

device_count="$("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { count += 1 } END { print count + 0 }')"
if [ "$device_count" -eq 0 ]; then
  echo "No Android emulator/device connected. Start one, then re-run this script." >&2
  exit 1
fi

cd "$ROOT_DIR"
./gradlew :msak-android-tester:connectedDebugAndroidTest
