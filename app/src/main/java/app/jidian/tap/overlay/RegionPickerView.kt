package app.jidian.tap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.jidian.tap.R
import app.jidian.tap.core.RegionShape
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val PICKER_IVORY = Color.parseColor("#EDEAE3")
private val PICKER_GRAPHITE = Color.parseColor("#14171A")
private val PICKER_SIGNAL = Color.parseColor("#C6372A")
private val PICKER_HAIRLINE = Color.parseColor("#D3D2CB")

/** 选区外遮罩：石墨 55%。 */
private val PICKER_MASK = Color.argb(140, 0x14, 0x17, 0x1A)

/**
 * 全屏框选覆盖层：选区外 55% 石墨遮罩，选区为象牙 2dp 椭圆/矩形 +
 * 贯穿中心的信号红十字线；拖动即重画。底部象牙浮条含
 * 「椭圆 / 矩形」切换、Mono 读数「W × H px · (cx, cy)」、「取消」「确认」。
 *
 * 坐标即窗口坐标：窗口以 FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS
 * 全屏铺满，因此 View 内的像素坐标就是屏幕绝对像素（与无障碍手势坐标系一致）。
 * 尺寸全部取自 [onSizeChanged] 传入的窗口宽高，不读 displayMetrics。
 */
class RegionPickerView(
    context: Context,
    initialShape: RegionShape,
    initialCx: Int,
    initialCy: Int,
    initialW: Int,
    initialH: Int,
) : View(context) {

    interface Listener {
        /** 确认：坐标为屏幕绝对像素；regionW/H 对椭圆是外接矩形宽高。 */
        fun onConfirm(shape: RegionShape, centerX: Int, centerY: Int, width: Int, height: Int)

        /** 取消：关闭框选层，不改配置。 */
        fun onCancel()
    }

    var listener: Listener? = null

    private var shape: RegionShape = initialShape
    private val initialCenterX = initialCx
    private val initialCenterY = initialCy
    private val initialSideW = initialW
    private val initialSideH = initialH

    private val rect = RectF()
    private var initialized = false

    private var savedLeft = 0f
    private var savedTop = 0f
    private var savedRight = 0f
    private var savedBottom = 0f

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false
    private var moved = false

    private var barTouching = false
    private var pressedId = ID_NONE

    private var readoutText = ""

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    private val monoTypeface: Typeface =
        runCatching { resources.getFont(R.font.ibm_plex_mono_medium) }.getOrNull()
            ?: Typeface.MONOSPACE

    private val barlowTypeface: Typeface =
        runCatching { resources.getFont(R.font.barlow_medium) }.getOrNull()
            ?: Typeface.DEFAULT

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PICKER_MASK
    }
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = PICKER_IVORY
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = PICKER_SIGNAL
    }
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val cellFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cellBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val cellTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = barlowTypeface
        textSize = sp(13f)
        color = PICKER_GRAPHITE
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = monoTypeface
        textSize = sp(13f)
        color = PICKER_GRAPHITE
    }

    private val cellFm = Paint.FontMetrics()
    private val readoutFm = Paint.FontMetrics()

    private val barRect = RectF()
    private val rectCircle = RectF()
    private val rectRect = RectF()
    private val rectCancel = RectF()
    private val rectConfirm = RectF()
    private val tmpRect = RectF()

    private var baselineCellRow = 0f
    private var baselineReadout = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutBar(w.toFloat(), h.toFloat())
        if (!initialized) {
            initialized = true
            initRect(w.toFloat(), h.toFloat())
        } else {
            clampRectToBounds(w.toFloat(), h.toFloat())
        }
        refreshReadout()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1) 选区外 55% 石墨遮罩（四块矩形围住选区）
        maskPaint.color = PICKER_MASK
        canvas.drawRect(0f, 0f, w, rect.top, maskPaint)
        canvas.drawRect(0f, rect.bottom, w, h, maskPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, maskPaint)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, maskPaint)

        // 2) 选区：象牙 2dp 椭圆 / 矩形
        regionPaint.color = PICKER_IVORY
        if (shape == RegionShape.CIRCLE) {
            canvas.drawOval(rect, regionPaint)
        } else {
            canvas.drawRect(rect, regionPaint)
        }

        // 3) 贯穿选区中心的信号红十字线（整屏）
        val centerXC = rect.centerX()
        val centerYC = rect.centerY()
        crossPaint.color = PICKER_SIGNAL
        canvas.drawLine(0f, centerYC, w, centerYC, crossPaint)
        canvas.drawLine(centerXC, 0f, centerXC, h, crossPaint)

        // 4) 底部浮条（最后画，压在遮罩与十字线上）
        drawBar(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                if (barRect.contains(x, y)) {
                    barTouching = true
                    pressedId = hitBar(x, y)
                    invalidate()
                    return true
                }
                barTouching = false
                pressedId = ID_NONE
                savedLeft = rect.left
                savedTop = rect.top
                savedRight = rect.right
                savedBottom = rect.bottom
                dragStartX = x
                dragStartY = y
                dragging = true
                moved = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (barTouching) {
                    val id = hitBar(event.x, event.y)
                    if (id != pressedId) {
                        pressedId = id
                        invalidate()
                    }
                    return true
                }
                if (!dragging) return true
                if (!moved && hypot(event.x - dragStartX, event.y - dragStartY) > touchSlop) {
                    moved = true
                }
                if (moved) {
                    updateRectFromDrag(event.x, event.y)
                    refreshReadout()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (barTouching) {
                    val id = pressedId
                    barTouching = false
                    pressedId = ID_NONE
                    invalidate()
                    if (id != ID_NONE && hitBar(event.x, event.y) == id) dispatchBar(id)
                    return true
                }
                if (dragging) {
                    dragging = false
                    if (!moved) {
                        // 只是点了一下：保留原选区，不误伤
                        rect.set(savedLeft, savedTop, savedRight, savedBottom)
                    } else {
                        finalizeRect()
                    }
                    refreshReadout()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                barTouching = false
                pressedId = ID_NONE
                if (dragging) {
                    dragging = false
                    rect.set(savedLeft, savedTop, savedRight, savedBottom)
                    refreshReadout()
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --------------------------------------------------------------- 选区状态

    private fun initRect(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        var width = initialSideW.toFloat().coerceIn(1f, w)
        var height = initialSideH.toFloat().coerceIn(1f, h)
        if (width < 1f) width = 1f
        if (height < 1f) height = 1f
        var centerX = if (initialCenterX >= 0) initialCenterX.toFloat() else w / 2f
        var centerY = if (initialCenterY >= 0) initialCenterY.toFloat() else h / 2f
        centerX = centerX.coerceIn(width / 2f, w - width / 2f)
        centerY = centerY.coerceIn(height / 2f, h - height / 2f)
        rect.set(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
        clampRectToBounds(w, h)
    }

    private fun updateRectFromDrag(x: Float, y: Float) {
        rect.set(min(dragStartX, x), min(dragStartY, y), max(dragStartX, x), max(dragStartY, y))
    }

    private fun finalizeRect() {
        val minSide = dp(24f)
        if (rect.width() < minSide) {
            val center = rect.centerX()
            rect.left = center - minSide / 2f
            rect.right = center + minSide / 2f
        }
        if (rect.height() < minSide) {
            val center = rect.centerY()
            rect.top = center - minSide / 2f
            rect.bottom = center + minSide / 2f
        }
        clampRectToBounds(width.toFloat(), height.toFloat())
    }

    private fun clampRectToBounds(maxW: Float, maxH: Float) {
        if (maxW <= 0f || maxH <= 0f) return
        if (rect.width() > maxW) {
            val center = rect.centerX()
            rect.left = center - maxW / 2f
            rect.right = center + maxW / 2f
        }
        if (rect.height() > maxH) {
            val center = rect.centerY()
            rect.top = center - maxH / 2f
            rect.bottom = center + maxH / 2f
        }
        if (rect.left < 0f) {
            val sideW = rect.width()
            rect.left = 0f
            rect.right = sideW
        }
        if (rect.top < 0f) {
            val sideH = rect.height()
            rect.top = 0f
            rect.bottom = sideH
        }
        if (rect.right > maxW) {
            val sideW = rect.width()
            rect.right = maxW
            rect.left = maxW - sideW
        }
        if (rect.bottom > maxH) {
            val sideH = rect.height()
            rect.bottom = maxH
            rect.top = maxH - sideH
        }
        if (rect.width() < 1f) rect.right = rect.left + 1f
        if (rect.height() < 1f) rect.bottom = rect.top + 1f
    }

    private fun refreshReadout() {
        val sideW = rect.width().roundToInt().coerceAtLeast(1)
        val sideH = rect.height().roundToInt().coerceAtLeast(1)
        val centerX = rect.centerX().roundToInt()
        val centerY = rect.centerY().roundToInt()
        readoutText = "$sideW × $sideH px · ($centerX, $centerY)"
    }

    // --------------------------------------------------------------- 底部浮条

    private fun layoutBar(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val margin = dp(16f)
        val barWidth = min(w - margin * 2f, dp(520f))
        val barHeight = dp(88f)
        // 离底 40dp（≈3 倍屏的 120px）：避开手势条/三键导航，又不至于离底太远
        val bottom = h - dp(40f)
        val left = (w - barWidth) / 2f
        barRect.set(left, bottom - barHeight, left + barWidth, bottom)

        val rowTop = barRect.top + dp(10f)
        val rowHeight = dp(30f)
        val cellWidth = dp(56f)
        val inset = dp(12f)
        rectCircle.set(barRect.left + inset, rowTop, barRect.left + inset + cellWidth, rowTop + rowHeight)
        rectRect.set(rectCircle.right, rowTop, rectCircle.right + cellWidth, rowTop + rowHeight)
        rectConfirm.set(barRect.right - inset - cellWidth, rowTop, barRect.right - inset, rowTop + rowHeight)
        rectCancel.set(rectConfirm.left - dp(8f) - cellWidth, rowTop, rectConfirm.left - dp(8f), rowTop + rowHeight)

        cellTextPaint.getFontMetrics(cellFm)
        readoutPaint.getFontMetrics(readoutFm)
        baselineCellRow = centerBaseline(rowTop + rowHeight / 2f, cellFm)
        baselineReadout = centerBaseline(barRect.top + dp(64f), readoutFm)
    }

    private fun drawBar(canvas: Canvas) {
        val bar = barRect
        if (bar.width() <= 0f || bar.height() <= 0f) return

        barFillPaint.color = PICKER_IVORY
        canvas.drawRect(bar, barFillPaint)
        barBorderPaint.color = PICKER_GRAPHITE
        val inset = barBorderPaint.strokeWidth / 2f
        tmpRect.set(bar.left + inset, bar.top + inset, bar.right - inset, bar.bottom - inset)
        canvas.drawRect(tmpRect, barBorderPaint)

        drawCell(canvas, rectCircle, "椭圆", shape == RegionShape.CIRCLE, pressedId == ID_CIRCLE)
        drawCell(canvas, rectRect, "矩形", shape == RegionShape.RECT, pressedId == ID_RECT)
        drawCell(canvas, rectCancel, "取消", false, pressedId == ID_CANCEL)
        drawCell(canvas, rectConfirm, "确认", true, pressedId == ID_CONFIRM)

        readoutPaint.color = PICKER_GRAPHITE
        canvas.drawText(readoutText, bar.left + dp(12f), baselineReadout, readoutPaint)
    }

    private fun drawCell(canvas: Canvas, cell: RectF, text: String, selected: Boolean, pressed: Boolean) {
        if (selected || pressed) {
            cellFillPaint.color = PICKER_GRAPHITE
            canvas.drawRect(cell, cellFillPaint)
            cellTextPaint.color = PICKER_IVORY
        } else {
            cellBorderPaint.color = PICKER_HAIRLINE
            val inset = cellBorderPaint.strokeWidth / 2f
            tmpRect.set(cell.left + inset, cell.top + inset, cell.right - inset, cell.bottom - inset)
            canvas.drawRect(tmpRect, cellBorderPaint)
            cellTextPaint.color = PICKER_GRAPHITE
        }
        val textWidth = cellTextPaint.measureText(text)
        canvas.drawText(text, cell.centerX() - textWidth / 2f, baselineCellRow, cellTextPaint)
    }

    private fun hitBar(x: Float, y: Float): Int = when {
        rectCircle.contains(x, y) -> ID_CIRCLE
        rectRect.contains(x, y) -> ID_RECT
        rectCancel.contains(x, y) -> ID_CANCEL
        rectConfirm.contains(x, y) -> ID_CONFIRM
        else -> ID_NONE
    }

    private fun dispatchBar(id: Int) {
        when (id) {
            ID_CIRCLE -> {
                if (shape != RegionShape.CIRCLE) {
                    shape = RegionShape.CIRCLE
                    invalidate()
                }
            }

            ID_RECT -> {
                if (shape != RegionShape.RECT) {
                    shape = RegionShape.RECT
                    invalidate()
                }
            }

            ID_CANCEL -> listener?.onCancel()

            ID_CONFIRM -> confirm()
        }
    }

    private fun confirm() {
        val sideW = rect.width().roundToInt()
        val sideH = rect.height().roundToInt()
        if (sideW < 1 || sideH < 1) return
        listener?.onConfirm(
            shape,
            rect.centerX().roundToInt(),
            rect.centerY().roundToInt(),
            sideW,
            sideH,
        )
    }

    private fun centerBaseline(centerY: Float, metrics: Paint.FontMetrics): Float =
        centerY - (metrics.ascent + metrics.descent) / 2f

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val ID_NONE = 0
        private const val ID_CIRCLE = 1
        private const val ID_RECT = 2
        private const val ID_CANCEL = 3
        private const val ID_CONFIRM = 4
    }
}
