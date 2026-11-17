package app.jidian.tap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jidian.tap.ui.theme.JdBrass
import app.jidian.tap.ui.theme.JdGraphite
import app.jidian.tap.ui.theme.JdGraphiteSoft
import app.jidian.tap.ui.theme.JdHairline
import app.jidian.tap.ui.theme.JdIvory
import app.jidian.tap.ui.theme.JdPanel
import app.jidian.tap.ui.theme.JdSignal
import app.jidian.tap.ui.theme.JdSteel
import app.jidian.tap.ui.theme.JdType
import kotlin.math.roundToInt

// —— 结构：墨线 / 小节标题 / 间距 ——

/** 1dp 通栏墨线。 */
@Composable
fun JdRule(modifier: Modifier = Modifier, color: Color = JdHairline) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** 小节之间的通栏分隔：1dp 墨线，上下各留 26dp（DESIGN.md §4）。 */
@Composable
fun JdSectionGap() {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(26.dp))
        JdRule()
        Spacer(Modifier.height(26.dp))
    }
}

/** 小节标题：13sp SemiBold 石墨 + 左侧 14dp×2dp 信号红刻度线（表盘刻度母题），右侧可放 11sp 钢灰提示。 */
@Composable
fun JdSectionHeader(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(14.dp)
                .background(JdSignal),
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = JdType.section, color = JdGraphite)
        Spacer(Modifier.weight(1f))
        if (hint != null) {
            Text(
                text = hint,
                style = JdType.micro,
                color = JdSteel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 说明文字：12sp 钢灰。 */
@Composable
fun JdNote(text: String, modifier: Modifier = Modifier, color: Color = JdSteel) {
    Text(text = text, style = JdType.note, color = color, modifier = modifier)
}

/** 控件名 + 右侧 Mono 读数。 */
@Composable
fun JdControlLabelRow(
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = JdType.body, color = JdGraphite)
        Spacer(Modifier.weight(1f))
        Text(valueText, style = JdType.monoM, color = JdGraphite, maxLines = 1)
    }
}

// —— 自绘控件 ——

/**
 * 自绘滑杆：轨 1dp Hairline，激活段 2dp 石墨，拇指 18dp 象牙底石墨圈。
 *
 * 不用 material Slider 是为了不破仪表风格。按下即定位（仪器滑杆的手感），
 * 拖动用 awaitEachGesture + drag；坐标一律按 size.width 换算。
 * pointerInput 的 key 只放原始数字，避免父层每帧重组时打断拖动。
 */
@Composable
fun JdSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: () -> Unit = {},
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val currentOnChange = rememberUpdatedState(onValueChange)
    val currentOnFinish = rememberUpdatedState(onValueChangeFinished)
    val currentSteps = rememberUpdatedState(steps)
    val currentSpan = rememberUpdatedState(span)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(valueRange.start, valueRange.endInclusive) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture

                    fun emit(positionX: Float) {
                        val raw = valueRange.start + (positionX / width).coerceIn(0f, 1f) * currentSpan.value
                        val stepCount = currentSteps.value
                        val snapped = if (stepCount > 0) {
                            val stepSize = currentSpan.value / (stepCount + 1)
                            val index = ((raw - valueRange.start) / stepSize).roundToInt().coerceIn(0, stepCount + 1)
                            valueRange.start + index * stepSize
                        } else {
                            raw
                        }
                        currentOnChange.value(snapped.coerceIn(valueRange.start, valueRange.endInclusive))
                    }

                    emit(down.position.x)
                    drag(down.id) { change ->
                        change.consume()
                        emit(change.position.x)
                    }
                    currentOnFinish.value()
                }
            },
    ) {
        val centerY = size.height / 2f
        val thumbRadius = 9.dp.toPx()
        val inset = thumbRadius
        val startX = inset
        val endX = size.width - inset
        if (endX <= startX) return@Canvas

        drawLine(
            color = JdHairline,
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = 1.dp.toPx(),
        )
        val thumbX = startX + (endX - startX) * fraction
        drawLine(
            color = JdGraphite,
            start = Offset(startX, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(JdIvory, thumbRadius, Offset(thumbX, centerY))
        drawCircle(
            color = JdGraphite,
            radius = thumbRadius,
            center = Offset(thumbX, centerY),
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

/** 四段/多段分段控件：44dp，选中石墨底象牙字，180ms 颜色过渡。 */
@Composable
fun JdSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(1.dp, JdHairline),
    ) {
        options.forEachIndexed { index, label ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(JdHairline),
                )
            }
            val selected = index == selectedIndex
            val background by animateColorAsState(
                targetValue = if (selected) JdGraphite else Color.Transparent,
                animationSpec = tween(180),
                label = "segmentBackground",
            )
            val foreground by animateColorAsState(
                targetValue = if (selected) JdIvory else JdGraphiteSoft,
                animationSpec = tween(180),
                label = "segmentForeground",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(background)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = JdType.controlLabel, color = foreground, maxLines = 1)
            }
        }
    }
}

