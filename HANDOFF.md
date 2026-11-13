# HANDOFF — دستیار من / Raya Android

آخرین به‌روزرسانی: 2026-11-11

## هدف
تکمیل دستیار صوتی اندروید با سه حالت واقعی فعال‌سازی، جلوگیری از duck ناخواسته موسیقی، barge-in طبیعی، تنظیمات عملی و رابط مکالمه فارسی RTL.

## مخزن و وضعیت
- Repository: `ijoolaie/Dastyare-man`
- Local: `D:\android-app`
- Module: `D:\android-app\android-app`
- Branch: `fix/android-voice-barge-in-multilingual-screen`
- آخرین commit: `4205570 docs(android): add voice assistant roadmap and handoff`
- کد برنامه در HEAD فعلی هنوز همان نسخه 1.16 / versionCode 17 است؛ آخرین کد پیش از سندها: `4d734964231c5e00bf20d1f3e5a7c8cc31c6dc3f`.
- PR: https://github.com/ijoolaie/Dastyare-man/pull/9
- آخرین CI گزارش‌شده: run ID `42992849093`، Debug APK و signature verification موفق؛ artifact ID `13842322860` با نام `dastyar-bridge-debug-apk`.
- آخرین push مستندات به branch موفق بود.
- ADB: `adb devices -l` هیچ دستگاه متصل نشان نداد.
- محیط build: JDK 17 در `C:\Program Files\Java\jdk-17.0.20` موجود است؛ Java پیش‌فرض سیستم 25.0.2 است و برای Gradle 8.7 پشتیبانی نمی‌شود. Android SDK در مسیر پیش‌فرض `C:\Users\Ali\AppData\Local\Android\Sdk` وجود ندارد؛ Platform 34 و Build Tools 34.0.0 نیز یافت نشدند. build محلی فعلاً به SDK نیاز دارد.
- Working tree در فایل‌های tracked تمیز است. تعداد زیادی فایل/پوشه untracked تاریخی در root وجود دارد؛ هیچ‌کدام را حذف یا stage نکن مگر فایل‌های مشخص و مجاز.

## اسناد مرجع
- `ROADMAP.md`: مراحل، معیارهای خروج و وضعیت چک‌باکس‌ها.
- `HANDOFF.md`: این سند؛ وضعیت، یافته‌ها و دستور ادامه.
Roadmap مرجع scope است؛ handoff باید در هر milestone به‌روزرسانی شود.

## فایل‌های اصلی
- `android-app/app/src/main/java/ir/dastyar/bridge/VoiceAssistantService.kt`: چرخه صوت، ASR و TTS (~57 KB).
- `Prefs.kt`: SharedPreferences فعلی؛ فقط server/token، voice enabled، STT model و TTS voice.
- `MainActivity.kt`: pairing، WebView و منوی تنظیمات AlertDialog؛ UI مکالمه native ندارد.
- `activity_main.xml`: WebView و فرم pairing؛ فاقد status، دکمه مکالمه و transcript.
- `AndroidManifest.xml`: foreground microphone service، boot receiver و permissions.
- `BootReceiver.kt`: پس از reboot سرویس صوتی را در صورت voiceEnabled و permission بالا می‌آورد.
- `VOICE-ASSISTANT.md`: سند قدیمی و ناسازگار با تغییرات جدید؛ باید پس از پیاده‌سازی به‌روزرسانی شود.

## یافته‌های ممیزی اولیه
1. `requestSpeechAudioFocus()` از `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` استفاده می‌کند؛ می‌تواند media را duck کند. نباید هنگام idle listening focus گرفته شود؛ call siteهای فعلی باید بازبینی شوند.
2. Vosk و Sherpa هر دو AudioRecord با `VOICE_COMMUNICATION` باز می‌کنند و AEC/NS را فعال می‌کنند؛ AGC عمداً خاموش است.
3. مسیر Vosk در زمان TTS همان `Recognizer` را در مسیر busy/barge-in استفاده می‌کند، در حالی که حلقه معمولی هم مالک آن است؛ ریسک thread-safety و decode هم‌زمان وجود دارد.
4. مسیر Sherpa برای barge-in thread جداگانه decode می‌سازد و از `offlineRecognizer` مشترک استفاده می‌کند؛ باید concurrency آن تضمین یا serialize شود.
5. `prepareBargeInCapture()` recorder را حین TTS فعال نگه می‌دارد؛ باید مالک واحد میکروفون و lifecycle صریح داشته باشد.
6. `handleRecognized()` فقط wake word یا active session را می‌پذیرد؛ Always-listen و Manual هنوز به‌عنوان mode واقعی وجود ندارند.
7. Prefs فاقد activation mode، media policy، TTS volume/rate، wake sensitivity و silence timeout است.
8. MainActivity تنظیمات پراکنده برای STT/TTS/memory/history/battery/accessibility/notification دارد؛ صفحه تنظیمات صوتی کامل نیست.
9. رابط native مکالمه وجود ندارد؛ اپ پس از pairing مستقیماً WebView را نشان می‌دهد.
10. بخشی از رشته‌ها و commentها در MainActivity و strings.xml mojibake هستند؛ اصلاح UTF-8 باید با تست واقعی نمایش فارسی انجام شود.
11. مستند VOICE-ASSISTANT.md رفتار قدیمی Vosk و 45 ثانیه session را توصیف می‌کند و باید بعداً اصلاح شود.
12. گوشی واقعی متصل نبود؛ هیچ تست فیزیکی میکروفون/بلندگو/باتری/reboot انجام نشده است.

