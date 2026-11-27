#!/usr/bin/env bash
# Starts an Android TV emulator for the smoke tests and waits until it's ready:
# start-emulator.sh <api> <target> <arch> [gpu]
# The emulator keeps running for the job's later steps; its log is emulator.log.
#
# Ready means more than sys.boot_completed: once booted, the emulator runs its own adb set-up and the device drops off
# adb for a second or two (seen on Google TV 16: "device offline", then "not found"). A command landing then fails.
# So it waits for the emulator's own "Boot completed" (logged before that set-up), then for the package manager to
# answer three times in a row.
set -euo pipefail

api=$1 target=$2 arch=$3
# How the emulator draws: swiftshader_indirect (on the host) unless told otherwise. Android 7.1 does not boot with it: its
# GL fence syncs need a newer GL context than that mode gives it ("invalid GLESv2 context version"), so it draws in the
# guest instead.
gpu=${4:-swiftshader_indirect}
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
nohup "$ANDROID_HOME/emulator/emulator" -avd tv -no-window -gpu "$gpu" -no-snapshot -noaudio -no-boot-anim \
    > emulator.log 2>&1 &
emulator=$!

deadline=$((SECONDS + 600))
waitFor() {
    until "$@"; do
        if ! kill -0 "$emulator" 2> /dev/null; then
            echo "The emulator stopped." >&2
            tail -n 50 emulator.log >&2
            exit 1
        fi
        if ((SECONDS > deadline)); then
            echo "The emulator wasn't ready within 10 minutes." >&2
            tail -n 50 emulator.log >&2
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

waitFor grep -q "Boot completed" emulator.log
waitFor steady

for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb" shell settings put global "$scale" 0
done
echo "Emulator ready: Android $("$adb" shell getprop ro.build.version.release | tr -d '\r') ($target, $arch)."
