# Glyph Lens — Android

Glyph Lens 是从 Dynamic Glyph iOS 识别核心迁移而来的原生 Android 应用。**0.4.3 将全流程改为纯特征识别，移除全部 OCR、ML Kit、四类模型和游戏语言设置。** 阶段由槽位高亮变迁、点阵和输入计时条驱动；保留 Glyph 识别与序列预测、实时/省电模式及可选地图自动隐藏。

当前实现与增量验收见 [FEATURE_ONLY.md](Docs/FEATURE_ONLY.md)，用户八条规则见 [Plan.md](Plan.md)。生产源码 `6fea790` 的 **11 项重点 JVM 测试、2 项正式 APK 双轮流程和 10 张关键截图人工核对通过**。本地 APK 仅 **417,845 bytes（约 0.418MB）**，比 0.4.2 小 **97.36%**。本轮没有重跑无关全套、做 CPU 对照或上传/下载 APK 验 SHA；0.4.2 记录继续保留为历史。

## 当前行为

- 阶段判断只使用槽位高亮及变化、点阵和输入计时条，不读取标题文字，不需要游戏语言配置。
- 11 节点标定、固定形状采样和跨帧确认保持；输入期间冻结本轮序列，不把玩家笔画当作新的展示 Glyph。
- 连续已确认前缀加唯一预测覆盖整组，或整组全部真实确认后，每轮自动显示；确认、观察和预测保持不同呈现。
- 实时授权后自动后台识别，初始不显示图标或窗口；省电模式点击悬浮图标开始。
- “返回地图后自动隐藏”默认关闭。开启时窗口只有可拖动画布，五个独立地图按钮任一出现即可隐藏，不代表游戏成功。
- 地图上通知手动显示后，按钮仍可见就再次隐藏。关闭选项时实时 × 隐藏但继续识别，省电 × 停止并回图标；通知提供显示和停止。
- 画布大小/清晰度、内置或导入 CSV 保留。去 OCR 不重写预测逻辑或表数据。

完成页、分数、掉落、输入正确性、历史统计、数据库、云同步、录像/音频等业务继续不在范围内。

## 使用与平台

交付目标仍是 Android 10 / API 29 以上的 arm64、竖屏整屏共享。选择模式和自动隐藏选项后开始，授权悬浮窗并选择**共享整个屏幕**。实时模式立即识别，省电点击图标后开始；模式与自动隐藏选项在完全停止共享时修改。

阶段识别不再需要选择游戏语言。下一轮由新的槽位与点阵展示特征恢复，自动隐藏不重建采集会话或重复请求授权。应用不需要 root、无障碍服务或麦克风。

当前适配边界仍为竖屏整屏共享；横屏、方形、分屏和单应用共享未全面认证。去掉语言依赖不等于所有 Android 设备和游戏布局已经验证。

## 本轮构建与验证

0.4.3 使用 versionCode 7。现有流程中的 8 项 Tracker、1 项 SessionPipeline、2 项 GlyphCaptureEngine 测试 **11/11 通过，无跳过或失败**；一次增量构建完成 Release 和测试 APK。正式 runner 的实时遮标题五图两轮、省电新三图两轮 **2/2 通过**，本轮实际查看 10 张关键截图。五图后两项正确，确认与预测样式保持；新三图在第三项真实确认后显示，两项均回地图隐藏并彻底停止。

ML Kit、四类模型、阶段 OCR 与专属 ProGuard 规则已移除。本地 `Glyph-Lens-0.4.3-arm64.apk` 为 **417,845 bytes**，检查无 OCR 依赖/资产及任何原生库；保留 NDK arm64 配置，没有为强制 ABI 添加空库。本轮未跑重复 Debug 设备流程、全单元、主动完整 Lint 或多尺寸矩阵；Release 自带 lintVital 随构建执行，没有额外工具或哈希下载流程。

