# Glyph Lens 0.4.4：授权后直接识别

更新日期：2026-11-09。

**0.4.4 / versionCode 8 删除省电模式和待命悬浮图标。打开 App，完成必要的系统授权后直接开始识别，不再选择模式或点击图标启动。** 本轮只调整启动、设置与窗口入口；已有纯特征识别、预测、地图五目标和自适应节流保持。

生产源码 `3304554`，增量构建、一项正式 APK 自动启动流程及五张重点截图人工核对已通过，已发布 v0.4.4。八条用户规则见 [Plan.md](../Plan.md#2-强制通用规则)，0.4.3 的原始实现和实测保留在 [FEATURE_ONLY.md](FEATURE_ONLY.md)。

## 行为与设置

- 打开 App 后进入现有必要授权流程，系统屏幕共享等授权完成后自动启动识别。保留系统授权，不绕过或模拟同意。
- 删除实时/省电模式选择、省电暂停/恢复及待命图标入口，只保留直接识别流程；停止仍结束当前共享。
- “返回地图后自动隐藏”和“显示悬浮窗”移到设置，两个开关默认值都为 **true**。
- 默认值只作用于尚无该项保存值的用户。已有明确保存的 true/false 继续生效，不强行覆盖旧偏好。
- 两开关独立。关闭“显示悬浮窗”后，自动启动、地图隐藏规则或识别结果就绪都不能将其重新开启；识别仍按原流程运行。

| 显示悬浮窗 | 返回地图后自动隐藏 | 结果 |
| --- | --- | --- |
| 开 | 开 | 按既有结果就绪规则显示，返回地图后隐藏 |
| 开 | 关 | 按既有规则显示，不因地图目标自动隐藏 |
| 关 | 任意 | 不自动显示悬浮窗，自动隐藏开关不反向开启显示 |

画布、通知、CSV、纯特征阶段、预测与原有自适应节流不重写，不增加新的模式或等待状态。不修改 iOS 源工程，不恢复 OCR 或语言设置。

## 仅增量验收

沿用仓库现有工具和流程，只检查本次改变的路径，不重新验证未改变的识别算法或性能。

| 本轮检查 | 结果 |
| --- | --- |
| 一次增量构建 | `:app:assembleRelease` 与 `:app:assembleDebugAndroidTest` 成功，用时 35 秒；仅附带原有 lintVital |
| 正式 APK | `Glyph-Lens-0.4.4-arm64.apk`，409,597 字节，约 0.410 MB |
| 一项正式自动启动流程 | 通过：授权后无需开始按钮；两个默认开关、独立性及采集中设置生效均符合预期 |
| 同流程五图两轮与停止 | 下一轮无需重启或重新授权即可显示并回地图隐藏，明确停止已验证，设备无屏幕共享 |
| 五张重点截图人工核对 | 通过：设置双开关开启，首页无模式和两开关、可见停止；两轮 `question / less / forget / all / lie` 均为正确实线且无 X 行，下一轮地图稳定画面有真实按钮且无 Glyph 浮层 |

本地证据为 `build/v044-validation/build.log`、`release-auto-start.log`、`package.json`；正式流程报告位于 `build/v044-validation/release-ui/real-recording-auto-start-auto-hide-1794216478929`。

没有运行旧 JVM 用例、全单元、完整 Lint、重复 Debug 设备流程、多尺寸矩阵或 CPU 对比，没有下载 APK 验 SHA，也未引入新工具或流程。旧测试及性能数据不继承为 0.4.4 验收。

## 交付与边界

交付入口为 [v0.4.4 Release](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.4) 和 [Glyph-Lens-0.4.4-arm64.apk](https://github.com/vhu231/Dynamic-Glyph-Android/releases/download/v0.4.4/Glyph-Lens-0.4.4-arm64.apk)。生产源码已推送原仓库 main，v0.4.4 标签指向 `3304554`，Latest Release 与 409,597 字节 APK 已发布。按既有安排将最终代码和文档同步 [szres/dynamic-glyph-android](https://github.com/szres/dynamic-glyph-android) 的 main，保留目标自身历史、不 force push。

平台仍为既有 Android 10 以上、arm64 交付目标和竖屏整屏共享。本轮不新增全机型适配承诺，不把旧版 CPU 或包大小作为新版本实测。
