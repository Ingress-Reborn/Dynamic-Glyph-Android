# Glyph Lens — Android

Glyph Lens 是从 Dynamic Glyph iOS 识别核心迁移而来的原生 Android 应用。**0.3.0 仅保留 Glyph 自动识别与序列预测，仅支持 arm64，提供实时与省电两种模式。** 应用通过系统授权读取屏幕，在游戏展示 Glyph 时识别形状与槽位，并在小型原生悬浮窗中显示已确认内容和唯一匹配的预测后缀；进入输入阶段后冻结展示序列。

当前模式、操作与验收要求见 [POWER_MODES.md](Docs/POWER_MODES.md)，实施原则与用户七条工程规则见 [Plan.md](Plan.md)。本轮新增了“本轮识别完成后自动停止”选项，完整版本正在统一构建与验收；此前不含该选项的中间构建数据不作为最终结果。[GLYPH_ONLY.md](Docs/GLYPH_ONLY.md) 保留 0.2.0 的历史证据，不作为 0.3.0 的验收结果。

## 当前功能

- 首页选择实时/省电模式、开始/停止、系统屏幕共享授权、显示/隐藏悬浮窗和打开 Ingress。
- 默认实时模式在共享授权后立即识别；省电模式先显示可拖动的 48dp 悬浮图标，点击后开始识别。
- 两种模式点击识别窗的 × 均停止识别、回到图标待命；待命不采集图像、不运行 OCR，再次点击图标可继续使用本次共享授权。
- 首页提供“本轮识别完成后自动停止”开关，默认关闭，两种模式都适用。开启后进入输入阶段、序列冻结时停止采集与 OCR，保留静态识别结果和“开始识别”按钮。
- 实际节点/槽位定位、跨帧稳定识别、连续确认前缀预测；观察、确认和预测分别显示。
- 输入期序列冻结，保留重复项与缺槽，不把玩家笔画当作新的展示 Glyph。
- 可拖动的 Canvas 悬浮窗，按真实内容空隙自动摆放；手动拖动后保留用户位置。
- 游戏语言自动/手动选择，画布大小、清晰度与显示设置。
- 内置形状表和序列表，可通过系统文件选择器导入替换。
- 随包 Latin、中文、日文、韩文四类 OCR 模型，安装后离线读取游戏阶段标题。

游戏结算/分数、装备与掉落、命令业务、输入正确性判断、历史统计、数据库、云同步、录像与音频、诊断导出、小组件、快捷设置、引导和演示功能已从本版范围删除，不作为未来完整移植的待办；保留本轮 Glyph 识别结果不恢复这些业务。

## 使用要求

需要 Android 10 / API 29 以上的 **arm64-v8a** 设备。安装包不包含 32 位 ARM、x86 或 x86_64 原生库；验证设备也必须能够运行 arm64 应用。

在首页选择模式并开始，允许显示在其他应用上层，在系统共享对话框选择**共享整个屏幕**，然后打开游戏。实时模式立即识别；省电模式需点击悬浮图标才开始。模式仅能在完全停止共享后切换，选择会保存。

如开启**本轮识别完成后自动停止**，本组 Glyph 展示结束、进入输入阶段并冻结后，会自动停止识别，悬浮窗保留当时结果。漏项和不确定项仍按原状态显示，自动停止不代表所有 Glyph 都已成功识别。点击结果窗的**开始识别**会清空旧内容并开始新会话；点 × 则清空结果、收回待命图标。开启此选项会确保悬浮窗显示，方便查看结果和再次开始。

点击识别窗的 **×** 停止识别并回到图标。此时图像采集和 OCR 已停止，但保留共享授权，因此系统的共享指示和前台通知仍可见；这不表示仍在识别。再次点击图标开始新的识别序列，不沿用上一次内容，也不重新弹出共享同意框。首页或通知的**停止**才会彻底结束共享并移除悬浮窗口；系统主动结束共享后也需要重新授权。

