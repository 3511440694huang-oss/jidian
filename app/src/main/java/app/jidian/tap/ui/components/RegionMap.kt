package app.jidian.tap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.jidian.tap.core.RegionShape
import app.jidian.tap.ui.theme.JdBrass
import app.jidian.tap.ui.theme.JdGraphite
import app.jidian.tap.ui.theme.JdHairline
import app.jidian.tap.ui.theme.JdPanel

/**
 * 落点迷你图：9:19.5 手机屏幕示意（72dp 宽），按屏幕比例缩放当前区域。
 *
 * 画的是「仪器上的小屏幕」：纸白屏、石墨外框、屏幕中心十字；
 * 已框选时用黄铜描出椭圆/矩形并在区域中心加十字。
 */
@Composable
fun RegionMap(
    modifier: Modifier = Modifier,
    configured: Boolean,
    shape: RegionShape,
    regionCx: Int,
    regionCy: Int,
    regionW: Int,
    regionH: Int,
    screenW: Int,
    screenH: Int,
) {
    Canvas(modifier = modifier.aspectRatio(9f / 19.5f)) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val hairline = 1.dp.toPx()
        drawRect(JdPanel, size = size)
        drawRoundRect(
            color = JdGraphite,
            topLeft = Offset(hairline / 2f, hairline / 2f),
            size = Size(width - hairline, height - hairline),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(hairline),
        )

        // 屏幕中心十字
        drawLine(JdHairline, Offset(0f, height / 2f), Offset(width, height / 2f), hairline)
        drawLine(JdHairline, Offset(width / 2f, 0f), Offset(width / 2f, height), hairline)

        if (configured && screenW > 0 && screenH > 0) {
            val cx = (regionCx.toFloat() / screenW).coerceIn(0f, 1f) * width
            val cy = (regionCy.toFloat() / screenH).coerceIn(0f, 1f) * height
            val maxHalfW = maxOf(1.5.dp.toPx(), minOf(cx, width - cx))
            val maxHalfH = maxOf(1.5.dp.toPx(), minOf(cy, height - cy))
            val halfW = (regionW.toFloat() / screenW * width / 2f)
                .coerceIn(1.5.dp.toPx().coerceAtMost(maxHalfW), maxHalfW)
            val halfH = (regionH.toFloat() / screenH * height / 2f)
                .coerceIn(1.5.dp.toPx().coerceAtMost(maxHalfH), maxHalfH)
            val regionTopLeft = Offset(cx - halfW, cy - halfH)
            val regionSize = Size(halfW * 2f, halfH * 2f)
            if (shape == RegionShape.CIRCLE) {
                drawOval(
                    color = JdBrass,
                    topLeft = regionTopLeft,
                    size = regionSize,
                    style = Stroke(1.25.dp.toPx()),
                )
            } else {
                drawRect(
                    color = JdBrass,
                    topLeft = regionTopLeft,
                    size = regionSize,
                    style = Stroke(1.25.dp.toPx()),
                )
            }
            // 区域中心十字（黄铜，短而克制的仪器标记）
            val cross = 4.dp.toPx()
            drawLine(JdBrass, Offset(cx - cross, cy), Offset(cx + cross, cy), 1.dp.toPx(), StrokeCap.Round)
            drawLine(JdBrass, Offset(cx, cy - cross), Offset(cx, cy + cross), 1.dp.toPx(), StrokeCap.Round)
            drawCircle(JdBrass, 1.dp.toPx(), Offset(cx, cy))
        } else {
            // 未框选：标出默认的屏幕中心点。
            drawCircle(JdGraphite, 1.5.dp.toPx(), Offset(width / 2f, height / 2f))
        }
    }
}