生产源码 `6fea790` 已进入原仓库 main，v0.4.3 标签指向该生产提交，[v0.4.3 Release](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.3) 已发布为 Latest，[0.4.3 APK](https://github.com/vhu231/Dynamic-Glyph-Android/releases/download/v0.4.3/Glyph-Lens-0.4.3-arm64.apk) 已上传。未为发布校验下载 APK 或计算 SHA；目标仓库同步沿用现有流程。

0.4.2 的 CPU 36.46% 降幅属于旧版本对照。0.4.3 不复做旧 CPU 对比，也不根据删除 OCR 推算新百分比或实体手机温度/续航变化。

## 已保留的 0.4.2 交付记录

下列是上一已发布版本的既有结果，不是 0.4.3 验收或本轮待重复的流程。完整测试、CPU 和证据边界见 [VISUAL_COMPLETION.md](Docs/VISUAL_COMPLETION.md)。

0.4.2 使用 versionCode 6。Release 继续启用 R8、仅打包 arm64，并精确保留 ML Kit 的三个实际反射构造入口。正式产物为 `app/build/outputs/apk/release/Glyph-Lens-0.4.2-arm64.apk`，**15,824,427 bytes**（约 15.82MB / 15.09MiB），比 0.4.1 增加 16,384 bytes；SHA-256 为 `e5f381a0c28b8dd15bbbfc26cd131a3365a27331c1c926998b7446193e539e27`。v3 签名、ZIP/ELF 16KiB、原始表哈希、无 INTERNET 权限及包内源码 `6fd03bc9` 已核验；同一签名包的 9 项流程、实际反射构造、完全停止和人工图形核对通过。

已更新 [vhu231/Dynamic-Glyph-Android](https://github.com/vhu231/Dynamic-Glyph-Android) 的 main 并发布正式最新版 [v0.4.2 Release](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.2)，可下载 [arm64 APK](https://github.com/vhu231/Dynamic-Glyph-Android/releases/download/v0.4.2/Glyph-Lens-0.4.2-arm64.apk)。发布后重新下载的文件为 15,824,427 bytes，SHA-256 与上述受测包及发布端 digest 一致。[szres/dynamic-glyph-android](https://github.com/szres/dynamic-glyph-android) 的 main 已同步，目标保留自身旧历史，已核验两边源码树一致，未使用 force push。

## 仓库与历史资料

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 首页、设置、采集、悬浮窗和表导入 |
| `recognition/` | 特征阶段追踪、Glyph 识别/预测与地图目标检测 |
| [FEATURE_ONLY.md](Docs/FEATURE_ONLY.md) | 0.4.3 全流程特征与增量验收 |
| [Plan.md](Plan.md) | 当前范围、八条用户规则及验收约束 |
| [VISUAL_COMPLETION.md](Docs/VISUAL_COMPLETION.md) | 0.4.2 地图自动隐藏、CPU 与已发布结果 |
| [REALTIME_VISIBILITY.md](Docs/REALTIME_VISIBILITY.md) | 0.4.1 原始行为、验证与发布 |
| [CALIBRATED_VISION.md](Docs/CALIBRATED_VISION.md) | 0.4.0 固定采样与负载历史 |
| `Apps/`、`Packages/`、`Tools/` | iOS 参考与研发资料，不进入 Android 包 |

CSV 的原始换行与内容保持。历史文档中的 OCR、模型、语言选项与测试数据按原版本理解，不覆盖本版去 OCR 范围。

## 许可证与来源

本仓库代码是 **source-available（源码可读）**，使用 [PolyForm Noncommercial License 1.0.0](LICENSE)。许可证原文及其中的 `Required Notice:` 保持不变；具体使用、修改和分发条件以许可证为准。

原项目名称与图标、第三方游戏素材及商标的说明见 [NOTICE.md](NOTICE.md)，贡献约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。Android 工作名称为 Glyph Lens，不改变原代码版权和许可条件。

部分真实截图、录屏和参考测试输入未随仓库分发。缺少这些素材的测试需明确标为跳过，不能算作识别通过；原输入来源记录见 [测试素材说明](Packages/GlyphRecognition/Tests/GlyphRecognitionTests/Fixtures/PROVENANCE.md)。
