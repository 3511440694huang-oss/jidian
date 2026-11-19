package app.jidian.tap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.jidian.tap.core.RegionShape
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val HANDLE_SIGNAL = Color.parseColor("#C6372A")
private val HANDLE_IVORY = Color.parseColor("#EDEAE3")

/**
 * 半透明「落点手柄」：直接把点击区域画在屏幕上，拖到哪就点到哪。
 *
 * 形状/大小与 [app.jidian.tap.core.ClickConfig] 里的落点区域完全一致：
 * 椭圆（圆）或矩形，窗口就是「区域 + 12dp 内边距」，所以拖动时看到的框就是实际采样范围。
 *
 * - 停止状态：整个窗口可拖动（按住任意位置拖动），松手写回 regionCx/Cy；
 * - 运行状态：宿主给窗口加 FLAG_NOT_TOUCHABLE，注入的点击直接穿过去，不会点在手柄上；
 * - 长按 380ms：展开控制面板；
 * - 轻点：不做任何事，避免误触。
 *
 * 拖动与圆盘同一套做法：宿主把窗口临时铺满全屏，本 View 只改内部偏移重绘，
 * 松手才缩回区域大小，所以拖动过程没有 WindowManager 重排，跟手不卡。
 */
class RegionHandleView(context: Context) : View(context) {

    interface Listener {
        /** 拖动开始：宿主把窗口铺满全屏后回调 [beginDrag]。 */
        fun onDragBegin()

        /** 拖动结束：区域中心最终坐标（屏幕绝对像素）。 */
        fun onDragFinished(centerX: Int, centerY: Int)

        /** 长按：请求展开控制面板。 */
        fun onOpenPanel()
    }

    var listener: Listener? = null

    private var shape: RegionShape = RegionShape.CIRCLE
    private var regionW = 0
    private var regionH = 0
    private var compactW = 0f
    private var compactH = 0f
    private var screenW = 0f
    private var screenH = 0f

    private var offsetX = 0f
    private var offsetY = 0f
    private var dragMode = false

    private var engineRunning = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var dragging = false
    private var longPressFired = false

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val oval = RectF()

    private val longPressRunnable = Runnable {
        if (!dragging && isPressed) {
            longPressFired = true
            isPressed = false
            invalidate()
            listener?.onOpenPanel()
        }
    }

    /** 宿主按区域尺寸挂窗口后调用；尺寸变化时重设。 */
    fun setMetrics(widthPx: Int, heightPx: Int, screenWidthPx: Int, screenHeightPx: Int) {
        compactW = widthPx.toFloat()
        compactH = heightPx.toFloat()
        screenW = screenWidthPx.toFloat()
        screenH = screenHeightPx.toFloat()
        invalidate()
    }

    fun setRegion(shape: RegionShape, widthPx: Int, heightPx: Int) {
        this.shape = shape
        regionW = widthPx
        regionH = heightPx
        invalidate()
    }

    /** 运行中：边框和填充更淡，提示「现在只是指示，不挡触摸」。 */
    fun setRunning(running: Boolean) {
        if (running == engineRunning) return
        engineRunning = running
        invalidate()
    }

    fun beginDrag(originX: Int, originY: Int) {
        dragMode = true
        offsetX = originX.toFloat()
        offsetY = originY.toFloat()
        dragOriginX = offsetX
        dragOriginY = offsetY
        invalidate()
    }

    fun endDrag() {
        dragMode = false
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    fun dragLeft(): Int = offsetX.roundToInt()

    fun dragTop(): Int = offsetY.roundToInt()

    /** 区域中心的屏幕坐标（落盘用）。 */
    fun centerX(): Int = (offsetX + compactW / 2f).roundToInt()

    fun centerY(): Int = (offsetY + compactH / 2f).roundToInt()

    override fun onDraw(canvas: Canvas) {
        if (compactW <= 0f || compactH <= 0f) return
        val left = if (dragMode) offsetX else 0f
        val top = if (dragMode) offsetY else 0f
        val cx = left + compactW / 2f
        val cy = top + compactH / 2f

        val pad = dp(PAD_DP)
        val halfW = min(regionW.toFloat(), compactW - pad * 2f).coerceAtLeast(1f) / 2f
        val halfH = min(regionH.toFloat(), compactH - pad * 2f).coerceAtLeast(1f) / 2f
        oval.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)

        val fillAlpha = if (engineRunning) 0.05f else 0.12f
        val borderAlpha = if (engineRunning) 0.45f else 0.8f
        val crossAlpha = if (engineRunning) 0.4f else 0.9f

        fillPaint.color = withAlpha(HANDLE_IVORY, fillAlpha)
        borderPaint.color = withAlpha(HANDLE_SIGNAL, borderAlpha)
        when (shape) {
            RegionShape.CIRCLE -> {
                canvas.drawOval(oval, fillPaint)
                canvas.drawOval(oval, borderPaint)
            }

            RegionShape.RECT -> {
                canvas.drawRect(oval, fillPaint)
                canvas.drawRect(oval, borderPaint)
            }
        }

        // 中心十字：标出「点击落点」的中心，比整块区域更精确
        crossPaint.color = withAlpha(HANDLE_SIGNAL, crossAlpha)
        val arm = dp(9f)
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
        dotPaint.color = withAlpha(HANDLE_SIGNAL, crossAlpha)
        canvas.drawCircle(cx, cy, dp(2.2f), dotPaint)

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
                if (!dragging && !longPressFired &&
                    hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop
                ) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    listener?.onDragBegin()
                }
                if (dragging && dragMode) {
                    val maxX = (screenW - compactW).coerceAtLeast(0f)
                    val maxY = (screenH - compactH).coerceAtLeast(0f)
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
                    listener?.onDragFinished(centerX(), centerY())
                    return true
                }
                longPressFired = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                invalidate()
                if (dragging) {
                    dragging = false
                    listener?.onDragFinished(centerX(), centerY())
                }
                longPressFired = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveTo(nextX: Float, nextY: Float) {
        if (nextX == offsetX && nextY == offsetY) return
        val oldX = offsetX
        val oldY = offsetY
        offsetX = nextX
        offsetY = nextY
        val pad = dp(3f)
        val left = (min(oldX, nextX) - pad).coerceAtLeast(0f).toInt()
        val top = (min(oldY, nextY) - pad).coerceAtLeast(0f).toInt()
        val right = (max(oldX, nextX) + compactW + pad).toInt()
        val bottom = (max(oldY, nextY) + compactH + pad).toInt()
        invalidate(left, top, right, bottom)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f) * 255f) + 0.5f).toInt() shl 24)

    companion object {
        /** 手柄窗口在区域外留的内边距（dp），宿主与 View 共用。 */
        const val PAD_DP = 12f

        private const val LONG_PRESS_MS = 380L
    }
}
