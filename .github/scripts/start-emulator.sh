#!/usr/bin/env bash
# Starts an Android TV emulator for the smoke tests and waits until it's ready: start-emulator.sh <api> <target> <arch>
# The emulator keeps running for the job's later steps; its log is emulator.log.
#
# Ready means more than sys.boot_completed: once booted, the emulator runs its own adb set-up and the device drops off
# adb for a second or two (seen on Google TV 16: "device offline", then "not found"). A command landing then fails.
# So it waits for the emulator's own "Boot completed" (logged before that set-up), then for the package manager to
# answer three times in a row.
set -euo pipefail

api=$1 target=$2 arch=$3
image="system-images;android-$api;$target;$arch"
tools=$ANDROID_HOME/cmdline-tools/latest/bin
adb=$ANDROID_HOME/platform-tools/adb
# One place for the AVD: avdmanager and the emulator otherwise look in different ones.
export ANDROID_AVD_HOME=$HOME/.android/avd
mkdir -p "$ANDROID_AVD_HOME"

yes | "$tools/sdkmanager" --licenses > /dev/null || true
"$tools/sdkmanager" --install emulator platform-tools "$image" > /dev/null
echo no | "$tools/avdmanager" create avd --force --name tv --package "$image" --device tv_1080p

"$adb" start-server
nohup "$ANDROID_HOME/emulator/emulator" -avd tv -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
    > emulator.log 2>&1 &
emulator=$!

deadline=$((SECONDS + 600))
# What the emulator said, without its repeated GL fence messages (Android 7.1 logs thousands), and what adb sees.
report() {
    grep -v -e "internal_glFenceSync" -e "Failed to create EGL fence sync" emulator.log | tail -n 80 >&2
    "$adb" devices >&2 || true
    echo "sys.boot_completed: $("$adb" shell getprop sys.boot_completed 2> /dev/null || true)" >&2
}
waitFor() {
    until "$@"; do
        if ! kill -0 "$emulator" 2> /dev/null; then
            echo "The emulator stopped." >&2
            report
            exit 1
        fi
        if ((SECONDS > deadline)); then
            echo "The emulator wasn't ready within 10 minutes." >&2
            report
            exit 1
        fi
        sleep 2
    done
}

answers() { "$adb" shell pm path android > /dev/null 2>&1; }
steady() {
    for _ in 1 2 3; do
        answers || return 1
        sleep 2
    done
}

# The emulator's own "Boot completed", or Android's: older images can boot without the emulator's line.
booted() {
    grep -q "Boot completed" emulator.log ||
        [[ "$("$adb" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" == 1 ]]
}
waitFor booted
waitFor steady

for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb" shell settings put global "$scale" 0
done
echo "Emulator ready: Android $("$adb" shell getprop ro.build.version.release | tr -d '\r') ($target, $arch)."
