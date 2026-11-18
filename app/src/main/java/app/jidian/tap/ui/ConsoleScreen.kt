package app.jidian.tap.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jidian.tap.BuildConfig
import app.jidian.tap.JidianApp
import app.jidian.tap.core.ClickConfig
import app.jidian.tap.core.EngineStatus
import app.jidian.tap.core.JidianLog
import app.jidian.tap.core.RegionShape
import app.jidian.tap.core.StopReason
import app.jidian.tap.core.TapMode
import app.jidian.tap.service.FloatService
import app.jidian.tap.service.TapAccessibilityService
import app.jidian.tap.ui.components.ChronographDial
import app.jidian.tap.ui.components.JdChevron
import app.jidian.tap.ui.components.JdControlLabelRow
import app.jidian.tap.ui.components.JdNote
import app.jidian.tap.ui.components.JdOutlineButton
import app.jidian.tap.ui.components.JdPresetBlock
import app.jidian.tap.ui.components.JdPrimaryActionButton
import app.jidian.tap.ui.components.JdRule
import app.jidian.tap.ui.components.JdSectionGap
import app.jidian.tap.ui.components.JdSectionHeader
import app.jidian.tap.ui.components.JdSegmented
import app.jidian.tap.ui.components.JdSlider
import app.jidian.tap.ui.components.JdSolidButton
import app.jidian.tap.ui.components.JdStatusLinkRow
import app.jidian.tap.ui.components.JdStepperRow
import app.jidian.tap.ui.components.JdSwitchRow
import app.jidian.tap.ui.components.RegionMap
import app.jidian.tap.ui.theme.JdBrass
import app.jidian.tap.ui.theme.JdGraphite
import app.jidian.tap.ui.theme.JdGraphiteSoft
import app.jidian.tap.ui.theme.JdHairline
import app.jidian.tap.ui.theme.JdIvory
import app.jidian.tap.ui.theme.JdPanel
import app.jidian.tap.ui.theme.JdSignal
import app.jidian.tap.ui.theme.JdSteel
import app.jidian.tap.ui.theme.JdType
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 疾点控制台：一块竖着读的钢制计时码表仪器（DESIGN.md §5）。
 *
 * 自上而下：顶栏 → 状态轨 → 仪表（表盘 / 数据 / 主按钮）→ 节奏 → 动作 → 落点 → 稳定性 → 页脚。
 * 单屏 LazyColumn；左右 20dp；平板/横屏限宽 520dp 居中；小节之间通栏 1dp 墨线 + 26dp 留白。
 */