/** 预设方块：Mono M；选中石墨实底象牙字。 */
@Composable
fun JdPresetBlock(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (selected) JdGraphite else Color.Transparent,
        animationSpec = tween(180),
        label = "presetBackground",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) JdIvory else JdGraphiteSoft,
        animationSpec = tween(180),
        label = "presetForeground",
    )
    Box(
        modifier = modifier
            .height(40.dp)
            .background(background)
            .border(1.dp, if (selected) JdGraphite else JdHairline)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = JdType.monoM, color = foreground, maxLines = 1)
    }
}

/** 自绘开关视觉：36×20dp 药丸 + 12dp 象牙拇指（点击由所在行处理）。 */
@Composable
fun JdSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val track by animateColorAsState(
        targetValue = if (checked) JdGraphite else JdHairline,
        animationSpec = tween(180),
        label = "switchTrack",
    )
    val thumbX by animateDpAsState(
        targetValue = if (checked) 20.dp else 4.dp,
        animationSpec = tween(180),
        label = "switchThumb",
    )
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 20.dp)
            .background(track, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbX)
                .size(12.dp)
                .background(JdIvory, CircleShape),
        )
    }
}

/** 开关行：整行 52dp 可点，语义是 Switch。 */
@Composable
fun JdSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = JdType.body, color = JdGraphite)
        Spacer(Modifier.weight(1f))
        JdSwitch(checked = checked)
    }
}

/** 步进行：文字 + [−] 读数 [+]。 */
@Composable
fun JdStepperRow(
    label: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = JdType.body, color = JdGraphite)
        Spacer(Modifier.weight(1f))
        JdStepButton(symbol = "−", onClick = onDecrease)
        Box(
            modifier = Modifier.widthIn(min = 64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(valueText, style = JdType.monoM, color = JdGraphite, maxLines = 1)
        }
        JdStepButton(symbol = "+", onClick = onIncrease)
    }
}

@Composable
private fun JdStepButton(symbol: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(120),
        label = "stepPress",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(1.dp, JdHairline)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = JdType.monoL, color = JdGraphite)
    }
}

/** 系统跳转行：名称 + 右侧状态（黄铜=未就绪）+ 细则箭头。 */
@Composable
fun JdStatusLinkRow(
    label: String,
    statusText: String,
    warn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val statusColor by animateColorAsState(
        targetValue = if (warn) JdBrass else JdSteel,
        animationSpec = tween(180),
        label = "statusColor",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = JdType.body, color = JdGraphite, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(
            text = statusText,
            style = JdType.note,
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        JdChevron(up = false, color = JdSteel)
    }
}

/** 小箭头（自己画，避免依赖图标库里不一定存在的图标）。 */
@Composable
fun JdChevron(
    up: Boolean,
    color: Color = JdSteel,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(10.dp)) {
        val stroke = 1.4.dp.toPx()
        val w = size.width
        val h = size.height
        if (up) {
            drawLine(color, Offset(w * 0.18f, h * 0.64f), Offset(w * 0.5f, h * 0.36f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.36f), Offset(w * 0.82f, h * 0.64f), stroke, StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.36f, h * 0.18f), Offset(w * 0.64f, h * 0.5f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.64f, h * 0.5f), Offset(w * 0.36f, h * 0.82f), stroke, StrokeCap.Round)
        }
    }
}

// —— 按钮 ——

/** 主按钮：全宽 64dp，待命石墨底象牙字 / 运行信号红底白字，0.98 缩放按压反馈。 */
@Composable
fun JdPrimaryActionButton(
    text: String,
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(120),
        label = "primaryPress",
    )
    val background by animateColorAsState(
        targetValue = if (running) JdSignal else JdGraphite,
        animationSpec = tween(180),
        label = "primaryBackground",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = JdType.buttonLabel, color = JdPanel)
    }
}

/** 次级实底按钮 44dp：石墨底象牙字（落点框选的主操作）。 */
@Composable
fun JdSolidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(120),
        label = "solidPress",
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(JdGraphite)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = JdType.buttonLabelSmall, color = JdPanel, maxLines = 1)
    }
}

/** 描边按钮 44dp：1dp 石墨描边、透明底（次操作）。 */
@Composable
fun JdOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(120),
        label = "outlinePress",
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(1.dp, JdGraphite)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = JdType.buttonLabelSmall, color = JdGraphite, maxLines = 1)
    }
}
