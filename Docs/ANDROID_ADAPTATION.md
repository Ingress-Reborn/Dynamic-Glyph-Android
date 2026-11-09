# Android 比例适配与采集负载修正

> **历史记录，非 0.2.0 当前规格。** 本文保存 2026-11-09 精简前的实现、包哈希、矩阵与性能证据；这些结果不能作为精简后的新包验收。0.2.0 已仅保留 Glyph 识别与预测、仅 arm64，并删除本文涉及的结果/Gear/Portal/命令业务。本文的 250ms 标题 OCR 策略已被替代：PRESENTING 与 INPUT 恢复 iOS 正常 100ms、系统受限 200ms 节奏。通用节点/槽位定位、采集节流与窗口避让保留。当前范围与本轮验证见 [GLYPH_ONLY.md](GLYPH_ONLY.md) 和 [Plan.md](../Plan.md)。

更新日期：2026-11-09。

本记录对应用户实际反馈的两件事：部分画面无法识别、反复提示移动窗口；授权屏幕共享后持续高负载、发热。后一项发生在普通识别配置，不以开启调试录像或相册编码为前提。

本次已实现通用竖屏布局定位、窗口避让、采集前置节流和有像素阶段依据的标题 OCR 调度。本轮实采矩阵覆盖 **9 组显示尺寸/density 配置、两段原片共 18 次首次播放，全部通过**。同尺寸、已排除播放器进程的应用 CPU 对比为五 Glyph 114.6%→102.8%、三 Glyph 130.6%→108.5%，100% 表示占用一个 CPU 核心。尚未做物理手机测温和长期功耗测量；不将显示配置矩阵称作全部机型、方向认证，也不以 CPU 降低代替温度测量。

## 1. 修改依据与范围

遵守原实施顺序：先移植 iOS 节点、线条、颜色、布局比例及阶段规则，完成 Android 可运行链路；此次在实际失败后修改采集边界和通用坐标定位。没有按品牌、型号或分辨率建立识别参数名单。

既有识别与预测验收保留在 [GLYPH_PREDICTION.md](GLYPH_PREDICTION.md)。该历史记录中的固定 26% 窗口位置已被本次实际几何避让替代；其旧版本性能数据不作为本次候选版的最终数据。

## 2. 已定位的根因

| 问题 | 具体原因 | 对应修改 |
| --- | --- | --- |
| 节点能找到，槽位或标题读不到 | iOS 布局比例以游戏内容充满画面为前提。内容居中、出现黑边或内容宽度缩小时，直接按整个捕获帧高度取区域会偏离实际槽位、计时条和标题 | 从已检测节点和真实槽位行建立内容坐标，所有相关读取共用该布局 |
| “移动窗口”提示不能直接证明用户挡住了内容 | 读不到槽位也可能来自区域偏差；仅靠移动窗口不能修复错误的裁剪坐标 | 保留未读到与遮挡证据的区别，修正区域定位，不把旧快照当成当前可读证据 |
| 固定位置本身遮挡槽位 | 603×2144 画面中，1333 像素高的原内容垂直居中时，槽位约在 y=547..634；旧 26% 默认位置从约 y=557 开始，确实覆盖槽位 | 以阵列顶部、已定位计时条和实际窗口高度计算空隙，必要时按空隙缩小画布 |
| 授权共享后高刷新率仍有大量图像工作 | 只在识别引擎中限速，不能避免此前的 `Image.planes` 映射、全屏 RGBA 分配和复制 | 在访问像素平面和复制前，按源帧时间执行约 20 Hz 准入 |
| 单张分析帧重复遍历颜色 | 原路径先生成灰度及顶部 RGB，又单独生成全屏 RGB | 同一次转换生成灰度与全屏 RGB，下游复用同一颜色图像 |
| 已进入 Glyph 阶段仍重复检查 Portal | 默认配置计算了 portalCandidate，却未把结果用于节奏；展示和输入阶段仍支付检查成本 | 正结果用于活动判断；已有槽位、计时条、新鲜阵列活动证据或已进入展示/输入阶段时跳过 Portal 检查，确需 Gear 读取时保留 |
| 重复标题识别仍占用 OCR 工作 | 活跃阶段即使已有指标或计时条证明阶段，也持续按原标题节奏提交；当前 ML Kit 接入没有 Vision 的 fast/accurate 开关 | 仅对本帧已有独立像素阶段证明的标题请求采用 250ms 复核，其余继续原规则 |

