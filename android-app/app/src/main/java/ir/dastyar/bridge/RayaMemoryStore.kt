package ir.dastyar.bridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class RayaMemoryStore(context: Context) : SQLiteOpenHelper(context, "raya_memory.db", null, 1) {
    data class Turn(val role: String, val content: String, val createdAt: Long)
    data class Conversation(val id: String, val preview: String, val updatedAt: Long, val turnCount: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profile (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE turns (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
        seed(db, "assistant_name", "رایا")
        seed(db, "app_name", "دستیار من (Dastyare-man)")
        seed(db, "creator", "ijoolaie (شناسه مالک مخزن؛ نام نمایشی قابل ویرایش)")
        seed(db, "purpose", "دستیار شخصی اندرویدی برای مکالمه فارسی، کمک به کاربر، مدیریت ارتباط با سرویس متصل و اجرای فرمان‌های مجاز با اجازه کاربر")
        seed(db, "principles", "پاسخ دقیق و صادقانه؛ اعلام محدودیت‌ها؛ حفظ حریم خصوصی؛ عدم ادعای انجام کاری که واقعاً انجام نشده؛ درخواست اجازه برای عملیات حساس")
        seed(db, "memory_notes", "")
    }

    private fun seed(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("profile", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun getProfile(key: String, fallback: String = ""): String {
        readableDatabase.rawQuery("SELECT value FROM profile WHERE key=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else fallback
        }
    }

    fun setProfile(key: String, value: String) {
        writableDatabase.insertWithOnConflict("profile", null, ContentValues().apply {
            put("key", key); put("value", value.trim())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun personaContext(userMessage: String): String = buildString {
        append("در مکالمه عادی هرگز خودت را معرفی نکن، نام یا سازنده یا هدف برنامه را داوطلبانه تکرار نکن و مستقیم به درخواست کاربر پاسخ بده. ")
        append("فقط اگر کاربر صریحاً درباره هویت، نام، سازنده یا خود برنامه پرسید، اطلاعات هویتی را بیان کن. ")
        append("اگر کاربر فقط کلمه بیدارباش را گفت، پاسخ دقیق تو باید این باشد: «بله، چه کاری می‌توانم انجام دهم؟» ")
        append("دستگاه Android متصل از این فرمان‌ها پشتیبانی می‌کند: ").append(BridgeService.CAPS.joinToString(", ")).append(". ")
        append("اگر درخواست کاربر در این فهرست است، از مسیر اجرای فرمان دستگاه متصل استفاده کن؛ صرفاً به‌دلیل اجرا در WebView یا Chrome نگو نمی‌توانم. فقط نتیجه واقعی ابزار را گزارش کن. ")
        append("برای هر فرمان روی گوشی، ابتدا ابزار list_devices را اجرا کن و دستگاه فیزیکی اندروید را انتخاب کن. گوشی فعلی Samsung Galaxy S8 با شناسه دستگاه s8 و Android 9 است؛ در فراخوانی device_command حتماً device=\"s8\" را بفرست. دستگاه Chrome (Android) فقط نشست مرورگر است، گوشی فیزیکی نیست و نباید برای کنترل گوشی انتخاب شود. اگر s8 در فهرست نبود، از دستگاه Chrome به‌عنوان جایگزین استفاده نکن و محدودیت را صریح اعلام کن. ")
        append("خواندن SMS و اعلان‌های سایر برنامه‌ها فعلاً غیرفعال است؛ ارسال SMS صفحه پیامک را برای تأیید نهایی کاربر باز می‌کند. ")
        val asksIdentity = Regex(
            "(اسم تو|نام تو|تو کی هستی|خودت رو معرفی|خودت را معرفی|سازنده|خالق|چه کسی ساخت|اسم برنامه|نام برنامه|درباره خودت|who are you|your name|who made you|creator)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(userMessage)
        if (asksIdentity) {
            append("اطلاعات هویتی در صورت درخواست صریح: نام دستیار: ").append(getProfile("assistant_name", "رایا")).append(". ")
            append("نام برنامه: ").append(getProfile("app_name", "دستیار من")).append(". ")
            append("سازنده برنامه: ").append(getProfile("creator", "نامشخص")).append(". ")
            append("هدف برنامه: ").append(getProfile("purpose", "دستیار شخصی")).append(". ")
        }
        append("اصول رفتاری: ").append(getProfile("principles", "صداقت و حفظ حریم خصوصی")).append(". ")
        val notes = getProfile("memory_notes")
        if (notes.isNotBlank()) append("حافظه تکمیلی: ").append(notes).append(".")
    }

    fun addTurn(conversationId: String?, role: String, content: String) {
        if (content.isBlank() || (role != "user" && role != "assistant")) return
        writableDatabase.insert("turns", null, ContentValues().apply {
            put("conversation_id", conversationId); put("role", role); put("content", content.take(12000)); put("created_at", System.currentTimeMillis())
        })
        writableDatabase.execSQL("DELETE FROM turns WHERE id NOT IN (SELECT id FROM turns ORDER BY id DESC LIMIT 300)")
    }

    fun recentTurns(limit: Int = 8): List<Turn> {
        val result = mutableListOf<Turn>()
        readableDatabase.rawQuery("SELECT role,content,created_at FROM turns ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) result.add(Turn(c.getString(0), c.getString(1), c.getLong(2)))
        }
        return result.asReversed()
    }

    fun history(limit: Int = 80): List<Turn> = recentTurns(limit)

    fun conversations(limit: Int = 50): List<Conversation> {
        val result = mutableListOf<Conversation>()
        readableDatabase.rawQuery(
            "SELECT COALESCE(conversation_id, 'legacy') AS cid, " +
                "COALESCE((SELECT content FROM turns t2 WHERE COALESCE(t2.conversation_id, 'legacy') = COALESCE(turns.conversation_id, 'legacy') AND t2.role='user' ORDER BY t2.id ASC LIMIT 1), '') AS preview, " +
                "MAX(created_at), COUNT(*) FROM turns GROUP BY cid ORDER BY MAX(created_at) DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) result.add(Conversation(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3)))
        }
        return result
    }

    fun turnsForConversation(conversationId: String, limit: Int = 200): List<Turn> {
        val result = mutableListOf<Turn>()
        val sql = if (conversationId == "legacy")
            "SELECT role,content,created_at FROM turns WHERE conversation_id IS NULL ORDER BY id DESC LIMIT ?"
        else
            "SELECT role,content,created_at FROM turns WHERE conversation_id=? ORDER BY id DESC LIMIT ?"
        val args = if (conversationId == "legacy") arrayOf(limit.toString()) else arrayOf(conversationId, limit.toString())
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) result.add(Turn(c.getString(0), c.getString(1), c.getLong(2)))
        }
        return result.asReversed()
    }

    fun clearHistory() { writableDatabase.delete("turns", null, null) }
}