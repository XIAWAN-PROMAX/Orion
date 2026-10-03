package com.orion.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 一次任务的记录 */
data class TaskRecord(
    val id: Long,
    val instruction: String,
    val status: String,
    val summary: String,
    val stepCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/** 任务中的一步：模型的想法 + 执行的动作 + 执行结果 */
data class StepRecord(
    val id: Long,
    val taskId: Long,
    val stepIndex: Int,
    val thought: String,
    val action: String,
    val result: String,
    val createdAt: Long
)

/**
 * 本地数据库（SQLite）。存放任务过程与结果，供「历史记录」展示与复盘。
 * TODO(接入点)：若需要复杂查询/迁移，可平滑替换为 Room（表结构已按 Room 习惯设计）。
 */
class OrionDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TASKS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                instruction TEXT NOT NULL,
                status TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                step_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_STEPS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                step_index INTEGER NOT NULL,
                thought TEXT NOT NULL DEFAULT '',
                action TEXT NOT NULL DEFAULT '',
                result TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_steps_task ON $TABLE_STEPS(task_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_STEPS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TASKS")
        onCreate(db)
    }

    companion object {
        private const val NAME = "orion_tasks.db"
        private const val VERSION = 1
        const val TABLE_TASKS = "tasks"
        const val TABLE_STEPS = "steps"
    }
}

/** 任务仓储：所有写操作都应该在 IO 线程调用。 */
class TaskRepository(context: Context) {

    private val helper = OrionDatabase(context.applicationContext)

    fun createTask(instruction: String): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("instruction", instruction)
            put("status", "RUNNING")
            put("summary", "")
            put("step_count", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        return helper.writableDatabase.insert(OrionDatabase.TABLE_TASKS, null, values)
    }

    fun updateTask(taskId: Long, status: String, summary: String, stepCount: Int) {
        val values = ContentValues().apply {
            put("status", status)
            put("summary", summary)
            put("step_count", stepCount)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update(OrionDatabase.TABLE_TASKS, values, "_id = ?", arrayOf(taskId.toString()))
    }

    fun addStep(
        taskId: Long,
        stepIndex: Int,
        thought: String,
        action: String,
        result: String
    ): Long {
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("step_index", stepIndex)
            put("thought", thought)
            put("action", action)
            put("result", result)
            put("created_at", System.currentTimeMillis())
        }
        return helper.writableDatabase.insert(OrionDatabase.TABLE_STEPS, null, values)
    }

    fun recentTasks(limit: Int = 20): List<TaskRecord> {
        val list = mutableListOf<TaskRecord>()
        helper.readableDatabase.query(
            OrionDatabase.TABLE_TASKS,
            null,
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.toString()
        ).use { c ->
            while (c.moveToNext()) list.add(c.toTask())
        }
        return list
    }

    fun stepsOf(taskId: Long): List<StepRecord> {
        val list = mutableListOf<StepRecord>()
        helper.readableDatabase.query(
            OrionDatabase.TABLE_STEPS,
            null,
            "task_id = ?",
            arrayOf(taskId.toString()),
            null,
            null,
            "step_index ASC"
        ).use { c ->
            while (c.moveToNext()) list.add(c.toStep())
        }
        return list
    }

    fun clearAll() {
        helper.writableDatabase.delete(OrionDatabase.TABLE_STEPS, null, null)
        helper.writableDatabase.delete(OrionDatabase.TABLE_TASKS, null, null)
    }

    private fun Cursor.toTask() = TaskRecord(
        id = getLong(getColumnIndexOrThrow("_id")),
        instruction = getString(getColumnIndexOrThrow("instruction")),
        status = getString(getColumnIndexOrThrow("status")),
        summary = getString(getColumnIndexOrThrow("summary")),
        stepCount = getInt(getColumnIndexOrThrow("step_count")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toStep() = StepRecord(
        id = getLong(getColumnIndexOrThrow("_id")),
        taskId = getLong(getColumnIndexOrThrow("task_id")),
        stepIndex = getInt(getColumnIndexOrThrow("step_index")),
        thought = getString(getColumnIndexOrThrow("thought")),
        action = getString(getColumnIndexOrThrow("action")),
        result = getString(getColumnIndexOrThrow("result")),
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )
}