# ساخت APK با یک دستور در ویندوز

این پروژه Android Native با Kotlin است. برای ساخت APK نیازی به نصب Android Studio یا Gradle به‌صورت سراسری نیست؛ فقط یک‌بار JDK و Android SDK لازم است.

## پیش‌نیازها (فقط بار اول)

1. **JDK 17 یا 21** نصب کن و مطمئن شو دستور `java -version` در PowerShell اجرا می‌شود.
2. **Android SDK Command-line Tools** را نصب کن. می‌توانی آن را از بخش Command line tools در صفحه رسمی Android Studio دریافت کنی؛ خود Android Studio برای این روش لازم نیست.
3. متغیر محیطی ویندوز `ANDROID_SDK_ROOT` را روی مسیر SDK قرار بده؛ مسیر پیش‌فرض معمولاً این است:
   `C:\Users\<نام‌کاربری>\AppData\Local\Android\Sdk`
4. در SDK Manager یا با `sdkmanager` این دو بسته را نصب کن:
   - `platforms;android-34`
   - `build-tools;34.0.0`
5. مجوزهای Android SDK را بپذیر. اگر `sdkmanager` در دسترس باشد، اسکریپت تلاش می‌کند بسته‌های بالا را خودکار نصب کند.

## ساخت APK

روی فایل `build-apk.bat` دوبار کلیک کن؛ یا در PowerShell از پوشه پروژه اجرا کن:

```powershell
.\build-apk.bat
```

اسکریپت در صورت نیاز Gradle 8.7 را داخل پوشه `.tools` دانلود می‌کند و سپس APK را می‌سازد. خروجی نهایی:

```text
dist\dastyar-debug.apk
```

برای ساخت نسخه Release (این نسخه هنوز با کلید اختصاصی امضا نشده است):

```powershell
.\build-apk.bat -BuildType release
```

## خطاهای متداول

- `Java پیدا نشد`: JDK 17 یا 21 نصب نیست یا PATH تنظیم نشده است.
- `Detected Java 25`: نسخه Java با Gradle 8.7 این پروژه سازگار نیست؛ JDK 17 یا 21 نصب و در ابتدای PATH قرار بده.
- `Android SDK پیدا نشد`: مقدار `ANDROID_SDK_ROOT` را تنظیم کن.
- خطای دانلود Gradle: اتصال اینترنت یا Proxy را بررسی کن؛ Gradle 8.7 باید از `services.gradle.org` دریافت شود.
- خطای License: دستور `sdkmanager --licenses` را اجرا و همه مجوزهای لازم را بپذیر.

> خروجی `debug` برای نصب و آزمایش شخصی مناسب است. برای انتشار عمومی، باید نسخه Release را با کلید امضای اختصاصی و امن امضا کرد.
