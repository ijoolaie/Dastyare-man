package ir.dastyar.bridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class RayaMemoryStore(context: Context) : SQLiteOpenHelper(context, "raya_memory.db", null, 1) {
    data class Turn(val role: String, val content: String, val createdAt: Long)

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

    fun personaContext(): String = buildString {
        append("اطلاعات هویتی و حافظه پایدار دستیار را رعایت کن. ")
        append("نام تو: ").append(getProfile("assistant_name", "رایا")).append(". ")
        append("نام برنامه: ").append(getProfile("app_name", "دستیار من")).append(". ")
        append("سازنده برنامه: ").append(getProfile("creator", "نامشخص")).append(". ")
        append("هدف برنامه: ").append(getProfile("purpose", "دستیار شخصی")).append(". ")
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

    fun clearHistory() { writableDatabase.delete("turns", null, null) }
}
