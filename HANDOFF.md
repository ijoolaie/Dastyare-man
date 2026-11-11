# HANDOFF — دستیار من / Raya Android

آخرین به‌روزرسانی: 2026-11-11

## هدف
تکمیل دستیار صوتی اندروید با سه حالت فعال‌سازی واقعی، صدای رسانه بدون duck ناخواسته، قطع طبیعی صحبت رایا هنگام صحبت کاربر، تنظیمات کاربردی و رابط فارسی RTL.

## مخزن و وضعیت Git
- Repository: `ijoolaie/Dastyare-man`
- Local path: `D:\android-app`
- Android module: `D:\android-app\android-app`
- Branch: `fix/android-voice-barge-in-multilingual-screen`
- HEAD: `4d734964231c5e00bf20d1f3e5a7c8cc31c6dc3f`
- HEAD subject: `chore(android): bump media fix build to 1.16`
- Previous commit: `0d65fa8544104eb57efcf90f2238709c5246d13c`
- PR: https://github.com/ijoolaie/Dastyare-man/pull/9
- Version: 1.16 / versionCode 17
- Last reported GitHub Actions build: run ID `42992849093`, Debug APK build/signature verification passed; artifact ID `13842322860`, name `dastyar-bridge-debug-apk`.
- Physical-device verification: NOT DONE. Latest `adb devices -l` returned no connected devices.
- Local working tree: tracked files clean at inspection. Many untracked historical ZIP/APK/test/artifact files and directories exist in repo root. Preserve all of them; do not run clean/reset/delete commands.

## Files to know
- `android-app/app/src/main/java/ir/dastyar/bridge/VoiceAssistantService.kt`: main audio/ASR/TTS service (~57 KB).
- `Prefs.kt`: SharedPreferences; currently persists server/token, voice enabled, STT model and TTS voice only.
- `MainActivity.kt`: pairing, WebView, settings AlertDialog navigation. No native voice conversation UI currently.
- `activity_main.xml`: WebView plus pairing form; no native voice status, start/stop or transcript surface.
- `AndroidManifest.xml`: microphone foreground service, boot receiver, permissions.
- `VOICE-ASSISTANT.md`: older documentation; describes Vosk wake-word flow and 45-second active session, and is now incomplete/out of sync.
- `app/build.gradle`: compileSdk 34, targetSdk 34, minSdk 26, Kotlin JVM 17, Vosk + sherpa-onnx dependencies.

## Confirmed findings from first inspection
1. Audio focus is requested with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` in `requestSpeechAudioFocus()`. This policy can duck other media while TTS is speaking. It must not be held during idle listening; current calls need full call-site audit.
2. Both Vosk and Sherpa microphone paths use `MediaRecorder.AudioSource.VOICE_COMMUNICATION`, and configure AEC/NS. AGC is commented as intentionally disabled.
3. The Vosk busy/barge-in path calls `recognizer.acceptWaveForm/result/partialResult` while the normal loop also owns that same recognizer. This is a concurrency/thread-safety risk and must be redesigned, not patched around.
4. Sherpa barge-in creates a background decode thread and shares `offlineRecognizer`; verify whether the engine supports concurrent streams and serialize or isolate decode as required.
5. `prepareBargeInCapture()` tries to keep/restart microphone capture during TTS. Verify one recorder owner and no overlapping recorders.
6. `handleRecognized()` always applies wake-word/session gating; always-listen and manual modes do not yet exist as a real persisted mode.
7. `Prefs.kt` has no activation mode, audio duck policy, speech rate/volume, sensitivity or silence timeout settings.
8. `MainActivity.kt` has settings dialogs for STT model, TTS voice, memory, history, battery, screen control, notification access and pairing disconnect. It has no complete audio settings page and no conversation control UI.
9. `activity_main.xml` is only WebView + pairing UI. Voice interaction appears mainly via persistent notification, not a dedicated native conversation screen.
10. Several strings and comments in Kotlin/XML resources appear mojibake/corrupted (for example `strings.xml` and parts of MainActivity); fix carefully with UTF-8 and verify rendered Persian.
11. `VOICE-ASSISTANT.md` documents an older behavior and needs updating after implementation.
12. No Android device was connected at inspection time.

## Existing implementation details to preserve/review
- TTS paths: Android TextToSpeech, Edge TTS via MediaPlayer, Offline Sherpa TTS / AudioTrack.
- STT choices in `VoiceModel`: Vosk, Whisper Tiny, Shenava Rizeh. `Prefs.voiceModel()` contains one-time migration logic defaulting old/null Vosk preference to Whisper Tiny.
- TTS voices in `TtsVoice`: Edge Farid, Edge Dilara, Piper, Android.
- Notification actions: stop listening (`ACTION_STOP`) and interrupt Raya speech (`ACTION_INTERRUPT`).
- Wake word normalization, media strict wake-word gate, adaptive noise gate, local voice history and Raya memory.
- Current barge-in changes are in commits `0d65fa8` and `4d73496`; treat as experimental until code review and device test.

## Immediate next actions
1. Continue Phase 0 in ROADMAP.md. Inspect the complete call graph and concurrency/cleanup paths in VoiceAssistantService.kt. Run baseline Gradle build using existing `build-apk.ps1` / documented build flow; do not assume a wrapper exists (module listing showed no gradlew).
2. Add tests and implement a single persisted activation-mode/state-machine design in Prefs + VoiceAssistantService before adding UI controls.
3. Fix audio focus policy and TTS attributes; ensure idle listener never requests ducking focus.
4. Redesign barge-in with explicit single-recorder ownership and safe ASR concurrency.
5. Build a native Persian RTL voice screen and settings controls, preserving the existing WebView pairing and bridge features.
6. Update documentation, run CI, and request/perform real-device acceptance when an Android device is connected.

## Acceptance rules
- Do not merge PR #9 without explicit user approval.
- Do not delete untracked artifacts, old APKs, ZIPs, test captures or backup files.
- Do not claim that an APK is installed or that microphone, speaker, audio focus, wake word, battery or reboot behavior passed unless physically tested.
- Separate automated build/test evidence from manual device acceptance.
- Update this handoff after every meaningful milestone: commit SHA, changed files, checks, artifact ID, device test status, and remaining blockers.
- Use ROADMAP.md as the source of truth for scope and completion checkboxes.