## قابلیت‌های فعلی که باید حفظ شوند
- Android TTS، Edge TTS (MediaPlayer)، Offline Sherpa TTS (AudioTrack).
- STT choices: Vosk، Whisper Tiny، Shenava Rizeh.
- TTS choices: Edge Farid، Edge Dilara، Piper، Android.
- notification actions: توقف شنود (`ACTION_STOP`) و توقف صحبت رایا (`ACTION_INTERRUPT`).
- wake word normalization، media strict wake gate، adaptive noise gate.
- local voice history و RayaMemoryStore.
- bridge pairing و WebView، کنترل‌های دستگاه، accessibility screen control، notification listener.
- Prefs migration فعلی برای مدل STT را بدون بررسی از بین نبر.

## قدم‌های بعدی (ترتیب الزامی)
1. تکمیل Phase 0: بازبینی همه call siteهای focus، start/stop recorder، cleanup و race conditionها؛ ثبت تست baseline.
2. طراحی state machine و ActivationMode در Prefs + VoiceAssistantService؛ هر سه mode باید رفتار واقعی داشته باشند.
3. حل single-recorder ownership و serial ASR decode؛ سپس barge-in.
4. Audio focus: حذف duck در idle؛ تعریف mix/duck قابل تنظیم برای زمان TTS؛ صدای خروجی مستقل و قابل تنظیم.
5. افزودن settings کامل و تست هر گزینه.
6. ساخت native RTL conversation UI و حفظ pairing/WebView و قابلیت‌های موجود.
7. تست CI و در نهایت device acceptance با گوشی متصل.

## قواعد پذیرش و ایمنی کار
- PR #9 را بدون اجازه صریح merge نکن.
- هیچ فایل untracked یا artifact قدیمی را پاک نکن.
- ادعای نصب APK یا تست واقعی صوت/باتری/reboot نکن مگر روی دستگاه انجام شده باشد.
- build CI را از device acceptance جدا گزارش کن.
- هر milestone: commit SHA، فایل‌های تغییرکرده، checks، artifact ID، device status و blockers را اینجا ثبت کن.
- فقط فایل‌های موردنیاز را stage کن؛ از `git add .`، `git clean` و reset مخرب استفاده نکن.

