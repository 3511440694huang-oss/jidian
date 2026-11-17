package app.jidian.tap.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val POD_GRAPHITE = Color.parseColor("#14171A")
private val POD_IVORY = Color.parseColor("#EDEAE3")
private val POD_SIGNAL = Color.parseColor("#C6372A")

/**
 * 收起态悬浮圆盘（64dp）：石墨底 + 象牙表圈 + 60 根刻度。
 *
 * - 待命：象牙播放三角；运行中：信号红方块 + 计数变化时外圈红弧脉冲（200ms 衰减）。
 * - 触摸：拖动 → [Listener.onDragBy]；轻点 → [Listener.onToggle]；长按 350ms →
 *   [Listener.onExpandRequested]；双击 → [Listener.onOpenApp]。
 *
 * 性能约定：Paint/Path/RectF 全部复用，[onDraw] 内不分配对象；不跑持续动画，
 * 只有运行中收到点击计数变化时才启动一次 200ms 的 ValueAnimator。
 */
class ControlPodView(context: Context) : View(context) {

    interface Listener {
        /** 轻点（≤180ms 且未滑动）。宿主决定是启停引擎还是先收起面板。 */
        fun onToggle()

        /** 长按 350ms：请求展开迷你面板。 */
        fun onExpandRequested()

        /** 双击：打开疾点主界面。 */
        fun onOpenApp()

        /** 拖动中：窗口位置偏移 (dx, dy) 像素。 */
        fun onDragBy(dx: Int, dy: Int)

        /** 拖动结束：宿主负责夹进屏幕并持久化位置。 */
        fun onDragFinished()
    }

    var listener: Listener? = null

    /** 引擎是否运行中（由宿主每 100ms 采样后推进来）。 */
    private var engineRunning = false

    /** 上一次采样到的本次点击计数，用于判断「计数变化」。 */
    private var lastSessionTaps = 0L

    /** 脉冲进度 0→1；初始为 1 表示不显示（未运行/动画已结束）。 */
    private var pulseFraction = 1f

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private val playPath = Path()
    private val pulseRect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var downAtMs = 0L
    private var dragging = false
    private var longPressFired = false
    private var singleTapPending = false
    private var lastTapUpMs = 0L

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
        strokeWidth = dp(0.8f)
        strokeCap = Paint.Cap.ROUND
        color = withAlpha(POD_IVORY, 0.10f)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = POD_IVORY
    }

    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = POD_SIGNAL
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        color = POD_SIGNAL
    }

    private val pulseAnimator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PULSE_DURATION_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener { animation ->
            pulseFraction = animation.animatedFraction
            invalidate()
        }
    }

    private val longPressRunnable = Runnable {
        if (!dragging && !longPressFired) {
            longPressFired = true
            lastTapUpMs = 0L
            listener?.onExpandRequested()
        }
    }

    private val singleTapRunnable = Runnable {
        if (singleTapPending) {
            singleTapPending = false
            listener?.onToggle()
        }
    }

    /**
     * 宿主采样入口：每 100ms 调一次，只有状态或计数变化时才 invalidate。
     * 计数变化且运行中 → 触发一次脉冲。
     */
    fun applyEngineState(running: Boolean, sessionTaps: Long) {
        val tapsChanged = running && engineRunning && sessionTaps != lastSessionTaps
        if (running == engineRunning && !tapsChanged) return
        engineRunning = running
        lastSessionTaps = sessionTaps
        when {
            !running -> {
                if (pulseAnimator.isRunning) pulseAnimator.cancel()
                pulseFraction = 1f
                invalidate()
            }
            tapsChanged -> {
                // 视觉上限：一次脉冲没走完就不再叠加，避免高频闪烁
                if (!pulseAnimator.isRunning) pulseAnimator.start()
                invalidate()
            }
            else -> invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w.toFloat(), h.toFloat()) / 2f - dp(1f)
        val size = dp(20f)
        playPath.reset()
        playPath.moveTo(centerX - size * 0.41f, centerY - size * 0.5f)
        playPath.lineTo(centerX + size * 0.41f, centerY)
        playPath.lineTo(centerX - size * 0.41f, centerY + size * 0.5f)
        playPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        val r = radius
        if (r <= 0f) return

        canvas.drawCircle(centerX, centerY, r, discPaint)

        // 表圈：1dp 象牙描边，位于 88% 半径
        ringPaint.color = POD_IVORY
        canvas.drawCircle(centerX, centerY, r * 0.88f, ringPaint)

        // 60 根 6° 刻度：10% 象牙，按下时提亮
        tickPaint.color = withAlpha(POD_IVORY, if (isPressed) 0.30f else 0.10f)
        val outer = r * 0.84f
        val inner = outer - r * 0.10f
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
            // 运行中：信号红方块 14dp
            val half = dp(7f)
            canvas.drawRect(centerX - half, centerY - half, centerX + half, centerY + half, signalPaint)
            if (pulseAnimator.isRunning) {
                val progress = pulseFraction
                pulsePaint.color = withAlpha(POD_SIGNAL, (1f - progress) * 0.9f)
                val pulseRadius = r * (0.30f + 0.60f * progress)
                pulseRect.set(
                    centerX - pulseRadius,
                    centerY - pulseRadius,
                    centerX + pulseRadius,
                    centerY + pulseRadius,
                )
                canvas.drawArc(pulseRect, 210f, 120f, false, pulsePaint)
            }
        } else {
            // 待命：象牙播放三角 20dp
            canvas.drawPath(playPath, iconPaint)
        }
    }

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
                    removeCallbacks(singleTapRunnable)
                    singleTapPending = false
                    lastTapUpMs = 0L
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
                val duration = SystemClock.uptimeMillis() - downAtMs
                if (duration <= TAP_MAX_MS) handleTap()
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
        removeCallbacks(singleTapRunnable)
        longPressFired = false
        singleTapPending = false
        dragging = false
        if (pulseAnimator.isRunning) pulseAnimator.cancel()
        pulseFraction = 1f
        super.onDetachedFromWindow()
    }

    /** 区分单击与双击：单击延迟 280ms 等第二击，双击立刻打开主界面。 */
    private fun handleTap() {
        val now = SystemClock.uptimeMillis()
        if (lastTapUpMs > 0L && now - lastTapUpMs <= DOUBLE_TAP_MS) {
            lastTapUpMs = 0L
            singleTapPending = false
            removeCallbacks(singleTapRunnable)
            listener?.onOpenApp()
            return
        }
        lastTapUpMs = now
        singleTapPending = true
        removeCallbacks(singleTapRunnable)
        postDelayed(singleTapRunnable, SINGLE_TAP_DELAY_MS)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f) * 255f) + 0.5f).toInt() shl 24)

    companion object {
        private const val TICK_COUNT = 60
        private const val PULSE_DURATION_MS = 200L
        private const val LONG_PRESS_MS = 350L
        private const val TAP_MAX_MS = 180L
        private const val DOUBLE_TAP_MS = 300L
        private const val SINGLE_TAP_DELAY_MS = 300L
    }
}