当前识别范围为竖屏；横屏、方形、分屏及单应用共享到悬浮窗的坐标映射尚未认证。应用不需要 root、无障碍服务或麦克风。

系统共享用于当前识别，不提供录像保存、音频采集或云端上传。阶段标题中仍需区分命令/展示/输入/完成，避免在错误阶段确认 Glyph；这不等于保留命令或结果业务。

## 构建

工程为 Kotlin + Android Views/Canvas，包含 `:app` 与纯 Kotlin `:recognition` 两个模块。使用 JDK 17、仓库中的 Gradle Wrapper 和本机 Android SDK；SDK 路径放在本机 `local.properties`，不写入共享构建文件。具体依赖版本以 [版本目录](gradle/libs.versions.toml) 和 [应用构建配置](app/build.gradle.kts) 为准。

Windows 下从仓库根目录执行：

```powershell
.\gradlew.bat :recognition:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease :app:lintRelease
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。Release 启用 R8、仅打包 arm64；包含自动停止选项的最终 APK 大小、SHA-256、签名和权限检查会在统一构建后写入 [0.3.0 验收记录](Docs/POWER_MODES.md#5-030-验收记录)，不沿用中间构建的包信息。

本轮测试环境为 Android 15 / API 35、1216×2688 / 440dpi 的模拟器，通过 ARM64 native bridge 执行 arm64 应用。最终验收需同时覆盖图标待命、自动停止后保留结果、再次开始和五图识别。模拟环境中的短期 CPU 观测不等同于零耗电、物理 ARM64 手机温度或长期电池验收。

iOS 使用系统 Vision，Android 当前选择将离线 OCR 模型随包携带。0.2.0 产物中 arm64 OCR 原生库本身约 11MB，另有模型、代码和资源；这不是最终 APK 大小，也不能承诺与 iOS 同体积。体积依据及 0.2.0 记录见 [GLYPH_ONLY.md](Docs/GLYPH_ONLY.md#4-为什么仍保留离线-ocr)。

## 仓库与历史资料

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 首页、设置、系统采集、OCR、悬浮窗及表导入 |
| `recognition/` | Glyph 像素识别、阶段/槽位追踪、预测和 JVM 测试 |
| `Docs/POWER_MODES.md` | 0.3.0 模式、待命/恢复行为和验收要求 |
| `Docs/GLYPH_ONLY.md` | 0.2.0 精简范围、识别语义和历史验证记录 |
| `Plan.md` | 当前实施约束、用户规则和验收要求 |
| `Apps/`、`Packages/`、`Tools/` | iOS 参考实现、历史测试和研发工具，不参与 Android 打包 |

[精简前迁移计划](Docs/HISTORY/PLAN_BEFORE_GLYPH_ONLY_2026-11-09.md)、[早期 Glyph 验收](Docs/GLYPH_PREDICTION.md)和[比例适配记录](Docs/ANDROID_ADAPTATION.md)均为历史资料。早期文档中的完整迁移、Room/Drive、录制、小米岛及 250ms OCR 说明不是当前产品范围或策略。旧矩阵测试与 CPU 数据对应各自旧包，不作为新版本结论。

## 许可证与来源

本仓库代码是 **source-available（源码可读）**，使用 [PolyForm Noncommercial License 1.0.0](LICENSE)。许可证原文及其中的 `Required Notice:` 保持不变；具体使用、修改和分发条件以许可证为准。

原项目名称与图标、第三方游戏素材及商标的说明见 [NOTICE.md](NOTICE.md)，贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。Android 工作名称为 Glyph Lens，不改变原代码版权和许可条件。

部分真实截图、录屏和参考测试输入未随仓库分发。缺少这些素材的测试需明确标为跳过，不能算作识别通过；原输入来源记录见 [测试素材说明](Packages/GlyphRecognition/Tests/GlyphRecognitionTests/Fixtures/PROVENANCE.md)。
