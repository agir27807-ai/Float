# Hotmail Floating OTP Reader (Native Android Kotlin App)

Native Android application built with **Kotlin**, **Jetpack Compose**, and a `WindowManager` **System Overlay Foreground Service** (`SYSTEM_ALERT_WINDOW`) so the movable OTP bar floats over any Android app (Facebook, MatrixClone, Chrome, etc.).

## Features
- **Movable System-Wide Floating Bar**: Drag anywhere on the Android screen (`TYPE_APPLICATION_OVERLAY`) with saved screen coordinates.
- **Click-to-Copy OTP**: Tap the green `OTP: <code>` chip on the floating bar to copy the verification code to Android's clipboard.
- **Expandable Latest Mail Preview**: Tap the chevron on the floating bar to view the full incoming Facebook email message and timestamp.
- **Single & Bulk Mail Box**: Paste `email|password|refresh_token|client_id` or `email|refresh_token|client_id` (one per line in **Bulk Mode**).
- **3-Second Auto-Read for 2 Hours**: Automatically polls `POST https://smail1s.com/get_messages` (`mode: "oauth"`) every 3 seconds for 2 hours and automatically updates the floating bar to whichever mailbox receives the latest code.

## Build APK in Android Studio or GitHub Actions
1. Open this repository root folder in **Android Studio** (Ladybug or newer, JDK 17), or push directly to GitHub.
2. Let Gradle sync dependencies.
3. Connect an Android device (API 26+) or run `./gradlew assembleDebug` at the repository root to generate `app/build/outputs/apk/debug/app-debug.apk`.
4. Install the APK, tap **Grant Overlay Permission** (enable *Display over other apps*), save your Hotmail OAuth2 accounts, and tap **Start Floating**.
