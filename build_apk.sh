#!/bin/bash
#
# Build the Family Tracks APK.
# Fixes common JDK/Gradle transform cache issues.
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Use Android Studio's bundled JDK
if [ -d "$HOME/apps/android-studio/jbr" ]; then
    export JAVA_HOME="$HOME/apps/android-studio/jbr"
elif [ -d "/opt/android-studio/jbr" ]; then
    export JAVA_HOME="/opt/android-studio/jbr"
else
    echo "Warning: Could not find Android Studio JBR, using system Java"
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
java -version 2>&1 | head -1

# Clear the corrupted jdkImage transform cache
echo ""
echo "Clearing JDK image transform cache..."
find "$HOME/.gradle/caches/transforms-3" -path "*/jdkImage*" -exec rm -rf {} + 2>/dev/null
find "$HOME/.gradle/caches/transforms-3" -path "*core-for-system-modules*" -exec rm -rf {} + 2>/dev/null

chmod +x gradlew

echo ""
echo "=== Cleaning ==="
./gradlew clean

echo ""
echo "=== Building Debug APK ==="
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "Build successful!"
    echo "APK: $SCRIPT_DIR/$APK_PATH"
    ls -lh "$APK_PATH"

    # Offer to install if a device is connected
    ADB="${ANDROID_HOME}/platform-tools/adb"
    if [ -x "$ADB" ]; then
        DEVICES=$("$ADB" devices 2>/dev/null | grep -w "device" | wc -l)
        if [ "$DEVICES" -gt 0 ]; then
            echo ""
            read -p "Device detected. Install APK? [y/N] " REPLY
            if [ "$REPLY" = "y" ] || [ "$REPLY" = "Y" ]; then
                "$ADB" install -r "$APK_PATH"
            fi
        fi
    fi
else
    echo ""
    echo "Build FAILED. Check errors above."
    exit 1
fi
