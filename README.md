# QQSpaceAutoLike

`QQSpaceAutoLike` is an Android app prototype that uses `AccessibilityService` to open mobile QQ, navigate into Qzone, scan feed cards, and perform a controlled "like back" pass.

The project is implemented as a native Android app with `Kotlin`, not an `Auto.js` script. The goal is to keep the automation logic maintainable, testable, and easy to adjust for different QQ UI variants.

## Status

- Android app scaffold is complete
- Accessibility service flow is implemented
- Basic navigation, feed scanning, ad filtering, random delay, and stop conditions are implemented
- GitHub Actions can build a debug APK on push and publish a release APK on tag
- The app still requires real-device tuning against specific QQ versions

## What It Does

- Watches for QQ entering the foreground
- Tries to enter `动态` and then `好友动态 / 空间动态`
- Scans visible cards for likely "like" controls
- Skips obvious ads, promoted content, and already-processed nodes
- Stops after timeout, repeated no-op scans, or old feed items
- Exposes a notification action to stop the run manually

## Non-Goals

- No root access
- No QQ app modification
- No use of private QQ APIs
- No guarantee that every QQ version exposes the same accessibility tree

## Project Layout

```text
QQSpaceAutoLike/
├── .github/workflows/
│   ├── android.yml
│   └── release.yml
├── app/
│   └── src/main/
│       ├── java/io/github/yanganqi/qqspaceautolike/
│       │   ├── automation/
│       │   ├── config/
│       │   ├── service/
│       │   └── ui/
│       ├── res/
│       └── AndroidManifest.xml
├── gradle/
├── build.gradle.kts
└── settings.gradle.kts
```

## How It Works

```text
QQ enters foreground
  ↓
Accessibility service receives the event
  ↓
Try to enter Qzone feed
  ↓
Scan visible nodes for candidate like buttons
  ↓
Filter ads / duplicates / invalid targets
  ↓
Click with randomized pacing
  ↓
Scroll and continue
  ↓
Stop on timeout / old content / repeated no-progress scans
```

## Key Implementation Notes

- Navigation prefers text labels and clickable parent nodes over fixed coordinates
- Scroll prefers `ACTION_SCROLL_FORWARD`, then falls back to gestures
- Like detection uses a heuristic mix of text/content-description, clickability, size, and screen position
- Ad filtering uses surrounding subtree text instead of a single node label
- Old-feed detection supports formats such as `昨天`, `前天`, `N天前`, `7月10日`, and `2026-07-10`

## Build

Requirements:

- `JDK 17`
- Android SDK
- Android Studio or a working Gradle Android toolchain

Local build:

```bash
./gradlew assembleDebug
```

## Install And Test

1. Build or download the debug APK
2. Install it on an Android device
3. Enable the app's accessibility service
4. Grant notification permission if prompted
5. Open mobile QQ and observe whether the app can enter Qzone and start a pass

## GitHub Actions

- Push to `main`: builds `app-debug.apk` as an Actions artifact
- Push a tag like `v0.1.0`: publishes a prerelease APK in GitHub Releases

Example:

```bash
git tag v0.1.0
git push origin main --tags
```

## Known Risks

- QQ UI copy and layout can change across versions
- Some ROMs aggressively throttle accessibility events
- Some like buttons may not expose usable text or descriptions
- Heuristics for ads and old content still need device-specific tuning

## Next Steps

1. Capture accessibility node trees from the target QQ version
2. Refine entry labels for `动态 / 好友动态 / 空间动态`
3. Add more button-identification and ad-filter rules
4. Validate stop conditions on real devices

## Disclaimer

This repository is a research/prototyping project around Android accessibility automation. Anyone using it is responsible for understanding the platform, app policy, and account-risk implications of automating user actions.
