# GitHub Actions workflow for the Android app

## What this does
- Uses JDK 17 on GitHub-hosted Ubuntu.
- Installs Android SDK Platform 34 and Build Tools 34.0.0.
- Installs Gradle 8.7 (this project currently does not include a Gradle wrapper).
- Runs `gradle --no-daemon clean assembleDebug`.
- Publishes the generated debug APK as a GitHub Actions artifact for 14 days.

## Repository layout
The Android Gradle project must be at the repository root:
- `settings.gradle`
- `build.gradle`
- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`

## Build
A push or pull request to `main` / `master` starts the workflow automatically. You can also start it manually from **Actions → Build Android APK → Run workflow**.

When the run succeeds, open it and download the artifact named `dastyar-bridge-debug-apk`.

This creates a debug APK for installation/testing, not a Play Store release APK. No signing secrets are required.
