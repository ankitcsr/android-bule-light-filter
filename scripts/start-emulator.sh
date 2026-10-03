#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/cloud-env.sh
amber_acceleration=off
if [[ -r /dev/kvm && -w /dev/kvm ]]; then amber_acceleration=on; fi
amber_api="${1:-35}"
# Android's x86_64 emulator warns it may not work without acceleration.
# Test the app's supported Android 8 baseline in software mode by default.
if [[ $# == 0 && "$amber_acceleration" == off ]]; then amber_api=26; fi
if [[ "$amber_api" != 26 && "$amber_api" != 35 ]]; then
    printf 'Supported test API levels: 26 or 35\n' >&2
    exit 1
fi
amber_arch=x86_64
if [[ "$amber_api" == 26 ]]; then amber_arch=x86; fi
amber_avd="amber-api$amber_api"
amber_image="system-images;android-$amber_api;default;$amber_arch"
if [[ ! -f "$ANDROID_AVD_HOME/$amber_avd.ini" ]]; then
    sdkmanager --sdk_root="$ANDROID_HOME" 'emulator' "platforms;android-$amber_api" "$amber_image"
    printf 'no\n' | avdmanager create avd --name "$amber_avd" \
        --package "$amber_image" --device 'pixel_2'
fi
# Keep this process in its own terminal/tool session. Snapshots do not retain it.
if [[ "$amber_acceleration" == off && "$amber_api" == 35 ]]; then
    # Android's supported global timeout accommodates software-emulated startup.
    # Application ANR checks and all functional assertions remain enabled.
    (
        for amber_attempt in {1..120}; do
            if timeout 10 adb -s emulator-5554 shell settings put global watchdog_timeout_millis 600000 2>/dev/null; then
                break
            fi
            sleep 2
        done
    ) &
fi
exec "$ANDROID_HOME/emulator/emulator" -avd "$amber_avd" -no-window -no-audio \
    -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader -accel "$amber_acceleration" \
    -memory 2048 -cores 2 -skin 480x800 -prop qemu.sf.lcd_density=160 -feature -Vulkan -port 5554
