#!/bin/bash
set -euo pipefail

ANDROID_KMP_DIR="${SRCROOT}/.."
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

GRADLE_USER_HOME="$ANDROID_KMP_DIR/.gradle-local"
mkdir -p "$GRADLE_USER_HOME"

TASK_NAME=":msak-shared:assembleMsakShared${CFG_CAP}XCFramework"
echo "Building XCFramework via Gradle task: $TASK_NAME"

args=(./gradlew --no-daemon --gradle-user-home "$GRADLE_USER_HOME")
if [ -n "${JAVA_HOME:-}" ]; then
  args+=("-Dorg.gradle.java.home=$JAVA_HOME")
fi
args+=("$TASK_NAME")
"${args[@]}"

SRC_XCF="$ANDROID_KMP_DIR/msak-shared/build/XCFrameworks/$CFG_LOW/MsakShared.xcframework"
OUT_XCF="$ANDROID_KMP_DIR/msak-shared/build/XCFrameworks/Current/MsakShared.xcframework"

if [ ! -d "$SRC_XCF" ]; then
  echo "Missing Gradle XCFramework output: $SRC_XCF"
  exit 1
fi

mkdir -p "$(dirname "$OUT_XCF")"
rm -rf "$OUT_XCF"
cp -R "$SRC_XCF" "$OUT_XCF"

echo "XCFramework ready at: $OUT_XCF"
