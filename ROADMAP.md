# Roadmap — دستیار من / Raya Android

آخرین به‌روزرسانی: 2026-11-13
مبنای کار: شاخه `fix/android-voice-barge-in-multilingual-screen`، نسخه 1.16، commit مستندات `4205570`.

## اصول اجرا
- این سند مرجع رسمی دامنه کار است؛ هیچ قابلیت موجود صرفاً برای ساده‌سازی حذف نشود.
- هر مرحله شامل پیاده‌سازی، build، تست و ثبت نتیجه است.
- تست نیازمند گوشی واقعی تا زمان اجرای واقعی روی دستگاه «انجام‌نشده» می‌ماند.
- تنظیمات نمایشی ممنوع؛ مقدار ذخیره‌شده باید در سرویس اعمال شود.
- شنود دائمی نباید Audio Focus بگیرد یا در زمان بی‌کاری صدای رسانه را کم کند.
- حفظ سازگاری، حریم خصوصی، فارسی/RTL و قابلیت توقف شفاف الزامی است.
- فایل‌های untracked قدیمی (APK/ZIP/log/artifact) متعلق به کاربرند؛ پاک‌سازی یا reset فقط با درخواست صریح کاربر مجاز است.

## فاز 0 — تثبیت خط مبنا و ممیزی
- [x] ثبت branch/commit و حفظ فایل‌های untracked.
- [x] ممیزی اولیه Prefs، MainActivity، layout، manifest، BootReceiver و مسیرهای صوتی.
- [x] شناسایی ریسک Audio Focus از نوع MAY_DUCK.
- [x] شناسایی استفاده هم‌زمان از Vosk Recognizer در مسیر barge-in.
- [x] شناسایی نبودن activation mode و صفحه native مکالمه.
- [x] شناسایی خرابی/به‌هم‌ریختگی متن فارسی در بخشی از فایل‌ها.
- [ ] ممیزی کامل lifecycle و همه مسیرهای cleanup/exception.
- [ ] اجرای build پایه محلی یا ثبت نتیجه CI.
- [ ] ثبت تست‌های واحد/دستگاهی و ریسک‌های باقیمانده.
**وضعیت:** ممیزی اولیه انجام شده؛ فاز 0 هنوز باز است. روی این میزبان JDK 17 موجود است اما Android SDK Platform 34/Build Tools 34.0.0 نصب نیست و ADB دستگاهی نشان نمی‌دهد؛ build و تست فیزیکی محلی فعلاً مسدودند.

## فاز 1 — معماری حالت‌های فعال‌سازی
سه حالت واقعی و ماندگار:
1. `ALWAYS_LISTEN`: تشخیص گفتار پیوسته، بدون wake word.
2. `WAKE_WORD`: شنود کم‌هزینه برای «رایا»، سپس دریافت فرمان.
3. `MANUAL`: میکروفون فقط بعد از فشردن دکمه مکالمه فعال شود.
- [ ] افزودن تنظیمات و migration امن.
- [ ] ذخیره و بازیابی بعد از restart؛ رفتار reboot مطابق محدودیت Android.
- [ ] state machine واحد: OFF, STARTING, IDLE, LISTENING, DETECTING, THINKING, SPEAKING, ERROR.
- [ ] چرخه عمر recorder متناسب با mode؛ فقط یک مالک میکروفون و یک ASR فعال.
- [ ] اتصال کنترل‌های UI به وضعیت واقعی سرویس.
**معیار خروج:** هر سه mode با تست/log اثبات شوند؛ Manual در idle هیچ AudioRecord نداشته باشد.

