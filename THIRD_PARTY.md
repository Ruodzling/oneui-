# Third-party components and references

## Xposed Framework API 82

- Origin: https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
- Upstream project: https://github.com/rovo89/XposedBridge
- Author: rovo89 and Xposed contributors.
- License: Apache License 2.0, full text in app/libs/XPOSED-API-LICENSE.txt.
- SHA-256: f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25
- Used only as compile-time stubs. Not bundled as classes in the APK.

## Gradle 8.10.2 wrapper

Generated using the installed Gradle 8.10.2 wrapper task.
Upstream: https://github.com/gradle/gradle/tree/v8.10.2
License: Apache License 2.0.
Distribution SHA-256: 31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26
The Windows launch script has an added D-drive environment guard.

## Android SDK / Android Gradle Plugin

The installed Android SDK 35 and Android Gradle Plugin 8.8.0 are used for compilation.
Their original licenses apply. They are not redistributed as part of the source archive.

## Kotlin 2.1.0

The new gesture implementation uses the Kotlin Android Gradle plugin and Kotlin standard library 2.1.0.
Upstream: https://github.com/JetBrains/kotlin/tree/v2.1.0
License: Apache License 2.0 (https://github.com/JetBrains/kotlin/blob/v2.1.0/license/LICENSE.txt).
Gradle resolves the plugin and runtime from the D-drive cache; the Android runtime library is included in the APK.
Samsung firmware APK/JAR analysis inputs and video frames are not redistributed in the source archive.

## Design references

- Legacy module entry:
  https://api.xposed.info/reference/de/robv/android/xposed/IXposedHookLoadPackage.html
- LSPosed API 93 shared preferences:
  https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences
- Android multi-window configuration changes:
  https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#configuration-changes
- AOSP ResourcesManager (reference only, not proof of Samsung firmware compatibility):
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ResourcesManager.java

No claim is made that Samsung Android 17 has the same private methods or dispatch
behavior as the reference AOSP source. Device validation is required.

## LSPosed target version correction
Target framework: LSPosed 2.2.1 (7912), corrected by the user.
Official 2.2.0 announcement schedules New XSharedPreferences removal for 2.3.0:
https://t.me/s/LSPosed
This prototype still uses that deprecated legacy extension; validate preferences on the target device.

## Generated live probe video

app/src/main/res/raw/live_probe.mp4 is an original synthetic, silent testsrc2 pattern generated locally with FFmpeg (640x360, 24fps, 12s, H.264). No user video or external media is included. FFmpeg itself is not bundled.
SHA256: 9ea4987803dd7f3a9e0ebe7c04c638e54aa5d38d8982fc4e4a779c6ddeecdc15.