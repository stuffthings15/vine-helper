package com.example.vinehelper

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class VineDbHelper(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
 
    companion object {
        const val DATABASE_NAME = "vine_helper.db"
        const val DATABASE_VERSION = 2
        const val TABLE_SEEN_ITEMS = "seen_items"
        const val COLUMN_ASIN = "asin"
        const val COLUMN_TITLE = "title"
        const val COLUMN_CATEGORY = "category"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_NOTIFIED = "notified"
        const val COLUMN_REC_ID = "rec_id"
    }
 
    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_SEEN_ITEMS (
                $COLUMN_ASIN TEXT PRIMARY KEY,
                $COLUMN_TITLE TEXT,
                $COLUMN_CATEGORY TEXT,
                $COLUMN_TIMESTAMP INTEGER,
                $COLUMN_NOTIFIED INTEGER,
                $COLUMN_REC_ID TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }
 
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_SEEN_ITEMS ADD COLUMN $COLUMN_REC_ID TEXT NOT NULL DEFAULT ''")
        }
    }
 
    fun insertOrUpdateItem(asin: String, title: String, category: String, timestamp: Long, notified: Boolean, recId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_ASIN, asin)
            put(COLUMN_TITLE, title)
            put(COLUMN_CATEGORY, category)
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_NOTIFIED, if (notified) 1 else 0)
            put(COLUMN_REC_ID, recId)
        }
        db.insertWithOnConflict(TABLE_SEEN_ITEMS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateItemMetadata(asin: String, title: String, category: String, recId: String) {
        val values = ContentValues().apply {
            if (title.isNotBlank()) put(COLUMN_TITLE, title)
            if (category.isNotBlank()) put(COLUMN_CATEGORY, category)
            if (recId.isNotBlank()) put(COLUMN_REC_ID, recId)
        }
        if (values.size() > 0) {
            writableDatabase.update(TABLE_SEEN_ITEMS, values, "$COLUMN_ASIN = ?", arrayOf(asin))
        }
    }
    fun isSeen(asin: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SEEN_ITEMS,
            arrayOf(COLUMN_ASIN),
            "$COLUMN_ASIN = ?",
            arrayOf(asin),
            null, null, null
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    fun isNotified(asin: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SEEN_ITEMS,
            arrayOf(COLUMN_NOTIFIED),
            "$COLUMN_ASIN = ?",
            arrayOf(asin),
            null, null, null
        )
        var notified = false
        if (cursor.moveToFirst()) {
            notified = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_NOTIFIED)) == 1
        }
        cursor.close()
        return notified
    }

    fun markAsNotified(asin: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NOTIFIED, 1)
        }
        db.update(TABLE_SEEN_ITEMS, values, "$COLUMN_ASIN = ?", arrayOf(asin))
    }
 
    fun getRecId(asin: String): String {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SEEN_ITEMS,
            arrayOf(COLUMN_REC_ID),
            "$COLUMN_ASIN = ?",
            arrayOf(asin),
            null, null, null
        )
        var recId = ""
        if (cursor.moveToFirst()) {
            recId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_REC_ID)) ?: ""
        }
        cursor.close()
        return recId
    }

    fun getItemTimestamp(asin: String): Long {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SEEN_ITEMS,
            arrayOf(COLUMN_TIMESTAMP),
            "$COLUMN_ASIN = ?",
            arrayOf(asin),
            null, null, null
        )
        var ts = System.currentTimeMillis()
        if (cursor.moveToFirst()) {
            ts = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
        }
        cursor.close()
        return ts
    }

    fun pruneOldItems() {
        val db = writableDatabase
        val cutoff = System.currentTimeMillis() - (48L * 60L * 60L * 1000L) // 48 hours
        db.delete(TABLE_SEEN_ITEMS, "$COLUMN_TIMESTAMP < ?", arrayOf(cutoff.toString()))

        // Database size check - keep size under 500 MB
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        val maxSize = 500L * 1024L * 1024L // 500 MB
        if (dbFile.exists() && dbFile.length() > maxSize) {
            // Delete the oldest 50% of the remaining items
            db.execSQL("DELETE FROM $TABLE_SEEN_ITEMS WHERE rowid IN (SELECT rowid FROM $TABLE_SEEN_ITEMS ORDER BY $COLUMN_TIMESTAMP ASC LIMIT (SELECT COUNT(*) FROM $TABLE_SEEN_ITEMS) / 2)")
            // Reclaim unused space to actually shrink the file
            db.execSQL("VACUUM")
        }
    }
}
