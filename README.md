# Glyph Lens — Android

Glyph Lens 是从 Dynamic Glyph iOS 识别核心迁移而来的原生 Android 应用。**0.4.2 只做 Glyph 识别与序列预测，仅支持 arm64；自动隐藏改为可选的“返回地图后自动隐藏”。** 使用传统视觉分别寻找地图的五个按钮，任意一个出现就隐藏窗口，不读取完成标题，也不把返回地图当作游戏成功。

当前行为和验收记录见 [VISUAL_COMPLETION.md](Docs/VISUAL_COMPLETION.md)，实施约束及用户七条规则见 [Plan.md](Plan.md)。核心 **248 项（245 通过、3 私有素材跳过）**，其中检测器 **12 项**；Android 单元 **102 项**、界面 **20 项**、实采 **11 项**及两组额外尺寸通过，CPU 对比已完成。生产源码 `6fd03bc9` 的正式签名包结构、**9 项实际界面流程及同包 9/9 人工截图核验全部通过**。main、Release 和目标仓库同步尚待执行；0.4.1 证据保留在 [REALTIME_VISIBILITY.md](Docs/REALTIME_VISIBILITY.md)。

## 当前功能

- 实时模式授权共享后自动后台识别，初始无窗口或图标；省电模式先显示可拖动图标，点击后识别。
- 连续已确认前缀加唯一预测覆盖整组、或整组全部确认后，每轮自动显示一次；观察、确认和预测分别显示。
- “返回地图后自动隐藏”默认关闭，沿用原自动隐藏偏好；开启时移除顶部 × 整行，仅保留可拖动画布。
- 五个地图目标独立检测：右侧菱形、信号弧、叠层圆钮，左侧方框加号，以及公文包。任意一个出现即可隐藏，不要求多个同时出现或先进入输入阶段。
- 地图命中只控制窗口与跳过无关识别工作，不产生 COMPLETED，不改变 Glyph 结果或预测；同会话继续等待下一轮。
- 关闭自动隐藏时，实时 × 隐藏且继续识别，省电 × 停止并回图标。通知提供“显示悬浮窗”和“停止”。
- 11 节点标定后复用固定采样，跨帧稳定确认；输入期序列冻结，保留重复与缺槽，不把玩家笔画作为新展示 Glyph。
- 游戏语言、画布大小/清晰度、内置或导入 CSV、随包离线 OCR 保留；地图判断独立于语言，其他阶段仍可能需要正确语言设置。

自动停止、静态停止结果重启、结算/分数、装备掉落、命令业务、输入正确性判断、历史统计、数据库、云同步、录制/音频、诊断导出、小组件、引导演示和岛接入不在产品范围内。地图按钮识别不恢复这些业务。

## 使用方式

需要 Android 10 / API 29 以上的 arm64-v8a 设备。选择实时或省电模式，设置自动隐藏选项后开始，允许悬浮窗并在系统对话框选择**共享整个屏幕**。实时模式立即识别，省电模式点击图标后开始。模式和自动隐藏选项在共享完全停止时修改并保存。

开启**返回地图后自动隐藏**后，窗口只显示可拖动 Glyph 画布。只要检测到任意目标地图按钮，窗口就隐藏；不用等待绘制完成标题、奖励面板或计时结束。回到地图后通过通知手动显示窗口，如果地图按钮仍然可见，窗口会再次自动隐藏。下一轮仍按原有识别与预测条件自动显示。

关闭该选项时保持正常 × 与通知操作。实时 × 完全隐藏但继续识别，省电 × 停止采集并回图标；通知“显示悬浮窗”恢复窗口，首页或通知“停止”彻底结束共享。自动隐藏本身不会重建采集会话或重复请求授权。

当前目标为竖屏整屏共享。横屏、方形、分屏和单应用共享坐标尚未全面认证；多尺度检测不等于所有 Android 设备已经通过。应用不需要 root、无障碍服务或麦克风，不保存录像或上传屏幕。

## 实现与负载

应用先将采集画面统一缩至宽度不超过 603 像素，再分别检测五个地图按钮。检测使用相对的边缘/底部区域、多尺度定位和实际图标的颜色/黑底证据；缓存命中位置后逐帧少量复验，未命中时全搜索最多 4Hz。地图正向命中后跳过不需要的形状、阵列搜索与标题 OCR；信号不修改阶段追踪、槽位或预测。原始 1216 宽截图直接输入检测器的部分目标未通过，不等同于应用的缩放采集路径，也不宣称所有原尺寸输入均可识别。

