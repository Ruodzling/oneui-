# One UI Popup Enhancer (SamsungPopupScale)

[中文](README.md) | [English](README_EN.md)

An open-source LSPosed module (`io.github.s24u.popupscale`) that enhances Samsung One UI freeform / pop-up windows with unified content scaling, frameless headers, gesture controls, and live mini-window stacking.

> **Author**: CoolApk @若冬之冷 ([Profile](https://www.coolapk.com/u/2970139))  
> **License**: GNU General Public License v3.0 (see [`LICENSE`](LICENSE))

## Open-Source Notice

- This repository is open-sourced under GPL-3.0 with **built-in diagnostic collection, screen recording, and feedback bundle export removed**.
- Remote diagnostic troubleshooting is not provided here. If you run into device-specific, firmware-specific, or multi-module compatibility issues, you are welcome to inspect the source code, adapt it locally, or fork and maintain your own build.

## Key Features

- **Unified System Pop-up Content Scaling**: Adjusts rendering density and layout inside freeform windows based on a target reference width (`400 / 500 / 600 dp` or custom). Supports either all ordinary apps (with an exclusion list) or a selected-apps list, plus Samsung Dual Messenger / owner clone profiles. Scaling scope is decoupled from window interactions—frameless headers, handle bars, hanging, and mini windows stay available for all ordinary apps by default.
- **App-Specific Compatibility**: Dedicated compatibility policies for WeChat (canvas scaling & dialogs), QQ / TIM (density check protection against restarts & input panel sizing), Bilibili (9.20+ in-popup fullscreen player compatibility), Douyin (comment/share sheets & mini-window playback preservation), and Weibo.
- **Frameless Top Header**: Removes freeform top decoration padding and outline while keeping the top drag handle and native window menu intact, with synchronized surface cropping.
- **Bottom Handle Bar Gestures & Opacity Control**:
  - Slowly drag inward to shrink and hang into a live mini window; flick inward to close (or send to background while keeping it in Recents); flick outward to expand fullscreen.
  - Long-press the handle bar (or use the supported native top scroll menu button) to adjust window opacity.
- **Live Mini Windows & Multi-Card Stacking**: Supports free dragging, fling physics, edge docking into semi-transparent side tabs, drag-off-edge dismissal, automatic stacking of multiple mini windows with horizontal swipe switching, automatic top-right homing of remaining minis when one expands, and HOME minimize protection for active mini tasks.
- **Detailed Heads-Up Notification Pull-to-Popup** & **One Hand Operation+ Quick Launcher Popup Redirection**.
- **Runtime Status Check**: Tap the status indicator at the top of the module's home page to inspect hook injection and ABI matching for `android` (System Framework), `com.android.systemui` (System UI), and `com.sec.android.app.launcher` (One UI Home).

## Requirements & LSPosed Scope

- **LSPosed**: `xposedminversion >= 93` with `xposedsharedprefs` support.
- **Android / One UI**: Designed for Samsung One UI 7 / 8 / 9 (Android 15–17) using runtime class/method signature probing (`minSdk 28`, `compileSdk 35`, `targetSdk 35`).
- **Recommended Scope** (matching `xposed_scope` in `app/src/main/res/values/resources.xml`):
  - Core system processes: `android`, `com.android.systemui`, `com.sec.android.app.launcher`
  - Gesture integration: `com.samsung.android.sidegesturepad` (One Hand Operation+)
  - Permissions & optional app-specific compatibility: `com.google.android.permissioncontroller`, `com.tencent.mm`, `com.tencent.mobileqq`, `com.tencent.tim`, `tv.danmaku.bili`, `com.ss.android.ugc.aweme`, `com.sina.weibo`, `com.coolapk.market`

## Local Build & Verification

### Prerequisites

- JDK 17 or JDK 21 (`JAVA_HOME`)
- Android SDK Platform 35 and Build Tools `35.0.0` (configure `ANDROID_HOME` or create `local.properties` with `sdk.dir=...`)

### Build APKs

Linux / macOS:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

> **Signing**: If neither `.local/signing/debug.keystore` nor the `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` environment variables are provided, Gradle automatically falls back to the default debug signing config so you can clone and build directly.

### Offline Tests & Policy Checks

```powershell
.\gradlew.bat :app:verifyReleaseNotes :app:verifyGesturePolicy :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
powershell -ExecutionPolicy Bypass -File .\checks\Run-PolicyChecks.ps1
```

## Project Layout

- `app/src/main/java/io/github/s24u/popupscale/`: Xposed entry point, Material settings UI, compatibility reporting, and app-specific scaling/density policies
- `app/src/main/java/io/github/s24u/popupscale/gestures/`: SystemUI / System Framework / Launcher hooks for handle bars, frameless headers, live mini windows, opacity control, and notification pull-to-popup
- `app/src/test/`: Offline unit tests for scaling, scoping, gestures, and lifecycle policies
- `app/src/androidTest/`: On-device read-only ABI and behavior verification checks
- `checks/`: Standalone pure-Java policy verification scripts

## License & Acknowledgments

- Licensed under [GNU General Public License v3.0](LICENSE).
- Third-party dependencies and references are listed in [`THIRD_PARTY.md`](THIRD_PARTY.md).
