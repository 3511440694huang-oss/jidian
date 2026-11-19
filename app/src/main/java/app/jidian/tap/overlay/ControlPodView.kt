package app.jidian.tap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
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
 * - **拖动** → 拖动开始后宿主把窗口临时铺满全屏，本 View 只改内部偏移重绘，
 *   **拖动期间一次 WindowManager.updateViewLayout 都不调用**。
 *
 * 拖动为什么这样做：悬浮窗跟着手指走如果每帧调 `updateViewLayout`，窗口位置和触摸坐标
 * 会互相追着跑（窗口移动 → 相对坐标变化 → 再移动），加上每次重排要走一次跨进程布局，
 * 手感就是「黏、迟、卡」。铺满全屏后窗口不动，只有画布里的圆盘在动，位移按手指的屏幕
 * 绝对坐标算，松手才把窗口缩回 52dp 落到新位置——跟手程度接近系统级悬浮球。
 *
 * 视觉：石墨底 + 象牙表圈 + 12 根刻度；待命象牙播放三角，运行中信号红方块 + 红色短弧。
 * **没有任何常驻动画**：只在引擎状态变化时重绘一次。
 */
class ControlPodView(context: Context) : View(context) {

    interface Listener {
        /** 轻点：宿主切换引擎启停。 */
        fun onToggle()

        /** 长按 350ms：请求展开迷你面板。 */
        fun onExpandRequested()

        /**
         * 拖动开始：宿主应把窗口临时改成全屏，然后回调 [beginDrag] 把起始窗口坐标交给本 View。
         */
        fun onDragBegin()

        /** 拖动结束：窗口左上角的最终屏幕坐标（宿主缩回小窗并落盘）。 */
        fun onDragFinished(left: Int, top: Int)
    }

    var listener: Listener? = null

    /** 引擎是否运行中（由宿主采样后推进来，只在变化时重绘）。 */
    private var engineRunning = false

    /** 宿主窗口边长（px），拖动/缩放时几何都以它为准，不读当前 View 尺寸。 */
    private var podSizePx = 0f

    private var screenW = 0f
    private var screenH = 0f

    /** 窗口在全屏拖动模式下的左上角偏移；普通模式恒为 0。 */
    private var offsetX = 0f
    private var offsetY = 0f

    private var dragMode = false

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private val playPath = Path()

    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var longPressFired = false
    private var dragOriginX = 0f
    private var dragOriginY = 0f

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
        if (!dragging && isPressed) {
            longPressFired = true
            isPressed = false
            invalidate()
            listener?.onExpandRequested()
        }
    }

    /** 宿主创建窗口后告知窗口边长与屏幕尺寸（拖动夹边用）。 */
    fun setMetrics(podSize: Float, screenWidthPx: Int, screenHeightPx: Int) {
        podSizePx = max(podSize, 0f)
        screenW = screenWidthPx.toFloat()
        screenH = screenHeightPx.toFloat()
        layoutPod()
        invalidate()
    }

    /** 全屏拖动已就绪：把窗口原来的左上角作为本次拖动的起始偏移。 */
    fun beginDrag(originX: Int, originY: Int) {
        dragMode = true
        offsetX = originX.toFloat()
        offsetY = originY.toFloat()
        dragOriginX = offsetX
        dragOriginY = offsetY
        layoutPod()
        invalidate()
    }

    /** 宿主已把窗口缩回小窗：偏移归零，圆盘画在小窗自己的坐标系里。 */
    fun endDrag() {
        dragMode = false
        offsetX = 0f
        offsetY = 0f
        layoutPod()
        invalidate()
    }

    /** 拖动模式下的窗口左上角（宿主落盘/夹边用）。 */
    fun dragLeft(): Int = offsetX.roundToInt()

    fun dragTop(): Int = offsetY.roundToInt()

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
        if (podSizePx <= 0f) podSizePx = min(w, h).toFloat()
        layoutPod()
    }

    /**
     * 重新计算圆盘几何。
     *
     * 一律使用 [podSizePx]，不读当前 View 宽高：拖动时窗口是全屏、缩回的一瞬间
     * 布局还没跑完，如果读宽高会把圆盘画成整屏大小。
     */
    private fun layoutPod() {
        val size = if (podSizePx > 0f) podSizePx else min(width, height).toFloat()
        if (size <= 0f) return
        val left = if (dragMode) offsetX else 0f
        val top = if (dragMode) offsetY else 0f
        centerX = left + size / 2f
        centerY = top + size / 2f
        radius = size / 2f - dp(1f)
        val icon = dp(16f)
        playPath.reset()
        playPath.moveTo(centerX - icon * 0.38f, centerY - icon * 0.5f)
        playPath.lineTo(centerX + icon * 0.44f, centerY)
        playPath.lineTo(centerX - icon * 0.38f, centerY + icon * 0.5f)
        playPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        val r = radius
        if (r <= 0f) return

        canvas.drawCircle(centerX, centerY, r, discPaint)

        // 表圈：1dp 象牙描边
        ringPaint.color = POD_IVORY
        canvas.drawCircle(centerX, centerY, r * 0.90f, ringPaint)

        // 12 根整点刻度
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
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                longPressFired = false
                isPressed = true
                invalidate()
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 屏幕绝对坐标：窗口一旦铺满全屏，相对坐标会跟着窗口变，
                // rawX/rawY 才是稳定的「手指在哪」。
                if (!dragging && !longPressFired &&
                    hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop
                ) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    // 起始窗口位置由宿主在 onDragBegin 里读 params，再回传 beginDrag
                    listener?.onDragBegin()
                }
                if (dragging && dragMode) {
                    val maxX = (screenW - podSizePx).coerceAtLeast(0f)
                    val maxY = (screenH - podSizePx).coerceAtLeast(0f)
                    moveTo(
                        (dragOriginX + (event.rawX - downRawX)).coerceIn(0f, maxX),
                        (dragOriginY + (event.rawY - downRawY)).coerceIn(0f, maxY),
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                invalidate()
                if (dragging) {
                    dragging = false
                    listener?.onDragFinished(dragLeft(), dragTop())
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
                    listener?.onDragFinished(dragLeft(), dragTop())
                }
                longPressFired = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 拖动模式下只改内部偏移并重绘圆盘覆盖过的区域，不做任何窗口操作。 */
    private fun moveTo(nextX: Float, nextY: Float) {
        if (nextX == offsetX && nextY == offsetY) return
        val oldX = offsetX
        val oldY = offsetY
        offsetX = nextX
        offsetY = nextY
        layoutPod()
        val size = podSizePx
        val padding = dp(3f)
        val left = (min(oldX, nextX) - padding).coerceAtLeast(0f).toInt()
        val top = (min(oldY, nextY) - padding).coerceAtLeast(0f).toInt()
        val right = (max(oldX, nextX) + size + padding).toInt()
        val bottom = (max(oldY, nextY) + size + padding).toInt()
        invalidate(left, top, right, bottom)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        isPressed = false
        longPressFired = false
        dragging = false
        dragMode = false
        offsetX = 0f
        offsetY = 0f
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
