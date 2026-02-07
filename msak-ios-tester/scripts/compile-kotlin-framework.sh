#!/bin/bash
set -euo pipefail

echo "*************************"
echo "ENABLE_USER_SCRIPT_SANDBOXING: ${ENABLE_USER_SCRIPT_SANDBOXING:-<unset>}"
if [ -n "${TARGET_BUILD_DIR:-}" ] && [ -n "${INFOPLIST_PATH:-}" ]; then
  /usr/libexec/PlistBuddy -c "Print :BuildSettings:ENABLE_USER_SCRIPT_SANDBOXING" "${TARGET_BUILD_DIR}/${INFOPLIST_PATH}" 2>/dev/null || echo "Plist check: no explicit setting in Info.plist"
else
  echo "Plist check skipped: TARGET_BUILD_DIR or INFOPLIST_PATH unset"
fi

ANDROID_KMP_DIR="${SRCROOT}/.."
cd "$ANDROID_KMP_DIR"

LOG_FILE="$ANDROID_KMP_DIR/msak-shared/build/xcode-kmp.log"
echo "[xcode] Script started at $(date)" >> "$LOG_FILE"
echo "[xcode] PWD=$(pwd)" >> "$LOG_FILE"
mkdir -p "$ANDROID_KMP_DIR/msak-shared/build/XCFrameworks"
touch "$ANDROID_KMP_DIR/msak-shared/build/XCFrameworks/.xcode-script-ran"

export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

JAVA_HOME_FROM_GRADLE=$(sed -n 's/^org\.gradle\.java\.home=//p' "$ANDROID_KMP_DIR/gradle.properties" | tail -n1)
if [ -n "$JAVA_HOME_FROM_GRADLE" ]; then
  export JAVA_HOME="$JAVA_HOME_FROM_GRADLE"
