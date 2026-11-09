# Glyph Lens 0.4.0：标定后固定采样与持续识别优化

更新日期：2026-11-09。

**本轮目标是在保留 Glyph 确认、预测和模式操作的前提下，减少持续识别时的扫描、图像转换与分配。** Glyph 形状此前已经使用传统视觉算法：检测节点、读取线段证据、匹配形状表。0.4.0 改的是标定后如何读取画面，不是首次用传统视觉取代 Glyph OCR，也不是完全移除标题文字识别。

本轮生产源码 `cd83b72` 已推送。JVM、Debug/Release 构建与 Lint、6 项真实采集、19 项 Android 界面/平台测试及另两组输出尺寸实采通过；同条件五图冷启动对比中，CPU 时间减少 40.65%，具体数据与限制见第 5 节。实际签名 Release 前 3 项流程与第 4 项测试等待修复后的单项复测通过，逐 Glyph、预测及静态结果截图人工核对通过。0.3.0 引入的两种模式及自动停止继续保留，见 [POWER_MODES.md](POWER_MODES.md)；该版中间数据不作为本版证据。用户七条规则原文见 [Plan.md](../Plan.md#2-强制通用规则)。

## 1. 从实际画面建立采样位置

首次定位仍使用真实 11 节点阵列及槽位行建立几何和内容区域；原 iOS 比例是初始参考。标定成功后，按照本次节点几何建立可复用的线段、背景和墨迹采样位置，后续帧读取这些位置来生成形状证据。这里的“固定”是固定于当前标定，不是为某一型号写死屏幕像素坐标。

形状仍与内置或导入的 Glyph 表匹配，连续重复项保留，漏槽不前移；唯一可见候选仍需跨帧稳定和展示边界才能确认。预测只使用连续已确认前缀与槽位数，歧义时不伪造唯一后缀。本轮不靠提高某个候选的可信度或降低确认标准来掩盖采样失败。

位置缓存与当前证据分开：缓存能减少搜索或帮助摆放窗口，但不能单凭旧位置宣布当前画面仍处于展示/输入阶段。尺寸、内容位置或标定有效性变化时，重新定位并更新采样计划，不维护品牌、机型、分辨率参数名单。

## 2. 减少哪些重复工作

| 部分 | 0.4.0 的处理 | 必须保留的边界 |
| --- | --- | --- |
| Glyph 线段与墨迹 | 标定后复用固定采样计划，读取边、附近背景和墨迹探针，减少逐帧大区域扫描与位置计算 | 墨迹和未解释墨迹变成采样估计，须回归杂线、渐隐笔迹、歧义和真实五图，不能把估计量当作完整像素统计 |
| 颜色与灰度 | 在同一已拥有的 RGBA 字节上提供按需读取视图，只转换读取到的像素 | 不声称整个采集过程零复制；采集缓冲区和异步 OCR 使用的数据生命周期仍需正确 |
| 节点复验 | 在已知 11 环附近局部检查，标定仍有效时复用位置，减少重复全图搜索 | 局部验证失败或标定失效时仍可重新搜索；仅保留旧几何不等于获得新像素证据 |
| 槽位与计时条 | 复用已定位的布局与区域，在必要时重新定位，避免反复扫描同一片画面 | 每帧内容和阶段仍由当前像素决定，不能冻结旧槽位状态来降低开销 |
| INPUT 阶段 | 当前计时条、布局及像素证据支持输入期、序列已冻结时，跳过不需要的 Glyph 形状与标题 OCR | 保留必要的阶段/槽位观察；离开有效条件后恢复相应读取，不能让下一组无法启动 |
| hack 内活动门控 | 已经处于 hack 时跳过全屏活动门控扫描 | 未进入 hack 或需要重新判断活动时，仍使用相应门控策略 |

这些改变减少每帧工作的范围，不通过不断累积待处理截图降低负载。继续保留约 20Hz 的前置帧准入和读取最新帧的策略，处理落后时不排队重放旧全屏图像。

**PRESENTING 阶段仍按 iOS 正常 100ms、系统受限 200ms 的资格间隔提交标题 OCR。** 展示期的阶段证据关系到第 4、5 项和最后确认边界，不能为追求低 CPU 擅自删掉。其他需要文字证据的命令/完成等阶段读取也保留；有当前像素支持的 INPUT 跳过标题读取，不代表应用完全不使用 OCR。单个在途请求、真实源帧时间和已有标题时效规则仍适用。

主要实现位置：

- [GlyphFrameRecognizer.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/GlyphFrameRecognizer.kt)：固定线段/墨迹采样计划及形状证据。
- [ImageFrame.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/ImageFrame.kt)、[LuminanceImage.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/LuminanceImage.kt)、[ColorImage.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/ColorImage.kt)：同源 RGBA 按需视图。
- [GlyphFrameSession.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/GlyphFrameSession.kt)、[NodeArrayDetector.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/NodeArrayDetector.kt)：标定生命周期、局部环复验与重新搜索。
- [GameUILayoutLocator.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/GameUILayoutLocator.kt)、[SlotStripReader.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/SlotStripReader.kt)：槽位/计时条布局与当前像素读取。
- [GlyphCaptureEngine.kt](../app/src/main/java/org/szres/glyphlens/capture/GlyphCaptureEngine.kt)：帧调度、INPUT 跳过策略、标题 OCR 和活动门控。

## 3. 保留的用户操作

默认实时模式获得系统共享授权后立即识别；省电模式先显示可拖动的 48dp 悬浮图标，点击才识别。两种模式使用同一识别管线，都可以点击识别窗 × 停止采集和 OCR、清空结果并回到图标。图标待命与识别之间切换复用同次共享授权，不重复弹出同意框。

“本轮识别完成后自动停止”默认关闭。开启后只有**当前会话已观察到本轮展示（`snapshot.hackID > 0`），进入 `INPUT` 且 `isFrozen`**，才停止采集、引擎、OCR 和刷新并保留静态结果窗。结果窗提供“开始识别”，点击后清空旧内容并创建新会话；× 清空结果并收回图标。

在上一轮 INPUT 界面点击开始时，新会话 `hackID == 0`，应继续等待下一轮，不能因画面处于输入阶段就立即停在空白结果上。自动停止也不要求全部槽位确认：缺项、未确认项和预测保留原状态，不能补齐或伪造成功。

首页或通知停止才彻底释放共享和窗口。图标待命、保留静态结果时均停止图像接收，但授权与前台通知仍保留，因此系统共享指示可能继续显示。详细状态与资源说明见 [模式文档](POWER_MODES.md#2-图标待命与保留结果时停止的工作)。

## 4. 范围与提交约定

工程保持轻量原生 Kotlin、Android Views/Canvas，仅打包 `arm64-v8a`，Release 使用 R8。当前交付目标仍为 Android 10 / API 29 及以上、竖屏整屏共享；横屏、方形、折叠设备横屏以及单应用共享/分屏坐标映射未因此自动获得认证。

四类离线 OCR 模型保留，本轮不增加 OpenCV、另一套推理后端或跨平台 UI。游戏结算/分数、装备、历史、云同步、录像等被删除业务不恢复。包体积与持续识别 CPU 是两个不同结果：少读取像素不代表 OCR 模型从 APK 中消失。

开始本轮优化前，现有版本已经以检查点 **`216eb07`** 推送到 `origin`（`vhu231/Dynamic-Glyph-Android`），分支为 **`codex/glyph-calibrated-vision`**。这是用户要求的代码保存点，不声称其最终验收已全部通过。

性能快照稳定性修复 **`9448027`** 与本轮生产源码 **`cd83b72`** 随后也已推送到同一分支。Release 测试开关等待修复 **`d87da7a`** 也已推送，仅修改测试，生产 APK 和哈希不变。本节 APK 对应 `cd83b72`；后续测试与验收文档提交与 APK 源码版本分开记录。

按用户要求，**每完成一组改动并通过相关验证后，提交并推送当前分支**。最终记录实际提交、推送与 APK 对应关系。外部录屏、私有素材、`build/` 目录和 APK 等构建输出不提交；本地测试证据可以链接到被忽略的构建目录，并注明它们不是随仓库提供的公开产物。

## 5. 0.4.0 验收记录

本节记录生产源码 `cd83b72` 的构建与产物，以及对应真实实现测试；Release 测试等待修复为 `d87da7a`。旧包或中间构建的数字不迁移为本轮结论。证据链接位于被忽略的本机 `build/vision-validation/`，不随 Git 仓库提交。

### 构建与 APK

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 识别 JVM | 231 项，3 项既有私有素材跳过、0 失败；跳过不算识别通过 | [build.log](../build/vision-validation/build.log) |
| 应用 JVM | 82 项通过 | [build.log](../build/vision-validation/build.log) |
| Debug/Release 构建与 Lint | 通过，Lint 0 error、30 warning | [build.log](../build/vision-validation/build.log) |
| 真实采集 | 6 项通过，含持续两轮、两种模式及自动停止恢复 | [live-final-tests.log](../build/vision-validation/live-final-tests.log) |
| 增补输出尺寸 | 720×1600 / 320dpi、1080×1920 / 420dpi 各完成五图及启停检查 | [720 日志](../build/vision-validation/matrix/720x1600/test.log)、[1080 日志](../build/vision-validation/matrix/1080x1920/test.log) |
| Android 界面/平台 | 19 项通过，覆盖浮窗/窄窗、偏好、首页、设置、裁剪、标题和表读取 | [ui-tests.log](../build/vision-validation/ui-tests.log) |
| 实际签名 Release 界面 | 首次运行前 3 项通过；第 4 项在测试清理失败，修复测试等待后单项复测通过，详见下节 | [首次日志](../build/vision-validation/release-ui-tests.log)、[复测日志](../build/vision-validation/release-auto-stop-retest.log) |
| Release 截图人工核对 | 五图、三图、预测、省电图标/恢复、自动停止两轮静态结果通过 | [manual-review.md](../build/vision-validation/release/manual-review.md) |

固定采样和按需视图的 JVM 测试使用真实实现，覆盖固定计划复用、部分短线、几何变化、空阵列移动、标定失效、RGBA 入口以及槽位/计时条当前状态读取。3 项私有素材跳过项不算已验证真实素材。

产物：[Glyph-Lens-0.4.0-arm64.apk](../app/build/outputs/apk/release/Glyph-Lens-0.4.0-arm64.apk)。

| 项目 | 值 |
| --- | --- |
| APK 源码 | `cd83b72`，已推送到 `codex/glyph-calibrated-vision` |
| 大小 | **15,808,043 B** |
| SHA-256 | `c1cdece2dc1dd2049da1ce2bddde33e0a7a26f7f70c2edc6001a084451fc7f60` |
| 架构与优化 | 仅 `lib/arm64-v8a`，Release 开启 R8 |
| 权限 | 无 INTERNET、RECORD_AUDIO |
| 签名与对齐 | 本地 debug key 的 v3 测试签名验证通过，16KiB ZIP 对齐通过；不是生产发布签名 |

### 五图、持续运行与恢复

真实五图录屏为用户提供的 `C:/Users/YuanChu/Downloads/Screenrecorder-20261108-133013.mp4`。本轮完整序列为 **`question / less / forget / all / lie`**，第 4、5 项正确确认；前两项确认 `question / less` 后，唯一预测为 **`forget / all / lie`**，输入期保持冻结。三图样本 **`nourish / xm / portal`** 也通过，前两项确认后预测 `portal`。

持续识别专门使用**同一个采集会话 `71286549199089`**：本轮 `hackID` 从 1 推进到 2，两轮都完成五图确认及前两项后的预测，上一轮前缀清空，不重启服务或创建新会话来冒充连续识别。

| 本轮 Debug 报告 | 覆盖内容 |
| --- | --- |
| [real-recording.json](../build/vision-validation/live-capture-tests/real-recording.json) | 实时五图、手动 × 后待命、图标恢复和清旧会话 |
| [real-recording-093906.json](../build/vision-validation/live-capture-tests/real-recording-093906.json) | 实时三图及完全停止的服务路径 |
| [real-recording-continuous.json](../build/vision-validation/live-capture-tests/real-recording-continuous.json) | 同一采集会话、两轮五图、两次预测和下一轮前缀清理 |
| [real-recording-power-saving.json](../build/vision-validation/live-capture-tests/real-recording-power-saving.json) | 省电初始图标待命、五图、× 停止与恢复后再次识别 |
| [real-recording-auto-stop.json](../build/vision-validation/live-capture-tests/real-recording-auto-stop.json) | 实时自动停止、静态结果保留、结果窗开始、旧 INPUT 等待下一轮、× 收回图标 |
| [real-recording-power-saving-auto-stop.json](../build/vision-validation/live-capture-tests/real-recording-power-saving-auto-stop.json) | 省电与自动停止组合、保留结果、旧 INPUT 恢复及下一轮识别 |

两种自动停止测试都在旧 INPUT 界面点击结果窗开始按钮：新会话运行并清空旧结果，等待约 2 秒仍未空白自动停止，然后成功识别下一轮。图标待命、× 后待命及静态结果期间检查采集、分析和 OCR 不再增长，保留结果与清空结果分别验证；同次授权恢复不弹第二次同意框。

上述自动断言读取真实采集和识别器结果，不把业务逻辑复制进测试，不靠重复播放修补同一轮失败。Debug 覆盖完全停止的服务操作路径；下述 Release 界面测试还实际通过首页按钮停止并检查浮窗移除，通知与系统停止入口的实际点击仍未验证。

### 三种共享输出尺寸

| 共享输出与密度 | 五图、前缀预测、输入期冻结 | × 后待命与恢复 | 证据 |
| --- | --- | --- | --- |
| 1216×2688 / 440dpi | 5/5，`question / less` 后预测 `forget / all / lie` | 3 秒采集/分析/OCR 不增长，同授权恢复、完全停止 | [报告](../build/vision-validation/live-capture-tests/real-recording.json) |
| 720×1600 / 320dpi | 5/5，相同前缀预测及冻结 | 3 秒计数保持，同授权恢复、完全停止 | [报告](../build/vision-validation/matrix/720x1600/five-glyph.json)、[日志](../build/vision-validation/matrix/720x1600/test.log) |
| 1080×1920 / 420dpi | 5/5，相同前缀预测及冻结 | 3 秒计数保持，同授权恢复、完全停止 | [报告](../build/vision-validation/matrix/1080x1920/five-glyph.json)、[日志](../build/vision-validation/matrix/1080x1920/test.log) |

720 组的稳定待命计数为采集 986→986、分析 250→250、OCR 41→41；1080 组为 993→993、261→261、48→48。两组均清空旧快照、显示待命图标，并在本次共享授权内创建新的识别会话。

**这验证的是模拟环境中的三种共享输出尺寸。** 播放器使用 `videoScaleMode = fitCenter`，将同一原始录屏等比例放入输出画面并保留留白，应用通过真实系统共享采集；游戏本身没有在这些尺寸中重新布局。因此它支持缩放和留白输入的通用识别，不等同于所有屏幕比例、真实游戏布局或实体机型均已认证。

### 实际签名 Release 界面旅程

以下测试运行在上述**相同签名 APK**上，使用真实系统授权、视频播放和应用界面；不为测试增加生产 R8 keep 规则。自动检查阶段变化、输入或结果保持至少 3 秒、图标/开始按钮、同授权恢复及首页完全停止。逐 Glyph 形状、预测显示与完整静态结果由截图人工核对，不能将这些界面流程写成 Release 逐 Glyph 自动断言。

| 旅程 | 自动流程结果 | 报告 |
| --- | --- | --- |
| 实时五图 | 首次整套运行通过 | [报告](../build/vision-validation/release/real-recording-realtime-1794188452415/report.json) |
| 实时三图 | 首次整套运行通过 | [报告](../build/vision-validation/release/real-recording-093906-realtime-1794188471161/report.json) |
| 省电五图与图标恢复 | 首次整套运行通过 | [报告](../build/vision-validation/release/real-recording-power-saving-1794188486616/report.json) |
| 自动停止、保留结果与再次开始 | 修正测试清理等待后，单项复测通过 | [复测报告](../build/vision-validation/release/real-recording-realtime-auto-stop-1794188681689/report.json) |

首次整套运行**没有一次全过**：第 4 项已完成识别、保留结果和再次开始，但在 `finally` 恢复原自动停止设置时，测试点击后立即读到了旧 checked 状态而失败。修复仅在 Java 测试中等待开关的真实 checked 值，并增加自动停止单项入口；随后在同一 APK 上复测，日志为 `OK (1 UI journeys)`。该测试修复已以 `d87da7a` 提交并推送，生产代码、APK 与 SHA-256 均未改变。原失败记录保留在 [release-ui-tests.log](../build/vision-validation/release-ui-tests.log)，复测见 [release-auto-stop-retest.log](../build/vision-validation/release-auto-stop-retest.log)。

逐张截图人工核对已完成，见 [manual-review.md](../build/vision-validation/release/manual-review.md)：实时五图和三图均正确；五图中已确认、当前观察及预测显示正确；省电初始图标与恢复后的五图正确；自动停止首次及重新开始后的结果都保留完整五图，并显示可用的开始和关闭按钮。此人工核对补充上述自动流程检查，不冒充 Release 逐 Glyph 自动断言。

### 同条件冷启动 CPU 对比

比较使用相同设备、相同五图测试和冷启动条件，基线与本轮都正确识别完整五图、预测及输入期冻结。最终优化数据使用 **[five-glyph-final-cold.json](../build/vision-validation/optimized/five-glyph-final-cold.json)**，不用较早的优化中间观测替代它。基线见 [five-glyph.json](../build/vision-validation/baseline/five-glyph.json)，运行记录见 [cold-comparison.log](../build/vision-validation/cold-comparison.log)。

| 指标 | 优化前基线 | 0.4.0 最终冷启动 |
| --- | --- | --- |
| 会话时间 | 17.398951311 秒 | 17.339784178 秒 |
| 进程 CPU 时间 | 17.81 秒 | 10.57 秒 |
| 已分析帧 | 244 | 250 |
| 引擎单帧工作平均值 | 24.3ms | 6.48ms |
| Glyph 读取平均值 | 11.5ms | 2.02ms |
| 图像转换 / 视图准备平均值 | 8.07ms | 0.03ms |

这次匹配观测的 CPU 时间减少 **40.65%**：`(17.81 - 10.57) / 17.81`。两次会话长度接近，分析帧数没有减少，活跃阶段仍保持约 20 次/秒的调度。图像准备降到 0.03ms 是因为按需视图不先转换整幅图；实际被采样像素的颜色/灰度转换计入后续读取，不等于转换成本完全消失或采集零复制。

测量环境为 **Android 15 / API 35、NCO_AL00 标识、1216×2688 / 440dpi 的 x86_64 模拟环境**，通过 ARM64 native bridge 执行 arm64 Debug 应用。以上是单次匹配冷启动观测，支持本轮在该环境减少工作量，不能直接推广成所有实体 ARM64 手机都降低同样幅度，也不是手机温度或长期电池续航验收。

图标待命和自动停止后保留结果的 CPU 要与持续识别分开理解；测试采用真实进程累计时间差，不用停止刷新的内部性能表推断零消耗。验证范围仍不包括通知/系统停止入口的实际点击，以及物理 ARM64 手机温度和长期耗电。

本轮结束后已恢复 **1216×2688 / 440dpi**，重新安装上述 **versionCode 4 / 0.4.0 / arm64** 签名 Release；`dumpsys media_projection` 确认为 **null**，没有保留正在进行的屏幕共享。
