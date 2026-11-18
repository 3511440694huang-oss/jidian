package app.jidian.tap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import app.jidian.tap.JidianApp
import app.jidian.tap.R
import app.jidian.tap.core.ClickConfig
import app.jidian.tap.core.ConfigStore
import app.jidian.tap.core.JidianLog
import app.jidian.tap.core.TapMode

private val PANEL_IVORY = Color.parseColor("#EDEAE3")
private val PANEL_GRAPHITE = Color.parseColor("#14171A")
private val PANEL_STEEL = Color.parseColor("#6E777D")
private val PANEL_HAIRLINE = Color.parseColor("#D3D2CB")
private val PANEL_SIGNAL = Color.parseColor("#C6372A")

/**
 * 展开态迷你面板（212×132dp，独立 overlay 窗口）：象牙底 + 石墨 1dp 描边。
 *
 * 四行，全部是「主界面同一份配置」的快捷操作：
 * 1. 间隔 Mono 读数 + [−][+]（按 [ClickConfig.INTERVAL_LADDER] 步进，1ms 起）；
 * 2. 模式中文名 + [切换]（单击 → 双击 → 长按 → 滑动 循环）；
 * 3. 「框选落点」「打开疾点」两个文字按钮；
 * 4. **通栏启停键**：运行中为信号红「停止」，待命为石墨「启动」。
 *    这是停止连点的主要入口——面板打开时圆盘被 scrim 盖住，按钮必须自己能用。
 */
class OverlayPanelView(context: Context) : View(context) {

    interface Listener {
        /** 点「框选落点」：宿主收起面板并进入框选态。 */
        fun onPickRegion()

        /** 点「打开疾点」：宿主收起面板并打开主界面。 */
        fun onOpenApp()

        /** 点通栏启停键：宿主切换引擎启停。 */
        fun onToggle()
    }

    var listener: Listener? = null

    private var shownInterval: Long = ClickConfig.Default.intervalMs
    private var shownMode: TapMode = ClickConfig.Default.mode
    private var engineRunning = false

    private var store: ConfigStore? = null

    private val configListener: (ClickConfig) -> Unit = { config -> onConfigChanged(config) }

    private var pressedId = ID_NONE

    private val monoTypeface: Typeface =
        runCatching { resources.getFont(R.font.ibm_plex_mono_medium) }.getOrNull()
            ?: Typeface.MONOSPACE

