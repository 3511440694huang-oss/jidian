package app.jidian.tap.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 纸白仪器面板（只此一套，不跟随系统深色）。
 * 这里只给 material3 一张最小的映射表；疾点自己的读数样式直接用 [JdType]。
 */
private val JdColorScheme = lightColorScheme(
    primary = JdGraphite,
    onPrimary = JdIvory,
    primaryContainer = JdIvory,
    onPrimaryContainer = JdGraphite,
    secondary = JdSignal,
    onSecondary = JdPanel,
    tertiary = JdBrass,
    onTertiary = JdPanel,
    background = JdPanel,
    onBackground = JdGraphite,
    surface = JdPanel,
    onSurface = JdGraphite,
    surfaceVariant = JdIvory,
    onSurfaceVariant = JdSteel,
    outline = JdHairline,
    outlineVariant = JdHairline,
    error = JdBrass,
    onError = JdPanel,
)

private val JdMaterialTypography = Typography(
    displayLarge = JdType.display,
    headlineSmall = JdType.title,
    titleMedium = JdType.section,
    bodyMedium = JdType.body,
    bodySmall = JdType.note,
    labelLarge = JdType.buttonLabelSmall,
    labelMedium = JdType.micro,
    labelSmall = JdType.micro,
)

@Composable
fun JidianTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JdColorScheme,
        typography = JdMaterialTypography,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides JdGraphite,
            content = content,
        )
    }
}
