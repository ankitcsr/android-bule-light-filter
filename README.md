# Amber — Android blue light filter

Amber adds a warm, touch-through screen overlay that stays active as you switch apps. It works offline on Android 8.0 and later, without root or accessibility access.

## Use it on your phone

1. Install the debug APK from `app/build/sdk/amber-debug.apk` (or a Gradle-built `app/build/outputs/apk/debug/app-debug.apk`). Android may ask you to allow installation from the app opening the APK.
2. Open **Amber** and tap **Turn on filter**.
3. In Android settings, allow **Display over other apps** for Amber, then return. Some phones show a list of apps first; select Amber there.
4. Allow notifications for a persistent **Turn off** shortcut. Notifications are optional; the filter still works if you decline.
5. Adjust **Warmth** and **Filter strength**, or choose **Gentle**, **Evening**, or **Deep**. Settings are saved and changes apply immediately while the filter is active.

Turn the filter off in Amber, from its notification, or from its optional Quick Settings tile. Add the tile through the Quick Settings panel's **Edit** button. Closing the activity does not stop the filter. After force-stop or a phone restart, open Amber to enable it again; there is no automatic boot activation.

## Android limits

Android can hide overlays on protected apps, permission dialogs, the lock screen, and parts of the system interface. Apps may also reject touches whenever an overlay is present; turn Amber off while using those screens. Manufacturer battery restrictions may stop the service.

Amber blends a color with a zero blue component over supported screens. Strength controls overlay opacity from 0 to 70%, with a margin below Android 12's 80% maximum obscuring opacity for touch pass-through. A zero-strength filter has no visual effect. This changes rendered colors, not the display hardware, and makes no medical claims. It cannot guarantee coverage of every screen on Android.

The app declares no Internet permission, reads no screen content, and sends no data. Its foreground service keeps filtering active and provides an accessible stop action.

## Build and test

Open the repository in Android Studio, or use JDK 17, Android SDK platform 35, build tools 35.0.0, and the checked-in Gradle 8.9 wrapper:

```bash
# Set ANDROID_HOME, or create an untracked local.properties with sdk.dir.
./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Android Gradle plugin is pinned to 8.7.3. The application uses only the native Android SDK; JUnit 4.13.2 is a test dependency. Release publishing requires your own signing configuration; the included builds use a development key.

### Codex cloud

```bash
bash scripts/setup-cloud.sh
source scripts/cloud-env.sh
```

Setup downloads pinned, checksum-verified JDK, Gradle, and Android command-line tools to `/workspace/toolchains`, installs the SDK, trusts the provided cloud proxy CA alongside Java's existing trust anchors, and keeps caches outside source control. Existing checkouts already run in an isolated environment; a new Git worktree is unnecessary.

Maven Central returned HTTP 429 (shared-IP rate limiting) during cloud setup. The SDK-only scripts provide a repeatable build and execute the same JUnit suite without fetching Maven dependencies:

```bash
source scripts/cloud-env.sh
bash scripts/test-sdk.sh
bash scripts/build-sdk.sh
# Output: app/build/sdk/amber-debug.apk
adb install -r app/build/sdk/amber-debug.apk
```

The SDK-only build compiles resources and Java, converts bytecode with D8, aligns the APK, signs it with an Android debug key, and verifies its signature and alignment. It does not run Android Lint. When Maven access is restored, run the full Gradle checks above. Alternatively, allow `maven-central.storage-download.googleapis.com` in cloud environment settings and set `AMBER_USE_CENTRAL_MIRROR=1` before sourcing `scripts/cloud-env.sh` to use Google's official Maven Central mirror. Keep the SDK-only build's namespace and version flags consistent with `app/build.gradle` when changing them.

For emulator checks, run `bash scripts/start-emulator.sh` in its own terminal, then `python3 scripts/smoke-test.py --serial emulator-5554` in another terminal after sourcing the cloud environment. The helper defaults to Android 8 (API 26, x86) without `/dev/kvm`, or Android 15 (API 35, x86_64) with hardware acceleration. You can select a version explicitly with `bash scripts/start-emulator.sh 26` or `35`. Android 15 software emulation booted during setup but produced no usable UI, so modern-device runtime validation remains outstanding.

The smoke test waits for boot and input readiness, wakes the display, and exercises actual overlay permission, screenshot color changes, presets, cross-app touch-through, notification stopping, and force-stop behavior. It resets only Amber data; use a disposable emulator. It requires the provided Python/Pillow runtime. Software startup is slower. Android 8 temporarily suppresses overlays while leaving its protected permission screen, so Amber retries the permission check for up to ten seconds while its activity is visible.