    private val barlowTypeface: Typeface =
        runCatching { resources.getFont(R.font.barlow_medium) }.getOrNull()
            ?: Typeface.DEFAULT

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = PANEL_GRAPHITE
    }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = PANEL_HAIRLINE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = barlowTypeface
        textSize = sp(11f)
        color = PANEL_STEEL
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = monoTypeface
        textSize = sp(15f)
        color = PANEL_GRAPHITE
    }
    private val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = barlowTypeface
        textSize = sp(14f)
        color = PANEL_GRAPHITE
    }
    private val cellTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = barlowTypeface
        textSize = sp(12f)
        color = PANEL_GRAPHITE
    }
    private val toggleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = barlowTypeface
        textSize = sp(14f)
        color = PANEL_IVORY
    }

    private val labelFm = Paint.FontMetrics()
    private val readoutFm = Paint.FontMetrics()
    private val modeFm = Paint.FontMetrics()
    private val cellFm = Paint.FontMetrics()
    private val toggleFm = Paint.FontMetrics()

    private val rectMinus = RectF()
    private val rectPlus = RectF()
    private val rectMode = RectF()
    private val rectPick = RectF()
    private val rectOpen = RectF()
    private val rectToggle = RectF()
    private val tmpRect = RectF()

    private var padX = 0f
    private var valueX = 0f
    private var hairlineY1 = 0f
    private var hairlineY2 = 0f
    private var baselineRow1Label = 0f
    private var baselineRow1Readout = 0f
    private var baselineRow1Button = 0f
    private var baselineRow2Label = 0f
    private var baselineRow2Mode = 0f
    private var baselineRow2Button = 0f
    private var baselineRow3 = 0f
    private var baselineToggle = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val configStore = runCatching { JidianApp.from(context).config }.getOrNull() ?: return
        store = configStore
        applyConfig(configStore.snapshot())
        runCatching { configStore.addListener(configListener) }
    }

    override fun onDetachedFromWindow() {
        val configStore = store
        store = null
        if (configStore != null) runCatching { configStore.removeListener(configListener) }
        pressedId = ID_NONE
        super.onDetachedFromWindow()
    }

    /** 宿主采样入口：运行状态变化只影响启停键的文案/颜色。 */
    fun setRunning(running: Boolean) {
        if (running == engineRunning) return
        engineRunning = running
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        padX = dp(12f)
        valueX = padX + dp(36f)
        hairlineY1 = dp(37f)
        hairlineY2 = dp(69f)

        val right = w.toFloat() - padX
        rectMinus.set(right - dp(56f), dp(6f), right - dp(28f), dp(32f))
        rectPlus.set(right - dp(26f), dp(6f), right + dp(2f), dp(32f))
        rectMode.set(right - dp(56f), dp(39f), right, dp(65f))
        rectPick.set(padX, dp(72f), padX + dp(90f), dp(96f))
        rectOpen.set(padX + dp(96f), dp(72f), right, dp(96f))
        rectToggle.set(padX, dp(102f), right, dp(126f))

        labelPaint.getFontMetrics(labelFm)
        readoutPaint.getFontMetrics(readoutFm)
        modePaint.getFontMetrics(modeFm)
        cellTextPaint.getFontMetrics(cellFm)
        toggleTextPaint.getFontMetrics(toggleFm)

        baselineRow1Label = centerBaseline(dp(21f), labelFm)
        baselineRow1Readout = centerBaseline(dp(21f), readoutFm)
        baselineRow1Button = centerBaseline(dp(21f), cellFm)
        baselineRow2Label = centerBaseline(dp(53f), labelFm)
        baselineRow2Mode = centerBaseline(dp(53f), modeFm)
        baselineRow2Button = centerBaseline(dp(53f), cellFm)
        baselineRow3 = centerBaseline(dp(85f), cellFm)
        baselineToggle = centerBaseline((rectToggle.top + rectToggle.bottom) / 2f, toggleFm)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 象牙面板 + 石墨 1dp 描边（内缩半像素，避免被裁）
        fillPaint.color = PANEL_IVORY
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        borderPaint.color = PANEL_GRAPHITE
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRect(inset, inset, w - inset, h - inset, borderPaint)

        // 两条 1dp 墨线分隔
        canvas.drawLine(padX, hairlineY1, w - padX, hairlineY1, hairlinePaint)
        canvas.drawLine(padX, hairlineY2, w - padX, hairlineY2, hairlinePaint)

        // 第一行：间隔
        labelPaint.color = PANEL_STEEL
        canvas.drawText("间隔", padX, baselineRow1Label, labelPaint)
        readoutPaint.color = PANEL_GRAPHITE
        canvas.drawText("${shownInterval} ms", valueX, baselineRow1Readout, readoutPaint)
        drawButton(canvas, rectMinus, "−", pressedId == ID_MINUS, baselineRow1Button, cellTextPaint)
        drawButton(canvas, rectPlus, "+", pressedId == ID_PLUS, baselineRow1Button, cellTextPaint)

        // 第二行：模式
        canvas.drawText("模式", padX, baselineRow2Label, labelPaint)
        modePaint.color = PANEL_GRAPHITE
        canvas.drawText(modeName(shownMode), valueX, baselineRow2Mode, modePaint)
        drawButton(canvas, rectMode, "切换", pressedId == ID_MODE, baselineRow2Button, cellTextPaint)

        // 第三行：两个文字按钮
        drawTextButton(canvas, rectPick, "框选落点", pressedId == ID_PICK, baselineRow3)
        drawTextButton(canvas, rectOpen, "打开疾点", pressedId == ID_OPEN, baselineRow3)

        // 第四行：通栏启停键
        drawToggleButton(canvas)
    }

    private fun drawToggleButton(canvas: Canvas) {
        fillPaint.color = if (engineRunning) PANEL_SIGNAL else PANEL_GRAPHITE
        canvas.drawRect(rectToggle, fillPaint)
        toggleTextPaint.color = PANEL_IVORY
        val text = if (engineRunning) "停止连点" else "启动连点"
        val textWidth = toggleTextPaint.measureText(text)
        canvas.drawText(
            text,
            rectToggle.centerX() - textWidth / 2f,
            baselineToggle,
            toggleTextPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedId = hitTest(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val id = hitTest(event.x, event.y)
                if (id != pressedId) {
                    pressedId = id
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val id = pressedId
                pressedId = ID_NONE
                invalidate()
                if (id != ID_NONE && hitTest(event.x, event.y) == id) dispatch(id)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedId = ID_NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dispatch(id: Int) {
        when (id) {
            ID_MINUS -> stepInterval(-1)
            ID_PLUS -> stepInterval(1)
            ID_MODE -> cycleMode()
            ID_PICK -> listener?.onPickRegion()
            ID_OPEN -> listener?.onOpenApp()
            ID_TOGGLE -> listener?.onToggle()
        }
    }

    private fun hitTest(x: Float, y: Float): Int = when {
        rectMinus.contains(x, y) -> ID_MINUS
        rectPlus.contains(x, y) -> ID_PLUS
        rectMode.contains(x, y) -> ID_MODE
        rectPick.contains(x, y) -> ID_PICK
        rectOpen.contains(x, y) -> ID_OPEN
        rectToggle.contains(x, y) -> ID_TOGGLE
        else -> ID_NONE
    }

    /** 按阶梯步进：先找当前值所在的档位，再跳到相邻档；不在档位上就取最近档。 */
    private fun stepInterval(direction: Int) {
        val ladder = ClickConfig.INTERVAL_LADDER
        val current = shownInterval
        val next = if (direction > 0) {
            ladder.firstOrNull { it > current }
                ?: (current + 500L)
        } else {
            ladder.lastOrNull { it < current }
                ?: (current - 1L)
        }
        val value = next.coerceIn(ClickConfig.MIN_INTERVAL_MS, ClickConfig.MAX_INTERVAL_MS)
        if (value == current) return
        updateConfig { it.copy(intervalMs = value) }
    }

    private fun cycleMode() {
        val index = MODE_ORDER.indexOf(shownMode)
        val next = MODE_ORDER[(index + 1) % MODE_ORDER.size]
        updateConfig { it.copy(mode = next) }
    }

    private fun updateConfig(transform: (ClickConfig) -> ClickConfig) {
        try {
            JidianApp.from(context).config.update(transform)
        } catch (t: Throwable) {
            JidianLog.error(TAG, "更新配置失败：${t.message}")
        }
    }

    private fun onConfigChanged(config: ClickConfig) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { applyConfig(config) }
            return
        }
        applyConfig(config)
    }

    private fun applyConfig(config: ClickConfig) {
        if (config.intervalMs == shownInterval && config.mode == shownMode) return
        shownInterval = config.intervalMs
        shownMode = config.mode
        invalidate()
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        text: String,
        pressed: Boolean,
        baseline: Float,
        textPaint: Paint,
    ) {
        if (pressed) {
            fillPaint.color = PANEL_GRAPHITE
            canvas.drawRect(rect, fillPaint)
            textPaint.color = PANEL_IVORY
        } else {
            borderPaint.color = PANEL_HAIRLINE
            val inset = borderPaint.strokeWidth / 2f
            tmpRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            canvas.drawRect(tmpRect, borderPaint)
            textPaint.color = PANEL_GRAPHITE
        }
        val textWidth = textPaint.measureText(text)
        canvas.drawText(text, rect.centerX() - textWidth / 2f, baseline, textPaint)
    }

    private fun drawTextButton(canvas: Canvas, rect: RectF, text: String, pressed: Boolean, baseline: Float) {
        if (pressed) {
            fillPaint.color = PANEL_GRAPHITE
            canvas.drawRect(rect, fillPaint)
            cellTextPaint.color = PANEL_IVORY
        } else {
            cellTextPaint.color = PANEL_GRAPHITE
        }
        val textWidth = cellTextPaint.measureText(text)
        canvas.drawText(text, rect.centerX() - textWidth / 2f, baseline, cellTextPaint)
    }

    private fun modeName(mode: TapMode): String = when (mode) {
        TapMode.SINGLE -> "单击"
        TapMode.DOUBLE -> "双击"
        TapMode.LONG_PRESS -> "长按"
        TapMode.SWIPE -> "滑动"
    }

    private fun centerBaseline(centerY: Float, metrics: Paint.FontMetrics): Float =
        centerY - (metrics.ascent + metrics.descent) / 2f

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val TAG = "OverlayPanel"
        private const val ID_NONE = 0
        private const val ID_MINUS = 1
        private const val ID_PLUS = 2
        private const val ID_MODE = 3
        private const val ID_PICK = 4
        private const val ID_OPEN = 5
        private const val ID_TOGGLE = 6

        /** 面板尺寸（dp）：宿主按这个值挂窗口。 */
        const val PANEL_WIDTH_DP = 212f
        const val PANEL_HEIGHT_DP = 132f

        private val MODE_ORDER = listOf(
            TapMode.SINGLE,
            TapMode.DOUBLE,
            TapMode.LONG_PRESS,
            TapMode.SWIPE,
        )
    }
}
