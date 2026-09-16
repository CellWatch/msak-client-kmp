#!/bin/bash
#
# Builds the MsakShared XCFramework with Gradle and publishes it to the stable
# path that msak-ios-tester.xcodeproj links against:
#
#   msak-shared/build/XCFrameworks/Current/MsakShared.xcframework
#
# Invoked from two places:
#   1. The "msak-ios-tester" shared scheme's build PRE-ACTION. This is the one
#      that matters for bootstrap: it runs before Xcode resolves the app
#      target's framework inputs, so a build succeeds even when
#      msak-shared/build has been deleted.
#   2. The "Compile Kotlin Framework" build phase of the msak-shared-xcframework
#      aggregate target (which the app target still depends on). By then the
#      pre-action has normally already produced the output, so the up-to-date
#      check below makes this a fast no-op.
#
# It is also runnable by hand:
#   CONFIGURATION=Debug ./msak-ios-tester/scripts/compile-kotlin-framework.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# SRCROOT/CONFIGURATION come from Xcode. Provide defaults so the script also
# works when run directly from a shell.
SRCROOT="${SRCROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
CONFIGURATION="${CONFIGURATION:-Debug}"

ANDROID_KMP_DIR="$(cd "${SRCROOT}/.." && pwd)"
cd "$ANDROID_KMP_DIR"

export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

# Prefer project-pinned Gradle JVM for deterministic Xcode builds.
JAVA_HOME_FROM_GRADLE=$(sed -n 's/^org\.gradle\.java\.home=//p' "$ANDROID_KMP_DIR/gradle.properties" | tail -n1)
if [ -n "$JAVA_HOME_FROM_GRADLE" ]; then
  export JAVA_HOME="$JAVA_HOME_FROM_GRADLE"
elif [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME_FROM_MACOS="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [ -n "$JAVA_HOME_FROM_MACOS" ]; then
    export JAVA_HOME="$JAVA_HOME_FROM_MACOS"
  fi
fi

if [ "${CONFIGURATION}" = "Debug" ]; then
  CFG_CAP="Debug"
  CFG_LOW="debug"
else
  CFG_CAP="Release"
  CFG_LOW="release"
fi

SRC_XCF="$ANDROID_KMP_DIR/msak-shared/build/XCFrameworks/$CFG_LOW/MsakShared.xcframework"
OUT_DIR="$ANDROID_KMP_DIR/msak-shared/build/XCFrameworks/Current"
OUT_XCF="$OUT_DIR/MsakShared.xcframework"
STAMP="$OUT_DIR/.bootstrap-stamp"

# Inputs that invalidate the published XCFramework.
inputs=(
  "$ANDROID_KMP_DIR/msak-shared/src"
  "$ANDROID_KMP_DIR/msak-shared/build.gradle.kts"
  "$ANDROID_KMP_DIR/build.gradle.kts"
  "$ANDROID_KMP_DIR/settings.gradle.kts"
  "$ANDROID_KMP_DIR/gradle.properties"
  "$ANDROID_KMP_DIR/gradle"
  "$SCRIPT_DIR/compile-kotlin-framework.sh"
)

# Skip the (expensive) Gradle invocation when the published output is already
# current for this configuration. This is what keeps the second invocation --
# the aggregate target's build phase, after the scheme pre-action already ran --
# from paying for a full Gradle startup on every build.
is_up_to_date() {
  [ -d "$OUT_XCF" ] || return 1
  [ -f "$STAMP" ] || return 1
  [ "$(cat "$STAMP" 2>/dev/null)" = "$CFG_CAP" ] || return 1
  local path
  for path in "${inputs[@]}"; do
    [ -e "$path" ] || continue
    if [ -n "$(find "$path" -newer "$STAMP" -print -quit 2>/dev/null)" ]; then
      return 1
    fi
  done
  return 0
}

if is_up_to_date; then
  echo "XCFramework already up to date for $CFG_CAP at: $OUT_XCF"
  exit 0
fi

TASK_NAME=":msak-shared:assembleMsakShared${CFG_CAP}XCFramework"
echo "Building XCFramework via Gradle task: $TASK_NAME"

GRADLE_USER_HOME="$ANDROID_KMP_DIR/.gradle-local"
mkdir -p "$GRADLE_USER_HOME"

args=(./gradlew --no-daemon --gradle-user-home "$GRADLE_USER_HOME")
if [ -n "${JAVA_HOME:-}" ]; then
  args+=("-Dorg.gradle.java.home=$JAVA_HOME")
fi
args+=("$TASK_NAME")
"${args[@]}"

if [ ! -d "$SRC_XCF" ]; then
  echo "Missing Gradle XCFramework output: $SRC_XCF" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
rm -rf "$OUT_XCF"
cp -R "$SRC_XCF" "$OUT_XCF"
printf '%s' "$CFG_CAP" > "$STAMP"
# The stamp must be at least as new as the copied tree for the up-to-date check.
touch "$STAMP"

echo "XCFramework ready at: $OUT_XCF"
