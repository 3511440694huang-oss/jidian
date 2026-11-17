# 疾点 · Jidian

**Android 精密连点器。** 用无障碍手势跨应用点击，用悬浮窗框选落点，节奏、双击、长按、
滑动和随机偏移都可调。界面是一块按下去的计时码表：象牙表盘、石墨墨线、一根信号红秒针。

> 目标系统：Android 8.0 – Android 17（API 26–37）；`compileSdk 36` / `targetSdk 35` / `minSdk 26`。

![疾点图标](icon-preview.png)

## 功能

- **跨应用连点**：无障碍 `dispatchGesture` 派发，切到任何 App 都生效，不需要 Root。
- **速度可调**：间隔 10 ms – 10 s（100 次/秒 – 0.1 次/秒），带节奏抖动。
- **四种动作**：单击 / 双击 / 长按 / 滑动（滑动距离、时长、方向可调）。
- **悬浮窗框选落点**：点悬浮盘 → 框选，拖出椭圆或矩形区域，确认即生效；
  也可以从 App 内点「悬浮窗框选」。
- **随机范围**：在落点区域内随机采样 + 额外像素偏移，避免同一像素被反复命中。
- **悬浮控制盘**：64dp 石墨圆盘，拖动移位、轻点启停、长按展开快调面板，
  通知栏 / 快捷设置磁贴也能一键开关。
- **前台服务常驻**：切换应用、锁屏再回来都不掉；熄屏自动暂停，亮屏继续。
- **极致性能**：独立 HandlerThread + `dispatchGesture` 回调链 + `postAtTime` 对齐，
  双击合成一条手势（一次 IPC），热路径零锁零分配。
- **稳定性兜底**：手势回调看门狗、无障碍断线自愈、点击上限、诊断计数（超时/自愈/丢弃）。
- **不联网**：App 没有 `INTERNET` 权限，不读屏、不上传任何数据。

## 下载

- 最新 Release：[github.com/3511440694huang-oss/jidian/releases](https://github.com/3511440694huang-oss/jidian/releases)
- 当前版本：`jidian-1.0.0.apk`（6.4 MB）
- SHA-256：`a31a0a2211b239a2aae74ceee7caa1ba2fcf70c6435e55048af7ac6ec64d4d39`

## 安装

1. 下载 `jidian-1.0.0.apk`。
2. 手机上允许「安装未知来源应用」，安装。
3. 依次开启三项权限（App 内每项都有直达按钮）：
   - **无障碍服务**：设置 → 无障碍 → 已安装的服务 → 疾点 → 开启。
     疾点只用它派发手势，**不读取屏幕内容**，配置里 `canRetrieveWindowContent=false`。
   - **悬浮窗**：设置 → 应用 → 疾点 → 显示在其他应用上层。
   - **通知**：允许通知（前台服务通知，可随时停止连点）。
4. 建议把疾点加入电池优化白名单，避免系统在后台冻结它。

## 用法

1. 打开悬浮窗（App 顶栏「悬浮控制台」）。
2. 拖动圆盘到不挡操作的位置；长按展开面板，点「框选落点」，拖出点击区域，确认。
3. 在 App 里调节奏、动作模式、随机偏移；回到目标 App，轻点圆盘开始/停止。
4. 通知栏或快捷设置磁贴也能随时停。

## 参数口径

| 参数 | 范围 | 说明 |
|---|---|---|
| 间隔 | 10 – 10 000 ms | 两次点击之间的间隔 |
| 双击间隔 | 20 – 400 ms | 双击两下之间的时间 |
| 长按时长 | 120 – 2 000 ms | 按住多久算长按 |
| 滑动 | 距离 ≤ 2000 px，时长 60 – 3000 ms | 从落点起按方向滑出 |
| 随机偏移 | 0 – 300 px | 在落点区域内随机抖动 |
| 节奏抖动 | 0 – 80 % | 间隔随机浮动，避免机械节拍 |
| 点击上限 | 0 – 1 000 000 | 0 = 不限 |

## 技术要点

- **手势链**：`HandlerThread("tap-engine")` + `GestureResultCallback` 里排下一枪，
  用 `SystemClock.uptimeMillis()` + `postAtTime` 对齐目标时刻，不积累漂移。
- **双击合一**：一个 `GestureDescription` 里放两个 `StrokeDescription`（startTime 0 与 gap），
  双击只要一轮无障碍 IPC。
- **随机采样**：圆内用 `sqrt(u)` 均匀采样，矩形均匀采样，最后 clamp 到屏幕内。
- **看门狗**：2.5 s 内没收到手势回调就判定卡死 → 清状态、记一次自愈、重排；不会把引擎卡死。
- **熄屏暂停**：屏幕熄灭自动停（可关），亮屏后可一键继续。
- **前台服务**：`specialUse` 类型前台服务承载悬浮窗，不依赖 Activity 存活。

## 从源码构建

```bash
# 需要 JDK 17 + Gradle 8.11.1 + Android SDK 36 + build-tools 35
bash build.sh :app:assembleRelease   # 产物 app/build/outputs/apk/release/app-release.apk
bash build.sh :app:testDebugUnitTest # 单元测试（几何采样/节奏抖动）
```

签名文件在 `keystore/jidian.keystore`（仅用于本项目自发布，不入库）。

## 构建信息

- 版本：1.0.0（versionCode 1）
- 签名证书 SHA-256：`7492b1c017585af88441ad0ddccf788ba179c0f387f48cb76968bad158518af9`
- 单元测试：14/14 通过（`GeometryTest`）
- 图标进包校验：`ic_launcher` / `ic_launcher_round` / `ic_launcher_foreground` PASS-EXACT，
  `ic_launcher_monochrome` PASS-CLEANUP（仅透明像素 RGB 清理）

## 隐私

- 无网络权限，无统计 SDK，无广告。
- 无障碍服务只调用 `dispatchGesture`；不订阅窗口内容、不截屏、不读输入。
- 所有配置只存在本机 SharedPreferences。

## 许可

[MIT](LICENSE) · 字体 [Barlow](https://github.com/google/fonts/tree/main/ofl/barlow) 与
[IBM Plex Mono](LICENSE-IBM-Plex-Mono.txt) 均为 SIL Open Font License 1.1。