@Composable
fun ConsoleScreen() {
    val context = LocalContext.current
    val app = remember(context) { JidianApp.from(context) }
    val config by app.config.state.collectAsStateWithLifecycle()
    val status by app.engine.status.collectAsStateWithLifecycle()
    val logEntries by JidianLog.recent.collectAsStateWithLifecycle()

    var permissions by remember { mutableStateOf(JdPermission.read(context)) }
    var batteryUnrestricted by remember { mutableStateOf(JdPermission.batteryUnrestricted(context)) }
    var reduceMotion by remember { mutableStateOf(JdPermission.reduceMotion(context)) }
    var liveCps by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val screenSize = remember(context) {
        val metrics = context.resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }

    val updateConfig: ((ClickConfig) -> ClickConfig) -> Unit = remember(app) {
        { transform ->
            runCatching { app.config.update(transform) }
                .onFailure { error -> JidianLog.error("UI", "保存配置失败：${error.message}") }
        }
    }

    // 通知权限：API 33+ 走系统请求；更低版本/已授权则打开通知设置让用户确认。
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        permissions = JdPermission.read(context)
    }

    // 从系统设置返回后重新读数（权限、电池、动画开关）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = JdPermission.read(context)
                batteryUnrestricted = JdPermission.batteryUnrestricted(context)
                reduceMotion = JdPermission.reduceMotion(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 实时 CPS：UI 侧每 250ms 采样 sessionTaps 差值（引擎只报计数）。
    LaunchedEffect(Unit) {
        var lastTaps = 0L
        var lastAt = 0L
        while (true) {
            val snapshot = app.engine.status.value
            val now = SystemClock.elapsedRealtime()
            if (snapshot.running) {
                if (lastAt == 0L) {
                    lastTaps = snapshot.sessionTaps
                    lastAt = now
                } else {
                    val elapsed = (now - lastAt).coerceAtLeast(1L)
                    val delta = snapshot.sessionTaps - lastTaps
                    liveCps = if (delta <= 0L) 0f else delta * 1000f / elapsed
                    lastTaps = snapshot.sessionTaps
                    lastAt = now
                }
            } else {
                lastTaps = 0L
                lastAt = 0L
                liveCps = 0f
            }
            delay(250L)
        }
    }

    val onToggle: () -> Unit = {
        runCatching {
            if (app.engine.isRunning) {
                app.engine.stop(StopReason.USER)
            } else if (!TapAccessibilityService.isEnabled(context)) {
                // 没开无障碍就不启动：滚回状态轨、把黄铜提示亮出来，再跳设置。
                permissions = JdPermission.read(context)
                scope.launch { listState.scrollToItem(0) }
                JdPermission.openAccessibility(context)
            } else {
                val started = app.engine.start("user")
                if (started) {
                    // 连点是在后台跨应用跑的，必须有一个随手能停的入口：
                    // 有悬浮窗权限就把控制圆盘拉起来（没权限时靠通知栏 action 兜底）。
                    if (permissions.overlay && !FloatService.isRunning) {
                        runCatching { FloatService.show(context) }
                            .onFailure { JidianLog.warn("UI", "拉起悬浮控制台失败：${it.message}") }
                    }
                    val hint = if (permissions.overlay) "已开始 · 轻点悬浮圆盘停止" else "已开始 · 可在通知栏停止"
                    Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
                } else {
                    permissions = JdPermission.read(context)
                    JidianLog.warn("UI", "引擎未进入运行态")
                }
            }
        }.onFailure { error ->
            JidianLog.error("UI", "启停失败：${error.message}")
        }
    }

    val onAccessibility: () -> Unit = {
        permissions = JdPermission.read(context)
        JdPermission.openAccessibility(context)
    }
    val onOverlay: () -> Unit = {
        permissions = JdPermission.read(context)
        JdPermission.openOverlay(context)
    }
    val onBattery: () -> Unit = {
        batteryUnrestricted = JdPermission.batteryUnrestricted(context)
        JdPermission.openBattery(context)
    }
    val onNotification: () -> Unit = {
        if (JdPermission.notificationsGranted(context)) {
            JdPermission.openNotificationSettings(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure {
                    JidianLog.warn("UI", "请求通知权限失败：${it.message}")
                    JdPermission.openNotificationSettings(context)
                }
        } else {
            JdPermission.openNotificationSettings(context)
        }
    }
    val onForeground: () -> Unit = {
        runCatching {
            if (FloatService.isRunning) FloatService.hide(context) else FloatService.show(context)
        }.onFailure {
            JidianLog.warn("UI", "切换悬浮窗失败：${it.message}")
            permissions = JdPermission.read(context)
        }
    }
    val onPickRegion: () -> Unit = {
        if (!permissions.overlay) {
            permissions = JdPermission.read(context)
            JdPermission.openOverlay(context)
        } else {
            runCatching { FloatService.pickRegion(context) }
                .onFailure { JidianLog.warn("UI", "进入框选失败：${it.message}") }
        }
    }

    val notice: Pair<String, Color>? = when {
        status.running && !permissions.foreground -> "前台服务未启动" to JdBrass
        !status.running && !permissions.accessibility -> "无障碍服务未开启" to JdBrass
        !status.running -> stopNotice(status.lastStopReason)?.let { it to JdSteel }
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JdPanel),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp,
            ),
        ) {
            item(key = "topBar") {
                PagePad { TopBar(running = status.running) }
            }
            item(key = "statusRail") {
                PagePad {
                    StatusRail(
                        permissions = permissions,
                        onAccessibility = onAccessibility,
                        onOverlay = onOverlay,
                        onForeground = onForeground,
                    )
                }
            }
            item(key = "statusRailRule") { JdRule() }
            item(key = "hero") {
                PagePad {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                    ) {
                        HeroSection(
                            config = config,
                            status = status,
                            liveCps = liveCps,
                            reduceMotion = reduceMotion,
                            notice = notice,
                            onToggle = onToggle,
                        )
                    }
                }
            }
            item(key = "gapRhythm") { JdSectionGap() }
            item(key = "rhythm") {
                PagePad { RhythmSection(config = config, update = updateConfig) }
            }
            item(key = "gapAction") { JdSectionGap() }
            item(key = "action") {
                PagePad { ActionSection(config = config, update = updateConfig) }
            }
            item(key = "gapLanding") { JdSectionGap() }
            item(key = "landing") {
                PagePad {
                    LandingSection(
                        config = config,
                        screenWidth = screenSize.first,
                        screenHeight = screenSize.second,
                        update = updateConfig,
                        onPickRegion = onPickRegion,
                    )
                }
            }
            item(key = "gapStability") { JdSectionGap() }
            item(key = "stability") {
                PagePad {
                    StabilitySection(
                        config = config,
                        status = status,
                        logs = logEntries,
                        permissions = permissions,
                        batteryUnrestricted = batteryUnrestricted,
                        update = updateConfig,
                        onAccessibility = onAccessibility,
                        onOverlay = onOverlay,
                        onBattery = onBattery,
                        onNotification = onNotification,
                    )
                }
            }
            item(key = "footer") { FooterBlock() }
        }
    }
}

