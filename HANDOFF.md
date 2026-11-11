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