elif [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME_FROM_MACOS="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [ -n "$JAVA_HOME_FROM_MACOS" ]; then
    export JAVA_HOME="$JAVA_HOME_FROM_MACOS"
  fi
fi

GRADLE_USER_HOME="$ANDROID_KMP_DIR/.gradle-local"
mkdir -p "$GRADLE_USER_HOME"

echo "=== Debugging Environment ==="
echo "PWD: $(pwd)"
echo "PATH: $PATH"
echo "JAVA_HOME: ${JAVA_HOME:-<unset>}"
echo "JAVA_HOME_FROM_GRADLE: ${JAVA_HOME_FROM_GRADLE:-<unset>}"
echo "GRADLE_USER_HOME: $GRADLE_USER_HOME"
echo "User: $(whoami)"
echo "Shell: $SHELL"
echo "CONFIGURATION: ${CONFIGURATION}"
echo "SDK_NAME: ${SDK_NAME}"
echo "ARCHS: ${ARCHS}"
echo "=============================="

if [ "${CONFIGURATION}" = "Debug" ]; then
  CFG_DIR="Debug"
else
  CFG_DIR="Release"
fi

run_gradle_task() {
  local task_name="$1"
  echo "Gradle task: ${task_name}"

  local args=(./gradlew --no-daemon --gradle-user-home "$GRADLE_USER_HOME")
  if [ -n "${JAVA_HOME:-}" ]; then
    args+=("-Dorg.gradle.java.home=$JAVA_HOME")
  fi
  args+=("$task_name")

  "${args[@]}"
}

XC_BASE="$ANDROID_KMP_DIR/msak-shared/build/XCFrameworks"
DST_DIR="$XC_BASE/$CFG_DIR"
OUT_XCF="$DST_DIR/MsakShared.xcframework"
mkdir -p "$DST_DIR"
rm -rf "$OUT_XCF"

case "${SDK_NAME}" in
  iphoneos*)
    echo "Building device slice (iosArm64)"
    run_gradle_task ":msak-shared:link${CFG_DIR}FrameworkIosArm64"

    FRAMEWORK_DIR_IOS_ARM64="$ANDROID_KMP_DIR/msak-shared/build/bin/iosArm64/${CFG_DIR}Framework/MsakShared.framework"
    if [ ! -f "$FRAMEWORK_DIR_IOS_ARM64/MsakShared" ]; then
      echo "Framework binary not found: $FRAMEWORK_DIR_IOS_ARM64/MsakShared"
      exit 1
    fi

    echo "Packaging XCFramework -> $OUT_XCF"
    xcodebuild -create-xcframework -framework "$FRAMEWORK_DIR_IOS_ARM64" -output "$OUT_XCF" >/dev/null
    ;;
  *)
    # Aggregate target can execute under macOS SDK; always build simulator slices.
    echo "Building simulator slices (iosSimulatorArm64 + iosX64)"
    run_gradle_task ":msak-shared:link${CFG_DIR}FrameworkIosSimulatorArm64"
    run_gradle_task ":msak-shared:link${CFG_DIR}FrameworkIosX64"

    FRAMEWORK_DIR_IOS_SIM_ARM64="$ANDROID_KMP_DIR/msak-shared/build/bin/iosSimulatorArm64/${CFG_DIR}Framework/MsakShared.framework"
    FRAMEWORK_DIR_IOS_X64="$ANDROID_KMP_DIR/msak-shared/build/bin/iosX64/${CFG_DIR}Framework/MsakShared.framework"

    if [ ! -f "$FRAMEWORK_DIR_IOS_SIM_ARM64/MsakShared" ]; then
      echo "Framework binary not found: $FRAMEWORK_DIR_IOS_SIM_ARM64/MsakShared"
      exit 1
    fi
    if [ ! -f "$FRAMEWORK_DIR_IOS_X64/MsakShared" ]; then
      echo "Framework binary not found: $FRAMEWORK_DIR_IOS_X64/MsakShared"
      exit 1
    fi

    FAT_FRAMEWORK_DIR="$ANDROID_KMP_DIR/msak-shared/build/bin/iosSimulatorUniversal/${CFG_DIR}Framework/MsakShared.framework"
    rm -rf "$FAT_FRAMEWORK_DIR"
    mkdir -p "$(dirname "$FAT_FRAMEWORK_DIR")"
    cp -R "$FRAMEWORK_DIR_IOS_SIM_ARM64" "$FAT_FRAMEWORK_DIR"
    lipo -create \
      "$FRAMEWORK_DIR_IOS_SIM_ARM64/MsakShared" \
      "$FRAMEWORK_DIR_IOS_X64/MsakShared" \
      -output "$FAT_FRAMEWORK_DIR/MsakShared"

    echo "Packaging XCFramework -> $OUT_XCF"
    xcodebuild -create-xcframework -framework "$FAT_FRAMEWORK_DIR" -output "$OUT_XCF" >/dev/null
    ;;
esac

SLICE_BIN=$(find "$OUT_XCF" -type f -path "*/MsakShared.framework/MsakShared" | head -n1 || true)
if [ -f "$SLICE_BIN" ]; then
  SHA=$(shasum -a 256 "$SLICE_BIN" | awk '{print $1}')
  echo "XCFramework slice SHA256: ${SHA}"
  touch "$SLICE_BIN"
else
  echo "Warning: could not locate inner slice binary to hash/touch."
fi

echo "XCFramework ready at: $OUT_XCF"

PLIST="$OUT_XCF/Info.plist"
if [ -f "$PLIST" ]; then
  /usr/libexec/PlistBuddy -c "Set :MsakSharedBuildStamp $(date +%s)" "$PLIST" 2>/dev/null \
  || /usr/libexec/PlistBuddy -c "Add :MsakSharedBuildStamp string $(date +%s)" "$PLIST"
  echo "Stamped XCFramework Info.plist with MsakSharedBuildStamp"
fi