## فاز 2 — Audio Focus و صدای رسانه
- [x] حذف درخواست Audio Focus از مسیر TTS برای جلوگیری از duck شدن رسانه؛ سیاست mix بدون duck در کد اعمال شد (نیازمند تست دستگاه).
- [ ] تأیید رفتار volume mix روی دستگاه واقعی و افزودن گزینه انتخابی mix/duck.
- [ ] سیاست قابل انتخاب mix بدون duck یا duck اختیاری هنگام TTS.
- [ ] حذف focus دائمی `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.
- [ ] یکسان‌سازی AudioAttributes در Android/Edge/Offline TTS.
- [ ] کنترل مستقل و واقعی حجم TTS.
- [ ] مدیریت focus loss/gain و release.
**معیار خروج:** در idle موسیقی کم نشود؛ صدای رایا واضح باشد و رفتار TTS مطابق سیاست ذخیره‌شده باشد.

## فاز 3 — مکالمه و Barge-in
- [ ] قطع خودکار TTS با شروع صحبت کاربر.
- [ ] Stop Speaking دستی، بدون توقف شنود.
- [ ] انتقال درست فرمان و حفظ کلمات ابتدایی.
- [ ] کاهش echo با AEC/NS و فیلتر صدای خود رایا.
- [ ] رفع استفاده هم‌زمان/ناامن از Vosk Recognizer.
- [ ] ایمن‌سازی هم‌زمانی Sherpa و streamها.
- [ ] کاهش false positive موسیقی/نویز و تشخیص متن تکراری.
- [ ] timeout و silence قابل تنظیم.
**معیار خروج:** تست گفتار روی TTS، موسیقی، echo، stop و ادامه مکالمه.

## فاز 4 — تنظیمات کامل و واقعی
- [ ] activation mode.
- [ ] موتور STT و زبان.
- [ ] صدای TTS، موتور، سرعت و حجم.
- [ ] حساسیت wake word و threshold.
- [ ] silence timeout، command timeout و طول نشست.
- [ ] سیاست رسانه (mix/duck).
- [ ] روشن/خاموش‌کردن barge-in.
- [ ] اجرای پس‌زمینه، اعلان و باتری.
- [ ] وضعیت مجوز میکروفون، سرویس و مدل.
- [ ] تست میکروفون، تشخیص گفتار و خروجی صدا.
- [ ] اتصال همه گزینه‌های فعلی به رفتار واقعی.
**معیار خروج:** همه تنظیمات پس از restart حفظ شوند و اثر عملی قابل مشاهده داشته باشند.

## فاز 5 — بازطراحی تجربه کاربری
- [ ] صفحه مکالمه native، مرتب، فارسی و RTL.
- [ ] وضعیت زنده: خاموش، آماده، شنیدن، تشخیص، فکرکردن، صحبت، خطا.
- [ ] دکمه‌های شروع/توقف، میکروفون و توقف صحبت.
- [ ] transcript و حباب‌های جداگانه، بدون قفل رابط.
- [ ] تنظیمات ساده و دسته‌بندی‌شده.
- [ ] responsive و accessibility.
**معیار خروج:** کنترل بی‌عملکرد یا وضعیت گمراه‌کننده وجود نداشته باشد.

## فاز 6 — پایداری، حریم خصوصی و باتری
- [ ] release قطعی AudioRecord، effects، Audio Focus، TTS، player و recognizer.
- [ ] lifecycle، stop، خطا، reboot و محدودیت‌های Android جدید.
- [ ] عدم recorder/thread یتیم و ASR موازی.
- [ ] سنجش CPU/RAM/باتری روی گوشی واقعی در هر mode.
- [ ] مستندسازی پردازش محلی صدا و ارسال متن به سرور.
- [ ] اعلان foreground متناسب با mode و وضعیت واقعی.
**معیار خروج:** بعد از stop هیچ recorder/thread فعال نماند و محدودیت‌ها مستند شوند.

## فاز 7 — آزمون پذیرش و انتشار
- [ ] Unit/instrumentation tests برای settings migration و state machine.
- [ ] Debug build و signature verification.
- [ ] تست دستگاه در سه mode، موسیقی، پس‌زمینه و reboot.
- [ ] تست فارسی، انگلیسی و متن ترکیبی.
- [ ] تست قطع پاسخ کوتاه/بلند.
- [ ] تست مجوزها و برگشت از تنظیمات Android.
- [ ] ثبت نسخه، commit، artifact و known limitations.
- [ ] تحویل APK و گزارش پذیرش با شواهد.
**معیار خروج:** تست‌های خودکار سبز و تست‌های دستگاه با شواهد ثبت شوند.

## وضعیت فعلی
- [x] ROADMAP.md و HANDOFF.md ثبت و به branch فعلی push شدند.
- [ ] هیچ فاز پیاده‌سازی بعدی کامل نشده است.
- [ ] build قبلی CI موفق بوده؛ این موفقیت، تست رفتار صوتی یا دستگاه محسوب نمی‌شود.


## Milestone ? Persisted activation modes (implementation candidate, 2026-11-11)
- Added a persisted `voice_activation_mode` preference. Existing installations default to `wake_word` to preserve the previous wake-word behavior.
- Added three selectable modes in Settings: `always` (each accepted speech segment is sent as a command), `wake_word` (the existing explicit Raya wake-word/session gate), and `manual` (no idle microphone capture; one command is captured after the notification action is pressed).
- Added manual start/stop capture actions to the foreground notification. The manual mode returns to idle after one accepted command and does not automatically reopen the microphone after TTS.
- Centralized mode-aware capture start/resume decisions in `startAudioForCurrentMode()`.
- Serialized Vosk recognizer decode/reset access with a lock to reduce the known barge-in race.
- Not complete: wake-word mode still uses the selected full ASR pipeline rather than a dedicated low-power detector; Sherpa recognizer concurrency still needs a dedicated serialized executor; always/manual behavior and barge-in need real-device validation. No native conversation screen or full settings audit is included in this milestone.
- Validation: `git diff --check` passed; GitHub Actions run #86 (`43025237456`) built the APK and `apksigner verify` passed. Artifact ID `13856413097` (`dastyar-bridge-debug-apk`, 34,959,976-byte ZIP, expires 2026-11-25).
- Device acceptance remains pending: `C:\adb\adb.exe devices -l` lists no connected device. An `adb install -r` attempt did not complete; no successful installation is claimed.


## Final APK candidate ? version 1.17 (2026-11-11)
- `versionName=1.17`, `versionCode=18`.
- GitHub Actions run #88 (`43026268829`) for commit `a58d01a8f4dd053c4b1ac7194012d1bbbf5faaeb` completed successfully; debug build, Android `apksigner` verification, and artifact upload all passed.
- Artifact ID `13857257356`; ZIP SHA-256 `56581a7f5d3781b992ec9e11a9433caa814881d21d68c3050269b4cc108a29a1`; expires 2026-11-25.
- Extracted APK: `D:\android-app\ci-run88-download\app-debug.apk`, 77,987,358 bytes; SHA-256 `2e6fe971b86204cd725c6c18f7662b822fd1aa1657c00cae0db7f71eaf1a4573`.
- Installation attempt: ADB lists no connected devices; `adb install -r` timed out. Device installation is not complete and must be retried after USB debugging authorization.

## Follow-up: Bridge pairing diagnosis and in-app listening control (2026-11-11, candidate 1.18)
- Live Galaxy S8 log showed `POST /api/devices/register HTTP 404`; the response body says the device token is invalid and directs the user to add the device from the web app's Devices tab. This is a confirmed pairing/authentication failure, not evidence of an unstable radio connection.
- Candidate 1.18 classifies invalid-token registration/poll responses (401/403 and token-specific 404) as permanent pairing failures, stops pointless exponential retry loops, and leaves an actionable Bridge foreground-notification status. Transient transport/server errors continue using bounded backoff.
- Added a visible main-screen Start/Stop Listening control. Manual capture state is persisted and broadcast to keep the button label synchronized; stopping the assistant is respected after relaunch, while a fresh pairing enables it initially.
- Added a persistent preference for manual-capture state and clear it when pairing data is cleared.
- AI chat model selection remains unimplemented. The Android chat client currently posts `conversationId`, `message`, `attachmentIds`, and `requestId` to `/api/chat`; the deployed web client uses the same payload and exposes no model catalog route. A real selector requires the standalone Raya server's supported model IDs and an API contract for per-request model selection. Do not confuse the local ASR model selector (Vosk/Whisper/Shenava) with the AI chat model selector, and do not connect this app to AI-Employee.
- Static checks: `git diff --check` and XML parsing passed. CI build/signature verification and device acceptance are pending; no 1.18 APK is claimed yet.
## Version 1.18 CI and installation record (2026-11-11)
- CI run #91 (`43032841754`) completed build, signature verification, and artifact upload successfully; artifact `13862935105`.
- Version 1.18 / versionCode 19 was installed successfully by ADB on the Samsung Galaxy S8 (`ce031713e36148230c`); package version and process launch were verified.
- Manual UI/audio acceptance is still pending because the phone display was off and another Samsung activity was foreground during the check. Network/Bridge reconnect was not verified after install; the prior invalid-token 404 still requires a fresh Android device token from Toolbox > Devices.
- AI model selection is still blocked on the standalone Raya backend API contract; it is not implemented in 1.18.
## Milestone 8 — Speech pickup and phone command execution (candidate 1.19)
- [x] Lower the fixed microphone gate floor after device logs showed ordinary quiet speech below the previous 420/520 threshold.
- [x] Add stage timing logs around `/api/chat` to measure request latency without logging spoken content.
- [x] Align Android Bridge capability advertisement with the existing `sms_read` command implementation.
- [x] Prepare API changes for missing `screen`, `brightness`, `ringer`, and `device_status` allow-list entries and missing `state`/`mode` argument forwarding.
- [x] Remove the unnecessary mandatory `list_devices` call before simple single-phone commands in the API agent instructions; retain server-side capability validation and sensitive-operation confirmations.
- [ ] Deploy the prepared API source patch to the standalone Raya API host (not AI-Employee).
- [ ] Build/sign/install APK 1.19 and verify quiet-voice pickup, command execution/result reporting, and latency on the Galaxy S8.
- [ ] Validate screen-off permission flow, screen-on, brightness, ringer, and SMS-read capability (the latter remains confirmation-gated).
## Milestone 9 — Microphone activation and audio I/O reliability (candidate 1.20)
- [x] Inspect actual persisted activation mode, RECORD_AUDIO permission, active recording history, input device and output routes on the Galaxy S8.
- [x] Switch voice capture from VOICE_COMMUNICATION to MIC for both Vosk and Sherpa; retain available acoustic echo cancellation.
- [x] Verify AudioRecord actually enters RECORDSTATE_RECORDING and expose the active mode/session/source in logs.
- [x] Add microphone peak-RMS metering and automatic capture recovery after repeated AudioRecord read failures.
- [x] Set explicit full playback volume for assistant TTS and log AudioTrack/MediaPlayer output initialization.
- [ ] Build/install 1.20 and verify wake-word pickup with several quiet and normal-volume Persian/English mixed phrases.
- [ ] Verify speaker output during media playback, no music ducking, and reliable microphone resumption after each spoken reply.
- [ ] Recheck microphone start after screen-off, screen-on, process restart and phone-call completion.- [x] GitHub Actions build/signature verification and 1.20 install on the connected Galaxy S8.
- [x] Confirm persistent wake-word activation mode and active 16 kHz MIC recording session after app launch.
- [x] Confirm real microphone RMS activity and Sherpa final transcript generation; segments without the wake word are filtered in wake-word mode.
- [x] Run the TTS voice test: Edge TTS synthesis succeeds and MediaPlayer starts at volume 1.0 on the assistant speaker route.
- [ ] Confirm a human-spoken wake phrase is detected (`wakeDetected=true`) and verify audible TTS from the phone speaker with the device owner.

- [ ] Verify Persian and Latin wake-word variants (Raya/raia) on device after the recognition-normalization fix.

## Milestone 10 - Persian wake-word segmentation fix (candidate 1.21, 2026-11-11)
- User tested the spoken wake word twice; device logs showed active microphone input and Sherpa final results, but every observed segment returned wakeDetected=false.
- Expanded wake-word matching to handle ASR splitting the Persian name across whitespace while preserving source offsets for command extraction. Matching is constrained to whole-token boundaries to avoid matching inside ordinary words.
- Bumped Android package to versionCode 22 / versionName 1.21.
- CI build, signature verification, installation and on-device wake-word acceptance are pending.

## Milestone 11 - Floating command button and navigation (candidate 1.22)
- [x] Add a draggable circular system-overlay Raya button that captures one spoken command on tap without requiring the wake word.
- [x] Keep one-shot capture isolated from continuous wake-word listening and resume no idle microphone after the reply.
- [x] Hide the full-width listening control that covered the chat input.
- [x] Add native page-back control and prevent Android Back from unexpectedly closing the app at the WebView root.
- [x] Add overlay permission onboarding and a Settings entry to re-enable the floating button.
- [ ] Build/sign APK 1.22 in GitHub Actions and install on Galaxy S8.
- [ ] Verify bubble overlay over other apps, drag/tap, one-shot Persian/English command, and no idle microphone after TTS.
- [ ] Verify WebView/SPA page back and text composer interaction on device.
- [ ] Deploy standalone Raya API allow-list/tool-schema fix for `screen` (and verify `state` forwarding); enable Android Accessibility service and test real screen lock.

- [ ] Re-run CI against latest PR head after dialog-string syntax correction; prior rerun checked the cached merge ref and is not valid verification.


## Milestone 12 - 1.22 device acceptance and 1.23 correction
- [x] CI built and signature-verified APK 1.22.
- [x] Installed 1.22 on Galaxy S8 preserving app data.
- [x] Confirmed floating overlay permission, foreground bubble service, and system overlay window.
- [x] Confirmed chat composer remains present and legacy full-width listener control is absent.
- [x] Confirmed Android Back keeps the app open at WebView root.
- [ ] Verify the visible native back arrow in 1.23 (explicit view binding correction).
- [ ] User speaks a real one-shot command after tapping the bubble; verify `/api/chat`, command execution and TTS end-to-end.
- [ ] User enables `Dastyar screen control` in Android Accessibility; verify `GLOBAL_ACTION_LOCK_SCREEN`.
- [ ] Deploy model-facing API tool/schema route for the `screen` action; API source repository currently contains only README.


## Milestone 13 - One-shot capture lifecycle (candidate 1.24)
- [x] Do not change global voice-enabled preference when using the floating button.
- [x] Add 15-second no-speech timeout and restore the prior listening policy.
- [x] Preserve always-on/wake-word listening after the one-shot response only when it was enabled before the tap.
- [ ] Build/sign APK 1.24 and install on Galaxy S8.
- [ ] Verify tap ? one Persian command ? chat execution ? speech response; verify timeout resets bubble and prior mic policy is preserved.


## Milestone 14 - Visible page-back control (candidate 1.25)
- [x] Use an accessible text arrow rather than ImageButton for the native back overlay.
- [x] Reassert visibility when the app resumes and after loading the paired chat view.
- [ ] Build/sign APK 1.25 and verify `btnBack` appears in the Galaxy S8 accessibility/UI tree.
- [ ] Tap the arrow on a nested app page and confirm it navigates back; verify root does not exit.


## Milestone 15 - Native local screen action (candidate 1.26)
- [x] Detect explicit Persian/English screen-off and screen-on commands locally before forwarding other speech to the model.
- [x] Execute the Android `GLOBAL_ACTION_LOCK_SCREEN` / native wake-lock implementation through `Commands`, not PowerShell.
- [x] Preserve the normal chat path for non-screen commands.
- [ ] Build/sign APK 1.26 and install on Galaxy S8.
- [ ] Enable `Dastyar screen control` in Android Accessibility (user confirmation required), then test voice screen-off and screen-on.


## Milestone 16 - Wake-word loop and compact R bubble (candidate 1.27)
- [x] Replace floating Persian glyph with English `R`; reduce bubble to 44dp / 19sp.
- [x] Wait silently after a standalone wake-word segment; route the next ASR segment as the command.
- [x] Keep same-segment wake+command handling.
- [ ] Build/sign APK 1.27 and install on Galaxy S8.
- [ ] Verify wake+command invokes `/api/chat` once and no greeting loop occurs.


## Milestone 17 - ALWAYS-mode wake-word parsing (candidate 1.28)
- [x] Parse and remove `????` in ALWAYS mode instead of forwarding it as part of the prompt.
- [x] Wait silently for the following ASR segment when wake word is detected alone.
- [x] Keep direct always-listening command behavior when no wake word is used.
- [ ] Build and install APK 1.28.
- [ ] Verify `????? ???? ?????` yields one actual answer and no generic greeting loop.

## Milestone 18 - Voice command routing, barge-in and push-to-talk (candidate 1.29, 2026-11-12)
- [x] Add hold-to-talk to the floating R control: a tap retains the existing one-shot capture; holding for 220 ms starts continuous capture, and releasing submits the captured utterance.
- [x] Keep speech segments during the hold and combine them only after release; short pauses no longer submit a partial command.
- [x] Finalize Vosk tail audio on release and let Sherpa flush the active utterance; add a guarded fallback if no final segment arrives.
- [x] Interrupt Raya playback immediately when push-to-talk begins, then capture the user's command.
- [x] Reduce barge-in recognition latency for Vosk and Sherpa while preserving transcript-based speech checks.
- [x] Send the recognized voice command as the direct `/api/chat` message instead of wrapping it in a second natural-language context sentence that can dilute the imperative.
- [x] Raise listening cue gain to the alarm stream at maximum ToneGenerator level and lengthen both start/stop cues.
- [x] Bump Android package to versionCode 30 / versionName 1.29.
- [ ] Build and signature-verify APK 1.29 in GitHub Actions.
- [ ] Install over 1.28 on Galaxy S8 without clearing app data.
- [ ] Verify voice commands invoke the expected Bridge tool and do not fall back to generic greeting.
- [ ] Verify holding/releasing R submits exactly one complete Persian/English utterance, including pauses.
- [ ] Verify PTT press interrupts ongoing TTS, and passive barge-in interrupts within an acceptable delay.
- [ ] Confirm the two listening cues are clearly audible at the phone's configured alarm volume.

## Milestone 19 - Duplicate voice-command suppression (candidate 1.30)
- [x] Add normalized transcript deduplication so stale repeated ASR final results cannot re-dispatch the same command during a 12-second cooldown.
- [x] Apply the guard to normal recognition and push-to-talk final dispatch; distinct commands remain eligible immediately.
- [x] Bump package to versionCode 31 / versionName 1.30.
- [x] Build/signature-verify APK 1.30 in GitHub Actions and install over 1.29 with `adb install -r`; app data preserved.
- [ ] Verify one spoken command triggers one action, then a different command works immediately; verify intentionally repeating the same command after cooldown works.

## Milestone 20 - Voice capture reliability and floating-button feedback (candidate 1.31)
- [x] Start PTT capture after a model finishes loading, including when the user holds the button during model initialization.
- [x] Use Android's VOICE_RECOGNITION audio source and a larger capture buffer for both Vosk and Sherpa paths.
- [x] Lower adaptive speech-gate thresholds to avoid clipping quieter/farther speech; extend Sherpa barge-in silence window to 300 ms.
- [x] Keep barge-in capture active in manual mode; after interrupting TTS, route the recognized command into the active session.
- [x] Accept short one-word interruption phrases when they are not simply part of Raya's current spoken response.
- [x] Improve floating R touch reliability: 180 ms hold, 14 dp drag slop, immediate press scale, 1.22x hold scale, haptic feedback, and return-to-normal release animation.
- [x] Build/signature-verify APK 1.31 in GitHub Actions and install over 1.30 on Galaxy S8 with `adb install -r`; app data preserved. MainActivity and both foreground services are running.\r\n- [ ] Spoken acceptance still needed: validate Persian/English recognition in quiet and noisy rooms, PTT start/release, and interruption during long TTS.


## Milestone 21 - ASR capture tuning and app-launch fuzzy fallback (candidate 1.32)
- Device file inspection confirmed Whisper Tiny encoder/decoder/tokens (about 102 MB) and Shenava model/tokens (about 114 MB) are present under app-private `files/asr-models`; Vosk is downloaded separately only when selected and was not present in that directory at inspection time.
- `open_app` previously attempted fuzzy lookup only when `target` was empty; an invalid non-empty package ID returned immediately. It now tries the exact package first, then searches using `query` or the invalid target, normalizes Persian/Arabic letter variants and separators, and ranks launcher labels/package names with edit-distance fallback. `apps` listing remains available.
- Speech gate now starts with a lower ambient floor and less aggressive threshold so quiet/initial speech is less likely to be rejected on Galaxy S8. Added bounded transcript diagnostics for comparing selected recognizers. Unknown stored ASR IDs now safely default to Whisper rather than unexpectedly selecting Vosk.
- VersionCode 33 / versionName 1.32. Source validation and CI/device verification pending. No app data cleared; PR #9 remains open/unmerged.

## 2026-11-13 - Local workspace cleanup and early Sherpa barge-in candidate
- At the user's explicit request, removed 112 obsolete local generated/test files across 98 filesystem items: old CI downloads, superseded APK/ZIP packages, duplicate artifacts, device XML/SSE/log captures, and old artifact directories.
- Preserved tracked source, `.git`, the latest successful 1.32 CI artifact (run `43174077986`, APK SHA-256 `C197ADA3519D98C5`), `dastyar-app-backup.tar`, and `vosk-model-small-fa-0.42.zip`.
- Workspace reduced from approximately 4.17 GB to 165.2 MB; approximately 4.0 GB reclaimed. 46 project files remain outside `.git`; 1,098 total files including Git metadata.
- Candidate audio change: Sherpa now requests TTS interruption after three consecutive speech-gated 40 ms frames, then continues the same microphone capture for ASR. A short-lived echo guard ignores a transcript matching the interrupted TTS text. Wake-word session window is extended on onset so a barge-in command can be dispatched in wake-word mode.
- Validation pending: Android SDK/Gradle are not installed locally, ADB currently reports no connected device, and this candidate has not yet passed CI or real-device spoken acceptance. Do not claim the early interruption is verified until CI and device tests pass.
## 2026-11-13 - Sherpa barge-in CI build completed
- GitHub Actions run `43367558696` completed successfully for PR #9's merge test (merge commit `b8f1dfff2421be3575797891afba523b665525c7`, containing source commit `c8782d2`). `assembleDebug` passed; APK v2 signature verification passed; artifact upload succeeded.
- Artifact ID `14011262211`, `dastyar-bridge-debug-apk`; locally downloaded and digest-verified ZIP SHA-256 `a680de179c43efeaf9fc532ab8385bdcf93ea2977f63b6a3d930203f7a4bcfdb`.
- Extracted APK at `artifacts/run-43367558696/app-debug.apk` (78,004,934 bytes; SHA-256 `041b77eb9a07a3556113f82badf73d06856dc5cf64a6a536fdd9ffd66c812d79`). CI signer certificate SHA-256: `4888f90cd7dc389d3aed7012595ec58efbd049860ac61eafc06d95fb96c27c9e`.
- Local ADB check still shows no connected device. No installation or live microphone/TTS acceptance test has been performed. Do not treat build success as proof of runtime barge-in behavior.
## 2026-11-13 - Corrected Sherpa barge-in candidate installed on Galaxy S8
- Source commit `fa46c89760e77730961985b668a83be2b5418901` corrects onset accounting so any non-speech frame resets the consecutive-frame counter; this enforces three genuinely consecutive 40 ms speech frames before automatic TTS interruption.
- GitHub Actions run `43368654436` completed successfully: Android build, APK signature verification, and artifact upload all passed. Artifact ID `14012045248`; artifact ZIP SHA-256 `f6f8310b91271928e5992b3c5d8a93b50e2faefc4e5cb37cd31ea319e3303994`.
- Extracted APK: `D:\android-app\artifacts\run-43368654436\app-debug.apk`, 78,004,934 bytes; SHA-256 `ec9211f73a802897a50d5b864d9c28e210c1140782476e85740291c256e153e5`.
- Galaxy S8 (`ce031713e36148230c`, SM-G950F) reconnected. `adb install -r` returned `Success`; package remains versionName 1.32 / versionCode 33 (this patch did not bump package version). Existing app data was preserved; no uninstall or data clear was performed.
- Launched `ir.dastyar.bridge/.MainActivity` successfully. Device log shows Raya voice notification channels (`raya_voice`, `raya_bubble`) after launch; immediate scan found no `FATAL EXCEPTION`/`AndroidRuntime` crash.
- Still pending: spoken acceptance for barge-in latency, ASR continuation after interruption, echo/false-trigger rejection, Persian-English mixed speech, wake-word session, and stop button. Build/install/launch are verified; real speech behavior is not yet verified. PR #9 remains open and unmerged.

## Milestone 13 — Sherpa barge-in transcript handoff fix (candidate 1.33, 2026-11-13)
- [x] Confirmed a state-machine bug: Sherpa detects speech onset and interrupts TTS immediately, but its final transcript callback required `speechPlaybackActive == true`; interruption had already set it false, so the user's spoken command was discarded.
- [x] Changed the post-interruption handoff to accept a non-empty Sherpa transcript within a bounded 5-second interruption window.
- [x] Made the shared barge-in handler accept either active playback or a recent automatic interruption, and preserve interrupted-TTS text for echo filtering.
- [x] Applied the same automatic-interruption metadata when Vosk detects barge-in.
- [x] Bumped Android candidate to versionCode 34 / versionName 1.33.
- [x] `git diff --check` passed.
- [ ] CI build/signature verification pending.
- [ ] Real Galaxy S8 microphone acceptance pending: wake-word activation, manual capture, repeated transcript suppression, and interrupting short/long TTS by speaking naturally.
- [ ] Investigate the reported beep-only activation and intermittent repeated speech with fresh device logs; current host has no ADB device connected, so do not mark these symptoms fixed yet.

## 2026-11-13 — نسخه 1.34: جلوگیری از قطع صدا با نویز
- علت گزارش‌شده در نسخه 1.33: مسیر Sherpa صرفاً با عبور انرژی میکروفون از آستانه و پس از سه فریم، پیش از تأیید ASR، پخش TTS را قطع می‌کرد؛ صدای محیط و بازتاب صدای خود دستیار نیز می‌توانست این مسیر را فعال کند.
- اصلاح: حذف قطع فوری TTS بر اساس RMS. ضبط کوتاه ادامه می‌یابد و فقط پس از تولید متن قابل‌تشخیص توسط ASR و عبور از فیلتر `isBargeInCandidate`، صحبت رایا قطع و ورودی کاربر پردازش می‌شود.
- Vosk همچنان از تشخیص متن/نامزد گفتار برای barge-in استفاده می‌کند.
- نسخه برنامه به 1.34 (versionCode 35) افزایش یافت.
- نیازمند تست واقعی روی گوشی: سکوت/صدای محیط نباید TTS را قطع کند؛ صحبت کاربر باید پس از پایان کوتاه مکث تشخیص و پاسخ قبلی قطع شود. صدای اعلان شنود باید فقط هنگام تغییر واقعی وضعیت پخش شود.