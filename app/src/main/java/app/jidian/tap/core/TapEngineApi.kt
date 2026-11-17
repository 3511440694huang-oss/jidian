package app.jidian.tap.core

import kotlinx.coroutines.flow.StateFlow

/**
 * 引擎对外暴露的运行状态。所有字段由引擎线程原子更新，
 * UI/悬浮窗只读；CPS 由 UI 侧按采样间隔自行计算。
 */
data class EngineStatus(
    val running: Boolean = false,
    val sessionTaps: Long = 0L,
    val totalTaps: Long = 0L,
    val sessionStartedElapsed: Long = 0L,
    val lastStopReason: String? = null,
    val gestureTimeouts: Int = 0,
    val recoveries: Int = 0,
    val dropped: Long = 0L,
    val accessibilityReady: Boolean = false,
    val waitingForService: Boolean = false,
    val targetCps: Float = 0f,
) {
    /** 本次已运行时长（毫秒），未运行时为 0。 */
    fun sessionDurationMs(nowElapsed: Long = android.os.SystemClock.elapsedRealtime()): Long =
        if (running && sessionStartedElapsed > 0L) (nowElapsed - sessionStartedElapsed).coerceAtLeast(0L) else 0L
}

/**
 * 连点引擎契约。实现见 [app.jidian.tap.core.TapEngine]，
 * UI 与悬浮窗只依赖本接口，不直接碰无障碍服务。
 */
interface TapEngineApi {
    val status: StateFlow<EngineStatus>
    val isRunning: Boolean

    /** 返回 true 表示已进入运行态（无障碍服务就绪）。 */
    fun start(source: String = "user"): Boolean

    fun stop(reason: String = "user")

    fun toggle(source: String = "user"): Boolean

    fun onAccessibilityConnected()

    fun onAccessibilityDisconnected()

    fun onScreenInteractiveChanged(interactive: Boolean)

    fun refreshConfig()
}

/** 停止原因常量，写日志与状态展示用。 */
object StopReason {
    const val USER = "user"
    const val SCREEN_OFF = "screen_off"
    const val LIMIT_REACHED = "limit"
    const val SERVICE_LOST = "service_lost"
    const val SHUTDOWN = "shutdown"
}
