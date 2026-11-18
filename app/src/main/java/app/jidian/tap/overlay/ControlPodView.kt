package app.jidian.tap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val POD_GRAPHITE = Color.parseColor("#14171A")
private val POD_IVORY = Color.parseColor("#EDEAE3")
private val POD_SIGNAL = Color.parseColor("#C6372A")

/**
 * 收起态悬浮圆盘（宿主按 52dp 窗口挂载，视觉圆盘约 46dp）。
 *
 * 交互只有三个，全部在 350ms 内可预期，不存在「点了没反应」的死区：
 * - **轻点**（任何时长，只要没拖没长按）→ [Listener.onToggle] 立即启停，无 300ms 延时；
 * - **长按 350ms** → [Listener.onExpandRequested] 展开面板（面板里有更大的启停键）；
 * - **拖动** → [Listener.onDragBy] / [Listener.onDragFinished]。
 *
 * 视觉：石墨底 + 象牙表圈 + 12 根刻度；待命象牙播放三角，运行中信号红方块 +
 * 红色短弧。**没有任何常驻动画**：只在引擎状态变化时重绘一次，避免悬浮窗持续
 * 60fps 重绘拖慢整机。
 */
class ControlPodView(context: Context) : View(context) {

    interface Listener {
        /** 轻点：宿主切换引擎启停。 */
        fun onToggle()

        /** 长按 350ms：请求展开迷你面板。 */
        fun onExpandRequested()

        /** 拖动中：窗口位置偏移 (dx, dy) 像素。 */
        fun onDragBy(dx: Int, dy: Int)

        /** 拖动结束：宿主负责夹进屏幕并持久化位置。 */
        fun onDragFinished()
    }

    var listener: Listener? = null

    /** 引擎是否运行中（由宿主采样后推进来，只在变化时重绘）。 */
    private var engineRunning = false

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private val playPath = Path()

    private var downX = 0f
    private var downY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var downAtMs = 0L
    private var dragging = false
    private var longPressFired = false

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = POD_GRAPHITE
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = POD_IVORY
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
        strokeCap = Paint.Cap.ROUND
        color = withAlpha(POD_IVORY, 0.16f)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = POD_IVORY
    }

    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = POD_SIGNAL
    }

    private val runRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        color = POD_SIGNAL
    }

    private val longPressRunnable = Runnable {
        if (!dragging && !longPressFired) {
            longPressFired = true
            isPressed = false
            invalidate()
            listener?.onExpandRequested()
        }
    }

    /**
     * 宿主采样入口：每 250ms 调一次；只有运行状态变化时才 invalidate。
     */
    fun applyEngineState(running: Boolean) {
        if (running == engineRunning) return
        engineRunning = running
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w.toFloat(), h.toFloat()) / 2f - dp(1f)
        val size = dp(16f)
        playPath.reset()
        playPath.moveTo(centerX - size * 0.38f, centerY - size * 0.5f)
        playPath.lineTo(centerX + size * 0.44f, centerY)
        playPath.lineTo(centerX - size * 0.38f, centerY + size * 0.5f)
        playPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        val r = radius
        if (r <= 0f) return

        canvas.drawCircle(centerX, centerY, r, discPaint)

        // 表圈：1dp 象牙描边
        ringPaint.color = POD_IVORY
        canvas.drawCircle(centerX, centerY, r * 0.90f, ringPaint)

        // 12 根整点刻度（比 60 根省 80% 的绘制调用，视觉上更像计时码表）
        tickPaint.color = withAlpha(POD_IVORY, if (isPressed) 0.42f else 0.18f)
        val outer = r * 0.82f
        val inner = outer - r * 0.14f
        var i = 0
        while (i < TICK_COUNT) {
            val angle = Math.toRadians(i * 360.0 / TICK_COUNT)
            val cs = cos(angle).toFloat()
            val sn = sin(angle).toFloat()
            canvas.drawLine(
                centerX + cs * inner,
                centerY + sn * inner,
                centerX + cs * outer,
                centerY + sn * outer,
                tickPaint,
            )
            i++
        }

        if (engineRunning) {
            // 运行中：信号红方块（停止的通用语义）+ 外圈短弧
            val half = dp(5.5f)
            canvas.drawRect(centerX - half, centerY - half, centerX + half, centerY + half, signalPaint)
            canvas.drawArc(
                centerX - r * 0.62f,
                centerY - r * 0.62f,
                centerX + r * 0.62f,
                centerY + r * 0.62f,
                200f,
                140f,
                false,
                runRingPaint,
            )
        } else {
            // 待命：象牙播放三角
            canvas.drawPath(playPath, iconPaint)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastMoveX = event.x
                lastMoveY = event.y
                downAtMs = SystemClock.uptimeMillis()
                dragging = false
                longPressFired = false
                isPressed = true
                invalidate()
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastMoveX
                val dy = event.y - lastMoveY
                lastMoveX = event.x
                lastMoveY = event.y
                if (!dragging && !longPressFired &&
                    hypot(event.x - downX, event.y - downY) > touchSlop
                ) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (dragging) listener?.onDragBy(dx.roundToInt(), dy.roundToInt())
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                invalidate()
                if (dragging) {
                    dragging = false
                    listener?.onDragFinished()
                    return true
                }
                if (longPressFired) {
                    longPressFired = false
                    return true
                }
                // 只要没被拖走、没触发长按，任何按压时长都算一次轻点 → 立即启停
                listener?.onToggle()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                invalidate()
                if (dragging) {
                    dragging = false
                    listener?.onDragFinished()
                }
                longPressFired = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        isPressed = false
        longPressFired = false
        dragging = false
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f) * 255f) + 0.5f).toInt() shl 24)

    companion object {
        private const val TICK_COUNT = 12
        private const val LONG_PRESS_MS = 350L
    }
}
