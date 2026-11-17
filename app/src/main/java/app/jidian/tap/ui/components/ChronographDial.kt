package app.jidian.tap.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import app.jidian.tap.ui.theme.JdGraphite
import app.jidian.tap.ui.theme.JdGraphiteSoft
import app.jidian.tap.ui.theme.JdIvory
import app.jidian.tap.ui.theme.JdSignal
import app.jidian.tap.ui.theme.JdSteel
import app.jidian.tap.ui.theme.JdType
import java.util.Locale

/**
 * 248dp 计时码表盘（DESIGN.md §6）。
 *
 * 表盘主体一律 Canvas 自绘：表圈、60 刻度、红秒针、运行红弧；
 * 中央读数用 Text 叠层（字体可控，比 drawText 稳）。
 *
 * 动效纪律：首次进入只对一次表（-120° → 12 点，600ms）；运行中秒针 4s/圈、红弧 8s/圈；
 * 停表即定格；系统关闭动画时全部静止，只有数字变。
 */
@Composable
fun ChronographDial(
    modifier: Modifier = Modifier,
    running: Boolean,
    liveCps: Float,
    targetCps: Float,
    reduceMotion: Boolean,
) {
    // 秒针角度：初值停在 -120°，首进对表一次。
    var handAngle by remember { mutableFloatStateOf(-120f) }
    var arcAngle by remember { mutableFloatStateOf(0f) }
    var aligned by remember { mutableStateOf(false) }

    LaunchedEffect(running, reduceMotion) {
        if (reduceMotion) {
            // 关动画：直接静止在 12 点，不做入场，不做扫描。
            handAngle = 0f
            return@LaunchedEffect
        }
        if (!aligned) {
            animate(
                initialValue = handAngle,
                targetValue = 0f,
                animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            ) { value, _ ->
                handAngle = value
            }
            aligned = true
        }
        if (!running) return@LaunchedEffect

        // 运行中：按真实帧时间推进，帧率波动不影响速度。
        var previousNanos = 0L
        while (true) {
            val nanos = withFrameNanos { it }
            if (previousNanos != 0L) {
                val deltaSeconds = (nanos - previousNanos) / 1_000_000_000f
                handAngle = (handAngle + deltaSeconds * 90f) % 360f
                arcAngle = (arcAngle + deltaSeconds * 45f) % 360f
            }
            previousNanos = nanos
        }
    }

    Box(
        modifier = modifier.size(248.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            if (radius <= 0f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)

            // 表盘面：象牙实心圆；外圈：石墨 2dp 圆环。
            drawCircle(JdIvory, radius - 1.dp.toPx(), center)
            drawCircle(
                color = JdGraphite,
                radius = radius - 1.dp.toPx(),
                center = center,
                style = Stroke(2.dp.toPx()),
            )

            // 60 根刻度：第 5 根为主刻度（石墨 1.5dp × 8dp），其余次级（Graphite Soft 55%，1dp × 4dp）。
            val tickOuter = radius - 5.dp.toPx()
            for (index in 0 until 60) {
                val major = index % 5 == 0
                rotate(degrees = index * 6f, pivot = center) {
                    drawLine(
                        color = if (major) JdGraphite else JdGraphiteSoft.copy(alpha = 0.55f),
                        start = Offset(center.x, center.y - tickOuter),
                        end = Offset(
                            center.x,
                            center.y - tickOuter + if (major) 8.dp.toPx() else 4.dp.toPx(),
                        ),
                        strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }

            // 运行中的信号红细弧（alpha 0.25），8s/圈。
            if (running) {
                val arcRadius = radius - 13.dp.toPx()
                drawArc(
                    color = JdSignal.copy(alpha = 0.25f),
                    startAngle = arcAngle - 90f,
                    sweepAngle = 50f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // 秒针：信号红 1.6dp 宽、长 96dp，尾部配重 14dp，中心 5dp 石墨圆帽。
            rotate(degrees = handAngle, pivot = center) {
                drawLine(
                    color = JdSignal,
                    start = Offset(center.x, center.y),
                    end = Offset(center.x, center.y - 96.dp.toPx()),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = JdSignal,
                    start = Offset(center.x, center.y + 2.dp.toPx()),
                    end = Offset(center.x, center.y + 14.dp.toPx()),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(JdGraphite, 2.5.dp.toPx(), center)
        }

        // 中央读数：Mono 44sp 石墨；下方 10sp 钢灰「次 / 秒」。
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (running) formatCps(liveCps) else "—",
                style = JdType.dialReadout,
                color = JdGraphite,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "次 / 秒",
                style = JdType.dialCaption,
                color = JdSteel,
            )
        }

        // 表盘底部：10sp Mono 钢灰「目标 N」。
        Text(
            text = "目标 " + formatCps(targetCps),
            style = JdType.dialCaption,
            color = JdSteel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
        )
    }
}

private fun formatCps(value: Float): String {
    val safe = if (value.isFinite() && value > 0f) value else 0f
    return String.format(Locale.US, "%.1f", safe)
}