当前轮实际进入 INPUT 且展示序列已观察、冻结后，暂停新的标题 OCR，直到下一展示恢复。其他阶段仍使用原有标题有效期、OCR 节奏和语言设置。下一 Glyph 画面继续按原有采样规则处理，五图第 4、5 项与预测准确率必须通过真实回归。

同一 Android 15 模拟器上，旧/新版本各三次交替冷启动，固定测量五图录屏前 30 秒，六次均通过。应用与测试进程的 CPU 时间中位数 **20.606s → 13.094s，下降 36.46%**；标题 OCR 请求中位数 **78 → 41，减少 47.44%**。窗口隐藏时机也随版本变化，因此这是整版本端到端对照，不能单独归因于某个检测步骤，也不是实体手机电池或温度测量。完整六次数据与限制见验收文档；下一轮另做功能验收，不包含在该 CPU 窗口内。

## 构建与交付

工程使用 Kotlin + Android Views/Canvas，含 `:app` 与纯 Kotlin `:recognition` 模块。使用 JDK 17、Gradle Wrapper 和本机 Android SDK；SDK 路径放在 `local.properties`。依赖以 [版本目录](gradle/libs.versions.toml)和 [应用配置](app/build.gradle.kts)为准。

```powershell
.\gradlew.bat :recognition:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease :app:lintRelease
```

0.4.2 使用 versionCode 6。Release 继续启用 R8、仅打包 arm64，并精确保留 ML Kit 的三个实际反射构造入口。正式产物为 `app/build/outputs/apk/release/Glyph-Lens-0.4.2-arm64.apk`，**15,824,427 bytes**（约 15.82MB / 15.09MiB），比 0.4.1 增加 16,384 bytes；SHA-256 为 `e5f381a0c28b8dd15bbbfc26cd131a3365a27331c1c926998b7446193e539e27`。v3 签名、ZIP/ELF 16KiB、原始表哈希、无 INTERNET 权限及包内源码 `6fd03bc9` 已核验；同一签名包的 9 项流程、实际反射构造、完全停止和人工图形核对通过。

完成测试后更新 [vhu231/Dynamic-Glyph-Android](https://github.com/vhu231/Dynamic-Glyph-Android) 的 main 和 v0.4.2 Release，再同步 [szres/dynamic-glyph-android](https://github.com/szres/dynamic-glyph-android) 的 main。目标仓库保留自身旧历史，两边最终源码一致，不 force push。当前已发布旧版为 [v0.4.1](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.1)，不把旧 APK 或测试记录当成本轮产物。

## 仓库与历史资料

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 首页、设置、采集、OCR、悬浮窗和表导入 |
| `recognition/` | Glyph 识别/预测、地图按钮检测与阶段/槽位追踪 |
| [VISUAL_COMPLETION.md](Docs/VISUAL_COMPLETION.md) | 0.4.2 地图自动隐藏、负载与待验收计划 |
| [Plan.md](Plan.md) | 当前范围、用户规则与实施约束 |
| [REALTIME_VISIBILITY.md](Docs/REALTIME_VISIBILITY.md) | 0.4.1 已发布的完成阶段隐藏和原始验证 |
| [CALIBRATED_VISION.md](Docs/CALIBRATED_VISION.md) | 0.4.0 固定采样与负载优化历史 |
| [POWER_MODES.md](Docs/POWER_MODES.md) | 旧模式与已删除自动停止历史 |
| [GLYPH_ONLY.md](Docs/GLYPH_ONLY.md) | 0.2.0 精简范围、体积取舍和历史证据 |
| `Apps/`、`Packages/`、`Tools/` | iOS 参考与研发资料，不参与 Android 打包 |

识别表的原始换行由 [.gitattributes](.gitattributes)固定，继续核验固定哈希。历史迁移、Room/Drive、录制、岛和旧 250ms OCR 描述不作为当前范围；历史矩阵、性能数字和 APK 数据只适用于其原版本。

## 许可证与来源

本仓库代码是 **source-available（源码可读）**，使用 [PolyForm Noncommercial License 1.0.0](LICENSE)。许可证原文及其中的 `Required Notice:` 保持不变；具体使用、修改和分发条件以许可证为准。

原项目名称与图标、第三方游戏素材及商标的说明见 [NOTICE.md](NOTICE.md)，贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。Android 工作名称为 Glyph Lens，不改变原代码版权和许可条件。

部分真实截图、录屏和参考测试输入未随仓库分发。缺少这些素材的测试需明确标为跳过，不能算作识别通过；原输入来源记录见 [测试素材说明](Packages/GlyphRecognition/Tests/GlyphRecognitionTests/Fixtures/PROVENANCE.md)。
