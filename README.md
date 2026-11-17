# Glyph Lens — Android

Glyph Lens 是从 Dynamic Glyph iOS 识别核心迁移而来的原生 Android 应用。**0.4.6 降低共用识别底层的单帧开销，识别节奏与结果不变；0.4.5 增加设备点阵校准的保存、复用与重置，并修正槽位定位延迟和标题局部被误认成地图按钮的问题。** 全流程仍使用纯图像特征，不包含 OCR，不新增识别模型或依赖。

0.4.6 的改动、验证与未证实的省电幅度见 [FRAME_COST.md](Docs/FRAME_COST.md)。以下为 0.4.5 的记录：本轮实现与增量验收见 [DEVICE_CALIBRATION.md](Docs/DEVICE_CALIBRATION.md)，用户八条规则见 [Plan.md](Plan.md)。本轮 10 项重点 JVM 用例、两段真实素材回放、一项正式包校准流程及五张重点截图人工核对均已通过，v0.4.5 已发布。旧版本的通过数量、APK 大小和 CPU 数字不作为 0.4.5 结果。

## 使用与设置

打开 App，按系统要求完成屏幕共享等必要授权后，应用直接识别。共享范围继续选择**整个屏幕**；不需要选择模式或点击待命图标。

第一次进入 Glyph 界面并出现空白的 11 点阵时，应用自动校准当前设备并保存。以后先按保存的位置做局部确认，再复用线段采样位置。保存的是相对屏幕的点阵几何，不是机型名单或固定分辨率；实际点阵移动或比例变化后，局部确认失败会重新定位。

设置页提供“设备识别校准”状态和“重置识别校准”按钮。重置只清除识别校准，当前共享无需重新授权；下次出现空白点阵时重新校准。窗口与画布设置不受影响。

| 设置 | 默认 | 行为 |
| --- | --- | --- |
| 返回地图后自动隐藏 | 开启 | 识别到既有五个地图目标之一时隐藏窗口，后台继续识别 |
| 显示悬浮窗 | 开启 | 允许在识别及预测结果就绪时显示窗口 |

两个开关独立，已有明确保存的值保留。关闭“显示悬浮窗”后，自动隐藏不会将它重新打开。通知与明确停止沿用现有控制，停止结束当前共享。

## 构建与交付

当前源码版本为 **0.4.6 / versionCode 10**，生产源码 `51709f4`。签名 APK 为 **417,789 字节，约 0.418 MB**。沿用 Kotlin、Views/Canvas、Gradle/R8 与现有签名发布流程，保持自适应采样节流。0.4.6 的验证覆盖新旧实现差分、单元测试、Debug 真实采集 4 项和正式包 3 项黑盒流程；**设备上未测出省电幅度**，不下载发布包验 SHA。

交付目标仍是 Android 10 / API 29 以上的 arm64、竖屏整屏共享；不要求 root、无障碍服务或麦克风。校准依据当前画面，不为每个品牌或机型添加参数；本轮不声称已实测所有 Android 设备、横屏、分屏或单应用共享。

交付入口：[v0.4.6 Release](https://github.com/Ingress-Reborn/Dynamic-Glyph-Android/releases/tag/v0.4.6) · [Glyph-Lens-0.4.6-arm64.apk](https://github.com/Ingress-Reborn/Dynamic-Glyph-Android/releases/download/v0.4.6/Glyph-Lens-0.4.6-arm64.apk)。0.4.6 只发布到 origin，未同步 [szres/main](https://github.com/szres/dynamic-glyph-android)。完整记录见 [FRAME_COST.md](Docs/FRAME_COST.md)，0.4.5 验收记录见 [DEVICE_CALIBRATION.md](Docs/DEVICE_CALIBRATION.md)。

## 历史与工程资料

| 路径 | 用途 |
| --- | --- |
| [FRAME_COST.md](Docs/FRAME_COST.md) | 当前 0.4.6 单帧识别开销优化与验证 |
| [DEVICE_CALIBRATION.md](Docs/DEVICE_CALIBRATION.md) | 0.4.5 设备校准、定位修复与增量验收 |
| [Plan.md](Plan.md) | 八条用户规则与本轮约束 |
| [AUTO_START.md](Docs/AUTO_START.md) | 0.4.4 自动启动、窗口默认设置与验收记录 |
| [FEATURE_ONLY.md](Docs/FEATURE_ONLY.md) | 0.4.3 全流程去 OCR、原始验收与发布记录 |
| [VISUAL_COMPLETION.md](Docs/VISUAL_COMPLETION.md) | 0.4.2 地图隐藏、性能及发布历史 |
| `app/`、`recognition/` | Android 平台层与纯 Kotlin 识别核心 |
| `Apps/`、`Packages/`、`Tools/` | 既有 iOS 参考与研发资料，不进入 Android 包 |

历史测试、APK 大小和 CPU 数字只代表原版本。iOS 源工程不修改。

## 许可证与来源

本仓库代码是 **source-available（源码可读）**，使用 [PolyForm Noncommercial License 1.0.0](LICENSE)。许可证原文及其中的 `Required Notice:` 保持不变；具体使用、修改和分发条件以许可证为准。

原项目名称与图标、第三方游戏素材及商标的说明见 [NOTICE.md](NOTICE.md)，贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。Android 工作名称为 Glyph Lens，不改变原代码版权和许可条件。

部分真实截图、录屏和参考测试输入未随仓库分发。缺少这些素材的测试需明确标为跳过，不能算作识别通过；现有 CSV 与图像素材来源见 [测试素材说明](Docs/PROVENANCE.md)。
