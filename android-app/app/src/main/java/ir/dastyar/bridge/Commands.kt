package ir.dastyar.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * اجرای دستورهای رسیده از دستیار — هر عملیات (ok, result فارسی) برمی‌گرداند.
 * همه عملیات‌ها بدون root و فقط با مجوزهای استاندارد اندروید.
 */
object Commands {

    fun has(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission(ctx: Context, permission: String) {
        ctx.getSharedPreferences("permission_prompts", Context.MODE_PRIVATE)
            .edit().putString("pending_permission", permission).apply()
        ctx.sendBroadcast(
            Intent(MainActivity.ACTION_REQUEST_PERMISSION)
                .setPackage(ctx.packageName)
                .putExtra(MainActivity.EXTRA_PERMISSION, permission)
        )
    }

    fun execute(ctx: Context, action: String, args: JSONObject): Pair<Boolean, String> {
        return try {
            when (action) {
                "sysinfo" -> sysinfo(ctx)
                "battery" -> battery(ctx)
                "vibrate" -> vibrate(ctx, args)
                "notify" -> notifyPhone(ctx, args)
                "clipboard_write" -> clipWrite(ctx, args)
                "clipboard_read" -> clipRead(ctx)
                "open_url" -> openUrl(ctx, args)
                "open_app" -> openApp(ctx, args)
                "apps" -> apps(ctx, args)
                "notifications" -> notifications(args)
                "media" -> media(ctx, args)
                "volume" -> volume(ctx, args)
                "flashlight" -> flashlight(ctx, args)
                "geolocation" -> location(ctx)
                "sms_send" -> smsSend(ctx, args)
                "sms_read" -> smsRead(ctx, args)
                "contacts" -> contacts(ctx, args)
                "call" -> call(ctx, args)
                else -> Pair(false, "عملیات ناشناخته: $action")
            }
        } catch (e: Exception) {
            Pair(false, "خطا: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── باتری ────────────────────────────────────────────────────

    private fun batteryInfo(ctx: Context): Pair<Int, Boolean> {
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        var pct = ctx.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        if (pct <= 0 && level >= 0 && scale > 0) pct = (level * 100) / scale
        return Pair(pct.coerceIn(0, 100), charging)
    }

    private fun battery(ctx: Context): Pair<Boolean, String> {
        val (pct, charging) = batteryInfo(ctx)
        return Pair(true, "باتری گوشی: $pct٪${if (charging) " ⚡️ در حال شارژ" else ""}")
    }

    // ── اطلاعات سیستم ────────────────────────────────────────────

    private fun sysinfo(ctx: Context): Pair<Boolean, String> {
        val (pct, charging) = batteryInfo(ctx)
        val mem = android.app.ActivityManager.MemoryInfo()
        ctx.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mem)
        val stat = StatFs(Environment.getDataDirectory().path)
        val upMin = SystemClock.elapsedRealtime() / 60000
        val info = listOf(
            "مدل: ${Build.MANUFACTURER} ${Build.MODEL}",
            "اندروید: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "باتری: $pct٪${if (charging) " (در حال شارژ)" else ""}",
            "رم آزاد: ${String.format(Locale.US, "%.1f", mem.availMem / 1e9)} از ${String.format(Locale.US, "%.1f", mem.totalMem / 1e9)} گیگ",
            "حافظه داخلی آزاد: ${String.format(Locale.US, "%.1f", stat.availableBytes / 1e9)} از ${String.format(Locale.US, "%.1f", stat.totalBytes / 1e9)} گیگ",
            "مدت روشن بودن: ${upMin / 60} ساعت و ${upMin % 60} دقیقه",
            "زبان سیستم: ${Locale.getDefault().displayName}",
        )
        return Pair(true, info.joinToString("\n"))
    }

    // ── ویبره و اعلان ────────────────────────────────────────────

    private fun vibrate(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        val ms = args.optLong("duration", 800L).coerceIn(100, 10_000)
        val v = ctx.getSystemService(Vibrator::class.java) ?: return Pair(false, "سرویس ویبره در دسترس نیست")
        if (!v.hasVibrator()) return Pair(false, "گوشی ویبره ندارد")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
        return Pair(true, "$ms میلی‌ثانیه ویبره زده شد 📳")
    }

    private fun notifyPhone(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        val title = args.optString("title", "دستیار من").ifEmpty { "دستیار من" }.take(80)
        val text = args.optString("text", "").take(300)
        if (Build.VERSION.SDK_INT >= 33 && !has(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
            return Pair(false, "مجوز اعلان داده نشده — در اپ، مجوز Notification را بده")
        }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return Pair(false, "NotificationManager در دسترس نیست")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("dastyar_cmds", "دستورهای دستیار", NotificationManager.IMPORTANCE_HIGH))
        }
        val tap = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, "dastyar_cmds")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }
        b.setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.ic_app).setAutoCancel(true).setContentIntent(tap)
        nm.notify((System.currentTimeMillis() % 100000).toInt(), b.build())
        return Pair(true, "اعلان روی گوشی نمایش داده شد: «$title»")
    }

    // ── کلیپ‌بورد ────────────────────────────────────────────────

    private fun clipWrite(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        val text = args.optString("text", "")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return Pair(false, "کلیپ‌بورد در دسترس نیست")
        cm.setPrimaryClip(ClipData.newPlainText("دستیار", text))
        return Pair(true, "در کلیپ‌بورد کپی شد: «${text.take(60)}»")
    }

    private fun clipRead(ctx: Context): Pair<Boolean, String> {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return Pair(false, "کلیپ‌بورد در دسترس نیست")
        return try {
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
            if (text.isEmpty()) Pair(true, "کلیپ‌بورد خالی است") else Pair(true, "محتوای کلیپ‌بورد:\n${text.take(2000)}")
        } catch (e: Exception) {
            Pair(false, "اندروید ۱۰ به بالا خواندن کلیپ‌بورد از پس‌زمینه را نمی‌دهد — این محدودیت سیستمی است")
        }
    }

    // ── باز کردن ─────────────────────────────────────────────────

    private fun openUrl(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        var url = args.optString("target", args.optString("url", "")).trim()
        if (url.isEmpty()) return Pair(false, "پارامتر target (آدرس) الزامی است")
        if (!url.startsWith("http")) url = "https://$url"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Pair(true, "لینک باز شد: $url")
    }

    private fun findApps(ctx: Context, query: String, limit: Int): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val all = infos.mapNotNull { ri ->
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (e: Exception) {
                ri.activityInfo?.packageName ?: return@mapNotNull null
            }
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            Pair(label, pkg)
        }.distinctBy { it.second }
        val q = query.trim().lowercase()
        return if (q.isEmpty()) all.take(limit) else all.filter { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }.take(limit)
    }

    private fun openApp(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        var pkg = args.optString("target", args.optString("package", "")).trim()
        if (pkg.isEmpty()) {
            val q = args.optString("query", "").trim()
            if (q.isNotEmpty()) {
                val found = findApps(ctx, q, 1)
                if (found.isEmpty()) return Pair(false, "اپی با نام «$q» پیدا نشد")
                pkg = found[0].second
            }
        }
        if (pkg.isEmpty()) return Pair(false, "پارامتر target (نام پکیج اپ) الزامی است")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Pair(false, "اپ «$pkg» نصب نیست یا قابل باز شدن نیست")
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Pair(true, "اپ «$pkg» باز شد")
    }

    private fun apps(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        val limit = args.optInt("limit", 40).coerceIn(1, 100)
        val found = findApps(ctx, args.optString("query", ""), limit)
        if (found.isEmpty()) return Pair(true, "اپی پیدا نشد")
        return Pair(true, "${found.size} اپ:\n" + found.joinToString("\n") { "• ${it.first} (${it.second})" })
    }

    // ── نوتیفیکیشن‌ها ────────────────────────────────────────────

    private fun notifications(args: JSONObject): Pair<Boolean, String> {
        if (!NotificationListener.connected) {
            return Pair(false, "دسترسی خواندن نوتیفیکیشن فعال نیست — در تنظیمات اندروید: Settings › Notification access › دستیار من")
        }
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val list = NotificationListener.recent(limit)
        if (list.isEmpty()) return Pair(true, "فعلاً نوتیفیکیشن جدیدی ثبت نشده")
        return Pair(
            true,
            "${list.size} نوتیفیکیشن اخیر:\n" + list.joinToString("\n") {
                "• [${it["app"]}] ${it["title"]}${if (!it["text"].isNullOrEmpty()) " — ${it["text"]}" else ""} (${it["time"]})"
            }
        )
    }

    // ── موزیک و صدا ──────────────────────────────────────────────

    private fun media(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return Pair(false, "AudioManager در دسترس نیست")
        val button = args.optString("button", "toggle").lowercase(Locale.US)
        val code = when (button) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return Pair(false, "button باید play | pause | toggle | next | previous | stop باشد")
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return Pair(true, "دستور پخش «$button» ارسال شد 🎵")
    }

    private fun volume(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return Pair(false, "AudioManager در دسترس نیست")
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (args.has("level")) {
            val level = args.optInt("level", -1)
            if (level !in 0..100) return Pair(false, "level باید بین ۰ تا ۱۰۰ باشد")
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (level / 100.0 * max).toInt().coerceIn(0, max), 0)
            return Pair(true, "صدای رسانه روی $level٪ تنظیم شد")
        }
        when (args.optString("direction", "").lowercase(Locale.US)) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> {
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                return Pair(true, "صدای رسانه: $cur از $max")
            }
        }
        return Pair(true, "صدا تغییر کرد")
    }

    // ── چراغ‌قوه ─────────────────────────────────────────────────

    private fun flashlight(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        val on = args.optBoolean("on", true)
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return Pair(false, "CameraManager در دسترس نیست")
        val camId = cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return Pair(false, "چراغ‌قوه روی این گوشی پیدا نشد")
        cm.setTorchMode(camId, on)
        return Pair(true, if (on) "چراغ‌قوه روشن شد 🔦" else "چراغ‌قوه خاموش شد")
    }

    // ── موقعیت مکانی ─────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun location(ctx: Context): Pair<Boolean, String> {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            requestPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            return Pair(false, "برای استفاده از موقعیت مکانی، درخواست مجوز نمایش داده شد؛ پس از تأیید، عملیات را دوباره اجرا کن")
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return Pair(false, "LocationManager در دسترس نیست")
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(p)
                if (loc != null) {
                    return Pair(
                        true,
                        "آخرین موقعیت شناسایی‌شده: ${String.format(Locale.US, "%.5f", loc.latitude)}، " +
                            String.format(Locale.US, "%.5f", loc.longitude) +
                            " (دقت ~${loc.accuracy.toInt()} متر)"
                    )
                }
            } catch (e: Exception) {
                // ارائه‌دهنده غیرفعال — برو بعدی
            }
        }
        return Pair(false, "موقعیتی پیدا نشد — احتمالاً GPS خاموش است")
    }

    // ── پیامک ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun smsSend(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        if (!has(ctx, Manifest.permission.SEND_SMS)) {
            requestPermission(ctx, Manifest.permission.SEND_SMS)
            return Pair(false, "برای ارسال پیامک، درخواست مجوز نمایش داده شد؛ پس از تأیید، عملیات را دوباره اجرا کن")
        }
        val to = args.optString("to", "").trim()
        val text = args.optString("text", "").trim()
        if (to.isEmpty() || text.isEmpty()) return Pair(false, "پارامترهای to و text الزامی است")
        val sm: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        if (sm == null) return Pair(false, "SmsManager در دسترس نیست")
        val parts = sm.divideMessage(text)
        if (parts.size <= 1) {
            sm.sendTextMessage(to, null, text, null, null)
        } else {
            sm.sendMultipartTextMessage(to, null, parts, null, null)
        }
        return Pair(true, "پیامک به $to ارسال شد: «${text.take(60)}»")
    }

    @SuppressLint("MissingPermission")
    private fun smsRead(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        if (!has(ctx, Manifest.permission.READ_SMS)) {
            requestPermission(ctx, Manifest.permission.READ_SMS)
            return Pair(false, "برای خواندن پیامک، درخواست مجوز نمایش داده شد؛ پس از تأیید، عملیات را دوباره اجرا کن")
        }
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.US)
        val out = mutableListOf<String>()
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.DATE),
            null, null,
            "${Telephony.Sms.Inbox.DATE} DESC"
        )?.use { c ->
            var i = 0
            while (c.moveToNext() && i < limit) {
                val addr = c.getString(0) ?: "?"
                val body = (c.getString(1) ?: "").replace('\n', ' ')
                val date = try { fmt.format(Date(c.getLong(2))) } catch (e: Exception) { "" }
                out.add("• [$addr] ${body.take(160)} ($date)")
                i++
            }
        }
        return if (out.isEmpty()) Pair(true, "پیامکی در صندوق ورودی پیدا نشد")
        else Pair(true, "${out.size} پیامک اخیر:\n${out.joinToString("\n")}")
    }

    // ── مخاطبین ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun contacts(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) {
            requestPermission(ctx, Manifest.permission.READ_CONTACTS)
            return Pair(false, "برای دسترسی به مخاطبین، درخواست مجوز نمایش داده شد؛ پس از تأیید، عملیات را دوباره اجرا کن")
        }
        val limit = args.optInt("limit", 10).coerceIn(1, 50)
        val q = args.optString("query", "").trim()
        val where = if (q.isEmpty()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selArgs = if (q.isEmpty()) null else arrayOf("%$q%")
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            where, selArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: ""
                if (!seen.add("$name|$num")) continue
                out.add("• $name — $num")
            }
        }
        return if (out.isEmpty()) Pair(true, "مخاطبی${if (q.isNotEmpty()) " مطابق «$q»" else ""} پیدا نشد")
        else Pair(true, "${out.size} مخاطب:\n${out.joinToString("\n")}")
    }

    // ── تماس ─────────────────────────────────────────────────────

    private fun call(ctx: Context, args: JSONObject): Pair<Boolean, String> {
        val number = args.optString("target", args.optString("to", "")).trim()
        if (number.isEmpty()) return Pair(false, "شماره تلفن لازم است (پارامتر target)")
        return if (has(ctx, Manifest.permission.CALL_PHONE)) {
            ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Pair(true, "تماس با $number برقرار شد")
        } else {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Pair(true, "مجوز تماس خودکار داده نشده — صفحه تماس با $number باز شد تا خودت دکمه سبز را بزنی")
        }
    }
}
