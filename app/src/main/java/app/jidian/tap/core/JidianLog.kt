package app.jidian.tap.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内环形日志：引擎、悬浮窗、前台服务共用。
 * 只保留最近 240 条，UI 的「稳定性」区块直接展示，便于用户反馈问题。
 */
object JidianLog {

    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val seq: Long,
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 240
    private const val LOGCAT_TAG = "Jidian"

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private var sequence = 0L
    private val _recent = MutableStateFlow<List<Entry>>(emptyList())
    val recent: StateFlow<List<Entry>> = _recent.asStateFlow()

    fun info(tag: String, message: String) = append(Level.INFO, tag, message)

    fun warn(tag: String, message: String) = append(Level.WARN, tag, message)

    fun error(tag: String, message: String) = append(Level.ERROR, tag, message)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _recent.value = emptyList()
        }
    }

    private fun append(level: Level, tag: String, message: String) {
        synchronized(lock) {
            sequence += 1
            val entry = Entry(sequence, System.currentTimeMillis(), level, tag, message)
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            _recent.value = buffer.toList()
        }
        when (level) {
            Level.INFO -> Log.i(LOGCAT_TAG, "[$tag] $message")
            Level.WARN -> Log.w(LOGCAT_TAG, "[$tag] $message")
            Level.ERROR -> Log.e(LOGCAT_TAG, "[$tag] $message")
        }
    }
}
