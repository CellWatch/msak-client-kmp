#!/bin/bash
#
# Regression check for the XCFramework bootstrap path.
#
# Deletes the generated Kotlin/Native build output (including the
# msak-shared/build/XCFrameworks/Current/MsakShared.xcframework that
# msak-ios-tester.xcodeproj links against) and then runs a *normal*
# msak-ios-tester build. The build must succeed with no manual
# msak-shared-xcframework build first.
#
# Usage:
#   ./msak-ios-tester/scripts/verify-xcframework-bootstrap.sh [Debug|Release] [destination]
#
set -euo pipefail

CONFIG="${1:-Debug}"
DESTINATION="${2:-platform=iOS Simulator,name=iPhone 17 Pro}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TESTER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$TESTER_DIR/.." && pwd)"

XCF="$REPO_DIR/msak-shared/build/XCFrameworks/Current/MsakShared.xcframework"

fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== Removing generated Kotlin/Native output: $REPO_DIR/msak-shared/build"
rm -rf "$REPO_DIR/msak-shared/build"
[ -e "$XCF" ] && fail "framework still present after delete: $XCF"
echo "   confirmed absent: $XCF"

echo "== Building the normal tester scheme ($CONFIG) with no preliminary aggregate build"
set +e
xcodebuild \
  -project "$TESTER_DIR/msak-ios-tester.xcodeproj" \
  -scheme msak-ios-tester \
  -configuration "$CONFIG" \
  -destination "$DESTINATION" \
  build
BUILD_STATUS=$?
set -e

[ "$BUILD_STATUS" -eq 0 ] || fail "xcodebuild exited $BUILD_STATUS"
[ -d "$XCF" ] || fail "framework was not regenerated at $XCF"
[ -f "$XCF/Info.plist" ] || fail "regenerated framework has no Info.plist: $XCF"

echo
echo "PASS: $CONFIG build succeeded and regenerated $XCF"
