package com.orion.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 一条「自学习」经验：某次任务结束后复盘出的好的地方、不足之处与改进要点 */
data class LearningRecord(
    val id: Long,
    val instruction: String,
    val status: String,
    val good: String,
    val bad: String,
    val tip: String,
    val createdAt: Long
)

/**
 * 自学习数据库。与任务历史分开存放，方便「清空自学习」不影响「历史记录」，反之亦然。
 * 独立成库（而不是塞进 orion_tasks.db）是为了不触碰既有库的版本，避免升级时把用户历史清掉。
 */
class OrionLearningDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_LESSONS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                instruction TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT '',
                good TEXT NOT NULL DEFAULT '',
                bad TEXT NOT NULL DEFAULT '',
                tip TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LESSONS")
        onCreate(db)
    }

    companion object {
        private const val NAME = "orion_learning.db"
        private const val VERSION = 1
        const val TABLE_LESSONS = "lessons"
    }
}

/** 自学习仓储：所有写操作都应该在 IO 线程调用。 */
class LearningRepository(context: Context) {

    private val helper = OrionLearningDatabase(context.applicationContext)

    fun add(
        instruction: String,
        status: String,
        good: String,
        bad: String,
        tip: String
    ): Long {
        val values = ContentValues().apply {
            put("instruction", instruction)
            put("status", status)
            put("good", good)
            put("bad", bad)
            put("tip", tip)
            put("created_at", System.currentTimeMillis())
        }
        return helper.writableDatabase.insert(OrionLearningDatabase.TABLE_LESSONS, null, values)
    }

    /** 最近的若干条经验，最新在前 */
    fun recent(limit: Int = 50): List<LearningRecord> {
        val list = mutableListOf<LearningRecord>()
        helper.readableDatabase.query(
            OrionLearningDatabase.TABLE_LESSONS,
            null,
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(c.toRecord())
        }
        return list
    }

    fun count(): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${OrionLearningDatabase.TABLE_LESSONS}", null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun clearAll() {
        helper.writableDatabase.delete(OrionLearningDatabase.TABLE_LESSONS, null, null)
    }

    /**
     * 供提示词使用的精简经验：只取「改进要点」，最新的在前。
     * 去重并做长度保护，避免提示词被撑爆。
     */
    fun tips(limit: Int = 8): List<String> {
        val seen = LinkedHashSet<String>()
        for (record in recent(limit * 2)) {
            val tip = record.tip.trim()
            if (tip.isEmpty()) continue
            if (seen.add(tip)) {
                if (seen.size >= limit) break
            }
        }
        return seen.toList()
    }

    private fun Cursor.toRecord() = LearningRecord(
        id = getLong(getColumnIndexOrThrow("_id")),
        instruction = getString(getColumnIndexOrThrow("instruction")),
        status = getString(getColumnIndexOrThrow("status")),
        good = getString(getColumnIndexOrThrow("good")),
        bad = getString(getColumnIndexOrThrow("bad")),
        tip = getString(getColumnIndexOrThrow("tip")),
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )
}