// —— 顶栏 / 状态轨 ——

@Composable
private fun TopBar(running: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(8.dp)
                .background(JdSignal),
        )
        Spacer(Modifier.width(8.dp))
        Text("疾点", style = JdType.title, color = JdGraphite)
        Spacer(Modifier.weight(1f))
        Text("v" + BuildConfig.VERSION_NAME, style = JdType.monoS, color = JdSteel)
        Spacer(Modifier.width(10.dp))
        StatePill(running = running)
    }
}

@Composable
private fun StatePill(running: Boolean) {
    val background by animateColorAsState(
        targetValue = if (running) JdSignal else Color.Transparent,
        animationSpec = tween(180),
        label = "pillBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = if (running) JdSignal else JdGraphite,
        animationSpec = tween(180),
        label = "pillBorder",
    )
    val foreground by animateColorAsState(
        targetValue = if (running) JdPanel else JdGraphite,
        animationSpec = tween(180),
        label = "pillForeground",
    )
    Box(
        modifier = Modifier
            .widthIn(min = 62.dp)
            .background(background, RoundedCornerShape(percent = 50))
            .border(1.dp, borderColor, RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (running) "运行中" else "待命",
            style = JdType.micro,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusRail(
    permissions: JdPermissions,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
    onForeground: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        StatusLamp(
            label = "无障碍",
            ready = permissions.accessibility,
            onClick = onAccessibility,
            modifier = Modifier.weight(1f),
        )
        StatusLamp(
            label = "悬浮窗",
            ready = permissions.overlay,
            onClick = onOverlay,
            modifier = Modifier.weight(1f),
        )
        StatusLamp(
            label = "前台",
            ready = permissions.foreground,
            onClick = onForeground,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusLamp(
    label: String,
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val dotColor by animateColorAsState(
        targetValue = if (ready) JdGraphite else JdBrass,
        animationSpec = tween(180),
        label = "lampDot",
    )
    val textColor by animateColorAsState(
        targetValue = if (ready) JdGraphiteSoft else JdBrass,
        animationSpec = tween(180),
        label = "lampText",
    )
    Row(
        modifier = modifier
            .height(36.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = JdType.micro, color = textColor, maxLines = 1)
    }
}

// —— 仪表：表盘 + 数据格 + 主按钮 ——

@Composable
private fun HeroSection(
    config: ClickConfig,
    status: EngineStatus,
    liveCps: Float,
    reduceMotion: Boolean,
    notice: Pair<String, Color>?,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChronographDial(
            running = status.running,
            liveCps = liveCps,
            targetCps = config.targetCps,
            reduceMotion = reduceMotion,
        )
        Spacer(Modifier.height(20.dp))
        DataRow(status = status, liveCps = liveCps)
        Spacer(Modifier.height(20.dp))
        JdPrimaryActionButton(
            text = if (status.running) "停止连点" else "启动连点",
            running = status.running,
            onClick = onToggle,
        )
        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Text(notice.first, style = JdType.note, color = notice.second)
        }
    }
}

@Composable
private fun DataRow(status: EngineStatus, liveCps: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DataCell(
            label = "本次点击",
            value = status.sessionTaps.toString(),
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(1.dp)
                .height(24.dp)
                .background(JdHairline),
        )
        DataCell(
            label = "累计",
            value = status.totalTaps.toString(),
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .width(1.dp)
                .height(24.dp)
                .background(JdHairline),
        )
        DataCell(
            label = "实际频率",
            value = if (status.running) oneDecimal(liveCps) else "—",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DataCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = JdType.monoL, color = JdGraphite, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = JdType.micro, color = JdSteel, maxLines = 1)
    }
}

// —— 节奏 ——

@Composable
private fun RhythmSection(
    config: ClickConfig,
    update: ((ClickConfig) -> ClickConfig) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        JdSectionHeader(title = "节奏", hint = "两次点击之间的间隔")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // 读数本身就是输入框：点一下就能直接敲想要的毫秒数，不用来回拖滑杆
            JdIntervalField(
                interval = config.intervalMs,
                onCommit = { ms ->
                    if (ms != config.intervalMs) update { it.copy(intervalMs = ms) }
                },
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "ms",
                style = JdType.monoM,
                color = JdSteel,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "≈ " + oneDecimal(config.targetCps) + " 次/秒",
                style = JdType.monoM,
                color = JdSteel,
                modifier = Modifier.padding(bottom = 5.dp),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        // 对数滑杆：1ms–10000ms 均匀铺开，低端（极速）也点得准
        JdSlider(
            value = intervalToFraction(config.intervalMs),
            valueRange = 0f..1f,
            onValueChange = { fraction ->
                val interval = fractionToInterval(fraction)
                if (interval != config.intervalMs) {
                    update { it.copy(intervalMs = interval) }
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClickConfig.INTERVAL_PRESETS.forEach { preset ->
                JdPresetBlock(
                    label = preset.toString(),
                    selected = config.intervalMs == preset,
                    onClick = { update { it.copy(intervalMs = preset) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        JdNote(
            text = "可输入 1–10000 ms。1–16 ms 是极限档：能跑多快取决于机型的手势注入上限，" +
                "以顶部「实际频率」为准",
        )
    }
}

/** 毫秒 → 对数滑杆位置（0=1ms，1=10000ms）。 */
private fun intervalToFraction(ms: Long): Float {
    val min = ClickConfig.MIN_INTERVAL_MS.toDouble()
    val max = ClickConfig.MAX_INTERVAL_MS.toDouble()
    val value = ms.toDouble().coerceIn(min, max)
    return ((ln(value) - ln(min)) / (ln(max) - ln(min))).toFloat()
}

/** 对数滑杆位置 → 毫秒。 */
private fun fractionToInterval(fraction: Float): Long {
    val min = ClickConfig.MIN_INTERVAL_MS.toDouble()
    val max = ClickConfig.MAX_INTERVAL_MS.toDouble()
    val value = exp(ln(min) + (ln(max) - ln(min)) * fraction.coerceIn(0f, 1f).toDouble())
    return value.roundToLong().coerceIn(ClickConfig.MIN_INTERVAL_MS, ClickConfig.MAX_INTERVAL_MS)
}

/**
 * 可编辑的间隔读数：外观是 Mono 大号数字，点进去是数字键盘。
 * 提交时机：键盘 Done 或失焦；非法/越界输入自动收敛回合法区间。
 */
@Composable
private fun JdIntervalField(interval: Long, onCommit: (Long) -> Unit) {
    var text by remember(interval) { mutableStateOf(interval.toString()) }
    var focused by remember { mutableStateOf(false) }

    fun commit() {
        val parsed = text.toLongOrNull()
        if (parsed == null) {
            text = interval.toString()
            return
        }
        val value = parsed.coerceIn(ClickConfig.MIN_INTERVAL_MS, ClickConfig.MAX_INTERVAL_MS)
        text = value.toString()
        if (value != interval) onCommit(value)
    }

    BasicTextField(
        value = text,
        onValueChange = { raw -> text = raw.filter { it.isDigit() }.take(5) },
        textStyle = JdType.monoXL.copy(color = JdGraphite),
        singleLine = true,
        cursorBrush = SolidColor(JdGraphite),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .widthIn(min = 48.dp)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) commit()
                focused = state.isFocused
            },
    )
}

// —— 动作 ——

@Composable
private fun ActionSection(
    config: ClickConfig,
    update: ((ClickConfig) -> ClickConfig) -> Unit,
) {
    val modes = listOf(TapMode.SINGLE, TapMode.DOUBLE, TapMode.LONG_PRESS, TapMode.SWIPE)
    Column(Modifier.fillMaxWidth()) {
        JdSectionHeader(title = "动作")
        Spacer(Modifier.height(12.dp))
        JdSegmented(
            options = listOf("单击", "双击", "长按", "滑动"),
            selectedIndex = modes.indexOf(config.mode).coerceAtLeast(0),
            onSelect = { index -> update { it.copy(mode = modes[index]) } },
        )
        Spacer(Modifier.height(16.dp))
        when (config.mode) {
            TapMode.SINGLE -> JdNote("在落点单击一次，按节奏重复")
            TapMode.DOUBLE -> ParamSlider(
                label = "双击间隔",
                valueText = "${config.doubleGapMs} ms",
                value = config.doubleGapMs.toFloat(),
                valueRange = ClickConfig.MIN_DOUBLE_GAP_MS.toFloat()..ClickConfig.MAX_DOUBLE_GAP_MS.toFloat(),
                onValue = { next ->
                    val gap = next.roundToInt().toLong()
                    if (gap != config.doubleGapMs) update { it.copy(doubleGapMs = gap) }
                },
            )
            TapMode.LONG_PRESS -> ParamSlider(
                label = "长按时长",
                valueText = "${config.longPressMs} ms",
                value = config.longPressMs.toFloat(),
                valueRange = ClickConfig.MIN_LONG_PRESS_MS.toFloat()..ClickConfig.MAX_LONG_PRESS_MS.toFloat(),
                onValue = { next ->
                    val duration = next.roundToInt().toLong()
                    if (duration != config.longPressMs) update { it.copy(longPressMs = duration) }
                },
            )
            TapMode.SWIPE -> SwipeParams(config = config, update = update)
        }
    }
}

@Composable
private fun SwipeParams(
    config: ClickConfig,
    update: ((ClickConfig) -> ClickConfig) -> Unit,
) {
    val distance = swipeDistance(config)
    val direction = swipeDirectionIndex(config)
    Column(Modifier.fillMaxWidth()) {
        ParamSlider(
            label = "滑动距离",
            valueText = "$distance px",
            value = distance.toFloat(),
            valueRange = 40f..1000f,
            onValue = { next ->
                val newDistance = next.roundToInt()
                if (newDistance != distance) update { withSwipe(it, newDistance, direction) }
            },
        )
        Spacer(Modifier.height(14.dp))
        JdSegmented(
            options = listOf("上", "下", "左", "右"),
            selectedIndex = direction,
            onSelect = { index -> update { withSwipe(it, swipeDistance(it), index) } },
        )
        Spacer(Modifier.height(14.dp))
        ParamSlider(
            label = "滑动时长",
            valueText = "${config.swipeDurationMs} ms",
            value = config.swipeDurationMs.toFloat(),
            valueRange = ClickConfig.MIN_SWIPE_MS.toFloat()..ClickConfig.MAX_SWIPE_MS.toFloat(),
            onValue = { next ->
                val duration = next.roundToInt().toLong()
                if (duration != config.swipeDurationMs) update { it.copy(swipeDurationMs = duration) }
            },
        )
    }
}

@Composable
private fun ParamSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        JdControlLabelRow(label = label, valueText = valueText)
        Spacer(Modifier.height(2.dp))
        JdSlider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            valueRange = valueRange,
            onValueChange = onValue,
        )
    }
}

private fun swipeDistance(config: ClickConfig): Int =
    maxOf(abs(config.swipeDx), abs(config.swipeDy)).coerceAtLeast(1)

private fun swipeDirectionIndex(config: ClickConfig): Int = when {
    config.swipeDy < 0 -> 0
    config.swipeDy > 0 -> 1
    config.swipeDx < 0 -> 2
    else -> 3
}

private fun withSwipe(config: ClickConfig, distance: Int, direction: Int): ClickConfig {
    val d = distance.coerceIn(1, ClickConfig.MAX_SWIPE_DISTANCE)
    return when (direction) {
        0 -> config.copy(swipeDx = 0, swipeDy = -d)
        1 -> config.copy(swipeDx = 0, swipeDy = d)
        2 -> config.copy(swipeDx = -d, swipeDy = 0)
        else -> config.copy(swipeDx = d, swipeDy = 0)
    }
}

// —— 落点 ——

@Composable
private fun LandingSection(
    config: ClickConfig,
    screenWidth: Int,
    screenHeight: Int,
    update: ((ClickConfig) -> ClickConfig) -> Unit,
    onPickRegion: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        JdSectionHeader(title = "落点")
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(JdIvory)
                .border(1.dp, JdHairline)
                .padding(14.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                RegionMap(
                    modifier = Modifier.width(72.dp),
                    configured = config.regionConfigured,
                    shape = config.regionShape,
                    regionCx = config.regionCx,
                    regionCy = config.regionCy,
                    regionW = config.regionW,
                    regionH = config.regionH,
                    screenW = screenWidth,
                    screenH = screenHeight,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (config.regionConfigured) {
                            "自定义 · (${config.regionCx}, ${config.regionCy})"
                        } else {
                            "屏幕中心"
                        },
                        style = JdType.body,
                        color = JdGraphite,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (config.regionConfigured) {
                            "${config.regionW} × ${config.regionH} px · ${shapeLabel(config.regionShape)}"
                        } else {
                            "$screenWidth × $screenHeight px"
                        },
                        style = JdType.monoM,
                        color = JdSteel,
                    )
                    if (!config.regionConfigured) {
                        Spacer(Modifier.height(8.dp))
                        JdNote("还没框选落点，将点击屏幕中心")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                JdSolidButton(
                    text = "悬浮窗框选",
                    onClick = onPickRegion,
                    modifier = Modifier.weight(1f),
                )
                JdOutlineButton(
                    text = "恢复屏幕中心",
                    onClick = { update { it.copy(regionCx = -1, regionCy = -1) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        ParamSlider(
            label = "随机偏移",
            valueText = "±${config.randomOffsetPx} px",
            value = config.randomOffsetPx.toFloat(),
            valueRange = 0f..120f,
            onValue = { next ->
                val offset = next.roundToInt()
                if (offset != config.randomOffsetPx) update { it.copy(randomOffsetPx = offset) }
            },
        )
        Spacer(Modifier.height(4.dp))
        JdNote("在落点周围随机偏移，避免同一像素被反复命中")
        Spacer(Modifier.height(16.dp))
        ParamSlider(
            label = "节奏抖动",
            valueText = "${config.intervalJitterPct}%",
            value = config.intervalJitterPct.toFloat(),
            valueRange = 0f..80f,
            onValue = { next ->
                val jitter = next.roundToInt()
                if (jitter != config.intervalJitterPct) update { it.copy(intervalJitterPct = jitter) }
            },
        )
    }
}

private fun shapeLabel(shape: RegionShape): String = when (shape) {
    RegionShape.CIRCLE -> "椭圆"
    RegionShape.RECT -> "矩形"
}

// —— 稳定性 ——

@Composable
private fun StabilitySection(
    config: ClickConfig,
    status: EngineStatus,
    logs: List<JidianLog.Entry>,
    permissions: JdPermissions,
    batteryUnrestricted: Boolean,
    update: ((ClickConfig) -> ClickConfig) -> Unit,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
    onBattery: () -> Unit,
    onNotification: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        JdSectionHeader(title = "稳定性")
        Spacer(Modifier.height(6.dp))
        JdSwitchRow(
            label = "熄屏暂停",
            checked = config.pauseOnScreenOff,
            onCheckedChange = { checked -> update { it.copy(pauseOnScreenOff = checked) } },
        )
        JdRule()
        JdSwitchRow(
            label = "触感反馈",
            checked = config.haptic,
            onCheckedChange = { checked -> update { it.copy(haptic = checked) } },
        )
        JdRule()
        JdStepperRow(
            label = "点击上限",
            valueText = if (config.limitCount == 0) "不限" else "${config.limitCount} 次",
            onDecrease = {
                val step = if (config.limitCount > 1000) 500 else 100
                val next = (config.limitCount - step).coerceAtLeast(0)
                if (next != config.limitCount) update { it.copy(limitCount = next) }
            },
            onIncrease = {
                val step = if (config.limitCount >= 1000) 500 else 100
                val next = (config.limitCount + step).coerceAtMost(ClickConfig.MAX_LIMIT_COUNT)
                if (next != config.limitCount) update { it.copy(limitCount = next) }
            },
        )
        JdRule()
        Spacer(Modifier.height(4.dp))
        JdStatusLinkRow(
            label = "无障碍服务",
            statusText = if (permissions.accessibility) "已开启" else "无障碍服务未开启",
            warn = !permissions.accessibility,
            onClick = onAccessibility,
        )
        JdRule()
        JdStatusLinkRow(
            label = "悬浮窗权限",
            statusText = if (permissions.overlay) "已授权" else "悬浮窗未授权",
            warn = !permissions.overlay,
            onClick = onOverlay,
        )
        JdRule()
        JdStatusLinkRow(
            label = "电池优化",
            statusText = if (batteryUnrestricted) "不受限制" else "系统可能限制后台运行",
            warn = !batteryUnrestricted,
            onClick = onBattery,
        )
        JdRule()
        JdStatusLinkRow(
            label = "通知权限",
            statusText = if (permissions.notifications) "已授权" else "未授权",
            warn = !permissions.notifications,
            onClick = onNotification,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "超时 ${status.gestureTimeouts} · 自愈 ${status.recoveries} · 丢弃 ${status.dropped}",
            style = JdType.monoS,
            color = JdSteel,
        )
        Spacer(Modifier.height(12.dp))
        JdRule()
        LogSection(entries = logs)
    }
}

@Composable
private fun LogSection(entries: List<JidianLog.Entry>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val recent = remember(entries) { entries.takeLast(5).asReversed() }
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                ) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("运行日志", style = JdType.body, color = JdGraphite)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "收起" else "最近 5 条",
                style = JdType.micro,
                color = JdSteel,
            )
            Spacer(Modifier.width(6.dp))
            JdChevron(up = expanded)
        }
        if (expanded) {
            if (recent.isEmpty()) {
                JdNote("暂无日志")
            } else {
                recent.forEach { entry ->
                    Text(
                        text = logTime(entry.timeMs) + "  " + entry.message,
                        style = JdType.monoS,
                        color = JdSteel,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// —— 页脚 ——

@Composable
private fun FooterBlock() {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(26.dp))
        JdRule()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 24.dp),
        ) {
            Text(
                text = "v" + BuildConfig.VERSION_NAME,
                style = JdType.monoS,
                color = JdSteel,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "github.com/3511440694huang-oss/jidian",
                style = JdType.monoS,
                color = JdSteel,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "疾点不读屏，只在你按下启动后派发手势",
                style = JdType.note,
                color = JdSteel,
            )
        }
    }
}

// —— 小工具 ——

@Composable
private fun PagePad(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        content()
    }
}

private fun stopNotice(reason: String?): String? = when (reason) {
    StopReason.SCREEN_OFF -> "熄屏，已自动暂停"
    StopReason.LIMIT_REACHED -> "已达到点击上限"
    StopReason.SERVICE_LOST -> "无障碍服务已断开，点击已暂停"
    else -> null
}

private fun oneDecimal(value: Float): String {
    val safe = if (value.isFinite() && value > 0f) value else 0f
    return String.format(Locale.US, "%.1f", safe)
}

private fun logTime(timeMs: Long): String {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timeMs
    val hour = calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minute = calendar.get(Calendar.MINUTE).toString().padStart(2, '0')
    val second = calendar.get(Calendar.SECOND).toString().padStart(2, '0')
    return "$hour:$minute:$second"
}
