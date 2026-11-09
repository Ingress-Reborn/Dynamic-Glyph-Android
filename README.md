# Glyph Lens — Android

Glyph Lens 是从 Dynamic Glyph iOS 识别核心迁移而来的原生 Android 应用。**0.4.4 删除省电模式和待命悬浮图标，打开 App 完成必要系统授权后直接开始识别。** 全流程纯特征识别、预测和自适应节流保持，不包含 OCR 或语言设置。

本轮行为与增量验收见 [AUTO_START.md](Docs/AUTO_START.md)，用户八条规则见 [Plan.md](Plan.md)。本轮增量构建、一项正式 APK 自动启动、设置、五图两轮流程及五张重点截图人工核对已通过，已发布 v0.4.4；不继承旧版算法或 CPU 测量结果。

## 使用与设置

打开 App，按系统要求完成屏幕共享等必要授权后，应用直接识别，不再选择模式或点击待命图标。共享范围继续选择**整个屏幕**；必要权限仍由用户在系统流程中授予。

两项窗口开关移到设置页：

| 设置 | 默认 | 行为 |
| --- | --- | --- |
| 返回地图后自动隐藏 | 开启 | 识别到既有五个地图目标之一时隐藏窗口 |
| 显示悬浮窗 | 开启 | 允许按原有结果就绪规则显示识别与预测窗口 |

两个开关独立。关闭“显示悬浮窗”后，自动隐藏不会将它重新打开，识别可以继续在后台进行。默认值只用于没有保存过该项设置的用户；已有明确保存的 true/false 保留，不强行覆盖。

省电模式及其待命悬浮图标删除。通知与明确停止沿用现有控制，停止结束当前共享；CSV、画布与既有结果呈现保持。本轮不修改纯特征阶段、五图确认、预测、地图模板或自适应节流。

## 构建与交付

版本为 **0.4.4 / versionCode 8**，生产源码 `3304554`。沿用现有流程的一次增量构建用时 35 秒，正式 APK 为 **409,597 字节，约 0.410 MB**。一项正式 APK 流程已验证无需开始按钮、两个默认开关、开关独立及采集中设置生效；五图下一轮无需重启或重新授权即可显示并回地图隐藏，最终停止后无屏幕共享。五张重点截图人工核对通过，两轮五图的第 4、5 图正确，地图稳定画面无 Glyph 浮层。

交付入口：[v0.4.4 Release](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.4) · [Glyph-Lens-0.4.4-arm64.apk](https://github.com/vhu231/Dynamic-Glyph-Android/releases/download/v0.4.4/Glyph-Lens-0.4.4-arm64.apk)。生产源码已推送原仓库 main，v0.4.4 标签指向 `3304554`，Latest Release 与 APK 已发布。按既有安排同步 [目标 main](https://github.com/szres/dynamic-glyph-android)，保留目标自身历史、不 force push。

本轮没有运行全单元、完整 Lint、重复 Debug 设备流程、多尺寸或 CPU 对比，没有下载 APK 验 SHA。仅保留 Release 构建自带的 lintVital，未引入新工具或流程。

交付目标仍是 Android 10 / API 29 以上的 arm64、竖屏整屏共享；不要求 root、无障碍服务或麦克风。本轮不新增横屏、分屏、单应用共享或所有机型的适配结论。

## 历史与工程资料

| 路径 | 用途 |
| --- | --- |
| [AUTO_START.md](Docs/AUTO_START.md) | 当前 0.4.4 启动与设置范围、增量验收 |
| [Plan.md](Plan.md) | 八条用户规则与本轮约束 |
| [FEATURE_ONLY.md](Docs/FEATURE_ONLY.md) | 0.4.3 全流程去 OCR、原始验收与发布记录 |
| [VISUAL_COMPLETION.md](Docs/VISUAL_COMPLETION.md) | 0.4.2 地图隐藏、性能及发布历史 |
| `app/`、`recognition/` | Android 平台层与纯 Kotlin 识别核心 |
| `Apps/`、`Packages/`、`Tools/` | 既有 iOS 参考与研发资料，不进入 Android 包 |

历史版本见 [v0.4.3](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.3)。历史测试、APK 大小和 CPU 数字只代表原版本，不继承为 0.4.4 结果。iOS 源工程不修改。

## 许可证与来源

本仓库代码是 **source-available（源码可读）**，使用 [PolyForm Noncommercial License 1.0.0](LICENSE)。许可证原文及其中的 `Required Notice:` 保持不变；具体使用、修改和分发条件以许可证为准。

原项目名称与图标、第三方游戏素材及商标的说明见 [NOTICE.md](NOTICE.md)，贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。Android 工作名称为 Glyph Lens，不改变原代码版权和许可条件。

部分真实截图、录屏和参考测试输入未随仓库分发。缺少这些素材的测试需明确标为跳过，不能算作识别通过；现有 CSV 与图像素材来源见 [测试素材说明](Docs/PROVENANCE.md)。
