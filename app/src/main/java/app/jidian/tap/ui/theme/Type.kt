package app.jidian.tap.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.jidian.tap.R

/**
 * 拉丁字母与数字：Barlow；数据与读数：IBM Plex Mono（DESIGN.md §3）。
 * 中文交给系统回退（Noto Sans CJK），不额外内置字体。
 */
val BarlowFamily = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
    Font(R.font.barlow_extrabold, FontWeight.ExtraBold),
)

val PlexMonoFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

/**
 * 疾点的字阶。material3 的 Typography 字段不够用（Mono XL/L/M/S 与表盘读数没有对应位置），
 * 所以这里是独立对象，Theme 里只在 Typography 中映射常用的几个，其余直接引用 [JdType]。
 *
 * 单位均为 sp：显示 46 / 标题 22 / 小节 13 / 正文 14 / 注 12 / 微 11；
 * 数据 Mono XL 28 / L 16 / M 13 / S 11。字距默认 0，仅 Mono 小字 0.02em。
 */
object JdType {

    // —— Barlow：界面文字 ——
    val display = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 46.sp,
        lineHeight = 50.sp,
    )
    val title = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    )
    val section = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val body = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )
    val note = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    val micro = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

    // —— IBM Plex Mono：读数 ——
    val monoXL = TextStyle(
        fontFamily = PlexMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    )
    val monoL = TextStyle(
        fontFamily = PlexMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    )
    val monoM = TextStyle(
        fontFamily = PlexMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val monoS = TextStyle(
        fontFamily = PlexMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    )

    // —— 表盘专用 ——
    val dialReadout = TextStyle(
        fontFamily = PlexMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        lineHeight = 48.sp,
    )
    val dialCaption = TextStyle(
        fontFamily = PlexMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.2.sp,
    )

    // —— 按钮 ——
    val buttonLabel = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    )
    val buttonLabelSmall = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )
    val controlLabel = TextStyle(
        fontFamily = BarlowFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )
}
