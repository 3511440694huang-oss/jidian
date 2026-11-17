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

/**
 * 展开态迷你面板（240×96dp，独立 overlay 窗口）：象牙底 + 石墨 1dp 描边。
 *
 * 第一行「间隔」Mono 读数 + [−][+]（步进到 20/50/100/200/500/1000 的相邻档，
 * 超出预设范围退化到 ±10ms）；第二行「模式」当前模式中文名 + [切换]
 * （单击 → 双击 → 长按 → 滑动 循环）；底部「框选落点」「打开疾点」两个文字按钮。
 *
 * 配置读写全部走 [ConfigStore]（[JidianApp.from]），视图自身订阅配置变化，
 * 面板上的操作与主界面读写的是同一份数据。
 */
class OverlayPanelView(context: Context) : View(context) {

    interface Listener {
        /** 点「框选落点」：宿主收起面板并进入框选态。 */
        fun onPickRegion()

        /** 点「打开疾点」：宿主收起面板并打开主界面。 */
        fun onOpenApp()
    }

    var listener: Listener? = null

    private var shownInterval: Long = ClickConfig.Default.intervalMs
    private var shownMode: TapMode = ClickConfig.Default.mode

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
        textSize = sp(12f)
        color = PANEL_STEEL
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = monoTypeface
        textSize = sp(16f)
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
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = barlowTypeface
        textSize = sp(14f)
        color = PANEL_GRAPHITE
    }

    private val labelFm = Paint.FontMetrics()
    private val readoutFm = Paint.FontMetrics()
    private val modeFm = Paint.FontMetrics()
    private val cellFm = Paint.FontMetrics()
    private val buttonFm = Paint.FontMetrics()

    private val rectMinus = RectF()
    private val rectPlus = RectF()
    private val rectMode = RectF()
    private val rectPick = RectF()
    private val rectOpen = RectF()
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
    private var baselineBottom = 0f

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        padX = dp(12f)
        valueX = padX + dp(40f)
        hairlineY1 = dp(38f)
        hairlineY2 = dp(66f)

        rectMinus.set(dp(156f), dp(8f), dp(190f), dp(34f))
        rectPlus.set(dp(194f), dp(8f), dp(228f), dp(34f))
        rectMode.set(dp(158f), dp(40f), dp(228f), dp(64f))
        rectPick.set(dp(12f), dp(70f), dp(112f), dp(90f))
        rectOpen.set(dp(120f), dp(70f), dp(228f), dp(90f))

        labelPaint.getFontMetrics(labelFm)
        readoutPaint.getFontMetrics(readoutFm)
        modePaint.getFontMetrics(modeFm)
        cellTextPaint.getFontMetrics(cellFm)
        buttonTextPaint.getFontMetrics(buttonFm)

        baselineRow1Label = centerBaseline(dp(21f), labelFm)
        baselineRow1Readout = centerBaseline(dp(21f), readoutFm)
        baselineRow1Button = centerBaseline(dp(21f), buttonFm)
        baselineRow2Label = centerBaseline(dp(52f), labelFm)
        baselineRow2Mode = centerBaseline(dp(52f), modeFm)
        baselineRow2Button = centerBaseline(dp(52f), cellFm)
        baselineBottom = centerBaseline(dp(80f), cellFm)
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
        drawButton(canvas, rectMinus, "−", false, pressedId == ID_MINUS, baselineRow1Button, buttonTextPaint)
        drawButton(canvas, rectPlus, "+", false, pressedId == ID_PLUS, baselineRow1Button, buttonTextPaint)

        // 第二行：模式
        canvas.drawText("模式", padX, baselineRow2Label, labelPaint)
        modePaint.color = PANEL_GRAPHITE
        canvas.drawText(modeName(shownMode), valueX, baselineRow2Mode, modePaint)
        drawButton(canvas, rectMode, "切换", false, pressedId == ID_MODE, baselineRow2Button, cellTextPaint)

        // 底部：两个文字按钮
        drawTextButton(canvas, rectPick, "框选落点", pressedId == ID_PICK)
        drawTextButton(canvas, rectOpen, "打开疾点", pressedId == ID_OPEN)
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
        }
    }

    private fun hitTest(x: Float, y: Float): Int = when {
        rectMinus.contains(x, y) -> ID_MINUS
        rectPlus.contains(x, y) -> ID_PLUS
        rectMode.contains(x, y) -> ID_MODE
        rectPick.contains(x, y) -> ID_PICK
        rectOpen.contains(x, y) -> ID_OPEN
        else -> ID_NONE
    }

    private fun stepInterval(direction: Int) {
        val current = shownInterval
        val next = if (direction > 0) {
            ClickConfig.INTERVAL_PRESETS.firstOrNull { it > current } ?: (current + 10L)
        } else {
            ClickConfig.INTERVAL_PRESETS.lastOrNull { it < current } ?: (current - 10L)
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
        filled: Boolean,
        pressed: Boolean,
        baseline: Float,
        textPaint: Paint,
    ) {
        if (filled || pressed) {
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

    private fun drawTextButton(canvas: Canvas, rect: RectF, text: String, pressed: Boolean) {
        if (pressed) {
            fillPaint.color = PANEL_GRAPHITE
            canvas.drawRect(rect, fillPaint)
            cellTextPaint.color = PANEL_IVORY
        } else {
            cellTextPaint.color = PANEL_GRAPHITE
        }
        val textWidth = cellTextPaint.measureText(text)
        canvas.drawText(text, rect.centerX() - textWidth / 2f, baselineBottom, cellTextPaint)
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

        private val MODE_ORDER = listOf(
            TapMode.SINGLE,
            TapMode.DOUBLE,
            TapMode.LONG_PRESS,
            TapMode.SWIPE,
        )
    }
}