这些原因来自生产调用顺序、实际失败画面和采集报告。没有将普通共享负载归因于未开启的录像编码，也没有通过放宽 Glyph 确认条件来降低负载。

## 3. 当前生产实现

### 3.1 采集尺寸和前置节流

[CaptureSize](../app/src/main/java/org/szres/glyphlens/capture/CaptureSize.kt) 保持 iOS Broadcast 与已有 Android 回放使用的 **最大 603 像素宽度**，按内容比例计算高度，较小输入不放大。这一宽度是共同的分析输入尺度，不是某款手机的适配参数。

[CaptureFrameAdmission](../app/src/main/java/org/szres/glyphlens/capture/CaptureFrameAdmission.kt) 使用真实源时间，在 49ms 内拒绝重复全屏处理；重复源时间的 IDLE 帧不当成新像素，连续性打断仍进入处理。[CaptureService](../app/src/main/java/org/szres/glyphlens/capture/CaptureService.kt) 在这一步之后才访问 `Image.planes` 和复制像素。关闭省电且正在监听命令通知时，保留 iOS 已有的节流前小区域命令证据路径，不为此复制整张被限速的画面。

`onCapturedContentResize()` 按系统报告的实际内容尺寸更新现有 `VirtualDisplay` 与 `ImageReader` Surface，关闭旧 reader 并重置帧准入时间；较低 API 的显示变化继续走显示监听。Android 官方说明，单应用共享或窗口模式变化会改变捕获内容尺寸，内容与 Surface 比例不同时可能产生黑边，回调应同步调整虚拟显示和 Surface。参见 [MediaProjection：尺寸变化与回调](https://developer.android.com/media/grow/media-projection)。

[ImageFrame](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/ImageFrame.kt) 一次生成灰度和完整颜色图像；节点、槽位、Portal、Gear 与 OCR 共用这一帧的来源时间和表示。没有新建第二套采集、图像池或多级处理队列。

### 3.2 节点与槽位共同定位内容

[GameUILayoutLocator](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/GameUILayoutLocator.kt) 仍先尝试原有布局。需要重新定位时，以实际检测到的 11 节点阵列为锚点，在阵列上方查找既有金色/紫色槽位特征；利用节点的内容中心、宽度以及槽位间距建立 `contentLeft/contentRight`、槽位行和标题区域。候选布局继续交给生产 `SlotStripReader` 验证宽度、中心、间距和高亮约束。

原 iOS 相对间距仍用于宽度归一化的初始区域推算，实际计时轨道再用颜色定位。尺寸或节点几何变化会清掉旧定位；重新搜索有 0.5 秒间隔，未得到节点锚点时不持续进行全屏槽位扩展搜索。搜索上界是实际阵列顶部，不按手机型号指定搜索坐标。

槽位、计时条、`FrameEvidence` 的布局一致性判断与 [标题裁剪](../app/src/main/java/org/szres/glyphlens/ocr/FrameCrops.kt) 使用同一布局，避免只移动槽位区域而仍从旧区域读标题。布局估计不能填造缺失槽位、确认 Glyph 或替换真实计时条证据。

### 3.3 悬浮窗按实际空隙摆放

[OverlayPlacement](../app/src/main/java/org/szres/glyphlens/overlay/OverlayPlacement.kt) 与 [OverlayController](../app/src/main/java/org/szres/glyphlens/overlay/OverlayController.kt) 使用实际窗口高度和完整屏幕坐标：

1. 节点尚未出现时，使用统一的 18%..32% 启动区域约束窗口；放不下时采用底部位置。这只是启动占位，不参与任何识别判断。
2. 得到阵列后，以阵列顶部和窗口高度先挪开可能遮挡槽位的窗口。槽位布局能提供计时条位置估计时，可先用于摆放；检测到真实计时条后再完成定位。
3. 窗口放在计时条底部与阵列顶部之间，留出 8dp 间距。画布过高时按可用空隙等比缩小，保留必要控制区域；空间进一步收窄时不把已经缩小的窗口突然放大回节点区域。
4. 等待实际布局完成后才移动窗口，校正 WindowManager 坐标相对完整屏幕的状态栏偏移。
5. 用户实际拖动后，本次窗口生命周期内停止自动改位置和依据新识别结果缩放，隐藏/恢复不会夺回用户的位置。
6. 显示宽、高或 density 变化时，尚未被用户拖动的窗口可重新进行默认摆放和尺寸计算；手动位置仍优先，普通识别帧不会反复重置摆放状态。

`timerBottomEstimateFraction` 明确只服务于窗口摆放，不能作为 `hasInputTimerBar`、阶段或 Glyph 确认证据。此区分避免 UI 避让估计反过来污染识别。

### 3.4 保留 iOS 的采样与阶段语义

[GlyphCaptureEngine](../app/src/main/java/org/szres/glyphlens/capture/GlyphCaptureEngine.kt) 将上一张实际分析帧的槽位、计时条、新近空阵列匹配、Portal/结果等正证据送入活动节奏控制。空阵列匹配仅在本连续性 epoch 内、验证时间不超过 **1 秒**时算活动证据；可继续用于识别的旧几何缓存不等于当前屏幕仍活跃。每张新分析帧重算活动证据，断帧或源时间重置不借用旧 epoch 保活。

[PresentationTracker](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/session/PresentationTracker.kt) 保留原有规则：先有无高亮空阵列前奏，再有首槽高亮，才建立独立的指标阶段锚点；之后仍检查槽位几何和高亮推进。已经观测展示的 hack，在当前有效校准和真实计时条下进入 INPUT 并冻结序列。Redo 只改变玩家输入位置，不把玩家笔画加入展示序列。新鲜 command/completed 标题仍优先。

### 3.5 Android 标题 OCR 的明确差异

iOS 原标题间隔为活跃 hack 中 0.1 秒、受限时 0.2 秒；英文使用 Vision `.fast`，其余游戏语言使用 `.accurate`。当前 Android ML Kit 适配器按语言选择并复用模型，没有同义的 fast/accurate 档位。

Android 在省电模式下先接受本帧已完成 OCR 与当前像素证据，然后读取 tracker 的只读 `phaseSupportedByCurrentPixels`：

| 当前证据 | Header-only 调度 |
| --- | --- |
| 本帧由原有指标锚点或真实 INPUT_TIMER 分支独立证明阶段 | 复核间隔至少 0.25 秒 |
| 未建立锚点、槽位/有效校准/计时条不足以证明当前阶段、矛盾 command/completed 标题 | 恢复原间隔；hack 内通常 0.1 秒，受限时 0.2 秒 |
| hack 外或安静画面 | 仍使用原 0.5 秒、受限倍率与安静画面抑制规则 |
| 关闭省电 | 保持原正常分析与标题调度 |

标记在每次 `accept` 开始、阶段失效和 reset 时清除，只复用既有阶段分支，不增加新的猜测条件。失去证据的本帧立即恢复原提交资格，仍遵守单个在途请求和原有 lane 最小间隔。`headerInterval` 只影响 header-only 请求，结果、Gear、Portal、命令的优先级和提交规则不随之降频。

**来源时间和过期规则不变：** `GameHeaderReader` 仍校验返回标题的 `sourceTime` 等于请求源时间，读取时最多接受 **0.35 秒**来源年龄；tracker 原有 **0.75 秒**阶段 grace 保留。250ms 是复核提交间隔，不保证 OCR 耗时和下一帧采样之后始终落在 350ms 内。出现新鲜标题缺口时，阶段由当前真实像素证据维持，不推进旧标题时间戳。

现有 `HeaderTextCache` 只复用完全相同裁剪和语言的原始识别文本；新阶段判断仍绑定新捕获帧。压缩视频逐像素变化使其命中率有限，本次不将缓存或累计 OCR 墙钟耗时直接换算成承诺的 CPU 降幅。

## 4. 用户七条工程规则的落实

完整原文保留在 [Plan.md 第 2 节](../Plan.md#2-强制通用规则)，本次没有放宽规则。

| 规则 | 本次落实 |
| --- | --- |
| 不为猜测竞态、安全边界增加复杂度或扩大范围 | 修改针对已复现的裁剪、遮挡和重复处理；未引入机型表、通用约束框架或新采集后端 |
| 外部数据只在入口校验一次 | 图像尺寸、stride 和缓冲区仍在图像入口处理，内部传递既有图像、布局和证据类型 |
| 不提前抽只用一次的函数 | 新定位与摆放单元分别表示实际内容定位、窗口几何两个清晰概念；没有为每个条件建接口或工厂 |
| 主函数保留正常流程 | `CaptureService`、引擎继续按采集、证据、追踪、发布推进，几何搜索与窗口计算留在对应实现中 |
| 注释只解释不明显约束 | 注释说明来源时间、完整屏幕坐标、异步测量后摆放及 UI 估计不能当阶段证据等约束 |
| 不手改生成文件 | 修改源 Kotlin、源测试和文档；生成的 APK、报告与资源由构建或测试产出 |
| 测试真实实现，少用 mock | 几何与追踪测试直接执行生产类；引擎测试使用真实像素，仅在 OCR 边界使用既有替身；live 验收走系统授权、实际捕获服务和悬浮窗 |

## 5. 验证记录与未认证范围

### 完整整屏采集矩阵

初始候选版的 1216×2688、720×1280 各 2 项通过后，已完成以下本轮实采矩阵。9 组是同一 Android 15 模拟器环境的显示尺寸和 density 配置，包含窄长、平板比例、低分辨率及同像素尺寸不同 density；不是 9 款实体手机。

| Android 显示尺寸 | density（dpi） | 五 Glyph 原片证据 | 三 Glyph 原片证据 |
| --- | --- | --- | --- |
| 1216×2688 | 440 | [通过](../app/build/outputs/analysis/adaptation/verified-1216x2688-440/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-1216x2688-440/real-recording-093906.json) |
| 720×1280 | 320 | [通过](../app/build/outputs/analysis/adaptation/verified-720x1280-320/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-720x1280-320/real-recording-093906.json) |
| 1080×2160 | 420 | [通过](../app/build/outputs/analysis/adaptation/verified-1080x2160-420/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-1080x2160-420/real-recording-093906.json) |
| 1080×2400 | 420 | [通过](../app/build/outputs/analysis/adaptation/verified-1080x2400-420/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-1080x2400-420/real-recording-093906.json) |
| 1440×3200 | 560 | [通过](../app/build/outputs/analysis/adaptation/verified-1440x3200-560/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-1440x3200-560/real-recording-093906.json) |
| 1536×2048 | 320 | [通过](../app/build/outputs/analysis/adaptation/verified-1536x2048-320/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-1536x2048-320/real-recording-093906.json) |
| 900×3200 | 320 | [通过](../app/build/outputs/analysis/adaptation/verified-900x3200-320/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-900x3200-320/real-recording-093906.json) |
| 480×800 | 160 | [通过](../app/build/outputs/analysis/adaptation/verified-480x800-160/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-480x800-160/real-recording-093906.json) |
| 1216×2688 | 520 | [通过](../app/build/outputs/analysis/adaptation/verified-1216x2688-520/real-recording.json) | [通过](../app/build/outputs/analysis/adaptation/verified-1216x2688-520/real-recording-093906.json) |

18 份 JSON 均为 `status=passed`、`projectionRunning=true`、`overlayVisible=true`、`inputFreeze=true`，并记录正确的前两项预测前缀与完整确认序列。每组证据位于 `app/build/outputs/analysis/adaptation/verified-{尺寸}-{density}/`，链接指向本机实际生成的验证产物。

机器汇总见 [matrix-summary.json](../app/build/outputs/analysis/adaptation/matrix-summary.json)。18 次矩阵对应的 APK SHA-256 为 `d2f2a3e4c75535a736fc5f88e67d9777215270771e4e3b3679482c1d389c6575`；此哈希锁定矩阵构建，交付安装包的最终哈希与交付复测另行记录，避免混淆不同构建的验证范围。

两段原片分别为 `real-recording.mp4`（question / less / forget / all / lie）与 `real-recording-093906.mp4`（nourish / xm / portal）。文件保持只读，播放器按 `fitCenter` 展示，生产服务再次采集播放画面；录屏文件不直接送进识别引擎。

[LiveCaptureRecognitionInstrumentedTest](../app/src/androidTest/java/org/szres/glyphlens/capture/LiveCaptureRecognitionInstrumentedTest.kt) 从真实 `MainActivity` 点击开始，通过系统整屏共享授权启动 `CaptureService`，要求实际悬浮窗可见，使用省电开启、会话日志关闭的普通路径。首轮进入 INPUT 时必须已经凭前两个 CONFIRMED 槽位得到正确预测，完整序列全部确认，并继续冻结 3 秒。不能用后续播放循环补救首轮失败。

每次运行先删除同名旧结果，最后一次性保存本次成功或失败 JSON，包含源文件、服务状态、序列、预测前缀、冻结结果、少量状态变化与 PowerReport；来源不明或上一次运行的证据不算本次通过。相关 JVM 回归覆盖生产内容定位、源帧准入、窗口空隙、当前像素阶段证明、标题 sourceTime、失去证据恢复及 redo 冻结。

### 单次短会话 CPU 与入口成本对比

CPU 对比使用相同的 1216×2688 / 440dpi 环境及 live 测试类，基准为 `placement-original`，矩阵版本为 `verified-1216x2688-440`，每格仅有一次短会话观测。两边都已将播放器解码放入 `org.szres.glyphlens:fixture_video` 进程，表中为应用主进程的 CPU 时间除以对应会话时间；**100% 表示一个核心，不能按整机总核心数理解成占满整机**。基准已经包含此前的基础采集修正，因此这些数字只描述这两次运行，不能称为相对最初用户 APK 的全部收益，也不能据此声称稳定的百分比降幅。

| 原片 | CPU 时间 / 会话时间：基准→矩阵版本 | 平均 CPU：基准→矩阵版本 | Header read 计时项次数：基准→矩阵版本 | 证据 |
| --- | --- | --- | --- | --- |
| 五 Glyph | 19.91s / 17.374s → 17.78s / 17.296s | 114.6% → 102.8% | 71 → 59 | [基准](../app/build/outputs/analysis/adaptation/placement-original/real-recording.json)、[矩阵版本](../app/build/outputs/analysis/adaptation/verified-1216x2688-440/real-recording.json) |
| 三 Glyph | 18.28s / 13.999s → 15.12s / 13.934s | 130.6% → 108.5% | 88 → 69 | [基准](../app/build/outputs/analysis/adaptation/placement-original/real-recording-093906.json)、[矩阵版本](../app/build/outputs/analysis/adaptation/verified-1216x2688-440/real-recording-093906.json) |

`header read` 是 PowerReport 的任务计时项，其中可能包含精确文本缓存复用，不能当作单独记录的 ML Kit 模型实际推理次数。当前结果仍接近一个核心的持续工作量，不能表述为“几乎不占 CPU”。这些是短会话实测值，不是长期功耗或统计稳定性结论。另一次先执行真实 OCR 测试、再执行 live 测试的交付复测记录为五 Glyph **116.5%**、三 Glyph **112.7%**，显示运行之间存在明显波动（[五 Glyph](../app/build/outputs/analysis/adaptation/delivery-original/real-recording.json)、[三 Glyph](../app/build/outputs/analysis/adaptation/delivery-original/real-recording-093906.json)）。该运行顺序不同，不与表内单次数据合成稳定降幅；最终安装包的多轮同条件范围见下方“最终交付版本”。

另以 `final-original` 到矩阵版本对照**被跳过回调的入口耗时**，用于核查将拒绝帧路径移到 `Image.planes` 访问之前的效果。这里使用的是另一个阶段基准，不与上表 CPU 基准混写：

| 原片 | Skipped frame 平均耗时 | p95 | 证据 |
| --- | --- | --- | --- |
| 五 Glyph | 2.01ms → 0.37ms | 4.46ms → 1.76ms | [入口调整前](../app/build/outputs/analysis/adaptation/final-original/real-recording.json)、[矩阵版本](../app/build/outputs/analysis/adaptation/verified-1216x2688-440/real-recording.json) |
| 三 Glyph | 2.04ms → 0.15ms | 6.22ms → 0.20ms | [入口调整前](../app/build/outputs/analysis/adaptation/final-original/real-recording-093906.json)、[矩阵版本](../app/build/outputs/analysis/adaptation/verified-1216x2688-440/real-recording-093906.json) |

图像转换、节点查找、逐帧识别、OCR 和显示的完整计时分布保留在各 JSON 的 `powerReport`。累计 OCR 墙钟耗时不能直接换算成 CPU 时间，也不把总 CPU 差值全部归因于某一个函数。

### 构建与补充回归

本轮统一检查结果：识别模块 **483 项，0 失败，4 项既有私有 fixture 缺失跳过**；应用模块 **210 项，0 失败**；Debug/Release APK、`lintDebug`、`lintRelease` 全部成功。识别测试包括 Replay 复用同一 `GameUILayoutLocator` 与完整颜色帧的 3 项回归；应用测试包括显示尺寸/density 变化后允许再次自动摆放且保留手动优先的 3 项新增回归。跳过的私有素材测试不计为识别通过。

中间候选包的原始 1216×2688 与 720×1280 共 **4 项 live 复测，以及 2 项真实 OCR/crop 复测均通过**；live 证据位于 `adaptation/delivery-original`、`adaptation/delivery-720x1280`。

### 最终交付版本

最后省略了当前帧已有槽位、计时条或新鲜阵列活动证据时，仅用于维持节奏的 Portal 扫描。装备识别开启时保留原扫描；没有改变采样率、阈值或阶段判断。生产像素测试覆盖阶段尚为 INACTIVE 时的省略、证据过期后恢复和开启装备读取时仍执行。

该版本重新完成识别 483 项（4 项素材缺失跳过）、应用 210 项、Debug/Release 构建及两种 Lint，全部无失败。Lint 仍有 170 项 warning 和 1 项 hint，未将构建成功写成零警告。最终包另有 **6 项真实整屏采集与 2 项实际 OCR/crop 测试全部通过**，其中原始比例连续两轮、720×1280 一轮，每轮两段原片。汇总与最终包哈希见 [delivery-summary.json](../app/build/outputs/analysis/adaptation/delivery-summary.json)。

| 最终包测试 | 第一轮 CPU | 第二轮 CPU | 原始证据 |
| --- | --- | --- | --- |
| 1216×2688 / 440dpi，五 Glyph | 96.6% | 101.9% | [第一轮](../app/build/outputs/analysis/adaptation/portal-original-1/real-recording.json)、[第二轮](../app/build/outputs/analysis/adaptation/portal-original-2/real-recording.json) |
| 1216×2688 / 440dpi，三 Glyph | 93.8% | 104.0% | [第一轮](../app/build/outputs/analysis/adaptation/portal-original-1/real-recording-093906.json)、[第二轮](../app/build/outputs/analysis/adaptation/portal-original-2/real-recording-093906.json) |
| 720×1280 / 320dpi，五 Glyph | 76.4% | — | [实测](../app/build/outputs/analysis/adaptation/portal-720x1280/real-recording.json) |
| 720×1280 / 320dpi，三 Glyph | 79.0% | — | [实测](../app/build/outputs/analysis/adaptation/portal-720x1280/real-recording-093906.json) |

原始比例的最终两轮均使用同一个 live 测试类，不在前面混跑 OCR 测试；播放器保持独立进程。上述范围只是两轮短会话观测，不构成所有设备或长时间运行的稳定降幅承诺。

可安装文件：[app-debug.apk](../app/build/outputs/apk/debug/app-debug.apk)，63,097,348 B，SHA-256：`d971b7aa90f25acddc98b68d858b47f8da6464191285ccbdbf7c49a8bc302302`。已安装到当前 Android 15 模拟器；测试结束后显示恢复为 1216×2688 / 440dpi，MediaProjection 为 null，应用服务为空。

**物理手机温度、电池功耗及长时热稳定性仍未测**；模拟器 CPU、系统 thermal status 或原片识别通过不能证明手机不发热。

### 当前认证边界

已验证路径是用户选择 **共享整个屏幕** 的 MediaProjection。系统允许选择单应用共享，但分屏/单应用捕获图像到全屏悬浮窗的坐标映射 **尚未认证**；尺寸回调的实现不等于该映射已经通过验收。官方说明单应用共享排除状态栏、导航栏等系统区域，因此不能直接用整屏坐标验收结论替代。参见 [Android 单应用屏幕共享](https://developer.android.com/media/grow/media-projection)。

当前 `GlyphCaptureEngine` 仍要求 **`frame.width < frame.height` 的竖屏捕获**。横屏、方形捕获以及折叠设备的横屏展开形态尚未扩展或验证；显示尺寸变化后窗口可重新摆放，不代表识别核心已经支持全部方向。当前交付的是通用竖屏识别机制及上述覆盖实证。

本次没有完成真实 Ingress 所有页面、所有语言和设备的覆盖；历史云同步、账号及其他业务未验收项继续保留原状态，不借本次 Glyph 适配结果标记完成。