## Milestone — Audio Focus (2026-11-11)
- Changed `VoiceAssistantService.requestSpeechAudioFocus()` to intentionally skip requesting `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.
- Assistant TTS now uses the existing `USAGE_ASSISTANT` output attributes without explicitly asking Android to duck other media.
- Existing release/abandon path remains harmless because no focus request is retained.
- Static check: `git diff --check` passed.
- Device verification: pending; no ADB device is connected. Confirm media loudness and TTS intelligibility on a real phone before marking the phase complete.
- Next: implement persisted activation modes (always-listen / wake-word / manual), then barge-in and single-recorder ownership.

## Follow-up — Local build and APK install investigation (2026-11-11)
- Current HEAD observed locally: `fa164e06836a8f513d518a506db64a6ef5f3319e` (`fix(android): prevent assistant audio focus from ducking media`).
- Current branch: `fix/android-voice-barge-in-multilingual-screen`; tracked working tree clean. Numerous untracked historical APK/ZIP/log/model artifacts exist in the repository root; preserve them and do not stage them.
- Environment re-check corrected the earlier setup note: `java` resolves to Java 25.0.2; `JAVA_HOME` is unset; `ANDROID_HOME` is unset; Android SDK Platform 34 and Build Tools 34.0.0 are NOT present at `C:\Users\Ali\AppData\Local\Android\Sdk`; `adb` exists at `C:\adb\adb.exe`, but `adb devices -l` reports no connected devices. No Gradle wrapper is present in `android-app/gradle/wrapper`.
- Therefore a local build, `adb install`, and on-device reproduction cannot currently be performed. CI uses JDK 17, Android SDK 34 / Build Tools 34.0.0 and a repository-pinned `android-app/ci-debug.keystore`.
- Install failure is not diagnosed yet. Required evidence: exact Android installer error text/screenshot; Android version/device model; whether an older Dastyare-man package is already installed; whether the attempted APK came from the latest Actions artifact or an older ZIP. Do not recommend uninstalling until data preservation and signing/update compatibility are checked.
- APK configuration observed: applicationId `ir.dastyar.bridge`, minSdk 26, targetSdk 34, versionCode 17, versionName 1.16, ABIs `arm64-v8a` and `armeabi-v7a`, CI debug signing config `android-app/ci-debug.keystore`.
- Important implementation risks still open: Vosk recognizer is accessed from the normal and barge-in branches on the same microphone thread while response callbacks can mutate lifecycle; Sherpa uses the same OfflineRecognizer for normal and barge-in decode threads. Enforce serialized ownership before claiming robust barge-in.
- Activation modes (always-listen / wake-word / manual) are not implemented yet. Current service still auto-starts its selected ASR pipeline on model load and `handleRecognized` is wake-word/session based.
- Next work order: resolve single microphone/recognizer ownership and activation-mode state machine; then settings/UI. Keep build CI evidence separate from device acceptance.

## Follow-up correction — CI and signing evidence (2026-11-11, commit 720df6d)
- Current HEAD is `720df6d6f24819d79e3583f387ec199af535b14a`; PR #9 remains open and unmerged.
- GitHub Actions run `43021729016` (run #84) completed successfully. Every job step passed, including `Build debug APK`, `Verify APK signature` using Android `apksigner`, and `Upload APK`.
- Latest artifact: `dastyar-bridge-debug-apk`, artifact ID `13855940026`, 34,954,691 bytes, expires 2026-11-25. Artifact download is available from the run page: https://github.com/ijoolaie/Dastyare-man/actions/runs/43021729016
- Corrected local environment facts: JDK 17.0.20 IS installed at `C:\Program Files\Java\jdk-17.0.20`; JDK 25 is also installed. `D:\Android\Sdk` exists but is empty. No Android SDK platforms/build-tools or `sdkmanager` were found. `adb` exists at `C:\adb\adb.exe`; no device is currently connected. No Gradle wrapper is present.
- Local `jarsigner -verify` reports the APK as a JAR without a v1 manifest. This is NOT evidence that the APK is unsigned/invalid: Android APKs may use v2/v3 signing, and the authoritative CI `apksigner verify` step passed. Do not diagnose installation failure from `jarsigner` output.
- Historical extracted PR #9 APKs are 77,970,978 bytes; the latest artifact ZIP is ~34.95 MB. The old extracted APK hashes differ by build. No exact install failure has been captured; signing mismatch, package/version conflict, device ABI/API mismatch, and damaged transfer remain unconfirmed hypotheses only.
- Next required evidence from user: exact installer error/screenshot and phone model + Android version, plus confirmation of which artifact/APK was attempted. Preserve existing app data until update/signing compatibility is established.


## Follow-up ? Activation modes implementation candidate (2026-11-11)
- Working branch: `fix/android-voice-barge-in-multilingual-screen`; PR #9 remains open and must not be merged without explicit user authorization.
- Changed `Prefs.kt`, `MainActivity.kt`, and `VoiceAssistantService.kt`. Added persistent activation mode with backward-compatible default `wake_word`; settings offer Always / Wake word / Manual.
- Manual mode intentionally does not create an `AudioRecord` while idle. Its foreground notification exposes Start listening / Stop listening. One accepted manual utterance is sent as a command, then capture returns to idle. TTS completion does not reopen manual capture.
- Always mode sends each accepted ASR segment directly to chat. Wake-word mode retains the existing explicit wake-word/session behavior and still uses the full ASR engine continuously; it is NOT a low-power wake-word detector.
- Added a synchronization lock around Vosk decode and reset operations. Sherpa normal/barge-in decode concurrency remains an open risk and must be serialized before claiming robust barge-in.
- Not implemented in this candidate: native RTL conversation screen, complete settings controls (rate/volume/sensitivity/timeouts/media policy), dedicated low-power wake-word detector, comprehensive state machine, or real-device acceptance.
- Validation: `git diff --check` passed before commit. Local Android SDK is unavailable (`D:\Android\Sdk` is empty); `C:\adb\adb.exe devices -l` currently shows no device. CI build and `apksigner` verification are required before an APK can be considered installable.
- Installation instruction from user: once a new APK is produced, attempt installation through ADB. If no device is connected/authorized, do not claim installation; retain the artifact and retry when ADB detects the phone.


## Follow-up correction ? Activation mode CI artifact and ADB attempt (2026-11-11)
- Current HEAD: `8403f381de74f5c0791c4ec2dafbfbcebd56355d` (`feat(android): add persisted voice activation modes`). Branch push succeeded; PR #9 remains open and unmerged.
- GitHub Actions run #86: `43025237456` completed successfully. All steps passed: JDK 17, Android SDK, Gradle 8.7, `gradle clean assembleDebug`, Android `apksigner verify --verbose --print-certs`, and artifact upload.
- Artifact: `dastyar-bridge-debug-apk`, ID `13856413097`, ZIP size 34,959,976 bytes, SHA-256 `65bcff19e94654a7f0460bbd58a09040ef752dff548f7ecff4aafac8e07bac3c`, expires 2026-11-25. Run page: https://github.com/ijoolaie/Dastyare-man/actions/runs/43025237456
- Downloaded/extracted on Windows to `D:\android-app\ci-run86-download\app-debug.apk`; APK size 77,987,362 bytes; SHA-256 `F9332DB01041B70F52CBB4301D835878A6E6BE65D3E2A2EAF39BAEBF4D33796E`. This folder is intentionally untracked and must be preserved.
- ADB check after restarting the existing ADB server: `C:\adb\adb.exe devices -l` returned only `List of devices attached` with no device entries. `adb install -r` was attempted but timed out; no install success is claimed. The existing ADB binary reports version 1.0.32. An attempt to retrieve the Google platform-tools ZIP returned HTTP 404; no toolchain replacement was installed.
- Next installation step: connect/unlock the phone, enable USB debugging, accept the RSA authorization prompt, and verify `adb devices -l` shows `device` (not `unauthorized`). Then run `adb install -r D:\android-app\ci-run86-download\app-debug.apk`; preserve existing app data and report the exact installer output.
- Device tests remain unperformed: activation-mode switching/persistence, manual idle mic release, wake-word behavior, always-listen, barge-in, media loudness, background/reboot, and Persian/English recognition.


## Final APK candidate ? version 1.17 / versionCode 18 (2026-11-11)
- Current source commit for this APK: `a58d01a8f4dd053c4b1ac7194012d1bbbf5faaeb`; branch `fix/android-voice-barge-in-multilingual-screen`; PR #9 remains open/unmerged.
- GitHub Actions run #88 (`43026268829`) succeeded through build, Android `apksigner` signature verification, and upload.
- Artifact ID `13857257356`, name `dastyar-bridge-debug-apk`, 34,960,016 bytes, expires 2026-11-25. Run page: https://github.com/ijoolaie/Dastyare-man/actions/runs/43026268829
- Downloaded and extracted to `D:\android-app\ci-run88-download\app-debug.apk`; 77,987,358 bytes; SHA-256 `2E6FE971B86204CD725C6C18F7662B822FD1AA1657C00CAE0DB7F71EAF1A4573`. ZIP SHA-256 `56581A7F5D3781B992EC9E11A9433CAA814881D21D68C3050269B4CC108A29A1`. Preserve this folder; do not stage it.
- ADB was restarted and `adb devices -l` showed no device entries. `adb install -r D:\android-app\ci-run88-download\app-debug.apk` was attempted and timed out. Installation on the user's phone is NOT complete.
- To complete the requested installation: connect/unlock the phone, enable USB debugging, accept the RSA authorization prompt, then ensure `adb devices -l` shows the device as `device`; rerun `adb install -r` with the APK path above. Do not uninstall the existing app or clear its data.


## Device installation completed ? 2026-11-11
- User asked to retry ADB installation. On retry, device became visible: serial `ce031713e36148230c`, model `SM_G950F` (Samsung Galaxy S8 family), Android 9 / API 28.
- Installed APK: `D:\android-app\ci-run88-download\app-debug.apk` (CI run #88, artifact ID `13857257356`, source commit `a58d01a8f4dd053c4b1ac7194012d1bbbf5faaeb`).
- Command: `C:\adb\adb.exe -s ce031713e36148230c install -r D:\android-app\ci-run88-download\app-debug.apk` returned `Success`. Existing package was updated in place; no uninstall/data wipe.
- Post-install package verification: `versionCode=18`, `versionName=1.17`, `minSdk=26`, `targetSdk=34`; `lastUpdateTime=2026-11-11 16:47:45`.
- Launch verification: `adb shell monkey -p ir.dastyar.bridge -c android.intent.category.LAUNCHER 1` injected launch event; `pidof ir.dastyar.bridge` returned PID `20972`. This confirms package installation and process launch, but not full interactive/audio acceptance.
- Next manual acceptance on device: check activation-mode settings, test Always/Wake-word/Manual separately, test media ducking and barge-in, and inspect Persian/English recognition.

## Follow-up: reported bridge/model/listening defects and 1.18 candidate (2026-11-11)
- User reported that Bridge repeatedly disconnects, AI model selection is missing from Settings, and there is no visible listening activation button.
- Device diagnostics from `SM_G950F` showed a 404 from `/api/devices/register` with the server's explicit invalid-device-token message. The saved server is the existing standalone Raya preview host; the saved token was not printed or exposed. User must create a fresh Android device token from the web app's Toolbox > Devices > Add new device > Android app flow, then pair again. Do not clear app data or reveal the token in logs.
- Source changes for candidate 1.18: permanent pairing rejection handling in `BridgeService`; visible main-screen listening toggle; manual-capture preference/state broadcasts; stop state respected after app relaunch; versionCode 19 / versionName 1.18.
- AI model selection is still a blocker: current Android and deployed web chat clients send no model identifier in `/api/chat`; observed payload fields are `conversationId`, `message`, `attachmentIds`, and `requestId`. No model-list API route was found in the deployed frontend client. Need the standalone Raya backend repository or its documented supported model IDs and per-request selection field before implementing a real selector. The existing Vosk/Whisper/Shenava setting is speech-to-text only, not the AI model.
- Static validation so far: `git diff --check` passed and Android layout/strings XML parsed successfully. Local Android SDK is unavailable; CI build, APK signature verification, ADB installation, and manual acceptance remain pending.
- Preserve PR #9 as open/unmerged. Preserve all pre-existing untracked artifacts; do not stage them or run `git clean`.
## Version 1.18 build and device installation (2026-11-11)
- Commit: `d04afb67b552776ca6a7b9a2bf7e4c3b9aa87578` (`fix(android): expose listening control and diagnose bridge pairing`).
- GitHub Actions run #91: `43032841754`; build, APK signature verification, and artifact upload steps completed successfully. Run: https://github.com/ijoolaie/Dastyare-man/actions/runs/43032841754
- Artifact ID: `13862935105`; ZIP SHA-256: `9d597e6b54774f50db9aa98f49672d25d15643c4583b45898718df16f9addb5d`.
- APK: `D:\android-app\ci-run91-download\app-debug.apk`; size 77,988,330 bytes; SHA-256: `5e0fcc315aa931303b474f48e9af93c1fcfda3124e4e023149ae3bf52c359870`.
- ADB install on serial `ce031713e36148230c` (Samsung SM_G950F / Galaxy S8, Android 9) returned `Success`. `dumpsys package` confirmed `versionCode=19`, `versionName=1.18`, and `lastUpdateTime=2026-11-11 17:24:24`. Launcher event was injected and process PID `5144` was observed.
- This verifies build, signature, installation, and process launch only. UI/audio acceptance was not completed: the display was OFF and Samsung InCallUI was the resumed activity during the check, so UI hierarchy capture returned no root node. Do not claim the new listening button has passed manual UI testing yet.
- The phone's network was reported as not connected in the ADB monkey stats at this snapshot; Bridge runtime re-pair/online status was therefore not validated after install. Earlier device logs had confirmed the saved device token was rejected with HTTP 404. Generate a fresh token via Toolbox > Devices > Add new device > Android app and pair again after opening v1.18.
- AI chat model selection remains pending until the standalone Raya backend repository/API contract is available. Do not add a fake client-only selector or substitute ASR models.
- PR #9 remains open and unmerged. All pre-existing untracked artifacts were left untouched.
## Voice reliability + Android command capability fix (2026-11-11, candidate 1.19)
- Live Galaxy S8 diagnostics: quiet microphone frames were commonly around RMS 5–170 while the speech gate required at least 420 (520 during media playback). The gate minimum was reduced to 150 (180 during media playback) with ambient-relative scaling retained; this is intended to recover quiet speech without disabling media-aware filtering. Real-device false-positive/recall testing is still required.
- Added request timing logs (`Chat request started` and HTTP elapsed milliseconds) to separate microphone/ASR delay from network/server response time.
- Android Bridge now advertises `sms_read` as a capability, matching its existing `Commands.execute` implementation and the backend's existing allow-list. Existing APK-side `screen`, `brightness`, `ringer`, and `device_status` capabilities were already advertised by the current source but were absent from the API allow-list/tool schema; backend patch is prepared separately.
- Bumped Android package to versionCode 20 / versionName 1.19.
- API patch prepared in `/mnt/data/dastyar-man-api-voice-fixed.zip`: add Android `screen`, `brightness`, `ringer`, `device_status` capabilities and labels; pass `state` and `mode` arguments through `device_command`; update agent guidance to call `device_command` directly for straightforward phone actions rather than wasting a separate `list_devices` model round trip. Sensitive actions retain explicit confirmation gates.
- Important deployment boundary: the API patch is a source archive only; it has NOT been deployed to the live z.ai host or pushed to the empty GitHub API repository. Until that API patch is deployed, the added phone actions remain blocked by the server capability allow-list/tool schema.
- Validation: `git diff --check` passed. CI build/signature verification and install of 1.19 remain pending. Preserve all untracked APK/ZIP/log/model artifacts; do not stage them. PR #9 remains open and unmerged.
## Microphone activation + audio I/O hardening (2026-11-11, candidate 1.20)
- Device inspection on SM-G950F: RECORD_AUDIO is granted; built-in mic input and speaker output are available; STREAM_MUSIC and STREAM_TTS route to speaker. The persisted activation preference was initially `manual`, which intentionally leaves AudioRecord closed until the notification button is pressed. After app relaunch, current test session showed active Sherpa input and non-zero mic RMS peaks (231 and 658) with recognized segments; do not confuse a running foreground service with active recording.
- Android 1.20 switches both Vosk and Sherpa AudioRecord inputs from VOICE_COMMUNICATION to MIC, while retaining available AEC/NS processing. This is for an always-on assistant, not a phone-call capture route.
- Added explicit RECORDSTATE_RECORDING checks, session/source logs, mic level peak metering, and recovery after repeated negative AudioRecord.read results.
- Explicitly set MediaPlayer and AudioTrack output volume to 1.0 and log output-track state/route/sample count. Do not request transient-may-duck focus: that would lower music volume, contrary to the product requirement.
- Current installed device APK is still 1.19 while 1.20 CI is pending. Device input/output path was observed working on 1.19 at the Android API level; actual wake-word accuracy and human-audible output require a short spoken test after 1.20 installation.
## 1.20 build and device verification (2026-11-11)
- GitHub Actions run #95 for commit `fea02ed` completed successfully; artifact signature verification and upload succeeded. APK versionCode 21 / versionName 1.20 installed over 1.19 on the connected SM-G950F without clearing app data.
- Device preference was set and verified as `voice_activation_mode=wake_word`, `voice_enabled=true`; foreground VoiceAssistantService and BridgeService are active.
- 1.20 log confirms `Audio mode=WAKE_WORD`, AudioRecord session 5033, source `MIC`, 16 kHz, recording active. Live microphone meter observed peak RMS 1,986; Sherpa emitted final segments and the wake-word filter correctly ignored segments that did not contain the wake phrase.
- Physical routes: built-in microphone available; speaker is the active output device; no phone call was active during the output test.
- TTS test from the app voice settings: Edge Farid synthesis succeeded (32,400 bytes); MediaPlayer logged `output starting volume=1.0 route=ASSISTANT`. Human-perceived loudness still needs the device owner to confirm; software confirms the player was started, not the listener's subjective audibility.
- Remaining acceptance test: user says “رایا، صدا رو می‌شنوی؟” once while the service is running; inspect `wakeDetected=true`, request/response and mic resume after speech. Do not claim wake-word detection verified until that event is observed.
### Wake-word recognition follow-up (2026-11-11)
- Live 1.20 log showed continuous MIC capture and Sherpa final transcripts, but no wakeDetected=true during the user's two wake-word attempts.
- Root cause isolated to narrow wake-word normalization/matching: Arabic/Persian character variants and Latin ASR transliteration were not all accepted.
- Candidate fix expands normalization (Arabic yeh/alef-maqsura, tatweel, zero-width marks, case folding) and recognizes Persian plus Raya/raia token variants. Build/device verification pending.

## Milestone 10 - Persian wake-word segmentation fix (candidate 1.21, 2026-11-11)
- User tested the spoken wake word twice; device logs showed active microphone input and Sherpa final results, but every observed segment returned wakeDetected=false.
- Expanded wake-word matching to handle ASR splitting the Persian name across whitespace while preserving source offsets for command extraction. Matching is constrained to whole-token boundaries to avoid matching inside ordinary words.
- Bumped Android package to versionCode 22 / versionName 1.21.
- CI build, signature verification, installation and on-device wake-word acceptance are pending.

## Milestone 11 - Floating push-to-talk and navigation fixes (candidate 1.22, 2026-11-11)
- Added `RayaBubbleService`, a persistent Android foreground service that displays a draggable circular `?` overlay above other apps. Tapping it requests a single voice command; it does not require the wake word. Long-press is not required; the bubble can be repositioned by dragging.
- Added a dedicated `ACTION_QUICK_CAPTURE` one-shot path in `VoiceAssistantService`: it captures one utterance, sends it to `/api/chat`, and deliberately does not reopen continuous microphone capture after the spoken response. Existing wake-word/always/manual preferences are preserved.
- Added Android overlay permission handling and a Settings entry to re-open its system permission page.
- Hid the old full-width listening button that overlapped the chat composer. Added an in-app back arrow and changed hardware Back handling to navigate WebView history/SPA history rather than finish the activity at the root.
- Bumped package version to 1.22 / versionCode 23. CI build and device acceptance are pending.
- Screen-off command: Android already implements `screen` with `GLOBAL_ACTION_LOCK_SCREEN`, but requires the user to enable `Dastyar screen control` under Android Accessibility. The standalone API server must also allow/advertise `screen` and pass its `state` argument through `device_command`; source patch from prior work was prepared but is not deployed. Therefore do not claim screen-off works until API deployment and Accessibility enablement are verified.
- Preserve existing app data and all untracked artifacts. PR #9 remains open/unmerged.


### CI note for Milestone 11
- The first CI attempt exposed a missing closing quote in the overlay-permission dialog. It was fixed in `c0aa3dc`, and the bubble-state/onboarding path was hardened in `e07b070`. The workflow rerun reused its original merge ref, so a fresh PR event is required to compile the latest head; do not treat that stale rerun as validating the fixes.


## Milestone 12 - 1.22 device installation and back-button binding correction (2026-11-11)
- CI build `43092101657` succeeded, APK signature verification succeeded, artifact SHA-256 `a73b9766b81a656a0ee0635eea9955985b8c8e569ad5f61b85f2510e45a2dc9a`.
- Installed APK 1.22 (versionCode 23) on Galaxy S8 without clearing app data. Confirmed `RayaBubbleService` foreground service and `SYSTEM_ALERT_WINDOW=allow`; WindowManager reports a visible `TYPE_APPLICATION_OVERLAY` bubble. ADB tap of the bubble initiated the quick-capture path (`forceManual=true`). Actual speech-to-command acceptance still needs a spoken user test.
- Confirmed chat composer EditText remains present and old full-width native listening button is absent from the UI tree. Hardware Back no longer finishes MainActivity at the WebView root.
- The arrow button was not present in the device accessibility tree despite the XML view; version 1.23 binds it as a required field and explicitly hides/shows it with pairing/app state. CI/device verification pending.
- Screen action exists in `Commands.kt` and is advertised in `BridgeService.CAPS`, but Galaxy S8 currently lists the service as installed, not enabled (`enabled services` is empty). User must enable `Dastyar screen control` under Android Settings > Accessibility. Also the deployed chat API has no available source in the API repository (only README); the model-facing tool route that should queue `screen` is not deployed/verified. Do not claim screen-off is fixed yet.


## Milestone 13 - One-shot capture lifecycle hardening (candidate 1.24)
- Floating-button capture no longer flips the user's global `voice_enabled` preference on. It is a temporary one-shot capture.
- Added a 15-second no-speech timeout that resets the bubble to idle and stops the temporary capture. If the user had always-on/wake-word listening enabled before the tap, that mode resumes; if not, the microphone remains off after the one-shot reply.
- The quick-capture timeout is cancelled as soon as speech is accepted and when the service is destroyed.
- Bumped version to 1.24 / versionCode 25. CI and device test pending.


## Milestone 14 - Persistent visible back arrow (candidate 1.25)
- Replaced the native ImageButton back arrow with a high-contrast TextView arrow to make the control discoverable in the WebView overlay hierarchy.
- The back arrow is explicitly shown after pairing and reasserted in `onResume`; it is hidden only on the pairing screen. Hardware Back remains mapped to WebView/SPA history and never finishes the app at its root.
- Bumped version to 1.25 / versionCode 26. CI and device acceptance pending.


## Milestone 15 - Local Android screen commands (candidate 1.26)
- Added a voice-side fast path for explicit Persian/English screen lock/off and wake/on phrases. It calls `Commands.execute(..., "screen", {state})` directly on Android; it never attempts PowerShell or a shell command on the phone.
- Fast path runs for floating one-shot capture, manual capture, always-listening mode, wake-word command, and an active wake-word session. Non-screen requests still go to `/api/chat`.
- If Accessibility service is not enabled, the existing Android implementation opens Accessibility settings and speaks the exact enablement requirement. `ScreenControlAccessibilityService` is currently installed but not enabled on the Galaxy S8.
- Bumped version to 1.26 / versionCode 27. CI and device acceptance pending.


## Milestone 16 - Wake-word response loop and compact R bubble (candidate 1.27)
- Floating button changed to Latin `R`, 44dp diameter and 19sp glyph (previously 58dp / 25sp).
- When ASR emits the wake word as a standalone segment, Raya now stays silently listening instead of speaking the generic greeting that can be re-captured by the microphone. The next segment in the 45-second activation window is treated as the user's command. Same-segment wake+command remains immediate.
- Bumped version to 1.27 / versionCode 28. CI build and Galaxy S8 acceptance pending.


## Milestone 17 - ALWAYS-mode wake-word parsing (candidate 1.28)
- Fixed a separate root cause: `ActivationMode.ALWAYS` previously bypassed wake-word parsing and sent `"Raya, ..."` verbatim to `/api/chat`, which could produce a generic greeting rather than execute the command.
- ALWAYS mode now strips the wake word, waits silently up to 10 seconds if ASR emits it alone, and forwards the next transcript as the command. Wake-triggered turns receive a fresh voice conversation ID; ordinary always-listening utterances without a wake word still retain their existing behavior.
- Bumped to 1.28 / versionCode 29. CI and on-device validation pending.

## 2026-11-12 - Voice command execution and push-to-talk 1.29
- User acceptance of 1.28 found three regressions: recognized speech often returned only a generic greeting instead of acting on commands; speaking over TTS did not reliably interrupt it; listening cues were too quiet. User also requested walkie-talkie behavior.
- Candidate 1.29 changes on `fix/android-voice-barge-in-multilingual-screen`: direct raw recognized text is now sent as the `/api/chat` message (not appended after a long `Current user message:` wrapper); Vosk barge-in confirmation reduced from three to two partial-result frames; Sherpa barge-in silence window reduced from 300 ms to 150 ms and minimum speech buffer from 300 ms to 200 ms.
- Floating `R` now preserves tap-to-capture and adds hold-to-talk: press for 220 ms to start, release to finalize/send. During hold, ASR segments are buffered across pauses; Vosk final audio is flushed on release; Sherpa finalizes the in-flight utterance and has a decode-aware fallback. Starting PTT interrupts current TTS immediately.
- Listening start/stop tones use `STREAM_ALARM`, ToneGenerator level 100, and longer durations. Actual loudness remains subject to the device alarm-volume setting.
- Version 1.29 / versionCode 30. `git diff --check` and CI compile/build/signature verification still pending. Device ADB was disconnected during this work; no install or runtime acceptance is claimed.
- Preserve app data, all untracked artifacts, and PR #9 (do not merge without explicit approval).

## 2026-11-12 - Duplicate command loop guard 1.30
- User reported that after installing 1.29 Raya repeatedly executed one previously recognized command and blocked new work.
- Candidate 1.30 adds a normalized transcript fingerprint and 12-second duplicate cooldown before dispatch in regular recognition and PTT finalization. This is a defensive mitigation for stale/replayed ASR final callbacks; runtime root cause still needs device verification.
- Bumped to versionCode 31 / versionName 1.30. Not yet built, pushed, installed, or runtime-tested.
- ADB remains connected to Galaxy S8 (serial ce031713e36148230c). Do not clear app data or merge PR #9.

## 2026-11-12 - Voice reliability and bubble feedback 1.31
- User reports poor ASR, intermittent hold-to-talk activation, no barge-in while Raya speaks, and requests the floating R button scale up while held and return on release.
- Candidate 1.31: PTT model-load callback now forces capture if PTT remains held; both capture engines use AudioSource.VOICE_RECOGNITION with 4x/min 12,800-byte buffers; adaptive speech gate lowered; Sherpa barge-in waits 300 ms of silence; short one-word barge-in candidates accepted; manual-mode barge-in capture no longer blocked and restores manual session state after TTS interruption.
- Floating bubble: 180 ms hold threshold, drag slop 14 dp (was 8 px), subtle down-scale, 1.22x hold animation, haptic cue, and release animation back to 1.0x. Existing 44 dp base size unchanged.
- VersionCode 32 / versionName 1.31. Commit `f69b310997ba2d04c51df8057a04450d734420a3` pushed. GitHub Actions run `43171037817` succeeded; artifact `13921877561` signature-verified by CI. Installed on Galaxy S8 (`ce031713e36148230c`) using `adb install -r`; package reports versionCode 32 / versionName 1.31 and firstInstallTime unchanged (2026-11-09 19:45:43). MainActivity launched; VoiceAssistantService and RayaBubbleService are both foreground and running. Immediate crash scan empty. Spoken acceptance still pending. Do not clear data; do not merge PR #9.
- Note: current ASR choice remains user-configurable (Whisper Tiny / Shenava / Vosk). The recognition model itself has not been replaced in this patch; spoken accuracy still needs device tests and may require a stronger multilingual model on the S8.


## 2026-11-12 - Candidate 1.32 ASR and app-launch fallback
- Inspection of Galaxy S8 app-private storage: Whisper Tiny files are present (`tiny-encoder.int8.onnx` 12,937,772 bytes; `tiny-decoder.int8.onnx` 89,855,401 bytes; tokens 816,730 bytes). Shenava files are present (`model.onnx` 116,700,660 bytes; `tokens.txt` 12,236 bytes). Thus these two models were downloaded; their poor recognition is not explained by absent files. Vosk has a separate ~53 MB archive installer and is fetched on first selection; it was not found in `files/asr-models` (its expected directory is under `files/vosk-model-small-fa-0.42`).
- Existing live log only confirmed Whisper Tiny load; it did not establish successful Shenava or Vosk runtime selection. Model setting change calls `restartVoiceService`, so each selection should cause a fresh model load; verify by selecting each model and observing `Loading ASR model id=...` / `ASR model loaded id=...` or its failure.
- `Commands.openApp` had no fallback for invalid non-empty `target`; fuzzy lookup was also substring-only. Candidate 1.32 adds exact-package-first then Persian-normalized ranked fuzzy matching on app label/package, with edit-distance fallback, and restores `apps` listing.
- Candidate 1.32 lowers the initial adaptive speech-gate floor/threshold and adds short transcript diagnostics to logs. This is tuning, not a claim that ASR accuracy is solved.
- VersionCode 33 / versionName 1.32. Build, signature verification, install, and spoken acceptance remain pending. Preserve app data and untracked artifacts; do not merge PR #9.

- CI run `43174077986` succeeded: Gradle build, APK signature verification, artifact upload. Artifact ID `13923166990`; APK SHA-256 `C197ADA3519D98C5BB96A8FC2D2D2B729EA4AA9AC4D094E355691D37BB93E333`.
- Installed candidate 1.32 on connected Galaxy S8 with `adb install -r`; package reports versionCode 33 / versionName 1.32. `firstInstallTime` remains `2026-11-09 19:45:43`, confirming app data was retained. VoiceAssistantService and RayaBubbleService are foreground after launch. New process log confirms `Loading ASR model id=whisper_tiny` then `ASR model loaded id=whisper_tiny`. No fresh fatal crash was observed in the immediate check.
- Spoken acceptance for Persian/English accuracy, Shenava/Vosk model selection, and fuzzy app launch remains unverified. The device currently has `voice_model=whisper_tiny`, activation mode `manual`, and voice enabled. Do not claim those scenarios pass until user tests them.

## 2026-11-13 - Workspace cleanup and Sherpa barge-in follow-up
- User explicitly requested cleanup of the local `D:\android-app` workspace. Removed 112 generated/test files (98 filesystem items), reclaiming about 4.0 GB. Kept all tracked source, latest successful 1.32 artifact/run `43174077986`, the app backup TAR, and the Vosk model archive. Workspace now totals about 165.2 MB (46 non-Git project files; 1,098 files including `.git`).
- Current local candidate modifies `VoiceAssistantService.kt`: in Sherpa mode, three consecutive 40 ms speech frames during TTS trigger playback interruption before final ASR; recording continues so the utterance can be decoded. A 5-second transcript-vs-interrupted-TTS echo guard is included. The wake-word session window is extended on automatic interruption.
- `git diff --check` passed. No local Gradle executable/wrapper, Kotlin compiler, or Android SDK was found; ADB reports no connected device. CI build/signature verification and spoken Galaxy S8 acceptance remain pending. Do not merge PR #9.
## CI artifact update - 2026-11-13
- Run `43367558696` succeeded. Kotlin compilation, `assembleDebug`, APK v2 signature verification, and artifact upload all passed. Workflow built the PR #9 merge commit `b8f1dfff...`, which includes source commit `c8782d2`.
- Artifact `14011262211` downloaded and ZIP SHA-256 verified. APK saved locally at `D:\android-app\artifacts\run-43367558696\app-debug.apk`; size 78,004,934 bytes; SHA-256 `041b77eb9a07a3556113f82badf73d06856dc5cf64a6a536fdd9ffd66c812d79`.
- CI signer certificate SHA-256 `4888f90cd7dc389d3aed7012595ec58efbd049860ac61eafc06d95fb96c27c9e`.
- Device remains disconnected (`adb devices -l` returned no devices). Do not run install until Galaxy S8 is visible; then use `adb install -r` only, and test microphone barge-in, false-trigger rejection, Persian speech continuation, wake-word session behavior, and stop button.
## 2026-11-13 - Corrected Sherpa barge-in candidate installed on Galaxy S8
- Source commit `fa46c89760e77730961985b668a83be2b5418901` corrects onset accounting so any non-speech frame resets the consecutive-frame counter; this enforces three genuinely consecutive 40 ms speech frames before automatic TTS interruption.
- GitHub Actions run `43368654436` completed successfully: Android build, APK signature verification, and artifact upload all passed. Artifact ID `14012045248`; artifact ZIP SHA-256 `f6f8310b91271928e5992b3c5d8a93b50e2faefc4e5cb37cd31ea319e3303994`.
- Extracted APK: `D:\android-app\artifacts\run-43368654436\app-debug.apk`, 78,004,934 bytes; SHA-256 `ec9211f73a802897a50d5b864d9c28e210c1140782476e85740291c256e153e5`.
- Galaxy S8 (`ce031713e36148230c`, SM-G950F) reconnected. `adb install -r` returned `Success`; package remains versionName 1.32 / versionCode 33 (this patch did not bump package version). Existing app data was preserved; no uninstall or data clear was performed.
- Launched `ir.dastyar.bridge/.MainActivity` successfully. Device log shows Raya voice notification channels (`raya_voice`, `raya_bubble`) after launch; immediate scan found no `FATAL EXCEPTION`/`AndroidRuntime` crash.
- Still pending: spoken acceptance for barge-in latency, ASR continuation after interruption, echo/false-trigger rejection, Persian-English mixed speech, wake-word session, and stop button. Build/install/launch are verified; real speech behavior is not yet verified. PR #9 remains open and unmerged.

## 2026-11-13 voice reliability follow-up (candidate 1.33)
A Sherpa barge-in handoff defect was corrected in `VoiceAssistantService.kt`: speech onset interrupts TTS, and the subsequent decoded utterance is now accepted during a five-second post-interruption window instead of being discarded because playback is already inactive. The common handler retains the interrupted TTS text for echo suppression; Vosk barge-in also marks the interruption as automatic. Version bumped to 1.33 (versionCode 34). Static `git diff --check` passed. CI and real-device tests are still required. The current Windows host reports no connected ADB device; the beep-only start/listen symptom and repeated-speech symptom remain unverified and open. Do not clear app data, uninstall, reset settings, or claim microphone acceptance without a real spoken test.

## 2026-11-13 — نسخه 1.34: جلوگیری از قطع صدا با نویز
- علت گزارش‌شده در نسخه 1.33: مسیر Sherpa صرفاً با عبور انرژی میکروفون از آستانه و پس از سه فریم، پیش از تأیید ASR، پخش TTS را قطع می‌کرد؛ صدای محیط و بازتاب صدای خود دستیار نیز می‌توانست این مسیر را فعال کند.
- اصلاح: حذف قطع فوری TTS بر اساس RMS. ضبط کوتاه ادامه می‌یابد و فقط پس از تولید متن قابل‌تشخیص توسط ASR و عبور از فیلتر `isBargeInCandidate`، صحبت رایا قطع و ورودی کاربر پردازش می‌شود.
- Vosk همچنان از تشخیص متن/نامزد گفتار برای barge-in استفاده می‌کند.
- نسخه برنامه به 1.34 (versionCode 35) افزایش یافت.
- نیازمند تست واقعی روی گوشی: سکوت/صدای محیط نباید TTS را قطع کند؛ صحبت کاربر باید پس از پایان کوتاه مکث تشخیص و پاسخ قبلی قطع شود. صدای اعلان شنود باید فقط هنگام تغییر واقعی وضعیت پخش شود.