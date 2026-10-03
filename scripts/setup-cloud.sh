#!/usr/bin/env bash
# Reproducible Codex cloud setup. Does not edit source or dependency declarations.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p /workspace/toolchains/downloads
python3 - <<'PY'
from pathlib import Path
import hashlib, subprocess
root = Path('/workspace/toolchains/downloads')
archives = [
    ('OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz',
     'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz',
     'sha256', '166774efcf0f722f2ee18eba0039de2d685b350ee14d7b69e6f83437dafd2af1'),
    ('gradle-8.9-bin.zip', 'https://services.gradle.org/distributions/gradle-8.9-bin.zip',
     'sha256', 'd725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab'),
    ('commandlinetools-linux-13114758_latest.zip',
     'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip',
     'sha1', '5fdcc763663eefb86a5b8879697aa6088b041e70'),
]
for name, url, algorithm, expected in archives:
    dest = root / name
    if not dest.is_file():
        subprocess.run(['curl', '-fsSL', '--retry', '2', url, '-o', str(dest)], check=True)
    if hashlib.new(algorithm, dest.read_bytes()).hexdigest() != expected:
        raise SystemExit(f'Checksum mismatch for {name}; remove the download and retry from the official source.')
    print(f'Checksum verified: {name}')
PY
if [[ ! -x /workspace/toolchains/jdk17/bin/javac ]]; then
    mkdir -p /workspace/toolchains/jdk17
    tar -xzf /workspace/toolchains/downloads/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz \
        --strip-components=1 -C /workspace/toolchains/jdk17
fi
if [[ ! -x /workspace/toolchains/gradle-8.9/bin/gradle ]]; then
    unzip -q /workspace/toolchains/downloads/gradle-8.9-bin.zip -d /workspace/toolchains
fi
if [[ ! -x /workspace/toolchains/android-sdk/cmdline-tools/19.0/bin/sdkmanager ]]; then
    mkdir -p /workspace/toolchains/android-sdk/cmdline-tools
    unzip -q /workspace/toolchains/downloads/commandlinetools-linux-13114758_latest.zip \
        -d /workspace/toolchains/android-sdk/cmdline-tools
    mv /workspace/toolchains/android-sdk/cmdline-tools/cmdline-tools \
        /workspace/toolchains/android-sdk/cmdline-tools/19.0
fi
source scripts/cloud-env.sh
# Native adb still uses ~/.android. Preserve any pre-existing user configuration.
if [[ ! -e "$HOME/.android" && ! -L "$HOME/.android" ]]; then
    ln -s "$ANDROID_USER_HOME" "$HOME/.android"
fi
python3 - <<'PY' | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > /workspace/toolchains/sdk-licenses.log 2>&1
print('y\n' * 100)
PY
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
./gradlew --version
bash scripts/test-sdk.sh
bash scripts/build-sdk.sh
