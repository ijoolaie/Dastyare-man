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
